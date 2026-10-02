package com.airplay.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ultra-Low Latency H.264 / H.265 Hardware Decoder.
 *
 * Latency strategy:
 *  1. Dedicated HandlerThread serialises all MediaCodec calls — no blocking on the C++ network
 *     thread and no TOCTOU race on isConfigured (the bug that caused artifacts/black-screen).
 *  2. P-frame back-pressure: before posting a NAL to the handler, check pendingCount.
 *     If MAX_PENDING_FRAMES (= 2) non-keyframe posts are already queued, the incoming
 *     P-frame is silently dropped rather than added to a growing queue.
 *     SPS (7), PPS (8), IDR (5), and H.265 equivalents are NEVER dropped.
 *  3. dequeueInputBuffer timeout is 2 ms — short enough to stay real-time under chip load
 *     while still getting a buffer during transient stalls, without blocking for a full frame.
 *  4. KEY_LOW_LATENCY = 1 tells the MStar OMX layer to disable its internal display-reorder
 *     buffer so frames render as soon as they are decoded.
 *  5. KEY_COLOR_FORMAT is intentionally absent for Surface output — the HAL negotiates the
 *     format with SurfaceFlinger; setting it explicitly causes OMX BadParameter on this chip.
 */
class H264Decoder(private val surface: Surface) {

    private val tag = "H264Decoder"

    // Fields accessed only on codecThread (no synchronisation needed)
    private var codec: MediaCodec? = null
    private var isConfigured = false
    private var currentMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private var currentWidth    = 1920
    private var currentHeight   = 1080

    // Dedicated high-priority codec thread
    private val codecThread = HandlerThread(
        "BloomAir-Decoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val codecHandler = Handler(codecThread.looper)

    /**
     * Number of non-keyframe Runnables currently queued on the handler.
     * Atomic so it can be read/written from both the calling thread and codecThread.
     */
    private val pendingCount = AtomicInteger(0)

    /** Maximum non-keyframe posts allowed in the handler queue before we start dropping.
     *  2 frames @ 60 fps ≈ 33 ms of tolerable queuing lag. */
    private val MAX_PENDING = 2

    @Volatile var currentFps: Int = 0
        private set
    private var frameCount = 0L
    private var lastFpsTs  = System.currentTimeMillis()

    // -------------------------------------------------------------------------
    // Public API
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
        val targetMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC
                         else        MediaFormat.MIMETYPE_VIDEO_AVC

        // ---- Determine NAL type from Annex-B start code ----
        // H.264: start code (3 or 4 bytes) + NAL header byte [4:0] = NAL type
        // H.265: start code + 2-byte NAL header, type = bits [14:9]
        val sc4 = length >= 4 &&
            nalData[offset    ] == 0.toByte() &&
            nalData[offset + 1] == 0.toByte() &&
            nalData[offset + 2] == 0.toByte() &&
            nalData[offset + 3] == 1.toByte()
        val sc3 = !sc4 && length >= 3 &&
            nalData[offset    ] == 0.toByte() &&
            nalData[offset + 1] == 0.toByte() &&
            nalData[offset + 2] == 1.toByte()
        val scLen = when {
            sc4 -> 4
            sc3 -> 3
            else -> 0
        }

        val isMandatory: Boolean = if (scLen > 0 && length > scLen) {
            if (isH265) {
                val nalType = (nalData[offset + scLen].toInt() ushr 1) and 0x3F
                // H.265: VPS=32, SPS=33, PPS=34, IDR_W_RADL=19, IDR_N_LP=20, CRA=21
                nalType in 19..21 || nalType in 32..34
            } else {
                val nalType = nalData[offset + scLen].toInt() and 0x1F
                // H.264: SPS=7, PPS=8, IDR=5, SEI=6, AUD=9 — keep all except non-reference slices (1,2)
                nalType != 1 && nalType != 2
            }
        } else {
            true // unknown → always keep
        }

        // Drop stale P/B-frames when queue is backed up
        if (!isMandatory && pendingCount.get() >= MAX_PENDING) {
            Log.v(tag, "Drop P-frame — queue depth ${pendingCount.get()}")
            return
        }

        val copy = nalData.copyOfRange(offset, offset + length)
        if (!isMandatory) pendingCount.incrementAndGet()

        // Single atomic post: check + (re-)init + decode all on codecThread
        codecHandler.post {
            if (!isMandatory) pendingCount.decrementAndGet()

            if (!isConfigured || currentMimeType != targetMime) {
                initSync(currentWidth, currentHeight, targetMime)
            }
            if (isConfigured) {
                decodeSync(copy, ptsUs)
            }
        }
    }

    fun release() {
        codecHandler.post { releaseSync() }
        codecThread.quitSafely()
    }

    // -------------------------------------------------------------------------
    // Private — runs only on codecThread
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
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)     // disable OMX reorder buffer
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)        // real-time priority
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 2)
                // KEY_COLOR_FORMAT intentionally omitted for Surface output
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
            Log.e(tag, "initSync failed ($mimeType): ${e.message}")
            isConfigured = false
        }
    }

    private fun decodeSync(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        try {
            // 2 ms — short enough for real-time, long enough to survive brief chip stalls
            val inIdx = c.dequeueInputBuffer(2_000L)
            when {
                inIdx >= 0 -> {
                    val buf = c.getInputBuffer(inIdx)
                    if (buf != null) {
                        buf.clear()
                        val len = minOf(data.size, buf.remaining())
                        buf.put(data, 0, len)
                        c.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                    } else {
                        c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    }
                }
                else -> Log.w(tag, "No input buffer in 2 ms — dropping NAL")
            }
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
            Log.e(tag, "IllegalState: ${e.message} — reinitialising")
            initSync(currentWidth, currentHeight, currentMimeType)
        } catch (e: Exception) {
            Log.e(tag, "decodeSync error: ${e.message}")
        }
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var idx  = c.dequeueOutputBuffer(info, 0L)
        while (idx >= 0) {
            c.releaseOutputBuffer(idx, true)   // render = true → zero-copy to SurfaceFlinger
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
