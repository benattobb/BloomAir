#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "    Android TV Wireless ADB Pairing & Deployment Tool     "
echo "=========================================================="
echo ""
echo "On your TV, make sure the 'Pair device with pairing code' modal is OPEN:"
echo "(Settings -> System -> Developer Options -> Wireless debugging -> Pair device with pairing code)"
echo ""

# Find ADB binary
ADB_BIN="$(which adb || echo "/opt/homebrew/bin/adb")"
if [ ! -x "$ADB_BIN" ]; then
    echo "[-] Error: 'adb' not found."
    exit 1
fi

read -p "Enter your Android TV's IP address: " TV_IP
if [ -z "$TV_IP" ]; then
    echo "[-] Error: TV IP address is required."
    exit 1
fi

# Prompt for the pairing port shown on the TV screen
read -p "Enter the pairing PORT shown on your TV screen (e.g. 38475): " PAIR_PORT
if [ -z "$PAIR_PORT" ]; then
    echo "[-] Error: Pairing port is required."
    exit 1
fi

# Prompt for the 6-digit pairing code
read -p "Enter the 6-digit Wi-Fi pairing CODE shown on your TV: " PAIR_CODE
if [ -z "$PAIR_CODE" ]; then
    echo "[-] Error: Pairing code is required."
    exit 1
fi

echo ""
echo "[*] Pairing with TV at ${TV_IP}:${PAIR_PORT} using code ${PAIR_CODE}..."
$ADB_BIN pair "${TV_IP}:${PAIR_PORT}" "${PAIR_CODE}"

echo "[+] Successfully paired!"
echo ""

read -p "Enter the Wireless Debugging connection PORT shown on your TV: " CONNECT_PORT
if [ -z "$CONNECT_PORT" ]; then
    echo "[-] Error: Wireless Debugging connection port is required."
    exit 1
fi

echo "[*] Connecting to TV at ${TV_IP}:${CONNECT_PORT}..."
$ADB_BIN connect "${TV_IP}:${CONNECT_PORT}"

echo ""
echo "[*] Connected devices:"
$ADB_BIN devices

APK_PATH="./app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ] && [ -f "./AirPlayServer.apk" ]; then
    APK_PATH="./AirPlayServer.apk"
fi
if [ -f "$APK_PATH" ]; then
    echo ""
    echo "[*] Installing AirPlayServer.apk to your TV..."
    $ADB_BIN -s "${TV_IP}:${CONNECT_PORT}" install -r "$APK_PATH"
    echo "[+] Installation complete!"
    echo "[*] Launching AirPlay TV on your television..."
    $ADB_BIN -s "${TV_IP}:${CONNECT_PORT}" shell monkey -p com.airplay.tv -c android.intent.category.LEANBACK_LAUNCHER 1
    echo "[+] AirPlay receiver is now running on your TV! Ready to connect from iPad or Mac."
fi
