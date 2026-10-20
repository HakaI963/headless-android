# Headless Android

A minimal, self-contained Android app that creates a **real virtual display**,
launches any installed app onto it, streams a live preview, injects touch/key
input into it, and includes a **built-in self-test that proves the display
actually works** by reading the rendered pixels back.

This repository contains *only* the virtual display engine, extracted from
[ShadowAuto](https://github.com/HakaI963/ShadowAuto). OCR, the AI model client,
the automation engine, the shell/RPC layer and the web launcher were all
removed. See [docs/VIRTUAL_DISPLAY.md](docs/VIRTUAL_DISPLAY.md) for the exact
code map.

## What it does

| Feature | Notes |
|---|---|
| Create / release a virtual display | `DisplayManager.createVirtualDisplay` with `PUBLIC` + hidden `SUPPORTS_TOUCH` / `OWN_FOCUS` / `TRUSTED` flags, with automatic fallback per ROM |
| Launch any app on the display | `am start --display <id>` through the Shizuku shell service |
| Live preview | JPEG frames read from the display's `ImageReader` surface, ~2 fps, inside the app |
| Screenshots / evidence | Saved to `Downloads/HeadlessAndroid/` (no storage permission needed) |
| Input injection | tap, swipe, key and text events sent to the display id via `InputManager.injectInputEvent` |
| Self-test | creates the display, launches a deterministic pattern activity on it, verifies colors, animation and a real tap |

No `MediaProjection`, no screen capture consent dialog, no root requirement —
just [Shizuku](https://shizuku.rikka.app/) (which does require being started
once, via ADB or root).

## Requirements

- Android **10+** (API 29), tested target API 36
- [Shizuku](https://shizuku.rikka.app/download/) installed **and running** on
  the device (started via wireless debugging or root)
- The app itself needs no other permission; it requests the Shizuku API
  permission on first use

## Install

Download `headless-android-debug.apk` from the latest
[release](../../releases/latest) or from the CI artifacts, then install it.
It is debug-signed, so uninstall any previous copy before upgrading.

## Use

1. Open the app → **Grant Shizuku** (the shell engine binds automatically once
   permission is granted; otherwise tap **Start engine**).
2. **Create display** (default 720×1280 @320). The preview card turns live.
3. **Choose app…** → pick any installed app → **Launch on display**. The app
   starts on the virtual display, not on your physical screen.
4. Use **Tap / Swipe up / Back / Home / Type** to control it.
5. Tap **Run full self-test** for the end-to-end proof and **Save report +
   screenshots** to keep the evidence.

### Expected self-test output

```
[0.0s] service uid=2000 sdk=36 model=...
[0.1s] PASS display_created — id=3 720x1280 @320 flags=PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|...
[0.2s] PASS display_active — state=ON ...
[1.9s] PASS test_activity_launched — am start exit=0 Starting: Intent ...
[2.7s] PASS frame_captured — 720x1280 ...
[2.8s] PASS pattern_colors — TL=(255,0,255) TR=(255,235,60) BL=(0,190,215) BR=(65,82,180)
[3.5s] PASS frames_animating — mean_diff=...
[4.1s] PASS tap_reached_display — TL=(0,199,85) ...
[4.2s] RESULT: PASS
```

`PASS` means real buffers were rendered by the activity on the virtual display
and a real injected tap changed them. See [docs/SELF_TEST.md](docs/SELF_TEST.md)
for the full check list and independent verification commands
(`dumpsys display`, `SurfaceFlinger --display-id`, `logcat`).

## Build

### Gradle (same as CI)

```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### No-Gradle script (JDK 17 + Android build-tools)

```sh
JAVA_HOME=/path/to/jdk17 \
ANDROID_JAR=/path/to/android-36/android.jar \
BUILD_TOOLS=/path/to/build-tools/35.0.0 \
./scripts/build-apk.sh
# build/local/headless-android-debug.apk
```

The script compiles resources with `aapt2`, Java with `javac`, dexes with
`d8`, aligns with `zipalign` and signs with a throw-away debug keystore. It is
how the first release APK in this repo was produced.

## Repository layout

```
app/src/main/java/com/bliss/headless/
  DisplayService.java        Shizuku UserService entry point (runs as shell)
  DisplayEngine.java         display lifecycle + commands + self-test
  VirtualDisplaySession.java createVirtualDisplay, frame capture, IME policy
  InputBridge.java           display-targeted tap/swipe/key/text
  AppCatalog.java            app list + am start --display
  ScreenCapture.java         ImageReader -> Bitmap
  WindowManagerBridge.java   hidden window service (IME policy)
  FrameAnalysis.java         pixel checks for the self-test
  TestPatternActivity.java   deterministic pixels rendered on the display
  EngineClient.java          UI-side Shizuku bind + Binder calls
  MainActivity.java          UI: status, preview, apps, input, self-test
scripts/build-apk.sh         no-Gradle build
docs/VIRTUAL_DISPLAY.md      extraction/code map from ShadowAuto
docs/SELF_TEST.md            what the self-test proves
```

## Why Shizuku is required

A headless display that can host *other apps* must be `PUBLIC` and launched
into with shell privileges. `MediaProjection` can only mirror the physical
screen and cannot start another app into the projection. Shizuku provides
exactly those shell privileges without root; the entire interface to it here is
permission + bind + a handful of Binder transactions.

## Credits and license

Apache License 2.0. The virtual display and input-injection code is derived
from ShadowAuto; the Shizuku API is MIT (RikkaApps). See [NOTICE](NOTICE) and
[docs/VIRTUAL_DISPLAY.md](docs/VIRTUAL_DISPLAY.md).
