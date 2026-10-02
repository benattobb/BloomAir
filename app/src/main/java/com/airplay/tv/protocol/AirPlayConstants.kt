package com.airplay.tv.protocol

object AirPlayConstants {
    const val DEFAULT_AIRPLAY_PORT = 7000
    const val DEFAULT_RAOP_PORT = 7001
    const val DEFAULT_VIDEO_PORT = 7100
    const val DEFAULT_AUDIO_DATA_PORT = 6000
    const val DEFAULT_AUDIO_CONTROL_PORT = 6001
    const val DEFAULT_TIMING_PORT = 6002

    const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
    const val RAOP_SERVICE_TYPE = "_raop._tcp."

    // Features bitmask (AppleTV3,2 compatibility: Video, Screen Mirroring, Audio, FairPlay)
    // 0x5A7FFFF7,0x1E provides maximum compatibility for iOS 9+ and macOS mirroring
    const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"
    const val AIRPLAY_MODEL = "AppleTV3,2"
    const val AIRPLAY_SRCVERS = "220.68"
    const val AIRPLAY_FLAGS = "0x4"
    const val AIRPLAY_PK = "3b764a70267f7ba7c71a4f0b0ae14b4e20e"

    // RTSP Public header response
    const val RTSP_PUBLIC_METHODS = "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"
}
