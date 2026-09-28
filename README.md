<div align="center">

# SnapReel

**An offline, Reels-style video and photo viewer for your own folders on Android.**

[![Latest release](https://img.shields.io/github/v/release/shahriar-ahmed-seam/SnapReel?label=release&color=7C3AED)](https://github.com/shahriar-ahmed-seam/SnapReel/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

Pick a folder on your phone and SnapReel turns it into a grid and a full-screen, swipeable feed, like Instagram Reels or TikTok, but for your own photos and videos. Everything stays on the device: no account, no uploads, no tracking.

## Contents

- [Features](#features)
- [Install](#install)
- [Supported formats](#supported-formats)
- [Privacy and permissions](#privacy-and-permissions)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Releasing](#releasing)
- [License](#license)

## Features

**Browse**
- Open any folder with the system folder picker. Subfolders are included.
- 3-column grid with video badges and cached thumbnails that appear instantly on return visits.
- Recent folders (up to 10) with one-tap resume at the last item you viewed.
- Sort by name, date, size or type, or shuffle.

**Watch**
- Full-screen vertical feed. The next and previous videos are loaded before you swipe, so they start without a loading gap.
- Tap to show controls, tap again to pause, double-tap either side to seek 10 seconds, scrub with the slider.
- Mute, loop, and Smart / Fill / Fit framing with a blurred backdrop behind fitted media.
- Dedicated landscape video mode with its own controls.
- Photos: pinch to zoom up to 5× and pan, with optional auto-advance.
- Optional haptic tick on each swipe and file-name overlay.

**Reliable**
- Videos that can't be played show a clear message and a Retry button instead of a black screen.
- If SnapReel loses access to a folder (for example after a reinstall), it tells you and lets you pick the folder again.
- In-app updates from GitHub Releases. Each download is checked (size, package, version and signing key) before it is installed.

## Install

1. On your phone, open the [latest release](https://github.com/shahriar-ahmed-seam/SnapReel/releases/latest).
2. Download the `.apk` file and open it.
3. If asked, allow your browser or file manager to install unknown apps, then tap **Install**.

Later versions are offered inside the app (Home screen at launch, or **Settings › Check for Updates**).

> **Upgrading from 1.2.6 or earlier:** version 1.3.0 is signed with a new release key, so Android won't install it over an older version ("App not installed"). Uninstall SnapReel, then install 1.3.0. This is needed only once; later updates install normally from inside the app. You may need to pick your folders again.

## Supported formats

| Type | Extensions |
|---|---|
| Video | `mp4`, `mkv`, `webm`, `3gp`, `mov`, `avi`, `m4v`, `ts`, `flv` |
| Image | `jpg`, `jpeg`, `png`, `webp`, `gif`, `bmp` |

Playback uses the device's hardware decoders, so codec support (for example HEVC or AV1) depends on the phone.

## Privacy and permissions

- SnapReel reads only the folders you pick through the system folder picker. It never asks for broad storage access.
- Nothing leaves your device. The only network request is the update check against this repository's GitHub Releases.
- Thumbnails are cached in app-private storage and never appear in your gallery.

| Permission | Why |
|---|---|
| Internet | Checking for and downloading app updates |
| Install unknown apps | Installing a downloaded update (Android asks you once) |

## Tech stack

| Area | Library |
|---|---|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Video | AndroidX Media3 ExoPlayer 1.7 |
| Images and thumbnails | Coil 3 |
| Dependency injection | Hilt |
| Settings | DataStore Preferences |
| Tests | JUnit 4, Robolectric, Kotest property testing, Media3 test utils |

Minimum Android 8.0 (API 26), target API 36.

## Architecture

Single-activity MVVM app. Screens are Compose functions backed by Hilt ViewModels. Data and playback live in small, testable components:

```
app/src/main/java/com/snapreel/app/
├── data/          MediaRepository (folder scanning, shared snapshots), AppPreferences (DataStore)
├── di/            Hilt modules
├── navigation/    NavGraph and NavGuard (ignores repeated taps, never pops Home)
├── player/        ReelPlayerPool (up to 3 ExoPlayers), SlotPlanner, PlaybackErrorPolicy
├── ui/            home, viewer (grid, reels, landscape), settings, common dialogs, theme
└── util/
    ├── thumbnail/ Persistent video-thumbnail cache, bounded generator and Coil fetcher
    └── update/    Update coordinator, APK validation, PackageInstaller integration
```

Key design points:
- **Thumbnails:** a custom Coil fetcher backed by a 100 MiB on-disk LRU cache keyed on file URI, size and modification time. Provider thumbnails are used first; frame extraction runs on at most 2 workers and pauses while a viewer is open, so playback gets the decoders.
- **Playback:** each viewer owns a pool of up to 3 players (current, next, previous). Neighbors are prepared paused, so a swipe only has to call `play()`. The pool shrinks when the device runs low on decoders and is released with the screen.
- **Updates:** a state machine checks GitHub Releases, downloads to a partial file, validates the APK, handles the "install unknown apps" permission and installs through a `PackageInstaller` session so failures are reported back to the dialog.

## Building from source

Requirements: JDK 17 or newer and the Android SDK (API 36). Android Studio is optional.

```bash
git clone https://github.com/shahriar-ahmed-seam/SnapReel.git
cd SnapReel

# Debug build (no signing setup needed)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

The suite (86 tests) runs on the JVM with Robolectric; no device is needed. It includes property-based tests for the thumbnail cache and scheduler, the player pool, playback error handling, navigation, settings storage and every step of the update flow, plus preservation tests that pin existing behavior.

Use `:app:testDebugUnitTest` rather than `./gradlew test`: the plain `test` task also configures the release variant, which requires the release key.

## Releasing

Every release must be signed with the same release key. Android installs an update only over an app signed with the same key, so a different key breaks in-app updates. A release build fails with "Release signing is not configured" when the key is missing; it never falls back to the debug key.

1. **Signing config.** Put `keystore.properties` at the repo root (it is git-ignored, never commit it):
   ```properties
   storeFile=/path/to/snapreel-release.p12
   storePassword=...
   keyAlias=snapreel
   keyPassword=...
   ```
   Or set `SNAPREEL_KEYSTORE_FILE`, `SNAPREEL_KEYSTORE_PASSWORD`, `SNAPREEL_KEY_ALIAS` and `SNAPREEL_KEY_PASSWORD` (these take precedence).
2. **Bump the version.** Increase `versionCode` and `versionName` in `app/build.gradle.kts`. The in-app updater compares the release tag with `versionName`.
3. **Build and verify.**
   ```bash
   ./gradlew assembleRelease
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
   The certificate SHA-256 must match:
   `61ad7d7ee4fe2744f4c4112d35b267cedbfc1909cde96028b4d070cf585b9e49`
4. **Publish** a GitHub release tagged `vX.Y.Z` with the APK attached. Installed apps pick it up through the in-app updater.

Keep the keystore and its password backed up in two separate places. If the key is lost, no future release can update existing installs.

## License

SnapReel is released under the [MIT License](LICENSE).
