package com.bliss.headless;

import android.graphics.Bitmap;

/**
 * Pixel checks used by the built-in self-test. All methods sample a grid
 * instead of every pixel so they stay cheap even at 1080p.
 */
final class FrameAnalysis {
    /** Quadrant colors painted by {@link TestPatternActivity}. */
    static final int C_TOP_LEFT = 0xFFFF00FF;     // magenta
    static final int C_TOP_RIGHT = 0xFFFFEB3B;    // yellow
    static final int C_BOTTOM_LEFT = 0xFF00BCD4;  // cyan
    static final int C_BOTTOM_RIGHT = 0xFF3F51B5; // indigo
    /** Background after the display received a tap. */
    static final int C_TAPPED = 0xFF00C853;       // green
    /** JPEG/color-management tolerance per channel. */
    static final int TOLERANCE = 70;

    private FrameAnalysis() {
    }

    static int[] sample(Bitmap bitmap, double fractionX, double fractionY) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int x = clamp((int) Math.round(width * fractionX), 0, width - 1);
        int y = clamp((int) Math.round(height * fractionY), 0, height - 1);
        int pixel = bitmap.getPixel(x, y);
        return new int[]{(pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF};
    }

    static boolean near(int[] actual, int expected, int tolerance) {
        int red = (expected >> 16) & 0xFF;
        int green = (expected >> 8) & 0xFF;
        int blue = expected & 0xFF;
        return Math.abs(actual[0] - red) <= tolerance
                && Math.abs(actual[1] - green) <= tolerance
                && Math.abs(actual[2] - blue) <= tolerance;
    }

    static boolean allQuadrants(Bitmap bitmap, int expected, int tolerance) {
        return near(sample(bitmap, 0.25, 0.25), expected, tolerance)
                && near(sample(bitmap, 0.75, 0.25), expected, tolerance)
                && near(sample(bitmap, 0.25, 0.75), expected, tolerance)
                && near(sample(bitmap, 0.75, 0.75), expected, tolerance);
    }

    /**
     * Mean absolute RGB difference between two frames on a 32x32 sample grid.
     * A static screen stays near 0; the moving bar in TestPatternActivity
     * produces a clearly positive value, proving frames keep arriving.
     */
    static double meanDiff(Bitmap first, Bitmap second) {
        if (first == null || second == null
                || first.getWidth() != second.getWidth()
                || first.getHeight() != second.getHeight()) {
            return -1;
        }
        int steps = 32;
        long total = 0;
        int count = 0;
        for (int ix = 0; ix < steps; ix++) {
            for (int iy = 0; iy < steps; iy++) {
                double fx = (ix + 0.5) / steps;
                double fy = (iy + 0.5) / steps;
                int[] a = sample(first, fx, fy);
                int[] b = sample(second, fx, fy);
                total += Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
                count += 3;
            }
        }
        return count == 0 ? -1 : total / (double) count;
    }

    static String describe(int[] rgb) {
        return "(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + ")";
    }

    static String quadrantSummary(Bitmap bitmap) {
        return "TL=" + describe(sample(bitmap, 0.25, 0.25))
                + " TR=" + describe(sample(bitmap, 0.75, 0.25))
                + " BL=" + describe(sample(bitmap, 0.25, 0.75))
                + " BR=" + describe(sample(bitmap, 0.75, 0.75));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
