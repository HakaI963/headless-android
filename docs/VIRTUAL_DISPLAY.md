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
| `android-shell/.../ShellUserService.java` | `DisplayService.java` | Shizuku UserService kept, but **direct Binder transactions** replace the JSON-RPC server. `onTransact` command table: status/create/release/listApps/launch/capture/tap/swipe/key/text/self-test/evidence/logTail/shutdown. |
| — | `DisplayEngine.java` | New orchestration layer (display lifecycle, input, log tail, self-test). |
| — | `FrameAnalysis.java`, `TestPatternActivity.java` | New: deterministic pixels + quadrant color checks + tap-change check. |

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
