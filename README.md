# BloomAir (iPad & Mac to Android TV)

An open-source Android TV application that lets you use the built-in Screen Mirroring controls on a Mac or iPad to connect to an Android TV. BloomAir provides Apple **AirPlay screen mirroring**, **video streaming**, and **audio playback**. Actual latency depends on the TV hardware and Wi-Fi network.

---

## ✨ Features

* **Simple TV-ready interface**: A clear receiver status, easy-to-read connection instructions, and remote-friendly controls in a calm charcoal-and-sage palette.
* **Animated launch background**: A real-time shifting mesh gradient on launch and standby.
* **Ubuntu Font Family**: Modern, rounded typography optimized for 10-foot television readability.
* **Low-Latency Pipeline**:
  * `TCP_NODELAY = true` (Disables Nagle packet buffering).
  * Hardware `MediaCodec` decoding runs on its own thread and outputs directly to `SurfaceView`.
  * Oboe requests Android's low-latency audio path, with a jitter cushion to reduce dropouts.
  * A Wi-Fi performance lock is held while receiving. Actual latency depends on the TV, sender, and network.
* **Custom App Icon & TV Banner**: High-density adaptive icons and TV banners featuring the frosted squircle and black layered flower motif.

---

## 🚀 Fast Track: 3 Ways to Get Started

### Option 1: 1-Click Wireless Install via ADB from your Mac (Fastest)

If your Mac and Android TV are on the same Wi-Fi network:

1. **Enable Developer Options on Android TV**:
   - Go to **Settings** > **Device Preferences** (or **System**) > **About**.
   - Click **Android TV OS build** **7 times** until you see *"You are now a developer"*.
   - Return to **Settings** > **System** > **Developer Options** and turn **ON** **Wireless Debugging**.

2. **Run the guided pairing tool from Terminal**:
   ```bash
   cd ~/Documents/BloomAir
   ./scripts/pair_and_deploy.sh
   ```
   *(Enter the 5-digit port and 6-digit Wi-Fi pairing code shown on your TV screen).*

---

### Option 2: Build & Customize from Source

This repository contains a full Android TV application ready for Android Studio:
1. Open **Android Studio**.
2. Select **Open** and choose this repository's `BloomAir` directory.
3. Connect your Android TV or an Android TV emulator and click **Run** (`Shift + F10`).

---

## 📱 How to Connect from your iPad or Mac

Once BloomAir is running on your Android TV:

### From your iPad:
1. Ensure your iPad is connected to the **same Wi-Fi network** as your Android TV.
2. Swipe down from the top-right corner to open **Control Center**.
3. Tap the **Screen Mirroring** icon.
4. Select **"BloomAir TV"**.

### From your Mac:
1. Ensure your Mac is connected to the **same Wi-Fi network**.
2. Click the **Control Center** icon in the top macOS menu bar.
3. Click **Screen Mirroring** and select **"BloomAir TV"**.
4. Choose whether to **"Mirror Built-in Display"** or use the TV as an **"Extended Display"**.

---

## 🎨 Design & Artwork Assets

### BloomAir in use

![BloomAir receiver ready to connect, with a sender selecting BloomAir TV and video playing on the television](artwork/marketing/bloomair-watch-on-tv.png)

![BloomAir’s simple Screen Mirroring selection flow](artwork/marketing/bloomair-easy-connect.png)

The artwork illustrates the simple flow: open BloomAir on the TV, choose **BloomAir TV** from Screen Mirroring on a Mac or iPad, and watch on the television.

* **Vector App Icon**: [`artwork/BloomAir_Frosted_Icon.svg`](artwork/BloomAir_Frosted_Icon.svg)
* **Vector TV Banner**: [`artwork/BloomAir_TV_Banner.svg`](artwork/BloomAir_TV_Banner.svg)
* **Typography**: [`app/src/main/res/font/ubuntu.xml`](app/src/main/res/font/ubuntu.xml)
* **Moving Gradient Background**: [`app/src/main/java/com/airplay/tv/ui/MovingGradientView.kt`](app/src/main/java/com/airplay/tv/ui/MovingGradientView.kt)

## Device Testing and Troubleshooting

See [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md) for build, wireless installation, AirPlay discovery checks, and connection diagnostics.
