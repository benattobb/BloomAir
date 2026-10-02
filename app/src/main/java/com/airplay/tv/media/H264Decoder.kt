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
 * Design:
 *  - A dedicated HandlerThread ("BloomAir-Decoder") serialises every MediaCodec call.
 *    `decodeNalUnit()` is safe to call from any thread (including the C++ network thread)
 *    because it just copies data and posts a Runnable — it never blocks.
 *  - Synchronous MediaCodec API (no Callback mode). Each posted Runnable:
 *      1. dequeueInputBuffer(5 ms) – feeds one NAL unit
 *      2. drains all pending output buffers with dequeueOutputBuffer(0)
 *    This is intentionally simple and avoids the async-callback "lost slot" deadlock where
 *    onInputBufferAvailable fires while the NAL queue is empty and the slot is never reclaimed.
 *  - KEY_COLOR_FORMAT is NOT set when outputting to a Surface; the hardware codec negotiates
 *    the correct format with SurfaceFlinger automatically.
 *  - KEY_LOW_LATENCY = 1 disables the hardware reorder buffer (API 30+).
 */
class H264Decoder(private val surface: Surface) {

    private val tag = "H264Decoder"

    // Accessed only from codecThread
    private var codec: MediaCodec? = null
    @Volatile private var isConfigured = false

    private var currentMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private var currentWidth  = 1920
    private var currentHeight = 1080

    // All MediaCodec work runs on this thread
    private val codecThread = HandlerThread(
        "BloomAir-Decoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val codecHandler = Handler(codecThread.looper)

    // FPS telemetry (written on codecThread, read from any thread)
    @Volatile var currentFps: Int = 0
        private set
    private var frameCount = 0L
    private var lastFpsTs  = System.currentTimeMillis()

    // -------------------------------------------------------------------------
    // Public API – all safe to call from any thread
    // -------------------------------------------------------------------------

    fun initialize(
        width: Int = 1920,
        height: Int = 1080,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    ) {
        val w = if (width  > 0) width  else 1920
        val h = if (height > 0) height else 1080

        // Skip if already running with the same config
        if (isConfigured && currentWidth == w && currentHeight == h &&
            currentMimeType == mimeType && codec != null) return

        codecHandler.post { initSync(w, h, mimeType) }
    }

    /**
     * Submit one NAL unit for decoding. Returns immediately; decoding happens on codecThread.
     * Data is copied before the native buffer is released, so this is safe to call from JNI
     * callbacks.
     */
    fun decodeNalUnit(
        nalData: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        isH265: Boolean = false
    ) {
        val targetMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC
                         else        MediaFormat.MIMETYPE_VIDEO_AVC

        // Re-initialise on mime-type change (posted, async, safe)
        if (!isConfigured || currentMimeType != targetMime) {
            initialize(currentWidth, currentHeight, targetMime)
        }

        // Copy payload – native buffer may be reused immediately after we return
        val copy = if (offset == 0 && length == nalData.size) nalData.copyOf()
                   else nalData.copyOfRange(offset, offset + length)

        codecHandler.post { decodeSync(copy, ptsUs) }
    }

    fun release() {
        codecHandler.post { releaseSync() }
        codecThread.quitSafely()
        isConfigured = false
    }

    // -------------------------------------------------------------------------
    // Private – runs exclusively on codecThread
    // -------------------------------------------------------------------------

    private fun initSync(width: Int, height: Int, mimeType: String) {
        releaseSync()

        try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                // Disable hardware reorder buffer for minimum latency (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Real-time priority hint
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                // Conservative max slice size – do NOT set KEY_COLOR_FORMAT when using a Surface
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 2)
            }

            val c = MediaCodec.createDecoderByType(mimeType)
            // Surface output → zero-copy path; no crypto; decode (flags = 0)
            c.configure(format, surface, null, 0)
            c.start()

            codec = c
            currentWidth  = width
            currentHeight = height
            currentMimeType = mimeType
            isConfigured = true
            Log.i(tag, "Decoder started [$mimeType ${width}x$height]")
        } catch (e: Exception) {
            Log.e(tag, "initSync failed ($mimeType): ${e.message}")
            isConfigured = false
        }
    }

    private fun decodeSync(data: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        if (!isConfigured) return

        try {
            // Feed input — 5 ms timeout is short enough to stay real-time but
            // long enough to get a buffer even during brief codec stalls
            val inIdx = c.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: run {
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                    return
                }
                buf.clear()
                val writeLen = minOf(data.size, buf.remaining())
                buf.put(data, 0, writeLen)
                c.queueInputBuffer(inIdx, 0, writeLen, ptsUs, 0)
            }

            // Drain all available output frames
            drainOutput(c)

        } catch (e: MediaCodec.CodecException) {
            Log.e(tag, "CodecException: ${e.message} recoverable=${e.isRecoverable}")
            if (e.isRecoverable) {
                c.reset()
                initSync(currentWidth, currentHeight, currentMimeType)
            } else {
                releaseSync()
            }
        } catch (e: Exception) {
            Log.e(tag, "decodeSync error: ${e.message}")
        }
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var outIdx = c.dequeueOutputBuffer(info, 0L)
        while (outIdx >= 0) {
            // render = true → buffer is released directly to the Surface (zero-copy)
            c.releaseOutputBuffer(outIdx, true)
            updateFps()
            outIdx = c.dequeueOutputBuffer(info, 0L)
        }
        // INFO_OUTPUT_FORMAT_CHANGED and INFO_TRY_AGAIN_LATER are intentionally ignored;
        // we just proceed on the next frame call.
    }

    private fun releaseSync() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(tag, "releaseSync error: ${e.message}")
        } finally {
            codec = null
            isConfigured = false
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTs >= 1000L) {
            currentFps  = frameCount.toInt()
            frameCount  = 0
            lastFpsTs   = now
        }
    }
}
