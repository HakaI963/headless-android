package com.bliss.headless;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

/**
 * Minimal bridge to the hidden {@code window} service, used only to move the
 * IME to the virtual display.
 *
 * <p>Adapted from ShadowAuto {@code android-shell/.../WindowManagerBridge.java}
 * (Apache-2.0). Every method is best-effort: ROMs differ in whether
 * {@code setDisplayImePolicy}/{@code setShouldShowIme} exist, and IME placement
 * is a convenience, not a prerequisite for the display itself.
 */
final class WindowManagerBridge {
    static final int DISPLAY_IME_POLICY_LOCAL = 0;
    static final int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;
    static final int DISPLAY_IME_POLICY_HIDE = 2;

    private final IInterface manager;
    private Method getDisplayImePolicy;
    private Method setDisplayImePolicy;

    WindowManagerBridge() throws Exception {
        manager = service("window", "android.view.IWindowManager");
    }

    @TargetApi(29)
    int getDisplayImePolicy(int displayId) {
        try {
            Method method = getMethod();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return (int) method.invoke(manager, displayId);
            }
            return (boolean) method.invoke(manager, displayId)
                    ? DISPLAY_IME_POLICY_LOCAL
                    : DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @TargetApi(29)
    boolean setDisplayImePolicy(int displayId, int policy) {
        try {
            Method method = setMethod();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                method.invoke(manager, displayId, policy);
            } else if (policy != DISPLAY_IME_POLICY_HIDE) {
                method.invoke(manager, displayId, policy == DISPLAY_IME_POLICY_LOCAL);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Method getMethod() throws NoSuchMethodException {
        if (getDisplayImePolicy == null) {
            getDisplayImePolicy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? manager.getClass().getMethod("getDisplayImePolicy", int.class)
                    : manager.getClass().getMethod("shouldShowIme", int.class);
        }
        return getDisplayImePolicy;
    }

    private Method setMethod() throws NoSuchMethodException {
        if (setDisplayImePolicy == null) {
            setDisplayImePolicy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? manager.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
                    : manager.getClass().getMethod("setShouldShowIme", int.class, boolean.class);
        }
        return setDisplayImePolicy;
    }

    private static IInterface service(String name, String type) throws Exception {
        Method getService = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, name);
        if (binder == null) {
            throw new IllegalStateException("service unavailable: " + name);
        }
        Method asInterface = Class.forName(type + "$Stub")
                .getMethod("asInterface", IBinder.class);
        return (IInterface) asInterface.invoke(null, binder);
    }
}
