package com.airplay.tv.protocol

object PlistHelper {

    fun buildServerInfoPlist(deviceName: String, macAddress: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>deviceid</key>
                <string>$macAddress</string>
                <key>features</key>
                <integer>13036735671</integer>
                <key>model</key>
                <string>${AirPlayConstants.AIRPLAY_MODEL}</string>
                <key>name</key>
                <string>$deviceName</string>
                <key>protovers</key>
                <string>1.1</string>
                <key>srcvers</key>
                <string>${AirPlayConstants.AIRPLAY_SRCVERS}</string>
                <key>statusFlags</key>
                <integer>4</integer>
                <key>vv</key>
                <integer>2</integer>
                <key>pi</key>
                <string>b08f5a79-db29-4384-b456-a4784d9e6055</string>
                <key>pk</key>
                <data>O3ZKcCY/e6fHGk8LCuFLTiD=</data>
                <key>width</key>
                <integer>1920</integer>
                <key>height</key>
                <integer>1080</integer>
                <key>maxFPS</key>
                <integer>60</integer>
            </dict>
            </plist>
        """.trimIndent()
    }

    fun buildPlaybackInfo(): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>duration</key>
                <real>0.0</real>
                <key>position</key>
                <real>0.0</real>
                <key>rate</key>
                <real>1.0</real>
                <key>playbackBufferEmpty</key>
                <true/>
                <key>playbackBufferFull</key>
                <false/>
                <key>playbackLikelyToKeepUp</key>
                <true/>
                <key>readyToPlay</key>
                <true/>
                <key>loadedTimeRanges</key>
                <array>
                    <dict>
                        <key>duration</key>
                        <real>0.0</real>
                        <key>start</key>
                        <real>0.0</real>
                    </dict>
                </array>
                <key>seekableTimeRanges</key>
                <array>
                    <dict>
                        <key>duration</key>
                        <real>0.0</real>
                        <key>start</key>
                        <real>0.0</real>
                    </dict>
                </array>
            </dict>
            </plist>
        """.trimIndent()
    }
}
