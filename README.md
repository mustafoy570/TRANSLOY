# Dari Screen Translator

A native Android app (Kotlin + Jetpack Compose, Material 3) that translates any
text on your screen into **Persian/Dari (fa)**, fully **on-device and offline**
after a one-time model download.

## Version 2.0.0

The second Transloy version. Pushes to the `main` branch build an APK and
publish it as a public GitHub release.

The APK signing keystore and its password are intentionally not included in
this public repository. GitHub Actions signs downloadable APKs with its debug
key. Android may require uninstalling an existing installation signed with a
different key before installing the APK; uninstalling removes app settings
and downloaded language models.

Based on the architecture of [mzalhariz/screen-translator](https://github.com/mzalhariz/screen-translator)
(accessibility-tree reading, floating bubble, ML Kit on-device translation),
extended with MediaProjection + ML Kit OCR for images and games, a fixed
Persian target, bundled Vazirmatn font, DataStore settings and a permission
guide for Android 8–15.

## What's new in 1.4 — external review round

A full external audit of v1.3 found one false alarm and seven real issues;
all seven are fixed:

- **Android 14/15 screen capture fixed (critical):** a MediaProjection may
  create its VirtualDisplay exactly once; v1.3 recreated it on static-screen
  captures and rotations, which throws SecurityException on API 34+. The
  capture service now keeps one VirtualDisplay for the whole consent session,
  caches frames continuously via an ImageReader listener (so static screens
  still capture instantly), and rotates via `resize()` + `setSurface()`.
  Denied capture consent now also resets the pending OCR request instead of
  leaving it stuck, and the floating button hides itself during capture so
  it never covers the text being OCRed.
- **Overlay alignment:** overlay coordinates are now true physical-screen
  coordinates (`FLAG_LAYOUT_IN_SCREEN` + cutout mode), plus a measured
  `getLocationOnScreen()` compensation pass; the proximity filter matches
  text bounds against the bubble's real on-screen position.
- **Offline setup now tracks Persian AND English:** the setup card no longer
  disappears while English is still downloading, and offers a retry when one
  pack failed.
- **Short texts and buttons translate again:** ML Kit language ID returns
  "und" for 1–2 word labels; they now get a script-based fallback (Pashto
  letters → honest unsupported message, Arabic script → treated as Persian,
  Latin → dominant screen language, default English).
- **OCR "Auto" reaches non-Latin scripts:** the Latin pass must prove real
  Latin words before it may stop the fallback chain — clock digits no longer
  win.
- **CI fixed:** the workflow no longer unzips Gradle into root-owned `/opt`
  (permission denied on cache miss); it uses the project's gradle wrapper.
- **No more `runBlocking` on the main thread** when the bubble appears.

Note: the audit also claimed an unescaped `&` in strings.xml — verified
false (all XML files parse cleanly with a real XML parser), but the
verifier was upgraded anyway: it now parses every XML file, checks EN↔FA
format specifiers, and verifies ML Kit Task calls are awaited.

## What's new in 1.4 — security & privacy round

A follow-up privacy audit ("does user data ever leave the phone?") found
five hardening gaps, all fixed:

- **Password and typing fields are never read.** The accessibility reader
  now skips every `isPassword` / `isEditable` node and the keyboard (IME)
  window entirely — even when the button sits on top of an input field.
- **Translations are invisible to other apps.** The floating button and
  the translation boxes are `FLAG_SECURE` windows: they do not appear in
  screenshots or screen recordings made by other apps (nor in this app's
  own OCR captures, so boxes are never re-translated).
- **Nothing is ever backed up or extracted.** `allowBackup` is off and the
  backup / data-extraction rules include nothing — no adb backup, no
  cloud backup, no device-to-device copy of the app's private directory.
- **HTTPS only.** A network security config forbids cleartext HTTP for the
  whole process; the only traffic the app can generate is HTTPS downloads
  of ML Kit language packs.
- **A privacy card in the app** (English + Dari) states these guarantees
  where users can see them, and the verifier now enforces them as checks
  (section 11 of `scripts/verify_v140.py`) so they cannot silently regress.

## What's new in 1.3 — real offline

- **Offline-first translation path:** when the needed models are on the
  phone, translation never touches the network layer. In airplane mode it is
  instant instead of hanging on a network timeout.
- **Fixed a latent compile error:** `RemoteModelManager.isModelDownloaded()`
  returns a `Task<Boolean>`; `ModelManager.isDownloaded()` used to return the
  Task itself from a `Boolean` function, which cannot compile. It is now a
  proper `suspend` + `await()` call.
- **One-time offline setup, automatic:** the moment your phone has internet,
  the app downloads the Persian + English packs (about 35 MB) by itself and
  shows a progress card on the home screen. No models screen visit needed.
- **Clear offline errors instead of silence:** if a pack is missing while
  offline, you now get "connect once, then it works offline forever" naming
  the exact languages — no cryptic ML Kit exceptions.
- **Better detection feedback:** a page that is already Persian/Dari says so,
  and Pashto text now explains that offline Pashto→Dari is not supported
  (no Pashto model exists in ML Kit) instead of a generic "nothing to
  translate".

## What's new in 1.2

- **Drag-to-translate (proximity mode):** in "Near the button" mode you no
  longer need to tap — drag the floating button onto any text and release it;
  that text is translated immediately. Re-pointing the button somewhere else
  re-translates at the new spot.
- **Fixed signing key (update-safe installs):** every build (local debug, CI
  debug, CI release) is now signed with the same committed keystore, so a new
  APK installs **on top of** the old app and **keeps every downloaded language
  model and all settings** — nothing is ever re-downloaded. See
  [Updates keep your data](#updates-keep-your-data).
- **Redesigned screens:** animated hero card with live stats (languages ready,
  current mode, scroll behavior), icon-based permission checklist, progress
  bar + search on the models screen, progress bar and numbered badges on the
  setup guide.

### What was new in 1.1

- Translation mode "Near the button" (tap to translate nearby text) and
  "Whole screen".
- Scroll-aware overlays: translations hide the moment the page scrolls
  (default, can be turned off).

## Installing updates

Android only allows installing a new APK over an existing app when **both are
signed with the same key. The public GitHub Actions APK uses an automatically
generated debug key, not a private key committed to the repository. If Android
reports a signature mismatch, uninstall the existing app before installing
the APK. Uninstalling clears the app's settings and downloaded models.

## How it works

1. Turn ON the big switch in the app. A draggable, semi-transparent floating
   button appears over every app and remembers its last position.
2. **Tap** the button → the current screen is translated into Persian and the
   translations are drawn over the original text boxes (white, RTL,
   auto-sized Vazirmatn text). In **Near the button** mode only the text
   close to the button is translated — or simply **drag the button onto the
   text and release**: translation starts by itself.
3. **Tap again** → the translations disappear.
4. **Scroll the page** → translations hide automatically (when enabled).
5. **Long-press** the button → forces image translation (screen capture + OCR),
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

No contacts, location, camera, microphone, storage or telephony permission
is requested — or needed.

## Security & privacy — where your data goes

**Short answer: it does not. Everything happens on your phone.**

| What | Where it happens / goes |
|---|---|
| Screen text reading | on-device only, and only at the moment you tap the button. Password fields, typing boxes and the keyboard window are skipped. |
| OCR, language detection, translation | 100% on-device ML Kit with **bundled** models |
| Screen text / translations | never uploaded, never written to disk, no history — the in-memory translation cache dies with the process |
| The only network traffic | downloading ML Kit language packs from Google over HTTPS, only when you (or the one-time setup) trigger a download. Model downloads do not send your screen text. |
| Screenshot / screen-recording leakage | none from this app: overlay windows are `FLAG_SECURE`, so the button and translated text never appear in other apps' captures |
| Backups / device transfers | fully disabled — nothing leaves the phone through adb, cloud backup or device-to-device migration |
| DataStore contents | UI preferences only (toggle, bubble position, modes) — no personal data |
| Logging | the app never calls `android.util.Log`, so no text can leak into logcat |
| Analytics / ads / trackers | none — there is no analytics SDK, no ad SDK, no crash reporter |
| Exported app components | only the launcher activity; every service is `exported="false"`, and the accessibility service additionally requires the signature-level `BIND_ACCESSIBILITY_SERVICE` permission |

The APK signing keystore is kept out of this public repository; no keystore
or signing password is needed to build the debug APK.

## Offline models

**You no longer have to do anything manually.** The app downloads the Persian
and English packs automatically the first time it sees internet (about 35 MB,
once). A card on the home screen shows the progress; when it is gone, offline
translation is ready.

Open **Language models** to add more source languages — English, Arabic,
Turkish, Hindi, Urdu, Russian, Chinese, Japanese, Korean, French, German,
Spanish, Italian, Portuguese, Dutch (~30 MB each). After that, everything
works without internet. Missing models are also auto-downloaded on first use
when online.

## Build

**Android Studio:** open the project, let it sync, press Run. Requires
JDK 17, Android SDK 35.

**Command line:** `./gradlew :app:assembleDebug`
(APK at `app/build/outputs/apk/debug/app-debug.apk`).

**GitHub:** each push to `main` builds an APK and publishes
[`v2.0.0`](https://github.com/mustafoy570/TRANSLOY/releases/tag/v2.0.0).
Download `transloy-v2.0.0.apk` from the release assets.

## Notes and limitations

- Dari is covered by the Persian (fa) model; ML Kit has no Pashto model.
- Urdu translation works for accessibility-read text, but ML Kit has no
  Urdu-script OCR recognizer (Latin/Chinese/Japanese/Korean/Devanagari only).
- Screen-capture consent is re-asked per ON session on Android 14+ (system
  limitation); the capture service and its bitmaps are released when you
  switch translation OFF.
- In landscape, OCR box placement uses a rotation heuristic that may need
  adjustment on unusual devices.
