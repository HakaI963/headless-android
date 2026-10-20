package com.bliss.headless;

import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Shell-side state machine: owns the virtual display, the input bridge and the
 * clone manager, and answers Binder commands from the UI process.
 */
final class DisplayEngine {
    private static final String TAG = "HeadlessDisplay";
    private static final int MAX_LOG_LINES = 400;

    private final Context context;
    private final Object lock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final CloneManager clones;
    private final String controlToken;

    private VirtualDisplaySession display;
    private InputBridge input;
    private PowerManager.WakeLock wakeLock;
    private String lastError = "";
    private final long createdUptime = SystemClock.uptimeMillis();

    DisplayEngine(Context context) {
        if (context == null) {
            throw new IllegalStateException("no Context available in the shell service");
        }
        this.context = context;
        this.clones = new CloneManager(context, this);
        this.controlToken = loadOrCreateControlToken();
        log("engine created: uid=" + Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " model=" + Build.MODEL
                + " product=" + Build.PRODUCT);
    }

    // ---------------------------------------------------------------- status

    int displayId() {
        synchronized (lock) {
            return display == null ? -1 : display.displayId;
        }
    }

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
                acquireWakeLock();
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
        releaseWakeLock();
    }

    /**
     * Keeps the physical screen on while the virtual display exists. HyperOS
     * suspends virtual displays together with the phone screen; without this
     * the clone freezes as soon as the screen times out.
     */
    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return;
            }
            wakeLock = power.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HeadlessAndroid:display");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            log("wake lock acquired (screen stays on while the display exists)");
        } catch (Throwable e) {
            wakeLock = null;
            log("wake lock failed: " + e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        log("wake lock released");
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

    String tap(int x, int y) {
        return inputAction("tap", input -> {
            boolean ok = input.tap(displayId(), x, y);
            return ok ? Jsons.ok("tap injected at " + x + "," + y)
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return inputAction("swipe", input -> {
            boolean ok = input.swipe(displayId(), x1, y1, x2, y2, durationMs);
            return ok ? Jsons.ok("swipe injected")
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    String key(int keyCode) {
        return inputAction("key", input -> {
            boolean ok = input.key(displayId(), keyCode);
            return ok ? Jsons.ok("key injected")
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    String text(String value) {
        return inputAction("text", input -> {
            boolean ok = input.text(displayId(), value);
            return ok ? Jsons.ok("text injected")
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    private interface InputCall {
        String run(InputBridge input) throws Exception;
    }

    private String inputAction(String name, InputCall call) {
        InputBridge bridge;
        synchronized (lock) {
            ensureInput();
            bridge = input;
            if (display == null) {
                return Jsons.error("no virtual display; create one first");
            }
        }
        if (bridge == null || !bridge.available()) {
            return Jsons.error("input unavailable: "
                    + (bridge == null ? "not initialised" : bridge.unavailableReason()));
        }
        try {
            String result = call.run(bridge);
            log(name + " -> " + oneLine(result));
            return result;
        } catch (Throwable e) {
            log(name + " failed: " + message(e));
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

    // ------------------------------------------------------------------ auth

    boolean auth(String token) {
        return token != null && controlToken.equals(token.trim());
    }

    private String loadOrCreateControlToken() {
        File dir = new File("/data/local/tmp/headless");
        File file = new File(dir, "control.token");
        try {
            if (file.exists()) {
                byte[] buffer = new byte[(int) Math.min(128, file.length())];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int read = in.read(buffer);
                    if (read > 0) {
                        String existing = new String(buffer, 0, read, "UTF-8").trim();
                        if (existing.matches("[0-9a-f]{32}")) {
                            log("control token loaded: " + file.getAbsolutePath());
                            return existing;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String token = newControlToken();
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                log("cannot create control token directory");
                return token;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(token.getBytes("UTF-8"));
            }
            AppCatalog.run("chmod 770 " + dir.getAbsolutePath()
                    + "; chmod 640 " + file.getAbsolutePath());
            log("control token created: " + file.getAbsolutePath());
        } catch (Throwable e) {
            log("control token setup failed: " + e);
        }
        return token;
    }

    private static String newControlToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }

    // ----------------------------------------------------------------- clones

    String cloneListJson() {
        return clones.listJson();
    }

    String cloneFromInstalled(String packageName, String name) {
        return clones.createFromInstalled(packageName, name);
    }

    String cloneFromApk(ParcelFileDescriptor descriptor, String name) {
        return clones.createFromApk(descriptor, name);
    }

    String cloneLaunch(String clonePackage) {
        return clones.launch(clonePackage);
    }

    String cloneKill(String clonePackage) {
        return clones.kill(clonePackage);
    }

    String cloneDelete(String clonePackage) {
        return clones.delete(clonePackage);
    }

    // ----------------------------------------------------------------- utils

    void log(String message) {
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
