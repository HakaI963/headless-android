package com.bliss.headless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final int PICK_APK = 4001;

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

    private Button btnCloneInstalled;
    private Button btnInstallApkClone;
    private Button btnRefreshClones;
    private TextView txtCloneStatus;
    private LinearLayout cloneList;

    private EditText editTapX;
    private EditText editTapY;
    private Button btnTap;
    private Button btnSwipeUp;
    private Button btnBack;
    private Button btnHome;
    private EditText editTextValue;
    private Button btnSendText;

    private TextView txtLog;
    private Button btnClearLog;

    private boolean previewRunning;
    private boolean previewUserPaused;
    private Bitmap previewBitmap;
    private long previewFrames;
    private long previewWindowStart;
    private boolean clonesLoaded;
    private String selectedPackage = "";
    private final List<String> appLabels = new ArrayList<>();
    private final List<String> appPackages = new ArrayList<>();
    private int displayWidth = 720;
    private int displayHeight = 1280;
    private boolean displayReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        btnCloneInstalled = findViewById(R.id.btnCloneInstalled);
        btnInstallApkClone = findViewById(R.id.btnInstallApkClone);
        btnRefreshClones = findViewById(R.id.btnRefreshClones);
        txtCloneStatus = findViewById(R.id.txtCloneStatus);
        cloneList = findViewById(R.id.cloneList);

        editTapX = findViewById(R.id.editTapX);
        editTapY = findViewById(R.id.editTapY);
        btnTap = findViewById(R.id.btnTap);
        btnSwipeUp = findViewById(R.id.btnSwipeUp);
        btnBack = findViewById(R.id.btnBack);
        btnHome = findViewById(R.id.btnHome);
        editTextValue = findViewById(R.id.editTextValue);
        btnSendText = findViewById(R.id.btnSendText);

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
            clonesLoaded = false;
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
            String location = Downloads.save(this, name, "image/jpeg", frame.jpeg);
            main.post(() -> {
                appendLog("snapshot saved: " + location);
                toast("Saved: " + location);
            });
        }));

        btnRefreshApps.setOnClickListener(v -> refreshApps());
        btnChooseApp.setOnClickListener(v -> chooseApp(false));
        btnLaunchApp.setOnClickListener(v -> launchSelectedApp());

        btnCloneInstalled.setOnClickListener(v -> chooseApp(true));
        btnInstallApkClone.setOnClickListener(v -> pickApk());
        btnRefreshClones.setOnClickListener(v -> refreshClones());

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
        stopPreview();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        String name = uri.getLastPathSegment();
        if (name != null && name.toLowerCase(Locale.US).endsWith(".apk")) {
            name = name.substring(0, name.length() - 4);
        }
        final String suggested = name;
        io.execute(() -> {
            String result;
            try (ParcelFileDescriptor descriptor =
                         getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor == null) {
                    result = Jsons.error("cannot open the selected file");
                } else {
                    result = client.cloneFromApk(descriptor, suggested);
                }
            } catch (Throwable e) {
                result = Jsons.error(String.valueOf(e));
            }
            final String outcome = result;
            main.post(() -> {
                appendLog("install APK clone: " + outcome);
                toast(outcome.contains("\"ok\":true") ? "Clone installed" : "Clone install failed");
                refreshClones();
            });
        });
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

        boolean cloneButtons = connected;
        btnCloneInstalled.setEnabled(cloneButtons);
        btnInstallApkClone.setEnabled(cloneButtons);
        btnRefreshClones.setEnabled(cloneButtons);

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
            btnPreview.setEnabled(false);
            cloneList.removeAllViews();
            txtCloneStatus.setText("shell engine not connected");
            clonesLoaded = false;
            return;
        }

        btnChooseApp.setEnabled(true);
        btnRefreshApps.setEnabled(true);
        btnPreview.setEnabled(true);

        if (!clonesLoaded) {
            clonesLoaded = true;
            refreshClones();
        }

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

    private void chooseApp(boolean cloneMode) {
        if (appPackages.isEmpty()) {
            refreshApps();
            toast("Loading app list… tap again in a second");
            return;
        }
        String[] items = appLabels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(cloneMode ? "Clone which app?" : "Launch on the virtual display")
                .setItems(items, (dialog, which) -> {
                    if (cloneMode) {
                        promptCloneName(appLabels.get(which), appPackages.get(which));
                    } else {
                        selectedPackage = appPackages.get(which);
                        txtSelectedApp.setText(appLabels.get(which) + "\n" + selectedPackage);
                        btnLaunchApp.setEnabled(displayReady);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCloneName(String label, String packageName) {
        EditText input = new EditText(this);
        String suggested = label + " (clone)";
        input.setText(suggested);
        input.setSelection(input.getText().length());
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(20), dp(8), dp(20), 0);
        holder.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Clone name")
                .setMessage("The APK is repackaged with a new package name and signature, "
                        + "installed hidden, and launched on the virtual display. "
                        + "This can take a while for large apps.")
                .setView(holder)
                .setPositiveButton("Clone", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    io.execute(() -> {
                        String result = client.cloneFromInstalled(packageName, name);
                        main.post(() -> {
                            appendLog("clone " + packageName + ": " + result);
                            toast(result.contains("\"ok\":true")
                                    ? "Clone created" : "Clone failed");
                            refreshClones();
                        });
                    });
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

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        try {
            startActivityForResult(intent, PICK_APK);
        } catch (Throwable e) {
            toast("No file picker available");
        }
    }

    // ---------------------------------------------------------------- clones

    private void refreshClones() {
        if (!client.isServiceConnected()) {
            return;
        }
        io.execute(() -> {
            String json = client.cloneList();
            String error = null;
            JSONArray array = null;
            JSONObject root = parse(json);
            if (root == null) {
                error = "clone list parse failed";
            } else if (root.has("error")) {
                error = root.optString("error");
            } else {
                array = root.optJSONArray("clones");
            }
            final JSONArray clones = array;
            final String failure = error;
            final JSONObject status = root;
            main.post(() -> {
                if (failure != null) {
                    txtCloneStatus.setText("clone list error: " + failure);
                    return;
                }
                renderClones(clones, status);
            });
        });
    }

    private void renderClones(JSONArray array, JSONObject root) {
        cloneList.removeAllViews();
        int count = array == null ? 0 : array.length();
        int displayId = root == null ? -1 : root.optInt("display_id", -1);
        txtCloneStatus.setText("clones: " + count + " · repackaged in user 0"
                + (displayId > 0 ? " · display #" + displayId : " · no display yet"));
        if (array == null || count == 0) {
            TextView empty = new TextView(this);
            empty.setText("No clones yet.\n\"Clone installed app…\" builds a second, "
                    + "isolated copy of an app and runs it on the virtual display.");
            empty.setTextColor(getColor(R.color.text_dim));
            empty.setTextSize(12f);
            empty.setPadding(0, dp(8), 0, 0);
            cloneList.addView(empty);
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject clone = array.optJSONObject(i);
            if (clone != null) {
                addCloneRow(clone);
            }
        }
    }

    private void addCloneRow(JSONObject clone) {
        final String clonePackage = clone.optString("package");
        final String originalPackage = clone.optString("original");
        final String activity = clone.optString("activity");
        final String label = clone.optString("label", clonePackage);
        String status = clone.optString("status", "unknown");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(14f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(title);

        TextView detail = new TextView(this);
        detail.setText("clone: " + clonePackage
                + "\nfrom: " + originalPackage
                + "\nactivity: " + activity
                + "\n" + describeCloneStatus(status));
        detail.setTextColor(getColor(R.color.text_secondary));
        detail.setTextSize(11f);
        detail.setPadding(0, dp(3), 0, 0);
        row.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(7);
        actions.setLayoutParams(actionParams);

        Button launch = smallButton("Launch");
        launch.setOnClickListener(v -> cloneAction("launch", clonePackage));
        actions.addView(launch, rowButtonParams(true));

        Button kill = smallButton("Kill");
        kill.setOnClickListener(v -> cloneAction("kill", clonePackage));
        actions.addView(kill, rowButtonParams(true));

        Button delete = smallButton("Delete");
        delete.setOnClickListener(v -> confirmDeleteClone(clonePackage, label));
        actions.addView(delete, rowButtonParams(false));

        row.addView(actions);
        cloneList.addView(row);
    }

    private void confirmDeleteClone(String clonePackage, String label) {
        new AlertDialog.Builder(this)
                .setTitle("Delete clone?")
                .setMessage("\"" + label + "\" and its clone data will be uninstalled.")
                .setPositiveButton("Delete", (dialog, which) ->
                        cloneAction("delete", clonePackage))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void cloneAction(String action, String clonePackage) {
        io.execute(() -> {
            String result;
            switch (action) {
                case "launch":
                    result = client.cloneLaunch(clonePackage);
                    break;
                case "kill":
                    result = client.cloneKill(clonePackage);
                    break;
                default:
                    result = client.cloneDelete(clonePackage);
                    break;
            }
            final String outcome = result;
            main.post(() -> {
                appendLog("clone " + action + " " + clonePackage + ": " + outcome);
                toast(outcome.contains("\"ok\":true")
                        ? ("Clone " + action + " ok") : ("Clone " + action + " failed"));
                main.postDelayed(this::refreshClones, 1500);
            });
        });
    }

    private static String describeCloneStatus(String status) {
        switch (status) {
            case "running":
                return "running on the display";
            case "installed":
                return "installed · tap Launch to run it headlessly";
            case "missing":
                return "package missing — delete this entry";
            default:
                return status;
        }
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setTextColor(getColor(R.color.text_primary));
        button.setBackgroundResource(R.drawable.bg_secondary);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams rowButtonParams(boolean margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (margin) {
            params.rightMargin = dp(6);
        }
        return params;
    }

    // ----------------------------------------------------------------- utils

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void appendLog(String message) {
        String line = message == null ? "" : message;
        CharSequence current = txtLog.getText();
        String text = current + line + "\n";
        if (text.length() > 24000) {
            text = text.substring(text.length() - 16000);
        }
        txtLog.setText(text);
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
