package com.bliss.headless;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;

/**
 * Owns one virtual display and its {@link ImageReader} capture surface.
 *
 * <p>Extracted from ShadowAuto {@code android-shell/.../VirtualDisplaySession.java}
 * (Apache-2.0) and simplified for this app: no MediaCodec input surface and no
 * IME surface swapping in the capture path. The display renders directly into
 * the {@link ImageReader} surface, so every frame is available as an RGBA
 * bitmap/JPG without a second surface flip.
 *
 * <p>Hidden {@code VIRTUAL_DISPLAY_FLAG_*} values are read reflectively because
 * they are not part of the public SDK. Flags are tried from the richest set
 * (touch + own focus + trusted) down to plain public flags, which is what makes
 * this work across ROMs: some Android builds reject {@code TRUSTED}, others
 * reject {@code PRESENTATION}.
 */
final class VirtualDisplaySession {
    private static final String TAG = "HeadlessDisplay";

    final int displayId;
    final int width;
    final int height;
    final int dpi;

    private final ImageReader reader;
    private final Surface surface;
    private final VirtualDisplay virtualDisplay;
    private final Object captureLock = new Object();
    private final WindowManagerBridge windowManager;
    private final int previousImePolicy;
    private final boolean localIme;
    private final String flagsLabel;

    private Bitmap lastBitmap;
    private long lastFrameAt;
    private long frameCount;

    private VirtualDisplaySession(int width, int height, int dpi, ImageReader reader,
                                  Surface surface, VirtualDisplay virtualDisplay, Display display,
                                  int flags) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.reader = reader;
        this.surface = surface;
        this.virtualDisplay = virtualDisplay;
        this.displayId = display.getDisplayId();
        this.flagsLabel = describeFlags(flags);

        WindowManagerBridge manager = null;
        int previous = -1;
        boolean local = false;
        try {
            manager = new WindowManagerBridge();
            previous = manager.getDisplayImePolicy(displayId);
            local = manager.setDisplayImePolicy(displayId, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL);
        } catch (Throwable ignored) {
            // IME policy is best-effort; the display works without it.
        }
        this.windowManager = manager;
        this.previousImePolicy = previous;
        this.localIme = local;
    }

    static VirtualDisplaySession create(Context context, int width, int height, int dpi) {
        DisplayManager manager = displayManager(context);
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        Surface surface = reader.getSurface();

        int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int touchFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focusFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int trustedFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        int[] candidates = new int[]{
                baseFlags | touchFlag | focusFlag | trustedFlag,
                baseFlags | touchFlag | focusFlag,
                baseFlags | touchFlag,
                baseFlags
        };

        Throwable lastError = null;
        for (int flags : candidates) {
            VirtualDisplay display = null;
            try {
                display = manager.createVirtualDisplay(
                        "HeadlessAndroid", width, height, dpi, surface, flags);
                Display target = display == null ? null : display.getDisplay();
                if (target != null) {
                    return new VirtualDisplaySession(width, height, dpi,
                            reader, surface, display, target, flags);
                }
            } catch (Throwable e) {
                lastError = e;
            }
            if (display != null) {
                try {
                    display.release();
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
        }
        try {
            surface.release();
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("createVirtualDisplay failed for every flag set", lastError);
    }

    int displayId() {
        return displayId;
    }

    long frameCount() {
        return frameCount;
    }

    long lastFrameAt() {
        return lastFrameAt;
    }

    String flagsLabel() {
        return flagsLabel;
    }

    boolean localIme() {
        return localIme;
    }

    String stateName() {
        int state;
        try {
            state = virtualDisplay.getDisplay().getState();
        } catch (Throwable e) {
            return "unknown";
        }
        switch (state) {
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case Display.STATE_VR:
                return "VR";
            case Display.STATE_ON_SUSPEND:
                return "ON_SUSPEND";
            default:
                return "STATE_" + state;
        }
    }

    /**
     * Some ROMs create the virtual display in the OFF state, in which case the
     * content is never composed. Detaching and re-attaching the surface turns it
     * back ON (same workaround as the upstream engine).
     */
    boolean ensureOn() {
        if ("ON".equals(stateName())) {
            return true;
        }
        try {
            virtualDisplay.setSurface(null);
            SystemClock.sleep(120);
            virtualDisplay.setSurface(surface);
            SystemClock.sleep(300);
        } catch (Throwable ignored) {
        }
        return "ON".equals(stateName());
    }

    /** Captures the latest frame as JPEG, or {@code null} when none exists yet. */
    byte[] captureJpeg(int timeoutMs, int quality) {
        synchronized (captureLock) {
            Bitmap bitmap = captureLocked(timeoutMs);
            if (bitmap == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return out.toByteArray();
        }
    }

    /** Captures a defensive copy for analysis/evidence storage. */
    Bitmap captureCopy(int timeoutMs) {
        synchronized (captureLock) {
            Bitmap bitmap = captureLocked(timeoutMs);
            if (bitmap == null) {
                return null;
            }
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }
    }

    private Bitmap captureLocked(int timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, timeoutMs);
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (Throwable ignored) {
        }
        while (image == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25);
            try {
                image = reader.acquireLatestImage();
            } catch (Throwable ignored) {
            }
        }
        if (image != null) {
            try {
                Bitmap bitmap = ScreenCapture.bitmapFromImage(image);
                if (lastBitmap != null) {
                    lastBitmap.recycle();
                }
                lastBitmap = bitmap;
                lastFrameAt = SystemClock.uptimeMillis();
                frameCount++;
                return bitmap;
            } finally {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
        // Static content stops producing buffers once they are consumed; the
        // cached bitmap still represents the current display content.
        return lastBitmap;
    }

    String statusJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("display_id", displayId);
            object.put("width", width);
            object.put("height", height);
            object.put("dpi", dpi);
            object.put("state", stateName());
            object.put("flags", flagsLabel);
            object.put("frames", frameCount);
            object.put("last_frame_ms", lastFrameAt);
            object.put("ime_local", localIme);
        } catch (Throwable ignored) {
        }
        return object.toString();
    }

    void release() {
        synchronized (captureLock) {
            if (lastBitmap != null) {
                lastBitmap.recycle();
                lastBitmap = null;
            }
        }
        try {
            if (windowManager != null && previousImePolicy != -1) {
                windowManager.setDisplayImePolicy(displayId, previousImePolicy);
            }
        } catch (Throwable ignored) {
        }
        try {
            virtualDisplay.release();
        } catch (Throwable ignored) {
        }
        try {
            surface.release();
        } catch (Throwable ignored) {
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
        }
    }

    private static DisplayManager displayManager(Context context) {
        Object service = context.getSystemService(Context.DISPLAY_SERVICE);
        if (service instanceof DisplayManager) {
            return (DisplayManager) service;
        }
        try {
            java.lang.reflect.Constructor<DisplayManager> constructor =
                    DisplayManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            return constructor.newInstance(context);
        } catch (Throwable e) {
            throw new IllegalStateException("DisplayManager unavailable", e);
        }
    }

    private static String describeFlags(int flags) {
        StringBuilder out = new StringBuilder();
        int touch = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focus = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int trusted = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
            out.append("PUBLIC|");
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
            out.append("PRESENTATION|");
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
            out.append("OWN_CONTENT_ONLY|");
        }
        if (touch != 0 && (flags & touch) != 0) {
            out.append("SUPPORTS_TOUCH|");
        }
        if (focus != 0 && (flags & focus) != 0) {
            out.append("OWN_FOCUS|");
        }
        if (trusted != 0 && (flags & trusted) != 0) {
            out.append("TRUSTED|");
        }
        if (out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static int hiddenFlag(String name) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
