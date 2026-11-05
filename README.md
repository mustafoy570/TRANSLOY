# Dari Screen Translator

A native Android app (Kotlin + Jetpack Compose, Material 3) that translates any
text on your screen into **Persian/Dari (fa)**, fully **on-device and offline**
after a one-time model download.

Based on the architecture of [mzalhariz/screen-translator](https://github.com/mzalhariz/screen-translator)
(accessibility-tree reading, floating bubble, ML Kit on-device translation),
extended with MediaProjection + ML Kit OCR for images and games, a fixed
Persian target, bundled Vazirmatn font, DataStore settings and a permission
guide for Android 8–15.

## How it works

1. Turn ON the big switch in the app. A draggable, semi-transparent floating
   button appears over every app and remembers its last position.
2. **Tap** the button → the current screen is translated into Persian and the
   translations are drawn over the original text boxes (white, RTL,
   auto-sized Vazirmatn text).
3. **Tap again** → the translations disappear.
4. **Long-press** the button → forces image translation (screen capture + OCR),
   useful for games, photos and videos.

Text is read through two paths:

- **Accessibility service** (fast path) — reads real text nodes from apps.
- **MediaProjection + ML Kit OCR** (fallback) — captures the screen and
  recognizes Latin, Chinese, Japanese, Korean and Devanagari scripts.

Source languages are auto-detected with ML Kit Language ID; translation uses
ML Kit on-device Translate with **Persian as the fixed target**.

## Permissions (first-run guide walks you through each)

| Permission | Why |
|---|---|
| Display over other apps | floating button + translation boxes |
| Notifications (Android 13+) | required for foreground services |
| Accessibility service | fast text reading |
| Screen capture consent | asked automatically the first time an image needs OCR |

## Offline models

Open **Language models** in the app and pre-download models for English,
Arabic, Turkish, Hindi, Urdu, Russian, Chinese, Japanese, Korean, French,
German, Spanish, Italian, Portuguese, Dutch — plus the required Persian
target model (~30 MB each). After that, everything works without internet.
Missing models are also auto-downloaded on first use when online.

## Build

**Android Studio:** open the project, let it sync, press Run. Requires
JDK 17, Android SDK 35.

**Command line:** `./gradlew :app:assembleDebug`
(APK at `app/build/outputs/apk/debug/app-debug.apk`).

**GitHub Actions:** every push builds a debug APK. Download it from
*Actions → Build debug APK → Artifacts → dari-screen-translator-debug-apk*.

## Notes and limitations

- Dari is covered by the Persian (fa) model; ML Kit has no Pashto model.
- Urdu translation works for accessibility-read text, but ML Kit has no
  Urdu-script OCR recognizer (Latin/Chinese/Japanese/Korean/Devanagari only).
- Screen-capture consent is re-asked per ON session on Android 14+ (system
  limitation); the capture service and its bitmaps are released when you
  switch translation OFF.
- In landscape, OCR box placement uses a rotation heuristic that may need
  adjustment on unusual devices.
