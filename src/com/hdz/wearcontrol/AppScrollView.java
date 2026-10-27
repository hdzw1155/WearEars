package com.hdz.wearcontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * 滚动容器: 只负责"把旋转输入变成平滑滚动"这一件事。
 *
 * <p>旋转表冠 / 触摸表圈是系统层的东西(手指沿屏幕边缘转), 系统会把转动的格数作为
 * {@link MotionEvent#ACTION_SCROLL} 投递进来; 不同设备一格对应的原始单位不同, 这里用
 * {@link #DP_PER_UNIT} 归一化, 转满一格 = 22dp 位移。
 *
 * <p>普通手指拖动沿用 ScrollView 原生逻辑, 不做任何抢占 —— 外圈滑动是系统手势(回表盘/返回),
 * 应用层强行接管会和系统打架, 这也是 0.3.8 中期试过触摸表圈后放弃的原因。
 *
 * <p>滚动指示条不在这里画: 它必须固定在屏幕右缘、不随内容滚动, 由根布局里的
 * {@link ArcIndicatorView} 单独负责。
 */
public class AppScrollView extends ScrollView {

    /** 旋转输入里 1 个原始单位(一格)对应的滚动距离(dp) */
    private static final float DP_PER_UNIT = 22f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float unitPx;

    /** 目标滚动位置(px); 帧循环把它平滑逼近当前 scrollY */
    private float targetPx;
    private boolean chasing;

    /** 旋转输入累积的原始单位(小数部分留给下一次) */
    private float unitAccum;
    private float lastUnitCount;

    private boolean edgeLatched;

    private final Runnable chaseFrame = new Runnable() {
        @Override
        public void run() {
            int y = getScrollY();
            float diff = targetPx - y;
            if (Math.abs(diff) <= 0.6f) {
                scrollTo(0, Math.round(targetPx));
                chasing = false;
                return;
            }
            float step = diff * 0.42f;
            if (Math.abs(step) < 1f) {
                step = Math.signum(diff);
            }
            scrollTo(0, Math.round(y + step));
            ui.postDelayed(this, 16);
        }
    };

    public AppScrollView(Context context) {
        this(context, null);
    }

    public AppScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        unitPx = DP_PER_UNIT * getResources().getDisplayMetrics().density;
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
    }

    // ---------------- 旋转表冠 / 触摸表圈 ----------------

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float delta = e.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta != 0f) {
                onRotary(-delta);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    /**
     * 供 Activity 转发: 旋转事件如果没被焦点视图消费, 会冒泡到 Activity.onGenericMotionEvent,
     * 那边统一交给这里处理(0.3.7 版本就是靠 Activity 接住的)。
     *
     * @param delta AXIS_SCROLL 原始值, 正=向下滚
     * @return 是否消费
     */
    boolean handleRotary(float delta) {
        if (delta == 0f) {
            return false;
        }
        onRotary(-delta);
        return true;
    }

    /** @param units 为正=内容向上滚(看下面), 负=内容向下滚 */
    private void onRotary(float units) {
        if (Math.abs(units) < 0.001f) {
            return;
        }
        int max = getMaxScroll();
        if (max <= 0) {
            return; // 内容不够一屏, 不响应, 免得看着像 bug
        }

        // 单次事件最多认 4 格, 高速转动时事件会合并成大 delta
        float capped = Math.max(-4f, Math.min(4f, units));
        float base = chasing ? targetPx : getScrollY();
        unitAccum += capped;
        float target = base + capped * unitPx;

        boolean hitEdge = false;
        if (target < 0f) {
            target = 0f;
            hitEdge = true;
        } else if (target > max) {
            target = max;
            hitEdge = true;
        }
        if (hitEdge && !edgeLatched) {
            edgeLatched = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else if (!hitEdge) {
            edgeLatched = false;
        }

        targetPx = target;
        if (!chasing) {
            chasing = true;
            ui.post(chaseFrame);
        }
        if (Math.floor(Math.abs(unitAccum)) > Math.floor(lastUnitCount)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        lastUnitCount = Math.abs(unitAccum);
    }

    private int getMaxScroll() {
        View child = getChildAt(0);
        if (child == null) {
            return 0;
        }
        return Math.max(0, child.getHeight() - getHeight());
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        chasing = false;
        super.onDetachedFromWindow();
    }
}
