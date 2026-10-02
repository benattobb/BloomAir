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

    private var currentMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private var currentWidth = 1920
    private var currentHeight = 1080

    fun initialize(width: Int = 1920, height: Int = 1080, mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
        val targetWidth = if (width > 0) width else 1920
        val targetHeight = if (height > 0) height else 1080

        // Avoid destroying and re-creating active decoder if specs haven't changed
        if (isConfigured && currentWidth == targetWidth && currentHeight == targetHeight && currentMimeType == mimeType && codec != null) {
            return
        }

        try {
            release()
            currentWidth = targetWidth
            currentHeight = targetHeight
            currentMimeType = mimeType

            val format = MediaFormat.createVideoFormat(mimeType, currentWidth, currentHeight).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, currentWidth * currentHeight * 2)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }

            codec = MediaCodec.createDecoderByType(mimeType).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Log.i(tag, "Low-latency Decoder active ($mimeType $currentWidth x $currentHeight)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize low-latency MediaCodec ($mimeType)", e)
        }
    }

    fun decodeNalUnit(nalData: ByteArray, offset: Int, length: Int, ptsUs: Long, isH265: Boolean = false) {
        val targetMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (!isConfigured || currentMimeType != targetMime) {
            initialize(currentWidth, currentHeight, targetMime)
        }

        val decoder = codec ?: return
        if (!isConfigured) return

        try {
            // Convert length-prefixed NAL units (AVCC/HVCC from Mac/iOS) to Annex B start codes (0x00 0x00 0x00 0x01)
            ensureAnnexB(nalData, offset, length)

            // Use 20ms timeout to avoid dropping keyframes or SPS/PPS
            val inputBufferIndex = decoder.dequeueInputBuffer(20_000L)
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(nalData, offset, length)
                decoder.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, 0)
            }

            // Drain output frames directly to surface with zero delay
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            while (outputBufferIndex >= 0) {
                decoder.releaseOutputBuffer(outputBufferIndex, true)
                updateFps()
                outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error decoding low-latency frame: ${e.message}")
        }
    }

    private fun ensureAnnexB(data: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i + 4 <= end) {
            // Check if already Annex B start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))) {
                return
            }

            // Read 4-byte big-endian NAL unit length
            val nalLen = ((data[i].toInt() and 0xFF) shl 24) or
                         ((data[i + 1].toInt() and 0xFF) shl 16) or
                         ((data[i + 2].toInt() and 0xFF) shl 8) or
                         (data[i + 3].toInt() and 0xFF)

            if (nalLen > 0 && i + 4 + nalLen <= end) {
                data[i] = 0
                data[i + 1] = 0
                data[i + 2] = 0
                data[i + 3] = 1
                i += 4 + nalLen
            } else {
                data[i] = 0
                data[i + 1] = 0
                data[i + 2] = 0
                data[i + 3] = 1
                break
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

    fun release() {
        try {
            codec?.apply {
                stop()
                release()
            }
            codec = null
            isConfigured = false
            Log.i(tag, "Decoder released")
        } catch (e: Exception) {
            Log.e(tag, "Error releasing MediaCodec", e)
        }
    }
}
