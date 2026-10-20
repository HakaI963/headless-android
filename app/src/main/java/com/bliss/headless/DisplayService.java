package com.bliss.headless;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * Shizuku UserService entry point (runs as shell/root in a dedicated process).
 *
 * <p>Direct Binder transactions instead of ShadowAuto's JSON-RPC server
 * (Apache-2.0). JSON commands answer with a JSON string; frame commands answer
 * with {@code int ok, String error, byte[] jpeg}.
 */
public class DisplayService extends Binder {
    private static final String TAG = "HeadlessDisplay";
    private static volatile DisplayEngine engine;

    /** No-arg constructor used by Shizuku releases that do not pass a Context. */
    public DisplayService() {
        this(null);
    }

    /** Context constructor used by Shizuku 13+ (the app's Application object). */
    public DisplayService(Context context) {
        super();
        install(context);
    }

    private static synchronized void install(Context context) {
        if (engine != null) {
            return;
        }
        Context resolved = context;
        if (resolved == null) {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                java.lang.reflect.Method current = activityThread.getMethod("currentApplication");
                resolved = (Context) current.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        try {
            engine = new DisplayEngine(resolved);
        } catch (Throwable e) {
            Log.e(TAG, "engine init failed", e);
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != Protocol.CMD_STATUS && code != Protocol.CMD_SHUTDOWN
                && (code < Protocol.CMD_CREATE || code > Protocol.CMD_AUTH)) {
            return super.onTransact(code, data, reply, flags);
        }
        try {
            handle(code, data, reply);
        } catch (Throwable e) {
            Log.e(TAG, "command " + code + " failed", e);
            try {
                if (isFrameCommand(code)) {
                    reply.writeInt(0);
                    reply.writeString(Jsons.error(e).replace("\"", "'"));
                    reply.writeByteArray(new byte[0]);
                } else {
                    reply.writeString(Jsons.error(e));
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static boolean isFrameCommand(int code) {
        return code == Protocol.CMD_CAPTURE;
    }

    private void handle(int code, Parcel data, Parcel reply) {
        DisplayEngine local = engine;
        if (local == null && code != Protocol.CMD_SHUTDOWN) {
            install(null);
            local = engine;
        }
        if (local == null && code != Protocol.CMD_SHUTDOWN) {
            reply.writeString(Jsons.error("engine unavailable"));
            return;
        }

        switch (code) {
            case Protocol.CMD_SHUTDOWN: {
                if (local != null) {
                    local.releaseAll();
                }
                reply.writeString(Jsons.ok("shutdown requested"));
                new Thread(() -> {
                    SystemClock.sleep(200);
                    System.exit(0);
                }, "headless-exit").start();
                return;
            }
            case Protocol.CMD_STATUS: {
                reply.writeString(local.statusJson());
                return;
            }
            case Protocol.CMD_LOG_TAIL: {
                reply.writeString(local.logTailJson(data.readInt()));
                return;
            }
            case Protocol.CMD_CREATE: {
                int width = data.readInt();
                int height = data.readInt();
                int dpi = data.readInt();
                reply.writeString(local.createDisplay(width, height, dpi));
                return;
            }
            case Protocol.CMD_RELEASE: {
                reply.writeString(local.releaseDisplay());
                return;
            }
            case Protocol.CMD_LIST_APPS: {
                reply.writeString(local.listAppsJson());
                return;
            }
            case Protocol.CMD_LAUNCH_APP: {
                String packageName = data.readString();
                boolean forceStop = data.readInt() == 1;
                reply.writeString(local.launchApp(packageName, forceStop));
                return;
            }
            case Protocol.CMD_CAPTURE: {
                int timeoutMs = data.readInt();
                int quality = data.readInt();
                byte[] jpeg = local.captureJpeg(timeoutMs, quality);
                writeFrame(reply, jpeg, jpeg == null ? "no frame available yet" : "");
                return;
            }
            case Protocol.CMD_TAP: {
                reply.writeString(local.tap(data.readInt(), data.readInt()));
                return;
            }
            case Protocol.CMD_SWIPE: {
                int x1 = data.readInt();
                int y1 = data.readInt();
                int x2 = data.readInt();
                int y2 = data.readInt();
                int duration = data.readInt();
                reply.writeString(local.swipe(x1, y1, x2, y2, duration));
                return;
            }
            case Protocol.CMD_KEY: {
                reply.writeString(local.key(data.readInt()));
                return;
            }
            case Protocol.CMD_TEXT: {
                reply.writeString(local.text(data.readString()));
                return;
            }

            // ------------------------------------------------------ clones

            case Protocol.CMD_CLONE_LIST: {
                reply.writeString(local.cloneListJson());
                return;
            }
            case Protocol.CMD_CLONE_CREATE_INSTALLED: {
                String packageName = data.readString();
                String name = data.readString();
                reply.writeString(local.cloneFromInstalled(packageName, name));
                return;
            }
            case Protocol.CMD_CLONE_CREATE_APK: {
                String name = data.readString();
                ParcelFileDescriptor descriptor =
                        ParcelFileDescriptor.CREATOR.createFromParcel(data);
                reply.writeString(local.cloneFromApk(descriptor, name));
                return;
            }
            case Protocol.CMD_CLONE_LAUNCH: {
                String clonePackage = data.readString();
                reply.writeString(local.cloneLaunch(clonePackage));
                return;
            }
            case Protocol.CMD_CLONE_KILL: {
                String clonePackage = data.readString();
                reply.writeString(local.cloneKill(clonePackage));
                return;
            }
            case Protocol.CMD_CLONE_DELETE: {
                String clonePackage = data.readString();
                reply.writeString(local.cloneDelete(clonePackage));
                return;
            }
            case Protocol.CMD_AUTH: {
                reply.writeString(local.auth(data.readString())
                        ? Jsons.ok("authenticated")
                        : Jsons.error("bad control token"));
                return;
            }
            default: {
                reply.writeString(Jsons.error("unknown command " + code));
            }
        }
    }

    private static void writeFrame(Parcel reply, byte[] jpeg, String error) {
        boolean ok = jpeg != null && jpeg.length > 0;
        reply.writeInt(ok ? 1 : 0);
        reply.writeString(ok ? "" : error);
        reply.writeByteArray(ok ? jpeg : new byte[0]);
    }
}
