package com.hdz.wearcontrol;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 连接状态点: 一个小圆, 颜色代表状态, 可呼吸(连接中)或一次性 pop(刚连上).
 *
 * <p>状态不只靠颜色区分: 连接中=呼吸, 已连接=实心, 未连接=空心, 阻断/失败=常亮实心.
 */
public class StatusDotView extends View {

    private static final float SIZE_DP = 8f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int dotColor = 0xFF8E8E93;
    private boolean hollow;
    private ValueAnimator breath;
    private ObjectAnimator pop;

    public StatusDotView(Context context) {
        this(context, null);
    }

    public StatusDotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 设颜色; 同样颜色重复调用会被忽略(轮询每 30 秒都会写一次, 不能闪) */
    public void setDotState(int color, boolean hollowNow) {
        if (color == dotColor && hollowNow == hollow) {
            return;
        }
        dotColor = color;
        hollow = hollowNow;
        paint.setColor(color);
        invalidate();
    }

    public void setBreathing(boolean on) {
        if (on) {
            if (breath != null) {
                return;
            }
            breath = ObjectAnimator.ofFloat(this, "alpha", 0.30f, 1.00f);
            breath.setDuration(1200);
            breath.setRepeatMode(ValueAnimator.REVERSE);
            breath.setRepeatCount(ValueAnimator.INFINITE);
            breath.setInterpolator(new LinearInterpolator());
            breath.start();
        } else if (breath != null) {
            breath.cancel();
            breath = null;
            setAlpha(1f);
        }
    }

    /** 一次 1.0 -> 1.28 -> 1.0 的确认 pop(300ms 内结束, 只给"刚连上"用) */
    public void pop() {
        if (pop != null) {
            pop.cancel();
        }
        pop = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.28f, 1f);
        pop.setDuration(220);
        pop.setInterpolator(new android.view.animation.OvershootInterpolator(1.5f));
        ObjectAnimator y = ObjectAnimator.ofFloat(this, "scaleY", 1f, 1.28f, 1f);
        y.setDuration(220);
        y.setInterpolator(new android.view.animation.OvershootInterpolator(1.5f));
        pop.start();
        y.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int side = Math.round(SIZE_DP * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        paint.setColor(dotColor);
        if (hollow) {
            float stroke = 1.6f * getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            canvas.drawCircle(cx, cy, r - stroke / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
        } else {
            canvas.drawCircle(cx, cy, r, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        setBreathing(false);
        if (pop != null) {
            pop.cancel();
            pop = null;
        }
        super.onDetachedFromWindow();
    }
}
