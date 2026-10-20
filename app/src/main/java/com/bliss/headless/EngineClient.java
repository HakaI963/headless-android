package com.bliss.headless;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * UI-side owner of the Shizuku binding to {@link DisplayService}.
 *
 * <p>Derived from ShadowAuto's {@code controller/.../ShizukuServerManager.java}
 * (Apache-2.0) but reduced to the display engine: permission handling, the
 * user-service connection and direct Binder transactions. No OCR status
 * polling, no RPC port probing, no server-version handshakes.
 */
public final class EngineClient {
    private static final String TAG = "HeadlessDisplay";
    public static final String SHIZUKU_MANAGER = "moe.shizuku.privileged.api";
    private static final int PERMISSION_REQUEST_CODE = 4451;

    /** Callback so the activity can refresh its status card. */
    public interface Listener {
        void onStateChanged();
    }

    /** A frame reply (JPEG) from the shell service. */
    public static final class Frame {
        public final byte[] jpeg;
        public final String error;

        Frame(byte[] jpeg, String error) {
            this.jpeg = jpeg;
            this.error = error == null ? "" : error;
        }

        public boolean ok() {
            return jpeg != null && jpeg.length > 0;
        }
    }

    private interface Writer {
        void write(Parcel parcel);
    }

    private static volatile EngineClient instance;

    private final Context context;
    private final Shizuku.UserServiceArgs args;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile IBinder binder;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            Log.i(TAG, "shell service connected");
            notifyState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            Log.i(TAG, "shell service disconnected");
            notifyState();
        }
    };

    public static EngineClient get(Context context) {
        EngineClient local = instance;
        if (local == null) {
            synchronized (EngineClient.class) {
                if (instance == null) {
                    instance = new EngineClient(context.getApplicationContext());
                }
                local = instance;
            }
        }
        return local;
    }

    private EngineClient(Context context) {
        this.context = context;
        this.args = new Shizuku.UserServiceArgs(
                new ComponentName(context, Protocol.SERVICE_CLASS))
                .processNameSuffix("shell")
                .tag(Protocol.SERVICE_TAG)
                .version(Protocol.SERVICE_VERSION)
                .daemon(false)
                .debuggable(false);
        try {
            Shizuku.addBinderReceivedListenerSticky(this::notifyState);
            Shizuku.addBinderDeadListener(() -> {
                binder = null;
                notifyState();
            });
            Shizuku.addRequestPermissionResultListener((code, result) -> {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    startService();
                }
                notifyState();
            });
        } catch (Throwable e) {
            Log.w(TAG, "Shizuku listener registration failed: " + e);
        }
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyState() {
        main.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onStateChanged();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------- Shizuku

    public boolean isManagerInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_MANAGER, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int shizukuVersion() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public boolean isServiceConnected() {
        IBinder local = binder;
        return local != null && local.pingBinder();
    }

    /** @return {@code null} when the request was handed to Shizuku, otherwise the reason. */
    public String requestPermission() {
        try {
            if (!isBinderAlive()) {
                return "Shizuku is not running. Open the Shizuku app and start the service first.";
            }
            if (hasPermission()) {
                return null;
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            return null;
        } catch (Throwable e) {
            return message(e);
        }
    }

    /** @return {@code null} when binding was started, otherwise the reason. */
    public String startService() {
        try {
            if (!isBinderAlive()) {
                return "Shizuku is not running. Open the Shizuku app and start the service first.";
            }
            if (!hasPermission()) {
                return "Shizuku permission is not granted. Tap \"Grant Shizuku\" first.";
            }
            if (isServiceConnected()) {
                return null;
            }
            Shizuku.bindUserService(args, connection);
            return null;
        } catch (Throwable e) {
            return message(e);
        }
    }

    public void stopService() {
        IBinder local = binder;
        if (local != null) {
            try {
                callJson(Protocol.CMD_SHUTDOWN, null);
            } catch (Throwable ignored) {
            }
        }
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (Throwable ignored) {
        }
        binder = null;
        notifyState();
    }

    public boolean openShizukuManager() {
        try {
            android.content.Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage(SHIZUKU_MANAGER);
            if (intent == null) {
                return false;
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------ commands

    public String callJson(int code, Writer writer) {
        IBinder local = binder;
        if (local == null) {
            return Jsons.error("shell service not connected");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (writer != null) {
                writer.write(data);
            }
            local.transact(code, data, reply, 0);
            reply.setDataPosition(0);
            String value = reply.readString();
            return value == null ? Jsons.error("empty reply") : value;
        } catch (Throwable e) {
            if (e instanceof RemoteException) {
                Log.w(TAG, "binder call failed: " + e.getMessage());
            }
            return Jsons.error(message(e));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public Frame callFrame(int code, int first, int second) {
        IBinder local = binder;
        if (local == null) {
            return new Frame(null, "shell service not connected");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(first);
            if (code != Protocol.CMD_EVIDENCE) {
                data.writeInt(second);
            }
            local.transact(code, data, reply, 0);
            reply.setDataPosition(0);
            int ok = reply.readInt();
            String error = reply.readString();
            byte[] bytes = reply.createByteArray();
            if (ok == 1 && bytes != null && bytes.length > 0) {
                return new Frame(bytes, "");
            }
            return new Frame(null, error == null || error.isEmpty() ? "no frame" : error);
        } catch (Throwable e) {
            return new Frame(null, message(e));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Convenience wrappers ------------------------------------------------

    public String status() {
        return callJson(Protocol.CMD_STATUS, null);
    }

    public String listApps() {
        return callJson(Protocol.CMD_LIST_APPS, null);
    }

    public String logTail(int maxLines) {
        return callJson(Protocol.CMD_LOG_TAIL, parcel -> parcel.writeInt(maxLines));
    }

    public String createDisplay(int width, int height, int dpi) {
        return callJson(Protocol.CMD_CREATE, parcel -> {
            parcel.writeInt(width);
            parcel.writeInt(height);
            parcel.writeInt(dpi);
        });
    }

    public String releaseDisplay() {
        return callJson(Protocol.CMD_RELEASE, null);
    }

    public String launchApp(String packageName, boolean forceStop) {
        return callJson(Protocol.CMD_LAUNCH_APP, parcel -> {
            parcel.writeString(packageName);
            parcel.writeInt(forceStop ? 1 : 0);
        });
    }

    public String launchTestActivity() {
        return callJson(Protocol.CMD_LAUNCH_TEST_ACTIVITY, null);
    }

    public Frame capture(int timeoutMs, int quality) {
        return callFrame(Protocol.CMD_CAPTURE, timeoutMs, quality);
    }

    public Frame evidence(int index) {
        return callFrame(Protocol.CMD_EVIDENCE, index, 0);
    }

    public String tap(int x, int y) {
        return callJson(Protocol.CMD_TAP, parcel -> {
            parcel.writeInt(x);
            parcel.writeInt(y);
        });
    }

    public String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return callJson(Protocol.CMD_SWIPE, parcel -> {
            parcel.writeInt(x1);
            parcel.writeInt(y1);
            parcel.writeInt(x2);
            parcel.writeInt(y2);
            parcel.writeInt(durationMs);
        });
    }

    public String key(int keyCode) {
        return callJson(Protocol.CMD_KEY, parcel -> parcel.writeInt(keyCode));
    }

    public String text(String value) {
        return callJson(Protocol.CMD_TEXT, parcel -> parcel.writeString(value));
    }

    public String startSelfTest() {
        return callJson(Protocol.CMD_SELF_TEST_START, null);
    }

    public String selfTestStatus() {
        return callJson(Protocol.CMD_SELF_TEST_STATUS, null);
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
