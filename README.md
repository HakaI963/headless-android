# Headless Android

A minimal Android app that creates a **real virtual display**, runs apps on it,
streams a live preview, injects touch/key input, and builds **isolated app
clones** without touching your main installation.

The display engine is extracted from
[ShadowAuto](https://github.com/HakaI963/ShadowAuto). OCR, the AI client, the
automation loop and the web launcher were removed. The clone engine is new:
it repackages an installed app (or an APK you pick) under a new package name,
re-signs it, installs it hidden, and launches it on the virtual display.

## Features

| Feature | Notes |
|---|---|
| Create / release a virtual display | `DisplayManager.createVirtualDisplay` with `PUBLIC` + hidden `SUPPORTS_TOUCH` / `OWN_FOCUS` / `TRUSTED` flags, with per-ROM fallbacks |
| Launch any installed app on the display | `am start --display <id>` through the Shizuku shell service |
| Clone an installed app | Copies base + split APKs, patches the manifest, re-signs, silently installs in user 0 |
| Install an APK as a clone | File picker (SAF) → `ParcelFileDescriptor` → same repackaging pipeline |
| Multiple clones per app | Each clone gets a unique package (`com.bliss.hclone.h…`) and its own data |
| Launch / kill / delete clones | Rows in the app; clones are hidden from the phone launcher |
| Live preview | JPEG frames from the display's `ImageReader` surface, ~2 fps |
| Input injection | Tap, swipe, Back/Home, text; all scoped to the display |
| Snapshots | Saved to `Download/HeadlessAndroid/` |
| Automation API | Explicit broadcasts from shell/root with a control token |
| Keep-awake | A wake lock keeps the screen (and therefore the virtual display) alive while the engine exists |

## Requirements

- Android 10+ (tested on Android 16 / HyperOS).
- [Shizuku](https://shizuku.rikka.app/) running (root or shell mode) and the
  Shizuku permission granted to this app.
- The phone must be **unlocked** when you use the display. HyperOS sleeps
  virtual displays together with the phone screen, so the app holds a wake lock
  while a display is active; it cannot bypass a secure keyguard.

## Install

Download `headless-android-debug.apk` from the latest release and install it.
The APK is debug-signed. If you have a build signed with a different key,
uninstall the old version first; Android will refuse an update across keys.
Clones are uninstalled with the app (or from **Clone → Delete**); the original
apps are never modified.

## Use

1. Open **Headless Android** and grant Shizuku access.
2. Tap **Start engine**.
3. Pick a size/DPI and tap **Create display**.
4. To clone an app:
   - **Clone installed app…** → choose an app → give the clone a name.
   - or **Install APK as clone…** → pick a base APK.
   The engine copies the APK, rewrites its manifest, re-signs it, installs it
   hidden, and records it in the clone list. Large apps can take a while.
5. Tap **Launch** on a clone (or launch any installed app from the *Launch an
   app* card). The app opens on the virtual display; the phone's own screen is
   untouched.
6. Use the preview and input buttons for tap, swipe, Back/Home and text.
   **Save snapshot** writes a JPEG to `Download/HeadlessAndroid/`.

## How cloning works

```
original app (base + splits)
        │  pm path / ApplicationInfo
        ▼
/data/local/tmp/headless/stage-<id>/
        │  ARSCLib: rewrite binary AndroidManifest.xml
        │    package name, provider authorities, custom permissions,
        │    task affinity, relative class names, remove LAUNCHER category,
        │    set clone label
        ▼
apksig: re-sign all APKs with the embedded clone key
        ▼
pm install (single APK) or install-create/write/commit (splits)
        ▼
normal user-0 package, hidden from the launcher
        ▼
am start --display <id> -n <clone>/<original activity>
```

Clones live in **user 0**, because Android (and HyperOS in particular) stops
activities belonging to any other Android user; a shell `pm create-user` clone
installs fine but can never become visible on a virtual display. Repackaging
keeps the clone in the foreground user, so it renders and receives input.

### Limitations

- **Empty data.** A clone starts with fresh storage; the original app's private
  data is not copied. The original app and its data are never touched.
- **Signature checks.** The clone is signed with the embedded clone key, not the
  original developer key. Banking apps, WhatsApp, Google sign-in, license
  checks and Play Integrity will refuse to run. Drag-and-drop games and simple
  utilities usually work.
- **Package identity.** Apps that hard-code their package name in native code or
  verify it may misbehave. Apps with `sharedUserId` are rejected.
- **APK file picker loads base APKs only.** Split APKs must be cloned from the
  installed app (the engine copies all splits).
- **Play Store updates** do not apply to clones.
- **Clones are visible under Settings → Apps** (they are real packages) but have
  no launcher icon.
- **Security:** the clone-signing key is embedded in the APK. It is a
  convenience/test key, not a secret; do not use clones for sensitive accounts.

See [docs/CLONING.md](docs/CLONING.md) for the full design and
[docs/VIRTUAL_DISPLAY.md](docs/VIRTUAL_DISPLAY.md) for the display code map.

## Automation API

The app exports a receiver that runs the same commands as the UI. Only
`START_ENGINE`, `STOP_ENGINE` and `PING` work without the control token; every
other action requires the token from
`/data/local/tmp/headless/control.token` (readable by shell/root only).

```sh
TOKEN=$(cat /data/local/tmp/headless/control.token)
am broadcast -a com.bliss.headless.action.START_ENGINE \
  -n com.bliss.headless/.ControlReceiver
sleep 9   # Shizuku user service + input bridge

am broadcast -a com.bliss.headless.action.CREATE_DISPLAY \
  --ei width 720 --ei height 1280 --ei dpi 320 --es token "$TOKEN" \
  -n com.bliss.headless/.ControlReceiver

am broadcast -a com.bliss.headless.action.CLONE_INSTALLED \
  --es package com.example.app --es name "Example clone" --es token "$TOKEN" \
  -n com.bliss.headless/.ControlReceiver

am broadcast -a com.bliss.headless.action.CLONE_LIST --es token "$TOKEN" \
  -n com.bliss.headless/.ControlReceiver
# find the clone package in logcat: RESULT CLONE_LIST {...}

am broadcast -a com.bliss.headless.action.CLONE_LAUNCH \
  --es package com.bliss.hclone.hXXXXXXXX --es token "$TOKEN" \
  -n com.bliss.headless/.ControlReceiver

am broadcast -a com.bliss.headless.action.TAP --ei x 360 --ei y 640 \
  --es token "$TOKEN" -n com.bliss.headless/.ControlReceiver

am broadcast -a com.bliss.headless.action.SNAPSHOT --es name shot.jpg \
  --es token "$TOKEN" -n com.bliss.headless/.ControlReceiver

am broadcast -a com.bliss.headless.action.CLONE_DELETE \
  --es package com.bliss.hclone.hXXXXXXXX --es token "$TOKEN" \
  -n com.bliss.headless/.ControlReceiver
```

Results appear under logcat tag `HeadlessControl`
(`RESULT <action> …` / `ERROR <action> …`). Engine details use
`HeadlessDisplay` and `HeadlessClone`.

## Build

With Gradle:

```sh
./gradlew assembleDebug
```

Without Gradle (used on the development device):

```sh
JAVA_HOME=/path/to/jdk17 \
ANDROID_JAR=/path/to/android-36/android.jar \
BUILD_TOOLS=/path/to/build-tools \
./scripts/build-apk.sh
# -> build/local/headless-android-debug.apk
```

Both builds consume the Java libraries in `app/libs/`:
`arsc-lib.jar` (ARSCLib, Apache-2.0) for binary resource/manifest editing and
`apksig.jar` (Android apksig, Apache-2.0) for APK signing.

## Repository layout

```
app/src/main/java/com/bliss/headless/
  VirtualDisplaySession.java   display creation + ImageReader surface
  DisplayEngine.java           display lifecycle, input, wake lock, clones
  DisplayService.java          Shizuku shell service + Binder commands
  EngineClient.java            app-side Binder client
  InputBridge.java             display-scoped input injection
  AppCatalog.java              app listing + am start --display
  CloneManager.java            repackaging clone engine
  ControlReceiver.java         shell/root automation broadcasts
  MainActivity.java            UI: display, preview, apps, clones, input
  Downloads.java               snapshot saving via MediaStore
  Protocol.java                command codes / Binder version
app/src/main/assets/clone.keystore   clone signing key (test key)
app/libs/arsc-lib.jar                ARSCLib (Apache-2.0)
app/libs/apksig.jar                  apksig (Apache-2.0)
docs/CLONING.md                      clone engine design + limitations
docs/VIRTUAL_DISPLAY.md              display extraction/code map
```

## Credits

- Virtual display engine derived from
  [ShadowAuto](https://github.com/HakaI963/ShadowAuto) (Apache-2.0).
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API) (MIT).
- [ARSCLib](https://github.com/REAndroid/ARSCLib) (Apache-2.0).
- [apksig](https://android.googlesource.com/platform/tools/apksig/)
  (Apache-2.0).
