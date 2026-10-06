# BloomAir device testing

## Build and install

Requirements: Android SDK platform 35, Android Build Tools, ADB, and JDK 17.

```sh
./gradlew assembleDebug
adb pair <TV_IP>:<PAIRING_PORT> <PAIRING_CODE>
adb connect <TV_IP>:<WIRELESS_DEBUGGING_PORT>
adb -s <TV_IP>:<WIRELESS_DEBUGGING_PORT> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <TV_IP>:<WIRELESS_DEBUGGING_PORT> shell monkey -p com.airplay.tv -c android.intent.category.LEANBACK_LAUNCHER 1
```

The pairing port and the normal Wireless Debugging port are different. Read each from the corresponding screen on the TV. `scripts/pair_and_deploy.sh` performs this flow interactively.

## Verify discovery and a live mirror

1. Put the sender and TV on the same LAN. Guest Wi-Fi, client isolation, or a VLAN boundary can block AirPlay even when both devices have internet access.
2. Start BloomAir and confirm the TV shows **Ready to connect**.
3. On macOS, resolve the advertisement:

   ```sh
   dns-sd -L 'BloomAir TV' _airplay._tcp local.
   ```

   The output should resolve to the TV on port 7000. Its `deviceid` should match the per-device ID in the RAOP service name.
4. Select BloomAir TV under **System Settings → Displays → Add Display** (or Screen Mirroring in Control Center).
5. Confirm the TV changes to streaming and visibly shows the sender display.

## Useful diagnostics

```sh
adb -s <TV_IP>:<PORT> logcat -s AirPlayServerService NsdServiceManager H264Decoder AndroidRuntime
adb -s <TV_IP>:<PORT> shell dumpsys activity services com.airplay.tv
```

Look for native server startup on port 7000 and successful `_airplay._tcp` and `_raop._tcp` registration. During a mirror, the service should report connection initialization and `onMirrorRunning: true`; the decoder should initialize for the negotiated video size.

If the TV is discoverable but cannot connect, check that client isolation is off, both devices use the same LAN, the TV clock and Wi-Fi are stable, and the advertised AirPlay `deviceid` is unique. If the connection succeeds but playback stutters, collect the logs during a live session and record the TV model, sender model/OS, Wi-Fi band, and approximate distance before changing the audio jitter cushion. The current cushion favors stable playback; the decoder avoids blocking the native receive callback while codec work runs.

The measured end-to-end delay depends on the sender, Android codec, TV display pipeline, and network. The app does not claim a fixed latency figure without a measured device/sender pair.
