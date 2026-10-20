package com.bliss.headless;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * Shizuku UserService entry point (runs as shell/root in a dedicated process).
 *
 * <p>Replaces ShadowAuto's {@code ShellUserService} + JSON-RPC server
 * (Apache-2.0) with direct Binder transactions: the UI process binds this
 * service through Shizuku and calls it synchronously. This removes the whole
 * localhost RPC layer, its port handling and the unused OCR/AI commands while
 * keeping the exact same display engine underneath.
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
                && (code < Protocol.CMD_CREATE || code > Protocol.CMD_LOG_TAIL)) {
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
        return code == Protocol.CMD_CAPTURE || code == Protocol.CMD_EVIDENCE;
    }

    private void handle(int code, Parcel data, Parcel reply) {
        DisplayEngine local = engine;
        if (local == null && code != Protocol.CMD_SHUTDOWN) {
            install(null);
            local = engine;
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
                reply.writeString(local == null ? Jsons.error("engine unavailable") : local.statusJson());
                return;
            }
            case Protocol.CMD_LOG_TAIL: {
                int maxLines = data.readInt();
                reply.writeString(local == null
                        ? "[]" : local.logTailJson(maxLines));
                return;
            }
            case Protocol.CMD_CREATE: {
                int width = data.readInt();
                int height = data.readInt();
                int dpi = data.readInt();
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.createDisplay(width, height, dpi));
                return;
            }
            case Protocol.CMD_RELEASE: {
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.releaseDisplay());
                return;
            }
            case Protocol.CMD_LIST_APPS: {
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.listAppsJson());
                return;
            }
            case Protocol.CMD_LAUNCH_APP: {
                String packageName = data.readString();
                boolean forceStop = data.readInt() == 1;
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.launchApp(packageName, forceStop));
                return;
            }
            case Protocol.CMD_LAUNCH_TEST_ACTIVITY: {
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.launchTestActivity());
                return;
            }
            case Protocol.CMD_CAPTURE: {
                int timeoutMs = data.readInt();
                int quality = data.readInt();
                byte[] jpeg = local == null ? null : local.captureJpeg(timeoutMs, quality);
                writeFrame(reply, jpeg, jpeg == null ? "no frame available yet" : "");
                return;
            }
            case Protocol.CMD_EVIDENCE: {
                int index = data.readInt();
                byte[] jpeg = local == null ? null : local.evidence(index);
                writeFrame(reply, jpeg, jpeg == null ? "no evidence captured" : "");
                return;
            }
            case Protocol.CMD_TAP: {
                int x = data.readInt();
                int y = data.readInt();
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.tap(x, y));
                return;
            }
            case Protocol.CMD_SWIPE: {
                int x1 = data.readInt();
                int y1 = data.readInt();
                int x2 = data.readInt();
                int y2 = data.readInt();
                int duration = data.readInt();
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable")
                        : local.swipe(x1, y1, x2, y2, duration));
                return;
            }
            case Protocol.CMD_KEY: {
                int keyCode = data.readInt();
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.key(keyCode));
                return;
            }
            case Protocol.CMD_TEXT: {
                String value = data.readString();
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.text(value));
                return;
            }
            case Protocol.CMD_SELF_TEST_START: {
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.startSelfTest());
                return;
            }
            case Protocol.CMD_SELF_TEST_STATUS: {
                reply.writeString(local == null
                        ? Jsons.error("engine unavailable") : local.selfTestStatusJson());
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
