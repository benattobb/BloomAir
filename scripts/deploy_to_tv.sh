#!/usr/bin/env bash
set -e

echo "=========================================="
echo "   AirPlay TV - Wireless ADB Deployer     "
echo "=========================================="
echo ""

# Find ADB binary
ADB_BIN="$(which adb || echo "/opt/homebrew/bin/adb")"
if [ ! -x "$ADB_BIN" ]; then
    echo "[-] Error: 'adb' tool not found. Please install platform-tools via Homebrew: brew install android-platform-tools"
    exit 1
fi

echo "[+] Using ADB: $ADB_BIN"

# Check arguments or prompt for TV IP
TV_IP="$1"
if [ -z "$TV_IP" ]; then
    read -p "Enter your Android TV's IP address (e.g., 192.168.1.150): " TV_IP
fi

if [ -z "$TV_IP" ]; then
    echo "[-] Error: No TV IP address provided."
    exit 1
fi

PORT="${2:-5555}"
echo "[*] Connecting to Android TV at ${TV_IP}:${PORT}..."
$ADB_BIN connect "${TV_IP}:${PORT}"

echo "[*] Checking connected devices..."
$ADB_BIN devices

# Look for APK in standard release or debug locations
APK_PATH=""
if [ -f "./app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_PATH="./app/build/outputs/apk/debug/app-debug.apk"
elif [ -f "./AirPlayServer.apk" ]; then
    APK_PATH="./AirPlayServer.apk"
fi

if [ -n "$APK_PATH" ]; then
    echo "[*] Found APK: $APK_PATH"
    echo "[*] Installing APK to Android TV..."
    $ADB_BIN -s "${TV_IP}:${PORT}" install -r "$APK_PATH"
    echo "[+] Installation successful!"
    echo "[*] Launching AirPlay TV on your television..."
    $ADB_BIN -s "${TV_IP}:${PORT}" shell monkey -p com.airplay.tv -c android.intent.category.LEANBACK_LAUNCHER 1
    echo "[+] App launched! Your Android TV is now ready to receive AirPlay from your iPad and Mac."
else
    echo "[!] No compiled APK found in standard build directories."
    echo "[*] Run './gradlew assembleDebug' or execute './scripts/fetch_open_source_apk.sh' to download a ready-to-use APK."
fi
