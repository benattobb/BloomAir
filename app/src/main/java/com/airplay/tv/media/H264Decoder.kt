package com.airplay.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Ultra-Low Latency H.264 Hardware Decoder.
 * Configured with KEY_LOW_LATENCY = 1, real-time priority, non-blocking buffer draining,
 * and zero-copy SurfaceView direct output.
 */
class H264Decoder(private val surface: Surface) {

    private val tag = "H264Decoder"
    private var codec: MediaCodec? = null
    private var isConfigured = false
    private var frameCount = 0L
    private var lastFpsTimestamp = System.currentTimeMillis()
    var currentFps: Int = 0
        private set

    fun initialize(width: Int = 1920, height: Int = 1080) {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                // Enable ultra-low latency hardware decoding mode (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // Request realtime OS priority for video pipeline
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Log.i(tag, "Low-latency H.264 AVC Decoder active ($width x $height)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize low-latency MediaCodec", e)
        }
    }

    fun decodeNalUnit(nalData: ByteArray, offset: Int, length: Int, ptsUs: Long, isConfig: Boolean = false) {
        val decoder = codec ?: return
        if (!isConfigured) return

        try {
            // Non-blocking dequeue (timeout = 0) to avoid socket thread delays
            val inputBufferIndex = decoder.dequeueInputBuffer(0L)
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(nalData, offset, length)

                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                decoder.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, flags)
            }

            // Drain output frames directly to surface with zero delay
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            while (outputBufferIndex >= 0) {
                // render = true pushes hardware frame directly to the display overlay
                decoder.releaseOutputBuffer(outputBufferIndex, true)
                updateFps()
                outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error decoding low-latency frame: ${e.message}")
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

    fun release() {
        try {
            codec?.apply {
                stop()
                release()
            }
            codec = null
            isConfigured = false
            Log.i(tag, "H.264 Low-Latency Decoder released")
        } catch (e: Exception) {
            Log.e(tag, "Error releasing MediaCodec", e)
        }
    }
}
