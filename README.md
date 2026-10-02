# BloomAir (iPad & Mac to Android TV)

An open-source Android TV application providing seamless Apple **AirPlay Screen Mirroring**, **Video Streaming**, and **Audio Playback** with a modern **Frosted Glass (Violet & Blue) UI**, animated fluid mesh launch screen, and ultra-low latency (< 40ms sync).

---

## ✨ Features

* **Frosted Glass Aesthetic**: Glassmorphism cards with translucent highlights, blur backdrop, and smooth focus animations for TV remotes.
* **Fluid Moving Gradient**: Real-time shifting 60 FPS mesh gradient on launch and standby.
* **Ubuntu Font Family**: Modern, rounded typography optimized for 10-foot television readability.
* **Ultra-Low Latency Pipeline**:
  * `TCP_NODELAY = true` (Disables Nagle packet buffering).
  * Realtime hardware `MediaCodec` low-latency decoding directly to `SurfaceView`.
  * Audio synchronization down to ~40ms.
  * Low-latency Wi-Fi performance lock to eliminate power-save polling jitter.
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
   cd ~/Documents/AirPlayTV
   ./scripts/pair_and_deploy.sh
   ```
   *(Enter the 5-digit port and 6-digit Wi-Fi pairing code shown on your TV screen).*

---

### Option 2: Build & Customize from Source

This repository contains a full Android TV application ready for Android Studio:
1. Open **Android Studio**.
2. Select **Open** and choose `/Users/benattobb/Documents/AirPlayTV`.
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

* **Vector App Icon**: [`artwork/BloomAir_Frosted_Icon.svg`](file:///Users/benattobb/Documents/AirPlayTV/artwork/BloomAir_Frosted_Icon.svg)
* **Vector TV Banner**: [`artwork/BloomAir_TV_Banner.svg`](file:///Users/benattobb/Documents/AirPlayTV/artwork/BloomAir_TV_Banner.svg)
* **Typography**: [`app/src/main/res/font/ubuntu.xml`](file:///Users/benattobb/Documents/AirPlayTV/app/src/main/res/font/ubuntu.xml)
* **Frosted Glass Cards**: [`app/src/main/res/drawable/glass_card.xml`](file:///Users/benattobb/Documents/AirPlayTV/app/src/main/res/drawable/glass_card.xml)
* **Moving Gradient Background**: [`app/src/main/java/com/airplay/tv/ui/MovingGradientView.kt`](file:///Users/benattobb/Documents/AirPlayTV/app/src/main/java/com/airplay/tv/ui/MovingGradientView.kt)
