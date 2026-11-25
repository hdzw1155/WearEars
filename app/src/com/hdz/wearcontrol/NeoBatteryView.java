package com.hdz.wearcontrol;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * 新系列主界面的电量主视图。画法见 {@link NeoBatteryArt}。
 * 数值变化时做一次缓动; 液位风格在变化/点击后让波面荡几秒再静止, 不常驻动画以免耗电。
 */
public class NeoBatteryView extends View {

    private static final long WAVE_MS = 3600L;
    private static final long POP_MS = 460L;
    private static final long POP_STAGGER_MS = 55L;

    private final NeoBatteryArt art = new NeoBatteryArt();
    private final NeoBatteryArt.Data data = new NeoBatteryArt.Data();
    private final float[] shown = new float[3];
    private final float[] from = new float[3];
    private final float[] to = new float[3];
    private int variant;
    private ValueAnimator animator;
    private ValueAnimator popAnimator;
    private boolean poppedInTouch;
    private float phase = 0.6f;
    private long waveUntil;
    private long lastFrame;
    private boolean framePosted;

    private final Choreographer.FrameCallback waveFrame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            framePosted = false;
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            long left = waveUntil - System.currentTimeMillis();
            // 最后一秒放慢, 自然停下
            float speed = left > 1000 ? 3.2f : 3.2f * Math.max(0f, left / 1000f);
            phase += dt * speed;
            invalidate();
            if (left > 0 && isShown()) {
                framePosted = true;
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                lastFrame = 0;
            }
        }
    };

    public NeoBatteryView(Context context) {
        this(context, null);
    }

    public NeoBatteryView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NeoBatteryView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        variant = AppPrefs.family(AppPrefs.uiStyle(context));
        data.labels[0] = context.getString(R.string.label_left);
        data.labels[1] = context.getString(R.string.label_right);
        data.labels[2] = context.getString(R.string.label_case);
        setClickable(true);
        updateDescription();
    }

    void setVariant(int v) {
        if (variant == v) return;
        variant = v;
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        int desiredH = Math.round(NeoBatteryArt.designHeightDp(variant) * d)
                + getPaddingTop() + getPaddingBottom();
        int desiredW = Math.round(180f * d) + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec));
    }

    /** chargeFlags: bit0 左, bit1 右, bit2 盒 */
    void setBattery(int left, int right, int box, int chargeFlags, boolean single) {
        int[] values = {left, right, box};
        boolean changed = single != data.single;
        data.single = single;
        data.labels[0] = getContext().getString(single ? R.string.label_total : R.string.label_left);
        for (int i = 0; i < 3; i++) {
            boolean chg = (chargeFlags & (1 << i)) != 0;
            if (data.values[i] != values[i] || data.charging[i] != chg) changed = true;
            data.values[i] = values[i];
            data.charging[i] = chg;
        }
        if (!changed) return;
        updateDescription();
        startValueAnimation();
        if (variant == NeoBatteryArt.LIQUID) kickWave();
        if (variant == NeoBatteryArt.CLASSIC) kickPop();
        invalidate();
    }

    private void startValueAnimation() {
        for (int i = 0; i < 3; i++) {
            from[i] = shown[i];
            to[i] = NeoBatteryArt.known(data.values[i]) ? data.values[i] / 100f : 0f;
        }
        if (animator != null) animator.cancel();
        if (!isAttachedToWindow()) {
            System.arraycopy(to, 0, shown, 0, 3);
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        if (variant == NeoBatteryArt.CLASSIC) {
            // 新经典: 环先略微冲过目标再回弹, 和降噪滑块的弹簧手感一致
            animator.setDuration(820L);
            animator.setInterpolator(new OvershootInterpolator(1.15f));
        } else {
            animator.setDuration(720L);
            animator.setInterpolator(new DecelerateInterpolator(1.6f));
        }
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = a.getAnimatedFraction();
                for (int i = 0; i < 3; i++) shown[i] = from[i] + (to[i] - from[i]) * t;
                invalidate();
            }
        });
        animator.start();
    }

    private void kickWave() {
        waveUntil = System.currentTimeMillis() + WAVE_MS;
        if (!framePosted && isAttachedToWindow()) {
            framePosted = true;
            lastFrame = 0;
            Choreographer.getInstance().postFrameCallback(waveFrame);
        }
    }

    /** 新经典: 三枚胶囊环依次轻压再回弹(阻尼余弦), 约半秒结束, 不常驻 */
    private void kickPop() {
        if (popAnimator != null) popAnimator.cancel();
        if (!isAttachedToWindow()) {
            resetPop();
            return;
        }
        popAnimator = ValueAnimator.ofFloat(0f, 1f);
        popAnimator.setDuration(POP_MS + POP_STAGGER_MS * 2);
        popAnimator.setInterpolator(new LinearInterpolator());
        popAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float elapsed = a.getAnimatedFraction() * (POP_MS + POP_STAGGER_MS * 2);
                for (int i = 0; i < 3; i++) {
                    float t = (elapsed - POP_STAGGER_MS * i) / POP_MS;
                    data.scale[i] = popScale(t);
                }
                invalidate();
            }
        });
        popAnimator.start();
    }

    static float popScale(float t) {
        if (t <= 0f) return 1f;
        if (t >= 1f) return 1f;
        // 从 1 平滑压到约 0.93, 再冲过 1 约 2%, 最后落回 1
        return 1f - 0.12f * (float) (Math.exp(-3.0 * t) * Math.sin(2.6 * Math.PI * t))
                * (1f - t * t * t);
    }

    private void resetPop() {
        for (int i = 0; i < 3; i++) data.scale[i] = 1f;
    }

    private void stopWave() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(waveFrame);
            framePosted = false;
        }
        lastFrame = 0;
    }

    /** 外部刷新键按下时调用, 给出与点按电量区相同的视觉反馈 */
    void bounce() {
        if (variant == NeoBatteryArt.LIQUID) kickWave();
        if (variant == NeoBatteryArt.CLASSIC) kickPop();
    }

    private void animatePress(final float target) {
        if (popAnimator != null) popAnimator.cancel();
        if (!isAttachedToWindow()) {
            for (int i = 0; i < 3; i++) data.scale[i] = target;
            return;
        }
        final float start = data.scale[0];
        popAnimator = ValueAnimator.ofFloat(0f, 1f);
        popAnimator.setDuration(target < 1f ? 110L : 260L);
        popAnimator.setInterpolator(target < 1f
                ? new DecelerateInterpolator(1.5f)
                : new OvershootInterpolator(2.0f));
        popAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float s = start + (target - start) * a.getAnimatedFraction();
                for (int i = 0; i < 3; i++) data.scale[i] = s;
                invalidate();
            }
        });
        popAnimator.start();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (variant == NeoBatteryArt.CLASSIC) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                poppedInTouch = false;
                animatePress(0.95f);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                animatePress(1f);
            } else if (action == MotionEvent.ACTION_UP) {
                poppedInTouch = false;
                boolean handled = super.onTouchEvent(event);
                if (!poppedInTouch) animatePress(1f);
                return handled;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        poppedInTouch = true;
        if (variant == NeoBatteryArt.LIQUID) kickWave();
        if (variant == NeoBatteryArt.CLASSIC) kickPop();
        return super.performClick();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (variant == NeoBatteryArt.LIQUID) kickWave();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopWave();
        if (animator != null) animator.cancel();
        if (popAnimator != null) popAnimator.cancel();
        resetPop();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopWave();
    }

    @Override protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        art.draw(canvas, getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom(),
                variant, data, shown, d, phase);
    }

    private void updateDescription() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.count(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(data.labels[i]).append(' ');
            sb.append(NeoBatteryArt.known(data.values[i]) ? data.values[i] + "%" : "--");
        }
        sb.append(", ").append(getContext().getString(R.string.btn_refresh));
        setContentDescription(sb.toString());
    }
}
