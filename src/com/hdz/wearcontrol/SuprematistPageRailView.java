package com.hdz.wearcontrol;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Right-bezel Suprematist page index rail: discrete rotated diamonds with a
 * smoothly gliding Constructivist active bar tuned for circular Watch displays.
 */
public class SuprematistPageRailView extends View {

    private static final float REST_ALPHA = 0.34f;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final long IDLE_DELAY_MS = 1100L;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF activeRect = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int pageCount = 1;
    private float position = 0f;
    private boolean lightSurface = true;
    private float railAlpha = REST_ALPHA;
    private ValueAnimator fadeAnim;

    private final Runnable fadeOutTask = new Runnable() {
        @Override
        public void run() {
            animateAlpha(REST_ALPHA, 420L);
        }
    };

    public SuprematistPageRailView(Context context) {
        this(context, null);
    }

    public SuprematistPageRailView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        setClickable(false);
        setFocusable(false);
    }

    public void setPageCount(int count) {
        int clamped = Math.max(1, count);
        if (pageCount != clamped) {
            pageCount = clamped;
            if (position > pageCount - 1) {
                position = pageCount - 1;
            }
            invalidate();
        }
    }

    public void setPosition(float pos, boolean lightBg, boolean awake) {
        float clamped = Math.max(0f, Math.min(Math.max(0, pageCount - 1), pos));
        boolean changed = Math.abs(clamped - position) > 0.0005f || lightBg != lightSurface;
        position = clamped;
        lightSurface = lightBg;
        if (awake) {
            wake();
        }
        if (changed) {
            invalidate();
        }
    }

    public void wake() {
        ui.removeCallbacks(fadeOutTask);
        if (fadeAnim != null) {
            fadeAnim.cancel();
            fadeAnim = null;
        }
        if (railAlpha < ACTIVE_ALPHA) {
            railAlpha = ACTIVE_ALPHA;
            invalidate();
        }
        ui.postDelayed(fadeOutTask, IDLE_DELAY_MS);
    }

    private void animateAlpha(float target, long durationMs) {
        if (fadeAnim != null) {
            fadeAnim.cancel();
        }
        fadeAnim = ValueAnimator.ofFloat(railAlpha, target);
        fadeAnim.setDuration(durationMs);
        fadeAnim.setInterpolator(new DecelerateInterpolator());
        fadeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                railAlpha = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        fadeAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pageCount <= 1) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float radius = Math.min(w, h) * 0.5f - 6.5f * d;
        float halfSpan = Math.min(18f, (pageCount - 1) * 6.2f);
        float stepDeg = (2f * halfSpan) / (pageCount - 1);

        int alphaByte = Math.max(0, Math.min(255, Math.round(railAlpha * 255f)));
        int idleColor = lightSurface ? 0xFF101316 : 0xFFF6F6F2;
        int activeFill = lightSurface ? 0xFF1E4182 : 0xFFEAB31A;
        int activeStroke = lightSurface ? 0xFF101316 : 0xFF14171C;

        strokePaint.setStrokeWidth(1.1f * d);

        float dotHalf = 1.9f * d;
        for (int i = 0; i < pageCount; i++) {
            double rad = Math.toRadians(-halfSpan + i * stepDeg);
            float dx = cx + (float) (radius * Math.cos(rad));
            float dy = cy + (float) (radius * Math.sin(rad));

            fillPaint.setColor(idleColor);
            fillPaint.setAlpha(Math.round(alphaByte * 0.38f));
            canvas.save();
            canvas.rotate(45f, dx, dy);
            canvas.drawRect(dx - dotHalf, dy - dotHalf, dx + dotHalf, dy + dotHalf, fillPaint);
            canvas.restore();
        }

        float activeDeg = -halfSpan + position * stepDeg;
        double activeRad = Math.toRadians(activeDeg);
        float ax = cx + (float) (radius * Math.cos(activeRad));
        float ay = cy + (float) (radius * Math.sin(activeRad));

        float pillW = 2.8f * d;
        float pillH = 9.2f * d;
        activeRect.set(ax - pillW * 0.5f, ay - pillH * 0.5f, ax + pillW * 0.5f, ay + pillH * 0.5f);

        fillPaint.setColor(activeFill);
        fillPaint.setAlpha(alphaByte);
        strokePaint.setColor(activeStroke);
        strokePaint.setAlpha(Math.round(alphaByte * 0.72f));

        canvas.save();
        canvas.rotate(activeDeg, ax, ay);
        canvas.drawRect(activeRect, fillPaint);
        canvas.drawRect(activeRect, strokePaint);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        if (fadeAnim != null) {
            fadeAnim.cancel();
            fadeAnim = null;
        }
        super.onDetachedFromWindow();
    }
}
