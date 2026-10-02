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

    /**
     * PTS → wall-clock anchor for vsync-aligned rendering.
     *
     * On the first output frame we record (firstPtsUs, firstWallNs). Every subsequent
     * frame's render time is computed as:
     *   renderNs = firstWallNs + (bufferPtsUs - firstPtsUs) * 1_000
     *
     * This converts the encoder's presentation timestamps directly into absolute
     * System.nanoTime() values. SurfaceFlinger then presents the frame at exactly
     * that nanosecond, aligning it to the nearest preceding VSYNC. The result is that
     * display frame timing mirrors the encoder's inter-frame spacing regardless of
     * when WiFi packets arrived — smoothing out WiFi jitter at the display level.
     *
     * If we fall more than MAX_RENDER_AHEAD_NS (half a frame) behind real-time we
     * reset the anchor so the decoder catches up rather than drifting ever further.
     */
    private var anchorPtsUs  = Long.MIN_VALUE  // µs, from MediaCodec BufferInfo
    private var anchorWallNs = 0L              // ns, from System.nanoTime()
    private val MAX_RENDER_AHEAD_NS  = 50_000_000L   // 50 ms — don't schedule too far ahead
    private val MAX_RENDER_BEHIND_NS = 33_400_000L   // 2 frames @ 60 fps — reset if this far behind

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
            anchorPtsUs     = Long.MIN_VALUE   // reset render timeline on new session
            anchorWallNs    = 0L
            Log.i(tag, "Decoder started [$mimeType ${width}x${height}]")
        } catch (e: Exception) {
            Log.e(tag, "initSync failed ($mimeType ${width}x${height}): ${e.message}")
            isConfigured = false
        }
    }

    private fun decodeSync(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        try {
            // 5 ms — enough time for the chip to release a buffer under typical load.
            // Lower values (0-2 ms) risk returning -1 more often, stalling the pipeline.
            val inIdx = c.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)
                if (buf != null) {
                    buf.clear()
                    val len = minOf(data.size, buf.remaining())
                    buf.put(data, 0, len)
                    c.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                } else {
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                }
            } else {
                Log.w(tag, "No input buffer in 5 ms — NAL dropped")
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
            scheduleRender(c, idx, info)
            updateFps()
            idx = c.dequeueOutputBuffer(info, 0L)
        }
    }

    /**
     * Release one output buffer to SurfaceFlinger with a PTS-derived wall-clock timestamp.
     *
     * SurfaceFlinger interprets the timestamp as: "present this buffer at the VSYNC that
     * falls on or after this nanosecond". Passing System.nanoTime() means "present at the
     * very next VSYNC", which is identical to releaseOutputBuffer(idx, true) but allows
     * the compositor to batch more efficiently.
     *
     * By deriving the timestamp from the stream PTS we give each frame its exact
     * inter-frame spacing from the encoder, smoothing out WiFi packet-arrival jitter.
     */
    private fun scheduleRender(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
        val pts = info.presentationTimeUs
        if (pts <= 0) {
            // No valid PTS — render immediately
            c.releaseOutputBuffer(idx, true)
            return
        }

        val nowNs = System.nanoTime()

        if (anchorPtsUs == Long.MIN_VALUE) {
            // First frame: anchor the timeline to "right now"
            anchorPtsUs  = pts
            anchorWallNs = nowNs
        }

        // Map PTS offset (µs) → absolute wall-clock render time (ns)
        val ptsDeltaNs  = (pts - anchorPtsUs) * 1_000L
        val renderNs    = anchorWallNs + ptsDeltaNs
        val aheadNs     = renderNs - nowNs

        when {
            aheadNs in 1L..MAX_RENDER_AHEAD_NS -> {
                // On-time: schedule for exact PTS-derived instant
                c.releaseOutputBuffer(idx, renderNs)
            }
            aheadNs < -MAX_RENDER_BEHIND_NS -> {
                // Too far behind real-time — reset anchor and render immediately,
                // so the next frame re-anchors fresh rather than staying perpetually late
                Log.d(tag, "Render anchor reset (${aheadNs / 1_000_000} ms behind)")
                anchorPtsUs  = pts
                anchorWallNs = nowNs
                c.releaseOutputBuffer(idx, true)
            }
            else -> {
                // Slightly late or borderline — render immediately without anchor reset
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    private fun releaseSync() {
        anchorPtsUs  = Long.MIN_VALUE
        anchorWallNs = 0L
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
