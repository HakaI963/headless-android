package com.bliss.headless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements EngineClient.Listener {
    private static final String TAG = "HeadlessDisplay";
    private static final int KEYCODE_BACK = 4;
    private static final int KEYCODE_HOME = 3;

    private EngineClient client;
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean previewInFlight = new AtomicBoolean(false);

    private TextView txtShizukuStatus;
    private Button btnGrant;
    private Button btnStart;
    private Button btnStop;
    private Button btnOpenShizuku;

    private EditText editWidth;
    private EditText editHeight;
    private EditText editDpi;
    private Button btnCreate;
    private Button btnRelease;
    private TextView txtDisplayStatus;

    private ImageView imgPreview;
    private TextView txtPreviewStatus;
    private Button btnPreview;
    private Button btnSnapshot;

    private TextView txtSelectedApp;
    private Button btnChooseApp;
    private Button btnRefreshApps;
    private Button btnLaunchApp;
    private CheckBox checkForceStop;

    private EditText editTapX;
    private EditText editTapY;
    private Button btnTap;
    private Button btnSwipeUp;
    private Button btnBack;
    private Button btnHome;
    private EditText editTextValue;
    private Button btnSendText;

    private Button btnSelfTest;
    private TextView txtTestBadge;
    private TextView txtSelfTest;
    private ImageView imgBefore;
    private ImageView imgAfter;
    private Button btnSaveEvidence;

    private TextView txtLog;
    private Button btnClearLog;

    private boolean previewRunning;
    private boolean previewUserPaused;
    private Bitmap previewBitmap;
    private long previewFrames;
    private long previewWindowStart;
    private boolean selfTestPolling;
    private boolean evidenceLoaded;
    private String selectedPackage = "";
    private final List<String> appLabels = new ArrayList<>();
    private final List<String> appPackages = new ArrayList<>();
    private String lastSelfTestJson = "";
    private int displayWidth = 720;
    private int displayHeight = 1280;
    private boolean displayReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        client = ((App) getApplication()).client();
        io = Executors.newFixedThreadPool(3);

        txtShizukuStatus = findViewById(R.id.txtShizukuStatus);
        btnGrant = findViewById(R.id.btnGrant);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnOpenShizuku = findViewById(R.id.btnOpenShizuku);

        editWidth = findViewById(R.id.editWidth);
        editHeight = findViewById(R.id.editHeight);
        editDpi = findViewById(R.id.editDpi);
        btnCreate = findViewById(R.id.btnCreate);
        btnRelease = findViewById(R.id.btnRelease);
        txtDisplayStatus = findViewById(R.id.txtDisplayStatus);

        imgPreview = findViewById(R.id.imgPreview);
        txtPreviewStatus = findViewById(R.id.txtPreviewStatus);
        btnPreview = findViewById(R.id.btnPreview);
        btnSnapshot = findViewById(R.id.btnSnapshot);

        txtSelectedApp = findViewById(R.id.txtSelectedApp);
        btnChooseApp = findViewById(R.id.btnChooseApp);
        btnRefreshApps = findViewById(R.id.btnRefreshApps);
        btnLaunchApp = findViewById(R.id.btnLaunchApp);
        checkForceStop = findViewById(R.id.checkForceStop);

        editTapX = findViewById(R.id.editTapX);
        editTapY = findViewById(R.id.editTapY);
        btnTap = findViewById(R.id.btnTap);
        btnSwipeUp = findViewById(R.id.btnSwipeUp);
        btnBack = findViewById(R.id.btnBack);
        btnHome = findViewById(R.id.btnHome);
        editTextValue = findViewById(R.id.editTextValue);
        btnSendText = findViewById(R.id.btnSendText);

        btnSelfTest = findViewById(R.id.btnSelfTest);
        txtTestBadge = findViewById(R.id.txtTestBadge);
        txtSelfTest = findViewById(R.id.txtSelfTest);
        imgBefore = findViewById(R.id.imgBefore);
        imgAfter = findViewById(R.id.imgAfter);
        btnSaveEvidence = findViewById(R.id.btnSaveEvidence);

        txtLog = findViewById(R.id.txtLog);
        btnClearLog = findViewById(R.id.btnClearLog);

        btnOpenShizuku.setOnClickListener(v -> {
            if (!client.openShizukuManager()) {
                toast("Shizuku app not found");
            }
        });
        btnGrant.setOnClickListener(v -> {
            String reason = client.requestPermission();
            toast(reason == null ? "Shizuku permission dialog requested" : reason);
            refreshState();
        });
        btnStart.setOnClickListener(v -> {
            String reason = client.startService();
            toast(reason == null ? "Starting shell engine…" : reason);
            refreshState();
        });
        btnStop.setOnClickListener(v -> {
            stopPreview();
            client.stopService();
            displayReady = false;
            appendLog("engine stopped by user");
            refreshState();
        });

        btnCreate.setOnClickListener(v -> {
            int width = parseInt(editWidth, 720);
            int height = parseInt(editHeight, 1280);
            int dpi = parseInt(editDpi, 320);
            if (width < 320 || height < 480) {
                toast("Minimum display size is 320x480");
                return;
            }
            displayWidth = width;
            displayHeight = height;
            previewUserPaused = false;
            io.execute(() -> {
                String result = client.createDisplay(width, height, dpi);
                main.post(() -> {
                    appendLog("create: " + result);
                    refreshState();
                    startPreview();
                });
            });
        });
        btnRelease.setOnClickListener(v -> {
            stopPreview();
            io.execute(() -> {
                String result = client.releaseDisplay();
                main.post(() -> {
                    appendLog("release: " + result);
                    displayReady = false;
                    refreshState();
                });
            });
        });

        btnPreview.setOnClickListener(v -> {
            if (previewRunning) {
                previewUserPaused = true;
                stopPreview();
            } else {
                previewUserPaused = false;
                startPreview();
            }
        });
        btnSnapshot.setOnClickListener(v -> io.execute(() -> {
            EngineClient.Frame frame = client.capture(1500, 92);
            if (!frame.ok()) {
                main.post(() -> toast("snapshot failed: " + frame.error));
                return;
            }
            String name = "headless-snapshot-" + timestamp() + ".jpg";
            String location = saveBytes(name, "image/jpeg", frame.jpeg);
            main.post(() -> {
                appendLog("snapshot saved: " + location);
                toast("Saved: " + location);
            });
        }));

        btnRefreshApps.setOnClickListener(v -> refreshApps());
        btnChooseApp.setOnClickListener(v -> chooseApp());
        btnLaunchApp.setOnClickListener(v -> launchSelectedApp());

        btnTap.setOnClickListener(v -> {
            int x = parseInt(editTapX, displayWidth / 2);
            int y = parseInt(editTapY, displayHeight / 2);
            io.execute(() -> {
                String result = client.tap(x, y);
                main.post(() -> appendLog("tap " + x + "," + y + ": " + result));
            });
        });
        btnSwipeUp.setOnClickListener(v -> io.execute(() -> {
            int x = displayWidth / 2;
            String result = client.swipe(x, (int) (displayHeight * 0.75),
                    x, (int) (displayHeight * 0.25), 300);
            main.post(() -> appendLog("swipe up: " + result));
        }));
        btnBack.setOnClickListener(v -> io.execute(() -> {
            String result = client.key(KEYCODE_BACK);
            main.post(() -> appendLog("key back: " + result));
        }));
        btnHome.setOnClickListener(v -> io.execute(() -> {
            String result = client.key(KEYCODE_HOME);
            main.post(() -> appendLog("key home: " + result));
        }));
        btnSendText.setOnClickListener(v -> {
            String value = editTextValue.getText().toString();
            if (value.isEmpty()) {
                toast("Type some text first");
                return;
            }
            io.execute(() -> {
                String result = client.text(value);
                main.post(() -> appendLog("text: " + result));
            });
        });

        btnSelfTest.setOnClickListener(v -> startSelfTest());
        btnSaveEvidence.setOnClickListener(v -> saveEvidence());
        btnClearLog.setOnClickListener(v -> txtLog.setText(""));

        client.addListener(this);
        appendLog("ready. Grant Shizuku permission, then Start engine.");
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!selfTestPolling) {
            stopPreview();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.removeListener(this);
        stopPreview();
        io.shutdownNow();
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
    }

    // ------------------------------------------------------------- listener

    @Override
    public void onStateChanged() {
        refreshState();
    }

    private void refreshState() {
        boolean manager = client.isManagerInstalled();
        boolean alive = client.isBinderAlive();
        boolean permission = client.hasPermission();
        boolean connected = client.isServiceConnected();

        StringBuilder status = new StringBuilder();
        if (!manager) {
            status.append("Shizuku not installed — install it and start the service.");
        } else if (!alive) {
            status.append("Shizuku installed, service not running — open Shizuku and start it.");
        } else {
            status.append("Shizuku ").append(client.shizukuVersion()).append(" running");
            status.append(permission ? ", permission granted" : ", permission needed");
            status.append(connected ? ", shell engine connected" : ", shell engine not connected");
        }
        txtShizukuStatus.setText(status.toString());
        txtShizukuStatus.setTextColor(getColor(connected ? R.color.success
                : (alive && permission ? R.color.warning : R.color.danger)));

        btnGrant.setEnabled(manager && alive && !permission);
        btnStart.setEnabled(manager && alive && permission && !connected);
        btnStop.setEnabled(connected);
        btnOpenShizuku.setEnabled(manager);

        if (!connected) {
            txtDisplayStatus.setText("no shell engine — tap Start engine");
            btnCreate.setEnabled(false);
            btnRelease.setEnabled(false);
            btnChooseApp.setEnabled(false);
            btnRefreshApps.setEnabled(false);
            btnLaunchApp.setEnabled(false);
            btnTap.setEnabled(false);
            btnSwipeUp.setEnabled(false);
            btnBack.setEnabled(false);
            btnHome.setEnabled(false);
            btnSendText.setEnabled(false);
            btnSnapshot.setEnabled(false);
            btnSelfTest.setEnabled(false);
            btnPreview.setEnabled(false);
            return;
        }

        btnChooseApp.setEnabled(true);
        btnRefreshApps.setEnabled(true);
        btnSelfTest.setEnabled(!selfTestPolling);
        btnPreview.setEnabled(true);
        btnSnapshot.setEnabled(displayReady);

        // Engine status gives the real display state.
        io.execute(() -> {
            String json = client.status();
            main.post(() -> renderEngineStatus(json));
        });
    }

    private void renderEngineStatus(String json) {
        if (!client.isServiceConnected()) {
            return;
        }
        JSONObject object = parse(json);
        if (object == null) {
            txtDisplayStatus.setText("status parse failed");
            return;
        }
        displayReady = object.optBoolean("display_ready", false);
        if (displayReady) {
            displayWidth = object.optInt("display_width", displayWidth);
            displayHeight = object.optInt("display_height", displayHeight);
            String text = "display #" + object.optInt("display_id", -1)
                    + " · " + object.optString("display_state", "?")
                    + " · " + displayWidth + "x" + displayHeight
                    + " @" + object.optInt("display_dpi", 0)
                    + " · frames " + object.optLong("display_frames", 0)
                    + "\nflags: " + object.optString("display_flags", "-")
                    + " · input: " + (object.optBoolean("input_ready", false) ? "ready" : "unavailable");
            txtDisplayStatus.setText(text);
            btnCreate.setEnabled(true);
            btnRelease.setEnabled(true);
            btnLaunchApp.setEnabled(!selectedPackage.isEmpty());
            btnTap.setEnabled(true);
            btnSwipeUp.setEnabled(true);
            btnBack.setEnabled(true);
            btnHome.setEnabled(true);
            btnSendText.setEnabled(true);
            btnSnapshot.setEnabled(true);
            if (!previewRunning && !previewUserPaused) {
                startPreview();
            }
        } else {
            txtDisplayStatus.setText("no virtual display — create one");
            btnCreate.setEnabled(true);
            btnRelease.setEnabled(false);
            btnLaunchApp.setEnabled(false);
            btnTap.setEnabled(false);
            btnSwipeUp.setEnabled(false);
            btnBack.setEnabled(false);
            btnHome.setEnabled(false);
            btnSendText.setEnabled(false);
            btnSnapshot.setEnabled(false);
            String error = object.optString("error", "");
            if (!error.isEmpty()) {
                txtDisplayStatus.append("\nlast error: " + error);
            }
        }
    }

    // -------------------------------------------------------------- preview

    private void startPreview() {
        if (previewRunning || !client.isServiceConnected()) {
            return;
        }
        previewRunning = true;
        previewUserPaused = false;
        btnPreview.setText(R.string.pause_preview);
        previewFrames = 0;
        previewWindowStart = System.currentTimeMillis();
        main.post(previewTick);
    }

    private void stopPreview() {
        previewRunning = false;
        btnPreview.setText(R.string.start_preview);
        main.removeCallbacks(previewTick);
        txtPreviewStatus.setText(R.string.preview_paused);
    }

    private final Runnable previewTick = new Runnable() {
        @Override
        public void run() {
            if (!previewRunning) {
                return;
            }
            if (client.isServiceConnected() && previewInFlight.compareAndSet(false, true)) {
                io.execute(() -> {
                    EngineClient.Frame frame = client.capture(900, 70);
                    previewInFlight.set(false);
                    if (frame.ok()) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(
                                frame.jpeg, 0, frame.jpeg.length);
                        main.post(() -> showPreview(bitmap));
                    } else {
                        main.post(() -> txtPreviewStatus.setText("no frame yet: " + frame.error));
                    }
                });
            }
            main.postDelayed(this, 400);
        }
    };

    private void showPreview(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        if (previewBitmap != null) {
            previewBitmap.recycle();
        }
        previewBitmap = bitmap;
        imgPreview.setImageBitmap(bitmap);
        previewFrames++;
        long elapsed = System.currentTimeMillis() - previewWindowStart;
        double fps = elapsed <= 0 ? 0 : previewFrames * 1000.0 / elapsed;
        txtPreviewStatus.setText(String.format(Locale.US, "live %dx%d · %.1f fps · frame %d",
                bitmap.getWidth(), bitmap.getHeight(), fps, previewFrames));
    }

    // ------------------------------------------------------------- app list

    private void refreshApps() {
        io.execute(() -> {
            String json = client.listApps();
            JSONArray array = null;
            try {
                array = new JSONArray(json);
            } catch (Throwable ignored) {
            }
            final JSONArray apps = array;
            main.post(() -> {
                appLabels.clear();
                appPackages.clear();
                if (apps != null) {
                    for (int i = 0; i < apps.length(); i++) {
                        JSONObject object = apps.optJSONObject(i);
                        if (object == null) {
                            continue;
                        }
                        appLabels.add(object.optString("label", object.optString("package")));
                        appPackages.add(object.optString("package"));
                    }
                }
                appendLog("app list: " + appPackages.size() + " launchable apps");
                toast(appPackages.size() + " apps loaded");
            });
        });
    }

    private void chooseApp() {
        if (appPackages.isEmpty()) {
            refreshApps();
            toast("Loading app list… tap again in a second");
            return;
        }
        String[] items = appLabels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Launch on the virtual display")
                .setItems(items, (dialog, which) -> {
                    selectedPackage = appPackages.get(which);
                    txtSelectedApp.setText(appLabels.get(which) + "\n" + selectedPackage);
                    btnLaunchApp.setEnabled(displayReady);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchSelectedApp() {
        if (selectedPackage.isEmpty()) {
            toast("Choose an app first");
            return;
        }
        boolean force = checkForceStop.isChecked();
        io.execute(() -> {
            String result = client.launchApp(selectedPackage, force);
            main.post(() -> {
                appendLog("launch " + selectedPackage + ": " + result);
                toast(result.contains("\"ok\":true") ? "Launched on display" : "Launch failed");
            });
        });
    }

    // -------------------------------------------------------------- self-test

    private void startSelfTest() {
        if (!client.isServiceConnected()) {
            toast("Start the shell engine first");
            return;
        }
        selfTestPolling = true;
        evidenceLoaded = false;
        lastSelfTestJson = "";
        txtSelfTest.setText("starting…");
        txtTestBadge.setText(R.string.badge_running);
        txtTestBadge.setTextColor(getColor(R.color.warning));
        btnSelfTest.setEnabled(false);
        imgBefore.setImageDrawable(null);
        imgAfter.setImageDrawable(null);
        if (!previewRunning) {
            startPreview();
        }
        io.execute(() -> {
            String result = client.startSelfTest();
            main.post(() -> {
                appendLog("self-test: " + result);
                main.postDelayed(selfTestTick, 400);
            });
        });
    }

    private final Runnable selfTestTick = new Runnable() {
        @Override
        public void run() {
            if (!selfTestPolling) {
                return;
            }
            io.execute(() -> {
                String json = client.selfTestStatus();
                main.post(() -> {
                    lastSelfTestJson = json;
                    renderSelfTest(json);
                    if (selfTestPolling) {
                        main.postDelayed(selfTestTick, 450);
                    }
                });
            });
        }
    };

    private void renderSelfTest(String json) {
        JSONObject object = parse(json);
        if (object == null) {
            return;
        }
        JSONArray lines = object.optJSONArray("lines");
        StringBuilder builder = new StringBuilder();
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                builder.append(lines.optString(i)).append('\n');
            }
        }
        txtSelfTest.setText(builder.toString());
        boolean done = object.optBoolean("done", false);
        boolean pass = object.optBoolean("pass", false);
        if (done) {
            selfTestPolling = false;
            txtTestBadge.setText(pass ? R.string.badge_pass : R.string.badge_fail);
            txtTestBadge.setTextColor(getColor(pass ? R.color.success : R.color.danger));
            btnSelfTest.setEnabled(true);
            if (!evidenceLoaded) {
                evidenceLoaded = true;
                loadEvidence();
            }
        } else {
            txtTestBadge.setText(R.string.badge_running);
        }
    }

    private void loadEvidence() {
        io.execute(() -> {
            EngineClient.Frame before = client.evidence(0);
            EngineClient.Frame after = client.evidence(1);
            Bitmap beforeBitmap = before.ok()
                    ? BitmapFactory.decodeByteArray(before.jpeg, 0, before.jpeg.length) : null;
            Bitmap afterBitmap = after.ok()
                    ? BitmapFactory.decodeByteArray(after.jpeg, 0, after.jpeg.length) : null;
            main.post(() -> {
                if (beforeBitmap != null) {
                    imgBefore.setImageBitmap(beforeBitmap);
                }
                if (afterBitmap != null) {
                    imgAfter.setImageBitmap(afterBitmap);
                }
            });
        });
    }

    private void saveEvidence() {
        if (lastSelfTestJson.isEmpty()) {
            toast("Run the self-test first");
            return;
        }
        final String report = buildReport();
        io.execute(() -> {
            EngineClient.Frame before = client.evidence(0);
            EngineClient.Frame after = client.evidence(1);
            List<String> saved = new ArrayList<>();
            saved.add("report: " + saveBytes("headless-selftest-" + timestamp() + ".txt",
                    "text/plain", report.getBytes(StandardCharsets.UTF_8)));
            if (before.ok()) {
                saved.add("before: " + saveBytes("headless-before-" + timestamp() + ".jpg",
                        "image/jpeg", before.jpeg));
            }
            if (after.ok()) {
                saved.add("after: " + saveBytes("headless-after-" + timestamp() + ".jpg",
                        "image/jpeg", after.jpeg));
            }
            StringBuilder builder = new StringBuilder("Saved evidence\n");
            for (String line : saved) {
                builder.append(line).append('\n');
            }
            main.post(() -> {
                appendLog(builder.toString());
                toast("Evidence saved (Downloads/HeadlessAndroid)");
            });
        });
    }

    private String buildReport() {
        StringBuilder report = new StringBuilder();
        report.append("Headless Android self-test report\n");
        report.append("time: ").append(new Date().toString()).append('\n');
        report.append("device: ").append(android.os.Build.MODEL)
                .append(" / Android ").append(android.os.Build.VERSION.RELEASE).append('\n');
        report.append('\n');
        JSONObject object = parse(lastSelfTestJson);
        if (object != null) {
            JSONArray checks = object.optJSONArray("checks");
            if (checks != null) {
                for (int i = 0; i < checks.length(); i++) {
                    JSONObject check = checks.optJSONObject(i);
                    if (check == null) {
                        continue;
                    }
                    report.append(check.optBoolean("pass", false) ? "PASS " : "FAIL ")
                            .append(check.optString("name"))
                            .append(" — ").append(check.optString("detail")).append('\n');
                }
            }
            report.append('\n');
            JSONArray lines = object.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    report.append(lines.optString(i)).append('\n');
                }
            }
        }
        return report.toString();
    }

    // ----------------------------------------------------------------- utils

    private void appendLog(String message) {
        String line = message == null ? "" : message;
        CharSequence current = txtLog.getText();
        String text = current + line + "\n";
        if (text.length() > 24000) {
            text = text.substring(text.length() - 16000);
        }
        txtLog.setText(text);
    }

    private String saveBytes(String displayName, String mimeType, byte[] data) {
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/HeadlessAndroid");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("openOutputStream returned null");
                }
                out.write(data);
            }
            return "Downloads/HeadlessAndroid/" + displayName;
        } catch (Throwable e) {
            try {
                File dir = new File(getExternalFilesDir(null), "evidence");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("cannot create " + dir);
                }
                File file = new File(dir, displayName);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                return file.getAbsolutePath();
            } catch (Throwable fallbackError) {
                return "save failed: " + fallbackError.getMessage();
            }
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static int parseInt(EditText field, int fallback) {
        try {
            String value = field.getText().toString().trim();
            return value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static JSONObject parse(String json) {
        try {
            return new JSONObject(json);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
