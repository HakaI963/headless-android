# Self-test: how "it really works" is proven

The self-test is not a status ping. It creates the display, renders a known
activity **on that display**, reads the actual pixels back, injects a real
touch event and verifies that the screen changed because of it.

## Steps performed by the shell engine

1. **display_created** — `DisplayManager.createVirtualDisplay` returns a
   display with a real id. Hidden flags are tried from the richest set down to
   plain `PUBLIC|PRESENTATION|OWN_CONTENT_ONLY`; the accepted flag set is in the
   detail column.
2. **display_active** — `Display.getState()` is `ON`. If the ROM creates the
   virtual display in `OFF` state, the surface is detached and re-attached once
   and the check is repeated.
3. **test_activity_launched** — `am start --display <id> -f 0x10000000 -n
   com.bliss.headless/.TestPatternActivity`, executed with shell identity. Exit
   code and output are recorded.
4. **frame_captured** — an actual buffer is read from the `ImageReader` that
   backs the display (`captureCopy`), within 12 s.
5. **pattern_colors** — the four quadrant colors of `TestPatternActivity`
   (magenta / yellow / cyan / indigo) are sampled from the captured frame at
   25 %/75 % positions and compared channel-by-channel with ±70 tolerance.
   The detail shows the measured RGB values.
6. **frames_animating** — two frames 700 ms apart are compared on a 32×32 grid
   (mean absolute RGB difference). The pattern view sweeps a white bar, so a
   live display produces a clearly positive value. Informational, not gating.
7. **tap_reached_display** — a touch `ACTION_DOWN/UP` pair is injected into the
   display's centre with `InputManager.injectInputEvent` (`setDisplayId`), then
   the next frames must show the activity's green "tapped" background in all
   four quadrants. Up to 3 attempts.

`PASS` requires: display created, frame captured, pattern colors correct, tap
changed the screen — plus stored before/after evidence frames.

## Evidence

- The app shows the two evidence frames under the self-test log
  (*before tap* / *after tap*).
- **Save report + screenshots** writes to
  `Downloads/HeadlessAndroid/`:
  - `headless-selftest-<timestamp>.txt` — every check with measured values
  - `headless-before-<timestamp>.jpg` — quadrants visible
  - `headless-after-<timestamp>.jpg` — all green after the injected tap
- The same steps are written to logcat:

      adb logcat -s HeadlessSelfTest HeadlessDisplay

## Independent manual verification

1. Create the display, tap **Choose app…**, pick e.g. Settings, **Launch on
   display**. The preview must show Settings; the physical screen must not
   change.
2. Tap **Tap** with X/Y set to a button position — the virtual app reacts.
3. `adb shell dumpsys display | grep -A2 "HeadlessAndroid"` shows the display
   registered, and `adb shell dumpsys SurfaceFlinger --display-id <id>` shows
   the layer stack.

## If a check fails

The self-test log lists the measured value for each check, and *Log* in the app
shows the shell-side `am start` output and every engine step. The most common
cases:

| Symptom | Meaning |
|---|---|
| `create failed` | Shizuku service is not running as shell, or no flag set was accepted — the detail lists the last exception. |
| `frame_captured` fails | The activity never rendered: check the `am start` exit code in the log. |
| `pattern_colors` fails | The display renders but something else is on top (e.g. a previous app); release/recreate the display and re-run. |
| `tap_reached_display` fails | Input injection permission or touch flag issue; the log shows `input bridge: unavailable: …`. |
