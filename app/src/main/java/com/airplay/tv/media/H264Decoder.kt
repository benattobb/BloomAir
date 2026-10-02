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
 * Architecture:
 *  - A dedicated HandlerThread ("BloomAir-Decoder") serialises every single MediaCodec
 *    operation including the isConfigured check, initSync, and decodeSync.
 *    This is critical: checking isConfigured on the calling thread (the C++ network thread)
 *    and posting initSync separately created a TOCTOU race where multiple initSync calls
 *    were queued before any completed, causing each SPS/PPS/IDR to be sent to a fresh
 *    empty codec that had never seen the prior parameter sets → macroblock artifacts.
 *  - decodeNalUnit() copies NAL bytes and posts a single Runnable. Inside that Runnable,
 *    running on codecThread, isConfigured is authoritative with no race window.
 *  - MediaCodec synchronous API. No Callback mode (async mode causes a "lost input slot"
 *    deadlock when onInputBufferAvailable fires while no NAL is queued).
 *  - KEY_COLOR_FORMAT is intentionally NOT set; for Surface output the HAL negotiates
 *    the correct format with SurfaceFlinger. Setting it explicitly causes OMX errors on
 *    MStar/Amlogic TV chips.
 *  - KEY_LOW_LATENCY = 1 disables the decoder's internal display reorder buffer (API 30+).
 */
class H264Decoder(private val surface: Surface) {

    private val tag = "H264Decoder"

    // All fields below are accessed ONLY from codecThread (except currentFps which is @Volatile)
    private var codec: MediaCodec? = null
    private var isConfigured = false          // NOT volatile — only read/written on codecThread

    private var currentMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private var currentWidth    = 1920
    private var currentHeight   = 1080

    // Dedicated high-priority thread for all MediaCodec calls
    private val codecThread = HandlerThread(
        "BloomAir-Decoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).also { it.start() }
    private val codecHandler = Handler(codecThread.looper)

    @Volatile var currentFps: Int = 0
        private set
    private var frameCount  = 0L
    private var lastFpsTs   = System.currentTimeMillis()

    // -------------------------------------------------------------------------
    // Public API — safe to call from any thread
    // -------------------------------------------------------------------------

    /**
     * Ensure the decoder is initialised for the given dimensions / codec.
     * The actual init is posted to codecThread so it executes after any in-progress decode.
     */
    fun initialize(
        width: Int    = 1920,
        height: Int   = 1080,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
    ) {
        val w = if (width  > 0) width  else 1920
        val h = if (height > 0) height else 1080
        // Post unconditionally; idempotency is checked inside on the codec thread
        codecHandler.post { initIfNeeded(w, h, mimeType) }
    }

    /**
     * Submit one NAL unit. Safe to call from any thread.
     * Data is copied immediately so the native buffer can be recycled after return.
     *
     * IMPORTANT: The mimeType check AND the decode call happen inside the same posted Runnable,
     * ensuring they execute atomically on codecThread. This prevents the race where
     * isConfigured is checked on the caller thread while initSync is still pending.
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

        // Copy before returning — native may reuse its buffer immediately
        val copy = nalData.copyOfRange(offset, offset + length)

        // Single atomic post: check + (re-)init if needed + decode, all on codecThread
        codecHandler.post {
            // isConfigured here is authoritative — no race window
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
    // Private — runs exclusively on codecThread
    // -------------------------------------------------------------------------

    /** Idempotent: skips if specs already match the running decoder. */
    private fun initIfNeeded(width: Int, height: Int, mimeType: String) {
        if (isConfigured &&
            currentWidth  == width  &&
            currentHeight == height &&
            currentMimeType == mimeType &&
            codec != null) return
        initSync(width, height, mimeType)
    }

    private fun initSync(width: Int, height: Int, mimeType: String) {
        releaseSync()
        try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                // Disable hardware reorder buffer → minimal latency (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Real-time priority (0 = real-time)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                // DO NOT set KEY_COLOR_FORMAT for Surface output —
                // the HAL negotiates the format automatically.
                // Setting it on MStar/Amlogic chips causes OMX BadParameter errors.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 2)
            }

            val c = MediaCodec.createDecoderByType(mimeType)
            c.configure(format, surface, null, 0)   // Surface = zero-copy output
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
            // 10 ms timeout — generous enough for the chip under load, still real-time
            val inIdx = c.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)
                if (buf != null) {
                    buf.clear()
                    val writeLen = minOf(data.size, buf.remaining())
                    if (writeLen < data.size) {
                        Log.w(tag, "NAL truncated: ${data.size} → $writeLen bytes (buffer too small)")
                    }
                    buf.put(data, 0, writeLen)
                    c.queueInputBuffer(inIdx, 0, writeLen, ptsUs, 0)
                } else {
                    // Release the slot so the codec can reuse it
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                }
            } else {
                Log.w(tag, "No input buffer available (decoder stalled?) — dropping NAL")
            }

            // Drain all rendered output frames to the Surface
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
        var idx = c.dequeueOutputBuffer(info, 0L)
        while (idx >= 0) {
            // render = true → zero-copy blit to SurfaceView / SurfaceFlinger
            c.releaseOutputBuffer(idx, true)
            updateFps()
            idx = c.dequeueOutputBuffer(info, 0L)
        }
        // INFO_OUTPUT_FORMAT_CHANGED (-2) and INFO_TRY_AGAIN_LATER (-1) are intentionally
        // ignored here; the OMX layer handles port reconfiguration internally.
    }

    private fun releaseSync() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(tag, "releaseSync: ${e.message}")
        } finally {
            codec        = null
            isConfigured = false
        }
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
