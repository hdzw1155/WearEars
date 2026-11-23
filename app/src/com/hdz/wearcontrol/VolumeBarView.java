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
import android.view.ViewConfiguration;
import android.view.HapticFeedbackConstants;

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
        default void onLockChanged() { }
        default void onLockedIncrease() { }
    }

    private static final int STYLE_CLASSIC = AppPrefs.STYLE_CLASSIC;
    private static final int STYLE_LIQUID = AppPrefs.STYLE_LIQUID;
    private static final int STYLE_SUPREME = AppPrefs.STYLE_SUPREME;

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
    // 至上
    private static final int COLOR_SUPREME_TRACK = 0xFF2B313B;
    private static final int COLOR_SUPREME_BORDER = 0xFF464F5C;
    private static final int COLOR_SUPREME_FILL = 0xFF4CC38A;
    private static final int COLOR_SUPREME_TICK = 0xFFEAB31A;
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
    private VolumeGuard guard = new VolumeGuard();
    private float downX, downY;
    private boolean holding, unlockedThisTouch, lockPressed, hintSent;
    /** 新系列状态: 画法、按下回弹、锁钮弹跳、液面晃动 */
    private boolean neo;
    private NeoVolumeArt neoArt;
    private float press, pressTarget, lockScale = 1f, wavePhase = 0.6f, waveEnergy;
    private ValueAnimator pressAnim, lockAnim, waveAnim;
    private final Runnable unlock = new Runnable() {
        @Override public void run() {
            if (!holding) return;
            guard.unlock();
            unlockedThisTouch = true;
            updateLockDescription();
            if (listener != null) listener.onLockChanged();
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            popLock();
            invalidate();
        }
    };

    void setVolumeGuard(VolumeGuard guard) { this.guard = guard; updateLockDescription(); }
    private void updateLockDescription() {
        setContentDescription(getContext().getString(guard.isUnlocked()
                ? R.string.sup_vol_unlocked_hint : R.string.volume_locked));
    }
    void cancelInteraction() {
        holding = dragging = unlockedThisTouch = false;
        animatePress(0f);
        updateLockDescription();
        removeCallbacks(unlock);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

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

    /** 锁图标与主题覆盖配色 */
    private int lockColor = COLOR_KNOB;
    private int newFillColor = COLOR_FILL;
    private int classicFillColor = COLOR_CLASSIC_FILL;
    private int classicTrackInColor = COLOR_CLASSIC_TRACK_IN;
    private int liquidFillColor = COLOR_LIQUID_FILL;
    private int liquidSurfaceColor = COLOR_LIQUID_SURFACE;

    public void setLockColor(int color) {
        lockColor = color;
        invalidate();
    }

    public void setNeoColors(int fillColor, int trackColor) {
        newFillColor = fillColor;
        classicFillColor = fillColor;
        classicTrackInColor = trackColor;
        liquidFillColor = 0xFF1F7A52;
        liquidSurfaceColor = fillColor;
        trackPaint.setColor(trackColor);
        blockPaint.setColor(trackColor);
        outlinePaint.setColor(0xFF2E3D59);
        invalidate();
    }

    public void setOnLevelChangeListener(OnLevelChangeListener l) {
        listener = l;
    }

    /** 系统音量变化时由 Activity 调用: 外部改变直接跳过去, 不做动画 */
    public void setLevel(int level, int max) {
        updateLockDescription();
        this.max = Math.max(1, max);
        int clamped = Math.max(0, Math.min(this.max, level));
        boolean same = this.level == clamped && Math.abs(shownLevel - clamped) < 0.01f;
        this.level = clamped;
        if (same) {
            invalidate();
            return;
        }
        kickWave();
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
        if (neo && neoArt != null) {
            float a = travelStart(), b = travelEnd();
            neoArt.draw(canvas, style, w, h, frac, a + (b - a) * frac, neoTrackRight(),
                    press, guard.isUnlocked(), lockScale, wavePhase, waveEnergy,
                    getResources().getDisplayMetrics().density);
            return;
        }
        if (style == STYLE_CLASSIC) {
            drawClassic(canvas, w, h, frac);
        } else if (style == STYLE_LIQUID) {
            drawLiquid(canvas, w, h, frac);
        } else if (style == STYLE_SUPREME) {
            drawSupreme(canvas, w, h, frac);
        } else {
            drawNew(canvas, w, h, frac);
        }
        drawLock(canvas, w, h);
    }

    /** 白色线条锁，避免不同设备将 Unicode 锁字符替换为彩色表情。 */
    private void drawLock(Canvas canvas, int width, int height) {
        float d = getResources().getDisplayMetrics().density;
        float x = width - 12f * d, y = height / 2f;
        surfacePaint.setShader(null);
        surfacePaint.setColor(lockColor);
        surfacePaint.setStyle(Paint.Style.STROKE);
        surfacePaint.setStrokeWidth(1.4f * d);
        trackRect.set(x - 5f * d, y - d, x + 5f * d, y + 6f * d);
        canvas.drawRoundRect(trackRect, 1.5f * d, 1.5f * d, surfacePaint);
        float shift = guard.isUnlocked() ? 3f * d : 0f;
        trackRect.set(x - 3f * d + shift, y - 7f * d, x + 3f * d + shift, y + 2f * d);
        canvas.drawArc(trackRect, 180f, 180f, false, surfacePaint);
        surfacePaint.setStyle(Paint.Style.FILL);
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
            fillPaint.setColor(newFillColor);
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
        fillPaint.setColor(liquidFillColor);
        canvas.drawRect(0f, 0f, fillRight, h, fillPaint);
        canvas.restore();
        // 液面亮线: 比填充本身亮一点, 一眼看出液位在哪
        float lineW = Math.max(2f, getResources().getDisplayMetrics().density * 1.6f);
        surfacePaint.setColor(liquidSurfaceColor);
        canvas.drawRect(Math.max(0f, fillRight - lineW), 0f, fillRight, h, surfacePaint);
    }

    /** 经典: 深灰 stadium 轨道 + 从左往右水平推进填充 + 白色小圆点 */
    private void drawClassic(Canvas canvas, int w, int h, float frac) {
        float inset = strokePx / 2f;
        float left = inset;
        float right = w - inset;
        if (right <= left) {
            return;
        }
        trackRect.set(left, inset, right, h - inset);
        float radius = trackRect.height() / 2f;
        // 轨道先铺底色, 再叠主题填充
        fillPaint.setShader(null);
        fillPaint.setColor(classicTrackInColor);
        canvas.drawRoundRect(trackRect, radius, radius, fillPaint);
        float progressX = left + (right - left) * frac;
        if (frac > 0.002f) {
            clipPath.reset();
            clipPath.addRoundRect(trackRect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            fillPaint.setColor(classicFillColor);
            canvas.drawRect(left, trackRect.top, progressX, trackRect.bottom, fillPaint);
            canvas.restore();
        }
        // 外描边: 深灰卡片上也能看清轨道边界
        canvas.drawRoundRect(trackRect, radius, radius, outlinePaint);
        float knob = Math.min(knobRadius, trackRect.height() * 0.42f);
        knobPaint.setColor(COLOR_KNOB);
        canvas.drawCircle(progressX, h / 2f, dragging ? knob * 1.2f : knob, knobPaint);
    }

    /** 至上: 直角架构轨道 + 翡翠填充 + 镉黄竖标 */
    private void drawSupreme(Canvas canvas, int w, int h, float frac) {
        float d = getResources().getDisplayMetrics().density;
        float tickHalfW = (dragging ? 2.4f : 1.7f) * d;
        float left = tickHalfW;
        float right = w - tickHalfW;
        if (right <= left) {
            return;
        }
        float barTop = h * 0.28f;
        float barBottom = h * 0.72f;
        trackRect.set(left, barTop, right, barBottom);
        float r = 1.5f * d;

        fillPaint.setShader(null);
        fillPaint.setColor(COLOR_SUPREME_TRACK);
        canvas.drawRoundRect(trackRect, r, r, fillPaint);

        float fillX = left + (right - left) * frac;
        if (frac > 0.002f) {
            clipPath.reset();
            clipPath.addRoundRect(trackRect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            fillPaint.setColor(COLOR_SUPREME_FILL);
            canvas.drawRect(left, barTop, fillX, barBottom, fillPaint);
            canvas.restore();
        }

        surfacePaint.setStyle(Paint.Style.STROKE);
        surfacePaint.setStrokeWidth(1.1f * d);
        surfacePaint.setColor(COLOR_SUPREME_BORDER);
        canvas.drawRoundRect(trackRect, r, r, surfacePaint);
        surfacePaint.setStyle(Paint.Style.FILL);

        float markTop = h * 0.14f;
        float markBottom = h * 0.86f;
        trackRect.set(fillX - tickHalfW, markTop, fillX + tickHalfW, markBottom);
        knobPaint.setColor(COLOR_SUPREME_TICK);
        canvas.drawRect(trackRect, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY();
            removeCallbacks(unlock);
            holding = true;
            dragging = unlockedThisTouch = hintSent = false;
            lockPressed = e.getX() >= lockZoneLeft();
            if (!guard.isUnlocked()) postDelayed(unlock, ViewConfiguration.getLongPressTimeout());
            else if (!lockPressed) animatePress(1f);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelInteraction();
            return true;
        }
        if (!holding && !dragging) return false;
        if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return true;
        boolean released = action == MotionEvent.ACTION_UP;
        if (unlockedThisTouch) {
            if (released) cancelInteraction();
            return true;
        }
        if (holding) {
            float dx = e.getX() - downX, dy = e.getY() - downY;
            if (Math.hypot(dx, dy) <= ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                if (released) {
                    if (!lockPressed) changeLevel(e.getX());
                    else if (e.getX() >= lockZoneLeft()) performClick();
                    cancelInteraction();
                }
                return true;
            }
            if (Math.abs(dx) <= Math.abs(dy) || (lockPressed && !guard.isUnlocked())) {
                cancelInteraction();
                return false;
            }
            removeCallbacks(unlock);
            holding = false;
            dragging = true;
            if (animator != null) { animator.cancel(); animator = null; }
            animatePress(1f);
            if (!released && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        changeLevel(e.getX());
        if (released) cancelInteraction();
        return true;
    }

    private void changeLevel(float x) {
        float a = travelStart(), b = travelEnd();
        float fraction = b > a ? (x - a) / (b - a) : 0f;
        int target = Math.round(Math.max(0f, Math.min(1f, fraction)) * max);
        if (!guard.isUnlocked() && target > level) {
            if (!hintSent && !guard.needsHighConfirmation() && listener != null) listener.onLockedIncrease();
            hintSent = true;
            return;
        }
        if (target == level) return;
        level = target;
        shownLevel = target;
        kickWave();
        invalidate();
        if (listener != null) listener.onLevelChanged(level, max);
    }

    // ---------------- 新系列: 几何与 Q 弹动效 ----------------

    /** 新系列开关: 换成 {@link NeoVolumeArt} 的画法, 锁放到右侧独立圆钮 */
    void setNeo(boolean on) {
        if (neo == on) return;
        neo = on;
        if (on && neoArt == null) neoArt = new NeoVolumeArt();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 新系列轨道右边界, 右侧留出直径=控件高度的锁钮和 8dp 间距 */
    private float neoTrackRight() {
        return getWidth() - getHeight() - dp(8f);
    }

    /** 填充终点(圆钮中心)可到达的最左/最右位置, 画和触控共用 */
    private float travelStart() {
        if (!neo) return knobRadius;
        if (style == STYLE_CLASSIC) return getHeight() / 2f;
        if (style == STYLE_LIQUID) return dp(0.5f);
        return dp(11f);
    }

    private float travelEnd() {
        if (!neo) return getWidth() - knobRadius;
        float right = neoTrackRight();
        if (style == STYLE_CLASSIC) return right - getHeight() / 2f;
        if (style == STYLE_LIQUID) return right - dp(0.5f);
        return right - dp(11f);
    }

    private float lockZoneLeft() {
        return neo ? neoTrackRight() + dp(4f) : getWidth() - dp(24f);
    }

    /** 按下/松开圆钮: 带回弹的放大缩小 */
    private void animatePress(final float target) {
        if (!neo || pressTarget == target) return;
        pressTarget = target;
        if (pressAnim != null) pressAnim.cancel();
        final float start = press;
        pressAnim = ValueAnimator.ofFloat(0f, 1f);
        pressAnim.setDuration(target > 0f ? 260L : 320L);
        pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                press = start + (target - start) * backOut(a.getAnimatedFraction());
                invalidate();
            }
        });
        pressAnim.start();
    }

    /** 锁钮切换时轻轻一弹 */
    private void popLock() {
        if (!neo) return;
        if (lockAnim != null) lockAnim.cancel();
        lockAnim = ValueAnimator.ofFloat(0f, 1f);
        lockAnim.setDuration(420L);
        lockAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = a.getAnimatedFraction();
                lockScale = t >= 1f ? 1f : 1f - 0.16f
                        * (float) (Math.exp(-3.0 * t) * Math.sin(2.6 * Math.PI * t)) * (1f - t * t * t);
                invalidate();
            }
        });
        lockAnim.start();
    }

    /** 新液位: 音量变化时液面晃一会儿再静止, 不常驻动画 */
    private void kickWave() {
        if (!neo || style != STYLE_LIQUID) return;
        if (waveAnim != null) waveAnim.cancel();
        final float startPhase = wavePhase;
        waveAnim = ValueAnimator.ofFloat(0f, 1f);
        waveAnim.setDuration(1500L);
        waveAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = a.getAnimatedFraction();
                wavePhase = startPhase + t * 7f;
                waveEnergy = 1f - t;
                invalidate();
            }
        });
        waveAnim.start();
    }

    /** easeOutBack: 冲过终点约 10% 再回落 */
    private static float backOut(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** 明确的确认入口：点锁或无障碍点击解锁，下一次拖动才可增大。 */
    @Override public boolean performClick() {
        super.performClick();
        if (guard.isUnlocked()) guard.lock(); else guard.unlock();
        updateLockDescription();
        if (listener != null) listener.onLockChanged();
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        popLock();
        invalidate();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        guard.lock();
        cancelInteraction();
        if (animator != null) {
            animator.cancel();
        }
        animator = null;
        if (pressAnim != null) pressAnim.cancel();
        if (lockAnim != null) lockAnim.cancel();
        if (waveAnim != null) waveAnim.cancel();
        press = pressTarget = waveEnergy = 0f;
        lockScale = 1f;
        super.onDetachedFromWindow();
    }
}
