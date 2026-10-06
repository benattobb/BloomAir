package com.airplay.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Ultra-Low Latency H.264 / H.265 Hardware Decoder.
 *
 * Design principles:
 *
 *  1. NEVER DROP P-FRAMES.
 *     Dropping a P-frame corrupts the decoder's reference picture buffer: the next P-frame
 *     was encoded relative to the dropped one, so it decodes against the wrong reference →
 *     blockiness/smearing that accumulates every frame until the next IDR resets it.
 *     There is no "skip decode" flag in Android MediaCodec; the only safe way to discard a
 *     frame is to decode it and not render (releaseOutputBuffer(index, false)), which still
 *     costs the decode time, so there is nothing to gain by dropping on the input side.
 *
 *  2. Dedicated HandlerThread serialises all MediaCodec calls.
 *     decodeNalUnit() copies the payload, posts a Runnable, and returns immediately — the
 *     C++ network socket thread is never blocked on MediaCodec.
 *
 *  3. isConfigured check + reinit + decode are posted as ONE Runnable.
 *     This avoids the TOCTOU race where multiple NAL callbacks arriving before the first
 *     initSync completes each post their own initSync, causing SPS/PPS/IDR to land in
 *     three different codec instances (prior bug producing heavy macroblock artifacts).
 *
 *  4. Periodic output-drain timer (every 8 ms on the codec thread).
 *     Hardware decoders have a pipeline of 1-2 frames; draining only after each input
 *     misses frames that finished decoding between NAL arrivals, causing burst output →
 *     jitter. The 8 ms timer catches those frames independently of the input rate.
 *
 *  5. KEY_LOW_LATENCY = 1 (API 30+) disables the OMX display-reorder buffer.
 *
 *  6. KEY_COLOR_FORMAT intentionally omitted for Surface output — the HAL negotiates the
 *     pixel format with SurfaceFlinger automatically. Setting it causes OMX BadParameter
 *     on MStar/Amlogic TV chips.
 */
class H264Decoder(private val surface: Surface) {

    private val tag = "H264Decoder"

    // Accessed only from codecThread
    private var codec: MediaCodec? = null
    private var isConfigured  = false
    private var currentMimeType  = MediaFormat.MIMETYPE_VIDEO_AVC
    private var currentWidth     = 1920
    private var currentHeight    = 1080

    private val codecThread = HandlerThread(
        "BloomAir-Decoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val codecHandler = Handler(codecThread.looper)

    @Volatile var currentFps: Int = 0
        private set
    private var frameCount = 0L
    private var lastFpsTs  = System.currentTimeMillis()

    // Periodic drain Runnable — runs every DRAIN_INTERVAL_MS on the codec thread.
    // This ensures decoded frames reach SurfaceFlinger promptly even when the input
    // rate is lower than the decoder's pipeline depth.
    private val DRAIN_INTERVAL_MS = 8L
    private val drainRunnable: Runnable = object : Runnable {
        override fun run() {
            codec?.let { drainOutput(it) }
            codecHandler.postDelayed(this, DRAIN_INTERVAL_MS)
        }
    }

    init {
        codecHandler.postDelayed(drainRunnable, DRAIN_INTERVAL_MS)
    }

    // -------------------------------------------------------------------------
    // Public API — safe to call from any thread
    // -------------------------------------------------------------------------

    fun initialize(
        width: Int    = 1920,
        height: Int   = 1080,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    ) {
        val w = if (width  > 0) width  else 1920
        val h = if (height > 0) height else 1080
        codecHandler.post { initIfNeeded(w, h, mimeType) }
    }

    fun decodeNalUnit(
        nalData: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        isH265: Boolean = false
    ) {
        if (offset < 0 || length <= 0 || offset > nalData.size - length) {
            Log.w(tag, "Ignoring invalid NAL unit range: offset=$offset length=$length size=${nalData.size}")
            return
        }
        val targetMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC
                         else        MediaFormat.MIMETYPE_VIDEO_AVC
        // Copy immediately — native buffer may be reused after we return
        val copy = nalData.copyOfRange(offset, offset + length)

        // Atomic post: check + (re-)init + decode run sequentially on codecThread,
        // with no race window where a second initSync can slip in between.
        codecHandler.post {
            if (!isConfigured || currentMimeType != targetMime) {
                initSync(currentWidth, currentHeight, targetMime)
            }
            if (isConfigured) decodeSync(copy, ptsUs)
        }
    }

    fun release() {
        codecHandler.removeCallbacks(drainRunnable)
        codecHandler.post { releaseSync() }
        codecThread.quitSafely()
    }

    // -------------------------------------------------------------------------
    // Private — runs exclusively on codecThread
    // -------------------------------------------------------------------------

    private fun initIfNeeded(width: Int, height: Int, mimeType: String) {
        if (isConfigured && currentWidth == width && currentHeight == height &&
            currentMimeType == mimeType && codec != null) return
        initSync(width, height, mimeType)
    }

    private fun initSync(width: Int, height: Int, mimeType: String) {
        releaseSync()
        try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 2)
                // KEY_COLOR_FORMAT intentionally omitted — see class comment
            }
            val c = MediaCodec.createDecoderByType(mimeType)
            c.configure(format, surface, null, 0)
            c.start()
            codec           = c
            currentWidth    = width
            currentHeight   = height
            currentMimeType = mimeType
            isConfigured    = true
            Log.i(tag, "Decoder started [$mimeType ${width}x${height}]")
        } catch (e: Exception) {
            Log.e(tag, "initSync failed ($mimeType ${width}x${height}): ${e.message}")
            isConfigured = false
        }
    }

    private fun decodeSync(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        try {
            // This runs on the dedicated codec thread, so wait for decoder backpressure
            // instead of throwing away a predictive access unit. Losing one encoded
            // packet corrupts subsequent reference frames until the next IDR.
            val deadline = android.os.SystemClock.elapsedRealtime() + 250L
            var inIdx = c.dequeueInputBuffer(10_000L)
            while (inIdx < 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                drainOutput(c)
                inIdx = c.dequeueInputBuffer(10_000L)
            }
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)
                if (buf != null) {
                    buf.clear()
                    if (data.size > buf.remaining()) {
                        c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                        Log.w(tag, "Dropping oversized NAL unit: ${data.size} bytes")
                    } else {
                        buf.put(data)
                        c.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
                    }
                } else {
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                }
            } else {
                // A quarter-second stall means the codec is no longer making progress.
                // Reset it rather than feeding later predictive frames into a stale
                // reference chain. The sender's next keyframe restores synchronization.
                Log.e(tag, "Decoder input stalled for 250 ms; reinitialising codec")
                initSync(currentWidth, currentHeight, currentMimeType)
                return
            }
            // Immediate drain pass — catches any frame that finished during dequeueInputBuffer
            drainOutput(c)
        } catch (e: MediaCodec.CodecException) {
            Log.e(tag, "CodecException: ${e.message} recoverable=${e.isRecoverable}")
            if (e.isRecoverable) {
                try { c.reset() } catch (_: Exception) {}
                initSync(currentWidth, currentHeight, currentMimeType)
            } else {
                releaseSync()
            }
        } catch (e: IllegalStateException) {
            Log.e(tag, "IllegalState in decodeSync: ${e.message} — reinitialising")
            initSync(currentWidth, currentHeight, currentMimeType)
        } catch (e: Exception) {
            Log.e(tag, "decodeSync error: ${e.message}")
        }
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var idx  = c.dequeueOutputBuffer(info, 0L)
        while (idx >= 0) {
            // render immediately at next VSYNC — safe, zero-copy, no buffer exhaustion
            c.releaseOutputBuffer(idx, true)
            updateFps()
            idx = c.dequeueOutputBuffer(info, 0L)
        }
    }

    private fun releaseSync() {
        try { codec?.stop(); codec?.release() }
        catch (e: Exception) { Log.e(tag, "releaseSync: ${e.message}") }
        finally { codec = null; isConfigured = false }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTs >= 1000L) {
            currentFps = frameCount.toInt()
            frameCount = 0
            lastFpsTs  = now
        }
    }
}
