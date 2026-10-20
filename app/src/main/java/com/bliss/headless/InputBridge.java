package com.bliss.headless;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Display-targeted input injection through the hidden
 * {@code InputManager.injectInputEvent} API.
 *
 * <p>Adapted from ShadowAuto {@code android-shell/.../InputBridge.java}
 * (Apache-2.0). Runs inside the Shizuku user service, i.e. with shell identity,
 * which holds {@code android.permission.INJECT_EVENTS}.
 *
 * <p>{@code InputEvent.setDisplayId(int)} is what sends the event to the
 * virtual display instead of the physical screen.
 */
final class InputBridge {
    private static final int INJECT_WAIT_FOR_FINISH = 2;

    private final Object inputManager;
    private final Method inject;
    private final Method setDisplayId;
    private final String unavailable;

    InputBridge(Context context) {
        Object manager = null;
        Method injectMethod = null;
        Method displayMethod = null;
        String problem = null;
        try {
            manager = context.getSystemService(Context.INPUT_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("INPUT_SERVICE unavailable");
            }
            injectMethod = manager.getClass().getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            displayMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable e) {
            problem = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        this.inputManager = manager;
        this.inject = injectMethod;
        this.setDisplayId = displayMethod;
        this.unavailable = problem;
    }

    boolean available() {
        return inputManager != null && inject != null && setDisplayId != null;
    }

    String unavailableReason() {
        return unavailable == null ? "" : unavailable;
    }

    boolean tap(int displayId, int x, int y) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = motion(now, now, MotionEvent.ACTION_DOWN, x, y);
        MotionEvent up = motion(now, now + 80, MotionEvent.ACTION_UP, x, y);
        boolean downOk = send(displayId, down);
        boolean upOk = send(displayId, up);
        return downOk && upOk;
    }

    boolean longPress(int displayId, int x, int y, int durationMs) throws Exception {
        int duration = Math.max(450, durationMs);
        long now = SystemClock.uptimeMillis();
        boolean downOk = send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y));
        SystemClock.sleep(duration);
        boolean upOk = send(displayId, motion(now, now + duration, MotionEvent.ACTION_UP, x, y));
        return downOk && upOk;
    }

    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs)
            throws Exception {
        int duration = Math.max(120, durationMs);
        int steps = Math.max(6, duration / 24);
        long downTime = SystemClock.uptimeMillis();
        boolean ok = send(displayId, motion(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY));
        for (int i = 1; i < steps; i++) {
            float progress = i / (float) steps;
            int x = Math.round(startX + (endX - startX) * progress);
            int y = Math.round(startY + (endY - startY) * progress);
            long eventTime = downTime + Math.round(duration * progress);
            ok = send(displayId, motion(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y)) && ok;
            SystemClock.sleep(8);
        }
        return send(displayId, motion(downTime, downTime + duration,
                MotionEvent.ACTION_UP, endX, endY)) && ok;
    }

    boolean key(int displayId, int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        boolean downOk = send(displayId, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        boolean upOk = send(displayId, new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0));
        return downOk && upOk;
    }

    boolean text(int displayId, String value) throws Exception {
        if (value == null || value.isEmpty()) {
            return true;
        }
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(value.toCharArray());
        if (events == null || events.length == 0) {
            throw new IllegalArgumentException("text cannot be converted to key events");
        }
        boolean ok = true;
        for (KeyEvent event : events) {
            ok = send(displayId, event) && ok;
        }
        return ok;
    }

    private MotionEvent motion(long downTime, long eventTime, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y,
                1f, 1f, 0, 1f, 1f, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private boolean send(int displayId, InputEvent event) throws Exception {
        if (!available()) {
            throw new IllegalStateException("input injection unavailable: " + unavailable);
        }
        setDisplayId.invoke(event, displayId);
        Object result = inject.invoke(inputManager, event, INJECT_WAIT_FOR_FINISH);
        return !(result instanceof Boolean) || (Boolean) result;
    }
}
