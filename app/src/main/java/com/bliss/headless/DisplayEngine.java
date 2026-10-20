package com.bliss.headless;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shell-side state machine: owns the virtual display, the input bridge and the
 * self-test, and answers Binder commands from the UI process.
 */
final class DisplayEngine {
    private static final String TAG = "HeadlessDisplay";
    private static final String SELF_TEST_TAG = "HeadlessSelfTest";
    private static final int MAX_LOG_LINES = 300;

    private final Context context;
    private final Object lock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private VirtualDisplaySession display;
    private InputBridge input;
    private volatile SelfTest selfTest;
    private volatile byte[] evidenceBefore;
    private volatile byte[] evidenceAfter;
    private String lastError = "";
    private final long createdUptime = SystemClock.uptimeMillis();

    DisplayEngine(Context context) {
        if (context == null) {
            throw new IllegalStateException("no Context available in the shell service");
        }
        this.context = context;
        log("engine created: uid=" + Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " model=" + Build.MODEL
                + " product=" + Build.PRODUCT);
    }

    // ---------------------------------------------------------------- status

    String statusJson() {
        JSONObject object = new JSONObject();
        synchronized (lock) {
            try {
                VirtualDisplaySession session = display;
                object.put("service_version", Protocol.VERSION);
                object.put("uid", Process.myUid());
                object.put("sdk", Build.VERSION.SDK_INT);
                object.put("model", Build.MODEL);
                object.put("uptime_ms", SystemClock.uptimeMillis() - createdUptime);
                object.put("display_ready", session != null);
                object.put("display_id", session == null ? -1 : session.displayId);
                object.put("display_width", session == null ? 0 : session.width);
                object.put("display_height", session == null ? 0 : session.height);
                object.put("display_dpi", session == null ? 0 : session.dpi);
                object.put("display_state", session == null ? "" : session.stateName());
                object.put("display_flags", session == null ? "" : session.flagsLabel());
                object.put("display_frames", session == null ? 0 : session.frameCount());
                object.put("display_last_frame_ms", session == null ? 0 : session.lastFrameAt());
                object.put("ime_local", session != null && session.localIme());
                object.put("input_ready", input != null && input.available());
                object.put("input_error", input == null
                        ? "not initialised" : input.unavailableReason());
                object.put("error", lastError);
            } catch (Throwable ignored) {
            }
        }
        return object.toString();
    }

    String listAppsJson() {
        try {
            return AppCatalog.appsJson(context);
        } catch (Throwable e) {
            return "[]";
        }
    }

    String logTailJson(int maxLines) {
        JSONArray array = new JSONArray();
        synchronized (logLines) {
            int skip = Math.max(0, logLines.size() - Math.max(1, maxLines));
            int index = 0;
            for (String line : logLines) {
                if (index++ >= skip) {
                    array.put(line);
                }
            }
        }
        return array.toString();
    }

    // --------------------------------------------------------------- display

    String createDisplay(int width, int height, int dpi) {
        synchronized (lock) {
            releaseLocked();
            int safeWidth = clamp(width, 320, 2160, 720);
            int safeHeight = clamp(height, 480, 3840, 1280);
            int safeDpi = clamp(dpi, 120, 640, 320);
            try {
                display = VirtualDisplaySession.create(context, safeWidth, safeHeight, safeDpi);
                boolean on = display.ensureOn();
                ensureInput();
                lastError = "";
                log("display created id=" + display.displayId
                        + " " + safeWidth + "x" + safeHeight + " @" + safeDpi
                        + " state=" + display.stateName()
                        + (on ? "" : " (still not ON after surface toggle)")
                        + " flags=" + display.flagsLabel());
                return Jsons.ok("display " + display.displayId + " "
                        + safeWidth + "x" + safeHeight + " @" + safeDpi
                        + " state=" + display.stateName());
            } catch (Throwable e) {
                lastError = message(e);
                log("display create failed: " + message(e));
                return Jsons.error(message(e));
            }
        }
    }

    String releaseDisplay() {
        synchronized (lock) {
            if (display == null) {
                return Jsons.ok("no display");
            }
            int id = display.displayId;
            releaseLocked();
            log("display " + id + " released");
            return Jsons.ok("display " + id + " released");
        }
    }

    void releaseAll() {
        synchronized (lock) {
            releaseLocked();
        }
    }

    private void releaseLocked() {
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
    }

    private void ensureInput() {
        if (input == null) {
            input = new InputBridge(context);
            log("input bridge: " + (input.available()
                    ? "ready" : "unavailable: " + input.unavailableReason()));
        }
    }

    // --------------------------------------------------------------- actions

    String launchApp(String packageName, boolean forceStop) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
        }
        if (session == null) {
            return Jsons.error("no virtual display; create one first");
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            return Jsons.error("empty package name");
        }
        try {
            AppCatalog.CommandResult result = AppCatalog.launchPackage(
                    context, packageName.trim(), session.displayId, forceStop);
            log("launch " + packageName + " on display " + session.displayId
                    + " exit=" + result.exit + " " + oneLine(result.output));
            return result.ok()
                    ? Jsons.ok(result.output.isEmpty() ? "launched" : result.output)
                    : Jsons.error("am start exit=" + result.exit + " " + oneLine(result.output));
        } catch (Throwable e) {
            log("launch failed: " + message(e));
            return Jsons.error(message(e));
        }
    }

    String launchTestActivity() {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
        }
        if (session == null) {
            return Jsons.error("no virtual display; create one first");
        }
        try {
            ComponentName component = new ComponentName(context, TestPatternActivity.class);
            AppCatalog.CommandResult result = AppCatalog.launchComponent(
                    component, session.displayId, false);
            log("test activity launch exit=" + result.exit + " " + oneLine(result.output));
            return result.ok() ? Jsons.ok(result.output) : Jsons.error(
                    "am start exit=" + result.exit + " " + oneLine(result.output));
        } catch (Throwable e) {
            return Jsons.error(message(e));
        }
    }

    String tap(int x, int y) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
            ensureInput();
        }
        if (session == null) {
            return Jsons.error("no virtual display; create one first");
        }
        if (!input.available()) {
            return Jsons.error("input unavailable: " + input.unavailableReason());
        }
        try {
            boolean ok = input.tap(session.displayId, x, y);
            log("tap (" + x + "," + y + ") display=" + session.displayId + " ok=" + ok);
            return ok ? Jsons.ok("tap injected at " + x + "," + y)
                    : Jsons.error("injectInputEvent returned false");
        } catch (Throwable e) {
            log("tap failed: " + message(e));
            return Jsons.error(message(e));
        }
    }

    String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
            ensureInput();
        }
        if (session == null) {
            return Jsons.error("no virtual display; create one first");
        }
        if (!input.available()) {
            return Jsons.error("input unavailable: " + input.unavailableReason());
        }
        try {
            boolean ok = input.swipe(session.displayId, x1, y1, x2, y2, durationMs);
            log("swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2 + " ok=" + ok);
            return ok ? Jsons.ok("swipe injected") : Jsons.error("injectInputEvent returned false");
        } catch (Throwable e) {
            return Jsons.error(message(e));
        }
    }

    String key(int keyCode) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
            ensureInput();
        }
        if (session == null) {
            return Jsons.error("no virtual display; create one first");
        }
        if (!input.available()) {
            return Jsons.error("input unavailable: " + input.unavailableReason());
        }
        try {
            boolean ok = input.key(session.displayId, keyCode);
            log("key " + keyCode + " display=" + session.displayId + " ok=" + ok);
            return ok ? Jsons.ok("key injected") : Jsons.error("injectInputEvent returned false");
        } catch (Throwable e) {
            return Jsons.error(message(e));
        }
    }

    String text(String value) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
            ensureInput();
        }
        if (session == null) {
            return Jsons.error("no virtual display; create one first");
        }
        if (!input.available()) {
            return Jsons.error("input unavailable: " + input.unavailableReason());
        }
        try {
            boolean ok = input.text(session.displayId, value);
            log("text (" + (value == null ? 0 : value.length()) + " chars) ok=" + ok);
            return ok ? Jsons.ok("text injected") : Jsons.error("injectInputEvent returned false");
        } catch (Throwable e) {
            return Jsons.error(message(e));
        }
    }

    byte[] captureJpeg(int timeoutMs, int quality) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = display;
        }
        if (session == null) {
            return null;
        }
        return session.captureJpeg(timeoutMs, clamp(quality, 10, 100, 80));
    }

    byte[] evidence(int index) {
        return index == 0 ? evidenceBefore : evidenceAfter;
    }

    // -------------------------------------------------------------- self-test

    String startSelfTest() {
        synchronized (lock) {
            SelfTest running = selfTest;
            if (running != null && !running.done) {
                return selfTestStatusJson();
            }
            evidenceBefore = null;
            evidenceAfter = null;
            SelfTest test = new SelfTest();
            selfTest = test;
            Thread thread = new Thread(test, "headless-self-test");
            thread.setDaemon(true);
            thread.start();
            return selfTestStatusJson();
        }
    }

    String selfTestStatusJson() {
        JSONObject object = new JSONObject();
        SelfTest test = selfTest;
        try {
            if (test == null) {
                object.put("running", false);
                object.put("done", false);
                object.put("pass", false);
                object.put("elapsed_ms", 0);
                object.put("lines", new JSONArray());
                object.put("checks", new JSONArray());
                return object.toString();
            }
            List<String> lines = test.snapshotLines();
            List<JSONObject> checks = test.snapshotChecks();
            object.put("running", !test.done);
            object.put("done", test.done);
            object.put("pass", test.pass);
            object.put("elapsed_ms", SystemClock.uptimeMillis() - test.startedAt);
            object.put("lines", new JSONArray(lines));
            JSONArray checkArray = new JSONArray();
            for (JSONObject check : checks) {
                checkArray.put(check);
            }
            object.put("checks", checkArray);
        } catch (Throwable ignored) {
        }
        return object.toString();
    }

    private final class SelfTest implements Runnable {
        final long startedAt = SystemClock.uptimeMillis();
        private final List<String> lines = new ArrayList<>();
        private final List<JSONObject> checks = new ArrayList<>();
        volatile boolean done;
        volatile boolean pass;

        synchronized void step(String message) {
            String line = String.format(Locale.US, "[%.1fs] %s",
                    (SystemClock.uptimeMillis() - startedAt) / 1000.0, message);
            lines.add(line);
            Log.i(SELF_TEST_TAG, line);
        }

        synchronized boolean check(String name, boolean ok, String detail) {
            try {
                JSONObject object = new JSONObject();
                object.put("name", name);
                object.put("pass", ok);
                object.put("detail", detail == null ? "" : detail);
                checks.add(object);
            } catch (Throwable ignored) {
            }
            step((ok ? "PASS " : "FAIL ") + name + " — " + detail);
            return ok;
        }

        synchronized List<String> snapshotLines() {
            return new ArrayList<>(lines);
        }

        synchronized List<JSONObject> snapshotChecks() {
            return new ArrayList<>(checks);
        }

        @Override
        public void run() {
            boolean result = false;
            try {
                result = execute();
            } catch (Throwable e) {
                step("fatal: " + message(e));
            } finally {
                pass = result;
                done = true;
                step("RESULT: " + (result ? "PASS" : "FAIL"));
            }
        }

        private boolean execute() throws Exception {
            step("service uid=" + Process.myUid()
                    + " sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);

            VirtualDisplaySession session;
            synchronized (lock) {
                if (display == null) {
                    try {
                        display = VirtualDisplaySession.create(context, 720, 1280, 320);
                    } catch (Throwable e) {
                        step("create failed: " + message(e));
                    }
                }
                session = display;
                ensureInput();
            }
            boolean created = session != null;
            check("display_created", created, created
                    ? "id=" + session.displayId + " " + session.width + "x" + session.height
                      + " @" + session.dpi + " flags=" + session.flagsLabel()
                    : "createVirtualDisplay failed");
            if (!created) {
                return false;
            }

            boolean active = session.ensureOn();
            check("display_active", active,
                    "state=" + session.stateName() + " flags=" + session.flagsLabel());

            try {
                ComponentName component = new ComponentName(context, TestPatternActivity.class);
                AppCatalog.CommandResult launch =
                        AppCatalog.launchComponent(component, session.displayId, false);
                check("test_activity_launched", launch.ok(),
                        "am start exit=" + launch.exit + " " + oneLine(launch.output));
            } catch (Throwable e) {
                check("test_activity_launched", false, message(e));
            }

            // Wait for the pattern activity to render its four quadrant colors.
            long deadline = SystemClock.uptimeMillis() + 12000;
            Bitmap before = null;
            boolean patternOk = false;
            String patternDetail = "no frame within 12s";
            while (SystemClock.uptimeMillis() < deadline) {
                Bitmap frame = session.captureCopy(1200);
                if (frame != null) {
                    if (before != null) {
                        before.recycle();
                    }
                    before = frame;
                    patternDetail = FrameAnalysis.quadrantSummary(frame);
                    if (quadrantsMatch(frame, FrameAnalysis.C_TOP_LEFT, FrameAnalysis.C_TOP_RIGHT,
                            FrameAnalysis.C_BOTTOM_LEFT, FrameAnalysis.C_BOTTOM_RIGHT)) {
                        patternOk = true;
                        break;
                    }
                }
                SystemClock.sleep(400);
            }
            check("frame_captured", before != null, before == null
                    ? "no frame within 12s"
                    : before.getWidth() + "x" + before.getHeight()
                      + " after " + (SystemClock.uptimeMillis() - startedAt) + "ms total");
            check("pattern_colors", patternOk, patternOk
                    ? patternDetail
                    : patternDetail + " (expected magenta/yellow/cyan/indigo quadrants)");

            // Liveness: the pattern view sweeps a bar; two frames must differ.
            Bitmap frameA = session.captureCopy(1500);
            SystemClock.sleep(700);
            Bitmap frameB = session.captureCopy(1500);
            double diff = FrameAnalysis.meanDiff(frameA, frameB);
            if (frameA != null) {
                frameA.recycle();
            }
            if (frameB != null) {
                frameB.recycle();
            }
            check("frames_animating", diff > 0.8,
                    "mean_diff=" + String.format(Locale.US, "%.2f", diff)
                            + (diff > 0.8 ? "" : " (informational only)"));

            // Input: tap the centre of the virtual display and require the
            // activity to switch its whole background to green.
            boolean tapReached = false;
            String tapDetail = "not attempted";
            Bitmap after = null;
            if (input != null && input.available()) {
                for (int attempt = 1; attempt <= 3 && !tapReached; attempt++) {
                    boolean injected = input.tap(session.displayId,
                            session.width / 2, session.height / 2);
                    step("tap attempt " + attempt + " injected=" + injected
                            + " at (" + session.width / 2 + "," + session.height / 2 + ")");
                    long tapDeadline = SystemClock.uptimeMillis() + 2500;
                    while (SystemClock.uptimeMillis() < tapDeadline) {
                        SystemClock.sleep(250);
                        Bitmap frame = session.captureCopy(1000);
                        if (frame != null) {
                            if (after != null) {
                                after.recycle();
                            }
                            after = frame;
                            tapDetail = FrameAnalysis.quadrantSummary(frame);
                            if (FrameAnalysis.allQuadrants(frame, FrameAnalysis.C_TAPPED,
                                    FrameAnalysis.TOLERANCE)) {
                                tapReached = true;
                                break;
                            }
                        }
                    }
                }
            } else {
                tapDetail = "input unavailable";
            }
            check("tap_reached_display", tapReached, tapReached
                    ? tapDetail
                    : tapDetail + " (expected all-green quadrants)");

            // Store evidence frames for the UI (downloadable from the app).
            boolean haveBefore = before != null;
            try {
                if (before != null) {
                    evidenceBefore = encode(before, 85);
                }
                if (after != null) {
                    evidenceAfter = encode(after, 85);
                }
                step("evidence stored: before=" + size(evidenceBefore)
                        + "B after=" + size(evidenceAfter) + "B");
            } catch (Throwable e) {
                step("evidence encode failed: " + message(e));
            }
            if (before != null) {
                before.recycle();
            }
            if (after != null) {
                after.recycle();
            }

            return created && patternOk && tapReached && haveBefore;
        }

        private boolean quadrantsMatch(Bitmap bitmap, int topLeft, int topRight,
                                       int bottomLeft, int bottomRight) {
            return FrameAnalysis.near(FrameAnalysis.sample(bitmap, 0.25, 0.25),
                            topLeft, FrameAnalysis.TOLERANCE)
                    && FrameAnalysis.near(FrameAnalysis.sample(bitmap, 0.75, 0.25),
                            topRight, FrameAnalysis.TOLERANCE)
                    && FrameAnalysis.near(FrameAnalysis.sample(bitmap, 0.25, 0.75),
                            bottomLeft, FrameAnalysis.TOLERANCE)
                    && FrameAnalysis.near(FrameAnalysis.sample(bitmap, 0.75, 0.75),
                            bottomRight, FrameAnalysis.TOLERANCE);
        }
    }

    // ----------------------------------------------------------------- utils

    private void log(String message) {
        String line = String.format(Locale.US, "[%.2fs] %s",
                (SystemClock.uptimeMillis() - createdUptime) / 1000.0, message);
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
        Log.i(TAG, line);
    }

    private static byte[] encode(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private static int size(byte[] value) {
        return value == null ? 0 : value.length;
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').trim();
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
