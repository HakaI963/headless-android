# Virtual display: what was extracted from ShadowAuto

This document is the code map produced while reading the ShadowAuto fork
(`HakaI963/ShadowAuto`, `main`) and extracting **only** the virtual display
engine. Everything OCR-, shell-, AI- and web-related was left out.

## Original architecture (ShadowAuto)

ShadowAuto needs a shell-identity process to create a real headless display:

```
controller-app ──localhost JSON-RPC :43110──> android-shell (app_process, shell UID)
                                                 ├─ VirtualDisplaySession   (DisplayManager)
                                                 ├─ VideoStreamer           (MediaCodec H.264)
                                                 ├─ UiBridge / OcrTest      (UiAutomation, OCR)
                                                 ├─ InputBridge             (InputManager)
                                                 ├─ AppCatalog              (am start --display)
                                                 └─ AutomationEngine / AiClient / RPC
```

The Shizuku-based fork (`Raphele`) replaced the manual `adb shell app_process`
step with a Shizuku **UserService** (`ShellUserService`) but kept the same
localhost RPC server and all non-display subsystems.

## What this repo keeps

| ShadowAuto file | Here | Change |
|---|---|---|
| `android-shell/.../VirtualDisplaySession.java` | `VirtualDisplaySession.java` | Renders straight into the `ImageReader` surface (no MediaCodec surface, no surface flip around capture). Same hidden-flag fallback list (`SUPPORTS_TOUCH`, `OWN_FOCUS`, `TRUSTED`), same `ensureOn()` surface-toggle workaround, same IME policy bridge. |
| `android-shell/.../ScreenCapture.java` | `ScreenCapture.java` | Kept (row/pixel stride handling). |
| `android-shell/.../InputBridge.java` | `InputBridge.java` | Kept; `tap`, `longPress`, `swipe`, `key`, `text`, all via `InputManager.injectInputEvent` + `InputEvent.setDisplayId`. Availability is reported instead of throwing at construction. |
| `android-shell/.../WindowManagerBridge.java` | `WindowManagerBridge.java` | Kept (local IME policy only). |
| `android-shell/.../AppCatalog.java` | `AppCatalog.java` | Kept launch/list parts; `launchHome` dropped; command execution simplified to `/system/bin/am start --display`. |
| `android-shell/.../DisplaySpec.java` | inlined in `DisplayEngine`/UI | Device `wm size/density` probing replaced by user-editable 720×1280 @320 default. |
| `controller/.../ShizukuServerManager.java` | `EngineClient.java` | Kept Shizuku permission/bind/unbind flow. All OCR status polling, RPC port probing, server-version handshake and stop/rebind gymnastics removed. |
| `android-shell/.../ShellUserService.java` | `DisplayService.java` | Shizuku UserService kept, but **direct Binder transactions** replace the JSON-RPC server. `onTransact` command table: status/create/release/listApps/launch/capture/tap/swipe/key/text/clone*/logTail/auth/shutdown. |
| — | `DisplayEngine.java` | New orchestration layer (display lifecycle, input, wake lock, log tail, clone delegation). |
| — | `CloneManager.java` | New: repackage + sign + install + launch + delete clones (see [CLONING.md](CLONING.md)). |
| — | `ControlReceiver.java` | New: token-gated shell/root automation API for every engine command. |
| — | `AppCatalog.java` | Adds a timeout overload used by clone install/patch commands. |

## What was deliberately dropped

- `tess-ocr` / `paddler-ocr`, `OcrTest`, OCR assets and native `.so` files
- `AiClient`, `ActionDecision`, `ActionTools`, the whole ReAct loop
- `UiBridge`, `UiNodeUtils`, `McpSessions`, `ClipboardBridge`, `LogHub`
- `VideoStreamer` (MediaCodec H.264) — preview now uses JPEG frames from the
  same `ImageReader`, which is enough for a control UI and removes a codec
  surface from the display
- `RpcServer` + `JsonRpc` (no localhost socket; Binder only)
- `web-launcher` (Svelte/TangoADB) and the Gradle `android-stubs` module
- `android-shell` and `controller-app` as separate APKs (one unified APK now)

## Why Shizuku is still there

Creating a `PUBLIC` display that can host other apps, and running
`am start --display <id>`, needs shell privileges. Removing Shizuku would leave
only `MediaProjection`, which mirrors the physical screen and cannot host a
separate headless app session. The Shizuku surface kept here is ~200 lines
(permission + bind + Binder), nothing else.

## Display power on HyperOS

The virtual display is composited only while the phone screen is on. HyperOS
puts virtual displays to sleep with the physical display, so a snapshot taken
after the screen times out is black even though the display state still reports
`ON`. `DisplayEngine` therefore acquires a `FULL_WAKE_LOCK` with
`ACQUIRE_CAUSES_WAKEUP` when a display is created and releases it when the
display is released; `MainActivity` also sets `FLAG_KEEP_SCREEN_ON`. A secure
keyguard cannot be bypassed by the app — unlock the phone before using the
display.

## Clones on the display

Clones are not Android users. `CloneManager` repackages the APK under a new
package name and signature, installs it in user 0, and launches its recorded
activity with `am start --display`. Because user 0 is the foreground user, the
activity is visible and receives `InputManager` events scoped to the display.
Shell clone users are deliberately not used: Android/HyperOS stops activities
of non-foreground users, so tasks created with `am start --user <U> --display
<D>` never become visible. Details and limitations: [CLONING.md](CLONING.md).
