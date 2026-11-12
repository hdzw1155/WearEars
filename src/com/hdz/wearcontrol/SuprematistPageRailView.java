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
 * Left-bezel Suprematist orbital C-arc page indicator ("第一屏 <- -> 最后一屏").
 * Tracks curved orbital progression from top-left (224 deg) through 9-o'clock (180 deg)
 * down to bottom-left (136 deg).
 */
public class SuprematistPageRailView extends View {

    private static final float REST_ALPHA = 0.56f;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final long IDLE_DELAY_MS = 1400L;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
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
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
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
        int alphaByte = Math.max(0, Math.min(255, Math.round(railAlpha * 255f)));
        int idleColor = lightSurface ? 0xFF101316 : 0xFFF3F1E8;
        int trackBg = lightSurface ? 0xFF101318 : 0xFFF3F1E8;
        int activeFill = lightSurface ? 0xFFE5232A : 0xFFF4BA00;
        int activeStroke = 0xFF101318;

        if (!getResources().getConfiguration().isScreenRound()) {
            drawLinearPageRail(canvas, w, h, d, alphaByte, idleColor, trackBg, activeFill, activeStroke);
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float radius = Math.min(w, h) * 0.5f - 6.8f * d;

        // Top-left (222 deg = 第一屏) -> 9-o'clock (180 deg) -> Bottom-left (138 deg = 最后一屏)
        float halfSpan = Math.min(44f, 18f + (pageCount - 1) * 7.5f);
        float topDeg = 180f + halfSpan;
        float sweepDeg = -2f * halfSpan;
        float stepDeg = (2f * halfSpan) / (pageCount - 1);

        // Curved orbital C-arc tube along left bezel matching hand-drawn reference
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        trackPaint.setStrokeWidth(4.0f * d);
        trackPaint.setColor(trackBg);
        trackPaint.setAlpha(Math.round(alphaByte * 0.24f));
        canvas.drawArc(arcBounds, topDeg, sweepDeg, false, trackPaint);

        trackPaint.setStrokeWidth(1.1f * d);
        trackPaint.setAlpha(Math.round(alphaByte * 0.50f));
        canvas.drawArc(arcBounds, topDeg, sweepDeg, false, trackPaint);

        // End-cap rings framing "第一屏" & "最后一屏" along the C-arc
        float capR = 3.2f * d;
        drawEndStop(canvas, cx, cy, radius, topDeg, capR, idleColor, alphaByte);
        drawEndStop(canvas, cx, cy, radius, topDeg + sweepDeg, capR, idleColor, alphaByte);

        // Page station diamonds along the left C-arc
        float dotHalf = 2.0f * d;
        for (int i = 0; i < pageCount; i++) {
            float deg = topDeg - i * stepDeg;
            double rad = Math.toRadians(deg);
            float dx = cx + (float) (radius * Math.cos(rad));
            float dy = cy + (float) (radius * Math.sin(rad));

            fillPaint.setColor(idleColor);
            fillPaint.setAlpha(Math.round(alphaByte * 0.65f));
            canvas.save();
            canvas.rotate(45f, dx, dy);
            canvas.drawRect(dx - dotHalf, dy - dotHalf, dx + dotHalf, dy + dotHalf, fillPaint);
            canvas.restore();
        }

        // Bold sliding Constructivist capsule riding along the C-arc
        float activeDeg = topDeg - position * stepDeg;
        double activeRad = Math.toRadians(activeDeg);
        float ax = cx + (float) (radius * Math.cos(activeRad));
        float ay = cy + (float) (radius * Math.sin(activeRad));

        float pillW = 3.6f * d;
        float pillH = 12.0f * d;
        activeRect.set(ax - pillW * 0.5f, ay - pillH * 0.5f, ax + pillW * 0.5f, ay + pillH * 0.5f);

        fillPaint.setColor(activeFill);
        fillPaint.setAlpha(alphaByte);
        strokePaint.setColor(activeStroke);
        strokePaint.setStrokeWidth(1.3f * d);
        strokePaint.setAlpha(alphaByte);

        canvas.save();
        canvas.rotate(activeDeg + 180f, ax, ay);
        canvas.drawRect(activeRect, fillPaint);
        canvas.drawRect(activeRect, strokePaint);
        canvas.restore();
    }

    /** Linear vertical left-edge poster rail for rectangular phone screens */
    private void drawLinearPageRail(Canvas canvas, int w, int h, float d, int alphaByte,
                                    int idleColor, int trackBg, int activeFill, int activeStroke) {
        float x = 10f * d;
        float topY = h * 0.30f;
        float botY = h * 0.70f;
        float span = Math.max(1f, botY - topY);

        trackPaint.setStrokeWidth(4.0f * d);
        trackPaint.setColor(trackBg);
        trackPaint.setAlpha(Math.round(alphaByte * 0.22f));
        canvas.drawLine(x, topY, x, botY, trackPaint);

        trackPaint.setStrokeWidth(1.2f * d);
        trackPaint.setAlpha(Math.round(alphaByte * 0.50f));
        canvas.drawLine(x, topY, x, botY, trackPaint);

        float capR = 3.2f * d;
        fillPaint.setColor(idleColor);
        fillPaint.setAlpha(Math.round(alphaByte * 0.72f));
        canvas.drawCircle(x, topY, capR, fillPaint);
        canvas.drawCircle(x, botY, capR, fillPaint);

        float dotHalf = 2.1f * d;
        float step = span / (pageCount - 1);
        for (int i = 0; i < pageCount; i++) {
            float dy = topY + i * step;
            fillPaint.setColor(idleColor);
            fillPaint.setAlpha(Math.round(alphaByte * 0.65f));
            canvas.save();
            canvas.rotate(45f, x, dy);
            canvas.drawRect(x - dotHalf, dy - dotHalf, x + dotHalf, dy + dotHalf, fillPaint);
            canvas.restore();
        }

        float ay = topY + (position / (pageCount - 1)) * span;
        float pillW = 4.0f * d;
        float pillH = 14.0f * d;
        activeRect.set(x - pillW * 0.5f, ay - pillH * 0.5f, x + pillW * 0.5f, ay + pillH * 0.5f);

        fillPaint.setColor(activeFill);
        fillPaint.setAlpha(alphaByte);
        strokePaint.setColor(activeStroke);
        strokePaint.setStrokeWidth(1.3f * d);
        strokePaint.setAlpha(alphaByte);
        canvas.drawRect(activeRect, fillPaint);
        canvas.drawRect(activeRect, strokePaint);
    }

    private void drawEndStop(Canvas canvas, float cx, float cy, float radius, float deg,
                             float capR, int color, int alphaByte) {
        double rad = Math.toRadians(deg);
        float ex = cx + (float) (radius * Math.cos(rad));
        float ey = cy + (float) (radius * Math.sin(rad));
        fillPaint.setColor(color);
        fillPaint.setAlpha(Math.round(alphaByte * 0.72f));
        canvas.drawCircle(ex, ey, capR, fillPaint);
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
