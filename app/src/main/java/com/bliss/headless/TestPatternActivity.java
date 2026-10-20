package com.bliss.headless;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Deterministic drawing target for the self-test.
 *
 * <p>This activity is launched <b>on the virtual display</b> by the shell
 * engine ({@code am start --display <id>}). Its pixels are the evidence that the
 * display really composes content:
 *
 * <ul>
 *   <li>four flat, distinct quadrant colors — checked pixel by pixel,</li>
 *   <li>a moving sweep bar — two captured frames must differ,</li>
 *   <li>any tap flips the whole background to green — proves that injected
 *       input reached the virtual display and was processed by the app.</li>
 * </ul>
 */
public class TestPatternActivity extends Activity {
    private PatternView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        view = new PatternView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.animate(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.animate(false);
    }

    /** Full-screen pattern view; intentionally dependency-free. */
    static final class PatternView extends View {
        private static final int INK = 0xFF101418;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long startUptime = SystemClock.uptimeMillis();
        private boolean running;
        private boolean tapped;
        private int tapCount;

        PatternView(Context context) {
            super(context);
        }

        void animate(boolean run) {
            running = run;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            if (tapped) {
                paint.setColor(FrameAnalysis.C_TAPPED);
                canvas.drawRect(0, 0, width, height, paint);
            } else {
                paint.setColor(FrameAnalysis.C_TOP_LEFT);
                canvas.drawRect(0, 0, width / 2f, height / 2f, paint);
                paint.setColor(FrameAnalysis.C_TOP_RIGHT);
                canvas.drawRect(width / 2f, 0, width, height / 2f, paint);
                paint.setColor(FrameAnalysis.C_BOTTOM_LEFT);
                canvas.drawRect(0, height / 2f, width / 2f, height, paint);
                paint.setColor(FrameAnalysis.C_BOTTOM_RIGHT);
                canvas.drawRect(width / 2f, height / 2f, width, height, paint);
            }

            float centerX = width / 2f;
            float centerY = height / 2f;
            float radius = Math.min(width, height) * 0.20f;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(centerX, centerY, radius, paint);

            paint.setColor(INK);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(16f, width / 18f));
            canvas.drawText(tapped ? "TAP OK" : "HEADLESS",
                    centerX, centerY - radius * 0.08f, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(12f, width / 30f));
            canvas.drawText(tapped ? "taps: " + tapCount : "DISPLAY TEST",
                    centerX, centerY + radius * 0.48f, paint);

            // Moving sweep bar: makes frame-to-frame liveness measurable.
            float stripTop = height * 0.88f;
            float stripBottom = height * 0.94f;
            paint.setColor(INK);
            canvas.drawRect(0, stripTop, width, stripBottom, paint);
            long elapsed = SystemClock.uptimeMillis() - startUptime;
            float phase = (elapsed % 1600L) / 1600f;
            float barWidth = Math.max(24f, width * 0.08f);
            float barLeft = phase * (width + barWidth) - barWidth;
            paint.setColor(Color.WHITE);
            canvas.drawRect(barLeft, stripTop, barLeft + barWidth, stripBottom, paint);

            if (running) {
                postInvalidateOnAnimation();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                tapped = true;
                tapCount++;
                invalidate();
            }
            return true;
        }
    }
}
