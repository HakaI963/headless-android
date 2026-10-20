package com.bliss.headless;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Converts an {@link Image} from an {@link android.media.ImageReader} backed by
 * {@link android.graphics.PixelFormat#RGBA_8888} into a {@link Bitmap}.
 *
 * <p>Adapted from ShadowAuto {@code android-shell/.../ScreenCapture.java}
 * (Apache-2.0). The row-stride/pixel-stride handling is required because the
 * producer may pad rows, so the raw buffer cannot be copied into a
 * width-sized bitmap directly.
 */
final class ScreenCapture {
    private ScreenCapture() {
    }

    static Bitmap bitmapFromImage(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int paddedWidth = rowStride / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == width) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }
}
