package com.airplay.tv.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

class AudioStreamPlayer(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2
) {

    private val tag = "AudioStreamPlayer"
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun start() {
        if (isPlaying) return

        try {
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSize = minBufferSize * 2

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            Log.i(tag, "AudioTrack started: $sampleRate Hz, stereo")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize AudioTrack", e)
        }
    }

    fun writePcmData(pcmData: ByteArray, offset: Int, length: Int) {
        val track = audioTrack ?: return
        if (!isPlaying) return

        try {
            track.write(pcmData, offset, length)
        } catch (e: Exception) {
            Log.e(tag, "Error writing audio PCM data", e)
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun stop() {
        if (!isPlaying) return

        try {
            audioTrack?.apply {
                stop()
                release()
            }
            audioTrack = null
            isPlaying = false
            Log.i(tag, "AudioTrack stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping AudioTrack", e)
        }
    }
}
