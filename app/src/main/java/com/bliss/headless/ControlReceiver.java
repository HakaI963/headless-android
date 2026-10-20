package com.bliss.headless;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Headless automation interface: the same engine commands the UI uses, callable
 * with {@code am broadcast} from a root/shell context.
 *
 * <p>All results are written to logcat with the {@code HeadlessControl} tag:
 * {@code RESULT <action> <payload>} or {@code ERROR <action> <reason>}.
 *
 * <p>Security: apart from the bootstrap actions (START_ENGINE, STOP_ENGINE,
 * PING), every broadcast must carry the engine's control token. The token lives
 * in {@code /data/local/tmp/headless/control.token}, which only shell/root can
 * read (directory 0770, file 0640).
 *
 * <pre>
 * adb shell am broadcast -a com.bliss.headless.action.START_ENGINE -n com.bliss.headless/.ControlReceiver
 * TOKEN=$(cat /data/local/tmp/headless/control.token)
 * adb shell am broadcast -a com.bliss.headless.action.CREATE_DISPLAY --ei width 720 --ei height 1280 --ei dpi 320 --es token "$TOKEN" -n ...
 * adb shell am broadcast -a com.bliss.headless.action.CLONE_INSTALLED --es package com.example --es token "$TOKEN" -n ...
 * adb shell am broadcast -a com.bliss.headless.action.CLONE_LAUNCH --es package com.bliss.hclone.1234abcd --es token "$TOKEN" -n ...
 * adb shell am broadcast -a com.bliss.headless.action.SNAPSHOT --es token "$TOKEN" -n ...
 * </pre>
 */
public class ControlReceiver extends BroadcastReceiver {
    public static final String ACTION_PREFIX = "com.bliss.headless.action.";
    private static final String TAG = "HeadlessControl";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "headless-control");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null || !action.startsWith(ACTION_PREFIX)) {
            return;
        }
        String command = action.substring(ACTION_PREFIX.length());
        Context appContext = context.getApplicationContext();
        PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                handle(appContext, command, intent);
            } finally {
                pending.finish();
            }
        });
    }

    private void handle(Context context, String command, Intent intent) {
        EngineClient client = EngineClient.get(context);
        if (!isBootstrap(command)) {
            ensureConnected(client);
            JSONObject auth = parse(client.auth(intent.getStringExtra("token")));
            if (auth == null || !auth.optBoolean("ok", false)) {
                // A stale binder can look alive right after process death; the
                // reconnection usually lands within a couple of seconds.
                SystemClock.sleep(1500);
                ensureConnected(client);
                auth = parse(client.auth(intent.getStringExtra("token")));
            }
            if (auth == null || !auth.optBoolean("ok", false)) {
                error(command, "auth failed; pass --es token <"
                        + "/data/local/tmp/headless/control.token>");
                return;
            }
        }
        try {
            switch (command) {
                case "PING":
                    result(command, "pong");
                    break;
                case "START_ENGINE": {
                    String error = client.startService();
                    if (error != null) {
                        error(command, error);
                        return;
                    }
                    ensureConnected(client);
                    result(command, client.isServiceConnected() ? "connected" : "not connected yet");
                    break;
                }
                case "STOP_ENGINE":
                    client.stopService();
                    result(command, "stopped");
                    break;
                case "STATUS":
                    result(command, client.status());
                    break;
                case "LOG":
                    result(command, client.logTail(intent.getIntExtra("lines", 60)));
                    break;
                case "CREATE_DISPLAY":
                    result(command, client.createDisplay(
                            intent.getIntExtra("width", 720),
                            intent.getIntExtra("height", 1280),
                            intent.getIntExtra("dpi", 320)));
                    break;
                case "RELEASE_DISPLAY":
                    result(command, client.releaseDisplay());
                    break;
                case "LAUNCH_APP":
                    result(command, client.launchApp(
                            intent.getStringExtra("package"),
                            intent.getBooleanExtra("force", false)));
                    break;
                case "TAP":
                    result(command, client.tap(
                            intent.getIntExtra("x", 0), intent.getIntExtra("y", 0)));
                    break;
                case "SWIPE":
                    result(command, client.swipe(
                            intent.getIntExtra("x1", 0), intent.getIntExtra("y1", 0),
                            intent.getIntExtra("x2", 0), intent.getIntExtra("y2", 0),
                            intent.getIntExtra("duration", 300)));
                    break;
                case "KEY":
                    result(command, client.key(intent.getIntExtra("code", 4)));
                    break;
                case "TEXT":
                    result(command, client.text(intent.getStringExtra("text")));
                    break;

                // ---------------------------------------------------- clones

                case "CLONE_LIST":
                    result(command, client.cloneList());
                    break;
                case "CLONE_INSTALLED":
                    result(command, client.cloneFromInstalled(
                            intent.getStringExtra("package"),
                            intent.getStringExtra("name")));
                    break;
                case "CLONE_APK": {
                    String path = intent.getStringExtra("path");
                    if (path == null || path.isEmpty()) {
                        error(command, "missing path");
                        return;
                    }
                    try (ParcelFileDescriptor descriptor =
                                 ParcelFileDescriptor.open(new File(path),
                                         ParcelFileDescriptor.MODE_READ_ONLY)) {
                        result(command, client.cloneFromApk(descriptor,
                                intent.getStringExtra("name")));
                    }
                    break;
                }
                case "CLONE_LAUNCH":
                    result(command, client.cloneLaunch(intent.getStringExtra("package")));
                    break;
                case "CLONE_KILL":
                    result(command, client.cloneKill(intent.getStringExtra("package")));
                    break;
                case "CLONE_DELETE":
                    result(command, client.cloneDelete(intent.getStringExtra("package")));
                    break;

                // ------------------------------------------------- snapshots

                case "SNAPSHOT": {
                    EngineClient.Frame frame = client.capture(3000, 90);
                    if (!frame.ok()) {
                        error(command, frame.error);
                        return;
                    }
                    Bitmap bitmap = BitmapFactory.decodeByteArray(
                            frame.jpeg, 0, frame.jpeg.length);
                    String stats = stats(bitmap);
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    String name = intent.getStringExtra("name");
                    if (name == null || name.isEmpty()) {
                        name = "snapshot-" + timestamp() + ".jpg";
                    }
                    String where = Downloads.save(context, name, "image/jpeg", frame.jpeg);
                    result(command, where + " " + stats);
                    break;
                }
                default:
                    error(command, "unknown action");
            }
        } catch (Throwable e) {
            error(command, String.valueOf(e));
        }
    }

    private static String stats(Bitmap bitmap) {
        if (bitmap == null) {
            return "stats=decode_failed";
        }
        int steps = 32;
        long red = 0;
        long green = 0;
        long blue = 0;
        long bright = 0;
        int count = 0;
        for (int ix = 0; ix < steps; ix++) {
            for (int iy = 0; iy < steps; iy++) {
                int x = (int) ((ix + 0.5) * bitmap.getWidth() / steps);
                int y = (int) ((iy + 0.5) * bitmap.getHeight() / steps);
                int pixel = bitmap.getPixel(Math.min(x, bitmap.getWidth() - 1),
                        Math.min(y, bitmap.getHeight() - 1));
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                red += r;
                green += g;
                blue += b;
                if (Math.max(r, Math.max(g, b)) > 16) {
                    bright++;
                }
                count++;
            }
        }
        return String.format(Locale.US,
                "size=%dx%d mean=(%d,%d,%d) nonblack=%.2f",
                bitmap.getWidth(), bitmap.getHeight(),
                red / count, green / count, blue / count, bright / (double) count);
    }

    private static void result(String command, String payload) {
        Log.i(TAG, "RESULT " + command + " " + payload);
    }

    private static void error(String command, String message) {
        Log.w(TAG, "ERROR " + command + " " + message);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static boolean isBootstrap(String command) {
        return "START_ENGINE".equals(command)
                || "STOP_ENGINE".equals(command)
                || "PING".equals(command);
    }

    private static void ensureConnected(EngineClient client) {
        if (client.isServiceConnected()) {
            return;
        }
        client.startService();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline && !client.isServiceConnected()) {
            SystemClock.sleep(200);
        }
    }

    private static JSONObject parse(String json) {
        try {
            return new JSONObject(json);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
