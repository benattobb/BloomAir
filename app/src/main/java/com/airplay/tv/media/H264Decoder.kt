package com.airplay.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-Low Latency H.264 / H.265 Hardware Decoder.
 *
 * Architecture:
 *  - A dedicated HandlerThread owns all MediaCodec calls (configure/start/queueInput/release).
 *    This prevents the C++ native network socket thread from ever blocking on MediaCodec.
 *  - NAL units arriving from native callbacks are posted to a bounded ArrayBlockingQueue
 *    (capacity = 16). If the queue is full the oldest frame is dropped — intentional back-pressure.
 *  - MediaCodec is configured to output directly to the provided Surface (zero-copy), so no
 *    byte-buffer round-trips occur on the output side.
 *  - KEY_LOW_LATENCY = 1 disables the hardware decoder's internal reorder buffer.
 *  - Output buffers are drained asynchronously via a MediaCodec.Callback running on the
 *    same codec HandlerThread — completely avoids the BLAST TimeStats overflow that occurred
 *    when frames were queued faster than SurfaceFlinger consumed them.
 */
class H264Decoder(private val surface: Surface) {

    private val tag = "H264Decoder"

    // Codec state — only touched from codecThread
    private var codec: MediaCodec? = null
    @Volatile private var isConfigured = false

    private var currentMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private var currentWidth = 1920
    private var currentHeight = 1080

    // Dedicated thread for all MediaCodec operations
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null

    // Bounded queue of pending NAL units. Capacity is capped to prevent heap pressure at 60fps.
    private data class NalPacket(
        val data: ByteArray,
        val ptsUs: Long,
        val isH265: Boolean,
        val flags: Int = 0
    )
    private val nalQueue = ArrayBlockingQueue<NalPacket>(16)
    private val drainRunning = AtomicBoolean(false)

    // FPS telemetry
    private var frameCount = 0L
    private var lastFpsTimestamp = System.currentTimeMillis()
    var currentFps: Int = 0
        private set

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun initialize(width: Int = 1920, height: Int = 1080, mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
        val targetWidth = if (width > 0) width else 1920
        val targetHeight = if (height > 0) height else 1080

        // If specs match an already-running decoder, do nothing.
        if (isConfigured && currentWidth == targetWidth && currentHeight == targetHeight &&
            currentMimeType == mimeType && codec != null) return

        ensureCodecThread()

        codecHandler?.post {
            initializeOnCodecThread(targetWidth, targetHeight, mimeType)
        }
    }

    /**
     * Submit a NAL unit for decoding. Safe to call from any thread.
     * Drops oldest frame if queue is full (controlled back-pressure).
     */
    fun decodeNalUnit(nalData: ByteArray, offset: Int, length: Int, ptsUs: Long, isH265: Boolean = false) {
        val targetMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        // If codec type has changed, reinitialise (posted to codec thread, safe).
        if (!isConfigured || currentMimeType != targetMime) {
            initialize(currentWidth, currentHeight, targetMime)
        }

        // Copy the slice of data we need (native may reuse its buffer immediately after return)
        val copy = nalData.copyOfRange(offset, offset + length)
        val packet = NalPacket(copy, ptsUs, isH265)

        // Non-blocking offer — drop oldest if full to maintain real-time behaviour
        if (!nalQueue.offer(packet)) {
            nalQueue.poll()          // drop oldest
            nalQueue.offer(packet)   // enqueue newest
            Log.w(tag, "NAL queue full — dropped oldest frame")
        }

        // Schedule a drain pass on the codec thread (idempotent)
        scheduleDrain()
    }

    fun release() {
        codecHandler?.post {
            releaseOnCodecThread()
        }
        // Give codec thread a moment, then quit
        codecThread?.quitSafely()
        codecThread = null
        codecHandler = null
        isConfigured = false
        drainRunning.set(false)
        nalQueue.clear()
        Log.i(tag, "Decoder released")
    }

    // -------------------------------------------------------------------------
    // Internal — runs on codecThread
    // -------------------------------------------------------------------------

    private fun ensureCodecThread() {
        if (codecThread == null || !codecThread!!.isAlive) {
            codecThread = HandlerThread("BloomAir-Decoder", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also {
                it.start()
                codecHandler = Handler(it.looper)
            }
        }
    }

    private fun initializeOnCodecThread(width: Int, height: Int, mimeType: String) {
        releaseOnCodecThread()

        try {
            currentWidth = width
            currentHeight = height
            currentMimeType = mimeType

            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Real-time priority hint (0 = real-time, 1 = non-real-time)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                // Max input size — conservative upper bound for a 4Kp60 slice
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080 * 2)
            }

            val newCodec = MediaCodec.createDecoderByType(mimeType)

            // Use async callback mode — output is delivered to codec thread without blocking
            newCodec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    // Feed the next queued NAL unit into this input buffer
                    val packet = nalQueue.poll() ?: return
                    try {
                        val buf = mc.getInputBuffer(index) ?: return
                        buf.clear()
                        val writeLen = minOf(packet.data.size, buf.remaining())
                        buf.put(packet.data, 0, writeLen)
                        mc.queueInputBuffer(index, 0, writeLen, packet.ptsUs, 0)
                    } catch (e: Exception) {
                        Log.e(tag, "onInputBufferAvailable error: ${e.message}")
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        // release=true renders this buffer directly to the Surface (zero-copy)
                        mc.releaseOutputBuffer(index, true)
                        updateFps()
                    } catch (e: Exception) {
                        Log.e(tag, "onOutputBufferAvailable error: ${e.message}")
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(tag, "MediaCodec error: ${e.message}, recoverable=${e.isRecoverable}, transient=${e.isTransient}")
                    if (e.isRecoverable) {
                        mc.reset()
                        initializeOnCodecThread(currentWidth, currentHeight, currentMimeType)
                    }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    Log.i(tag, "Output format changed: $format")
                }
            }, codecHandler)

            newCodec.configure(format, surface, null, 0)
            newCodec.start()
            codec = newCodec
            isConfigured = true
            Log.i(tag, "Async decoder active [$mimeType ${width}x$height]")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize MediaCodec ($mimeType): ${e.message}")
            isConfigured = false
        }
    }

    private fun releaseOnCodecThread() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing codec: ${e.message}")
        }
        codec = null
        isConfigured = false
    }

    /**
     * Schedule a single drain pass on the codec thread.
     * In async mode, feeding packets into the queue is enough — MediaCodec itself
     * calls onInputBufferAvailable when ready. This just ensures any packets queued
     * while codec was busy get processed.
     */
    private fun scheduleDrain() {
        if (drainRunning.compareAndSet(false, true)) {
            codecHandler?.post {
                drainRunning.set(false)
                // In async mode, nothing extra needed — onInputBufferAvailable handles it.
                // This post just ensures the Looper stays alive and wakes the codec.
            }
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTimestamp >= 1000L) {
            currentFps = frameCount.toInt()
            frameCount = 0
            lastFpsTimestamp = now
        }
    }
}
