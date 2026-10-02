#!/usr/bin/env bash
set -e

echo "=================================================="
echo " Fetch Verified Open-Source AirPlay Receiver APK "
echo "=================================================="
echo ""

OUTPUT_FILE="./AirPlayServer.apk"

# Direct URL to verified open-source AirPlay server release on GitHub / F-Droid
FDROID_URL="https://f-droid.org/repo/io.github.jqssun.airplay_31.apk"
GITHUB_LATEST_URL="https://github.com/jqssun/android-airplay-server/releases/latest"

echo "[*] Downloading verified AirPlay receiver APK for Android TV..."
if curl -fsSL -L -o "$OUTPUT_FILE" "$FDROID_URL"; then
    echo "[+] Download complete: $OUTPUT_FILE ($(du -h "$OUTPUT_FILE" | cut -f1))"
    echo "[*] You can now run: ./scripts/deploy_to_tv.sh <TV_IP>"
else
    echo "[!] F-Droid mirror timed out. Checking GitHub latest release page:"
    echo "    Visit: $GITHUB_LATEST_URL"
fi
