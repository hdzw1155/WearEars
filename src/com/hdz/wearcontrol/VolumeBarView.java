package com.hdz.wearcontrol;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 音量条: 按当前界面风格画成对应的样子, 和电量区视觉统一.
 *
 * <ul>
 *   <li>极简(STYLE_NEW): 深灰细槽 + 电量条同款绿色填充 + 白色圆点;</li>
 *   <li>液位(STYLE_LIQUID): 整块圆角槽 + 半透明琥珀绿"液位"填充 + 液面亮线, 对应液位电量块;</li>
 *   <li>经典(STYLE_CLASSIC): 深灰 stadium 轨道 + 从左侧水平推进的绿色填充 + 白色小圆点, 和经典电量环同一套配色;</li>
 * </ul>
 *
 * <p>拖动时只通过 {@link OnLevelChangeListener} 把目标档位交给 Activity, 由 Activity 调
 * AudioManager 改 STREAM_MUSIC — 这样耳机音量和系统音量永远是同一个值。
 */
public class VolumeBarView extends View {

    /** 档位变化回调(拖动过程中会连续触发, Activity 自己决定要不要节流) */
    public interface OnLevelChangeListener {
        void onLevelChanged(int level, int max);
    }

    private static final int STYLE_CLASSIC = AppPrefs.STYLE_CLASSIC;
    private static final int STYLE_LIQUID = AppPrefs.STYLE_LIQUID;

    // 极简
    private static final int COLOR_TRACK = 0xFF1E1E21;
    private static final int COLOR_FILL = 0xFF4CC38A;
    private static final int COLOR_KNOB = 0xFFF2F2F5;
    // 液位
    private static final int COLOR_LIQUID_BLOCK = 0xFF1E1E21;
    private static final int COLOR_LIQUID_FILL = 0x454CC38A;
    private static final int COLOR_LIQUID_SURFACE = 0xFF4CC38A;
    // 经典
    private static final int COLOR_CLASSIC_TRACK = 0xFF3A3A3C;
    private static final int COLOR_CLASSIC_TRACK_IN = 0xFF26262A;
    private static final int COLOR_CLASSIC_FILL = 0xFF34C759;
    private static final float STROKE_DP = 3.2f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint classicFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final Path clipPath = new Path();

    private final float trackHeight;
    private final float knobRadius;
    private final float strokePx;

    private int style = AppPrefs.STYLE_NEW;
    private int level;
    private int max = 15;
    private float shownLevel;
    private ValueAnimator animator;
    private OnLevelChangeListener listener;
    private boolean dragging;

    public VolumeBarView(Context context) {
        this(context, null);
    }

    public VolumeBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        trackHeight = 6f * density;
        knobRadius = 5f * density;
        strokePx = STROKE_DP * density;
        trackPaint.setColor(COLOR_TRACK);
        knobPaint.setColor(COLOR_KNOB);
        blockPaint.setColor(COLOR_LIQUID_BLOCK);
        surfacePaint.setColor(COLOR_LIQUID_SURFACE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(strokePx);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        outlinePaint.setColor(COLOR_CLASSIC_TRACK);
        classicFillPaint.setStyle(Paint.Style.STROKE);
        classicFillPaint.setStrokeWidth(strokePx);
        classicFillPaint.setStrokeCap(Paint.Cap.ROUND);
        classicFillPaint.setColor(COLOR_CLASSIC_FILL);
        setClickable(true);
    }

    /** 跟随界面风格切换画法: 0=极简 1=经典 2=液位 */
    public void setStyle(int uiStyle) {
        if (style == uiStyle) {
            return;
        }
        style = uiStyle;
        invalidate();
    }

    public void setOnLevelChangeListener(OnLevelChangeListener l) {
        listener = l;
    }

    /** 系统音量变化时由 Activity 调用: 外部改变直接跳过去, 不做动画 */
    public void setLevel(int level, int max) {
        this.max = Math.max(1, max);
        int clamped = Math.max(0, Math.min(this.max, level));
        boolean same = this.level == clamped && Math.abs(shownLevel - clamped) < 0.01f;
        this.level = clamped;
        if (same) {
            invalidate();
            return;
        }
        animateTo(clamped);
    }

    public int getLevel() {
        return level;
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        final float start = shownLevel;
        final float delta = target - shownLevel;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(180L);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                shownLevel = start + delta * animation.getAnimatedFraction();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float frac = max > 0 ? shownLevel / max : 0f;
        frac = Math.max(0f, Math.min(1f, frac));
        if (style == STYLE_CLASSIC) {
            drawClassic(canvas, w, h, frac);
        } else if (style == STYLE_LIQUID) {
            drawLiquid(canvas, w, h, frac);
        } else {
            drawNew(canvas, w, h, frac);
        }
    }

    /** 极简: 细槽 + 电量绿 + 圆点 */
    private void drawNew(Canvas canvas, int w, int h, float frac) {
        float cy = h / 2f;
        float left = knobRadius;
        float right = w - knobRadius;
        if (right <= left) {
            return;
        }
        float half = trackHeight / 2f;
        trackRect.set(left, cy - half, right, cy + half);
        canvas.drawRoundRect(trackRect, half, half, trackPaint);

        float fillRight = left + (right - left) * frac;
        if (frac > 0.002f) {
            fillPaint.setShader(null);
            fillPaint.setColor(COLOR_FILL);
            clipPath.reset();
            clipPath.addRoundRect(trackRect, half, half, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            canvas.drawRect(left, trackRect.top, fillRight, trackRect.bottom, fillPaint);
            canvas.restore();
        }
        float knob = dragging ? knobRadius * 1.25f : knobRadius;
        canvas.drawCircle(fillRight, cy, knob, knobPaint);
    }

    /** 液位: 整块圆角 + 半透明液位 + 液面亮线 */
    private void drawLiquid(Canvas canvas, int w, int h, float frac) {
        float radius = h / 2f;
        trackRect.set(0f, 0f, w, h);
        canvas.drawRoundRect(trackRect, radius, radius, blockPaint);
        if (frac <= 0.002f) {
            return;
        }
        float fillRight = w * frac;
        clipPath.reset();
        clipPath.addRoundRect(trackRect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        fillPaint.setShader(null);
        fillPaint.setColor(COLOR_LIQUID_FILL);
        canvas.drawRect(0f, 0f, fillRight, h, fillPaint);
        canvas.restore();
        // 液面亮线: 比填充本身亮一点, 一眼看出液位在哪
        float lineW = Math.max(2f, getResources().getDisplayMetrics().density * 1.6f);
        surfacePaint.setColor(COLOR_LIQUID_SURFACE);
        canvas.drawRect(Math.max(0f, fillRight - lineW), 0f, fillRight, h, surfacePaint);
    }

    /** 经典: 深灰 stadium 轨道 + 从左往右水平推进的绿色填充 + 白色小圆点 */
    private void drawClassic(Canvas canvas, int w, int h, float frac) {
        float inset = strokePx / 2f;
        float left = inset;
        float right = w - inset;
        if (right <= left) {
            return;
        }
        trackRect.set(left, inset, right, h - inset);
        float radius = trackRect.height() / 2f;
        // 轨道先铺深灰, 再叠绿色进度; 比原来"沿轮廓描边"直观得多
        fillPaint.setShader(null);
        fillPaint.setColor(COLOR_CLASSIC_TRACK_IN);
        canvas.drawRoundRect(trackRect, radius, radius, fillPaint);
        float progressX = left + (right - left) * frac;
        if (frac > 0.002f) {
            clipPath.reset();
            clipPath.addRoundRect(trackRect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            fillPaint.setColor(COLOR_CLASSIC_FILL);
            canvas.drawRect(left, trackRect.top, progressX, trackRect.bottom, fillPaint);
            canvas.restore();
        }
        // 外描边: 深灰卡片上也能看清轨道边界
        canvas.drawRoundRect(trackRect, radius, radius, outlinePaint);
        float knob = Math.min(knobRadius, trackRect.height() * 0.42f);
        knobPaint.setColor(COLOR_KNOB);
        canvas.drawCircle(progressX, h / 2f, dragging ? knob * 1.2f : knob, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            dragging = true;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            float left = knobRadius;
            float right = getWidth() - knobRadius;
            float f = right > left ? (e.getX() - left) / (right - left) : 0f;
            f = Math.max(0f, Math.min(1f, f));
            int target = Math.round(f * max);
            if (target != level) {
                level = target;
                shownLevel = target;
                invalidate();
                if (listener != null) {
                    listener.onLevelChanged(level, max);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            invalidate();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        animator = null;
        super.onDetachedFromWindow();
    }
}
