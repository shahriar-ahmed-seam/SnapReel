# SnapReel 🎬

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.02.01-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An offline, gesture-driven media player for Android designed to replicate the seamless, immersive vertical-scrolling experience of Instagram Reels and TikTok. Instantly browse folders, view images, and watch high-definition videos with rich aesthetics, zero-lag loading, and intelligent state restoration.

---

## ✨ Features

- **Instagram-Style Media Grid:** Browse selected local folders in a beautiful 3-column media grid view resembling social media profiles.
- **Vertical Swiping:** Smooth vertical pager for flicking through images and videos with haptic feedback.
- **Pinch-to-Zoom & Pan:** Gesture-based image viewing that seamlessly releases vertical scrolling when zoomed out.
- **Interactive Video Scrubbing:** Smooth slider controls to skip or scrub through video timestamps at any point.
- **Robust Immersive Mode State Machine:**
  - *Single Tap:* Toggles the controls (back button, counter, slider, filename).
  - *Play/Pause:* A tap when controls are active pauses/resumes the video.
  - *Double Tap:* Skips forward/backward by 10s.
  - *Auto-Resume:* Finished videos replay instantly with a single click.
- **Keep Screen Awake:** Automatically keeps the screen on while video playback is active.
- **Zero-Lag Buffering:** Custom load control caching for ExoPlayer optimized specifically for buffering large local HD videos instantly.
- **Folder Progress Preservation:** Remembers the last viewed media index for each directory so you can resume exactly where you left off.

---

## 🛠️ Architecture & Tech Stack

SnapReel is built entirely using modern Android development practices:
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Declarative UI)
- **Media Engine:** Media3 ExoPlayer (for low-latency video loading)
- **Image Loading:** Coil3 (efficient image rendering)
- **Dependency Injection:** Hilt
- **Local Storage:** DataStore Preferences (state preservation)
- **Navigation:** Compose Navigation with URI encoding for directory paths

---

## 📲 Installation

To install the latest release on your Android device:

1. Head over to the [Releases](releases/) folder in this repository or the Github Releases tab.
2. Download `app-release.apk`.
3. Copy the APK to your Android device.
4. Open the APK on your device and tap **Install** (you may need to allow "Install from Unknown Sources" in your browser/file explorer settings).

---

## 🚀 Development Setup

To build the project locally on your machine:

### Prerequisites
- Android Studio Jellyfish (or newer)
- JDK 17+
- Android SDK 34 (Build Tools 34.0.0)

### Clone & Compile
```bash
# Clone the repository
git clone https://github.com/yourusername/snapreel.git

# Navigate to root directory
cd snapreel

# Build release APK
./gradlew assembleRelease
```
The output APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

---

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
