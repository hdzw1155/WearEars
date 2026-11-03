package com.hdz.wearcontrol;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * 滚动容器: 把旋转表冠 / 触摸表圈 / 国产表冠按键统一变成平滑滚动 + 三星风格分级震感,
 * 并且在页面顶/底超限继续转时给页面一层"橡皮筋拉伸"的 Q 弹回馈。
 *
 * <p>输入来源:
 * <ul>
 *   <li>Wear OS / 三星虚拟表圈: {@link MotionEvent#ACTION_SCROLL} 的 AXIS_SCROLL(部分魔改表用 AXIS_VSCROLL);</li>
 *   <li>国产安卓手表实体表冠: KEYCODE_NAVIGATE_PREVIOUS(260) / KEYCODE_NAVIGATE_NEXT(261),
 *       以及部分机型映射成 PAGE_UP/PAGE_DOWN 的滚轮键。</li>
 * </ul>
 *
 * <p>震感分级(对齐三星原厂旋转表圈的手感):
 * <ul>
 *   <li>旋转过程中: 每滚过一小段就打一下很短很轻的 tick, 形成绵密颗粒感, 转得越快 tick 越密;</li>
 *   <li>旋转停下: 一段旋转结束后补一下稍重的"落定"反馈, 和过程中的 tick 明显不同;</li>
 *   <li>已到顶/触底还在往同方向转: 页面被继续往外拉一点(橡皮筋拉伸), 同时打一下更重的
 *       双击"挡墙"反馈, 并用锁限制只打一次, 反向离开边界后才重新上锁。
 *       顶部和底部两个方向都生效。</li>
 * </ul>
 *
 * <p>tick 用 Vibrator 直接播放短脉冲(可控时长/强度), 而不是走 View.performHapticFeedback:
 * 后者在三星表上会把 ROTARY_SCROLL_TICK 映射成 110ms 的整段马达, 密起来会糊成一片、分不出颗粒。
 * 如果拿不到 Vibrator 或系统关掉了触感反馈, 再退回 performHapticFeedback 的三星常量 18/20。
 */
public class AppScrollView extends ScrollView {

    /** 旋转输入里 1 个原始单位(一格)对应的滚动距离(dp) */
    private static final float DP_PER_UNIT = 22f;

    /** 三星 ROTARY_SCROLL_TICK / ROTARY_SCROLL_LIMIT 的数值(API34 android.jar 无此常量, 只能裸传) */
    private static final int HAPTIC_ROTARY_TICK = 18;
    private static final int HAPTIC_ROTARY_LIMIT = 20;

    /** 每滚过多少 dp 补一下过程 tick */
    private static final float TICK_DIST_DP = 12f;
    /** 单次旋转事件最多补几下, 防止高速时马达排队 */
    private static final int MAX_TICKS_PER_EVENT = 2;

    /** 旋转停下多久算"一段转完" */
    private static final long SETTLE_DELAY_MS = 220L;
    /** 一段旋转至少滚过这么多格才补"落定"震感, 单点一下不补 */
    private static final float SETTLE_MIN_UNITS = 1.2f;

    // ---- 超限拉伸(橡皮筋)参数 ----
    /** 超限时页面最多被拉出多少 dp */
    private static final float MAX_OVERSCROLL_DP = 26f;
    /** 每次超限旋转把多少位移转成拉伸量 */
    private static final float OVERSCROLL_DAMP = 0.55f;
    /** 松手后回弹速度 */
    private static final float OVERSCROLL_SPRING = 0.24f;
    /** 拉伸形变幅度(页面纵向比例) */
    private static final float STRETCH_RATIO = 0.14f;

    // ---- 惯性滑行(旋转/滚轮/按键) ----
    /** 一次滚动给一个初速度, 停手后会继续滑一小段 */
    private static final float INERTIA_GAIN = 0.35f;
    /** 每帧速度衰减系数 */
    private static final float INERTIA_DECAY = 0.90f;
    /** 低于这个速度(px/帧)直接停 */
    private static final float INERTIA_MIN = 0.8f;
    /** 输入停手多久后开始惯性滑行 */
    private static final long INPUT_HOLD_MS = 55L;

    // ---- 短脉冲参数(直接用 Vibrator 播) ----
    /** 过程 tick: 短、轻 */
    private static final int TICK_MS = 8;
    private static final int TICK_AMP = 150;
    /** 落定: 比 tick 稍长稍重 */
    private static final int SETTLE_MS = 16;
    private static final int SETTLE_AMP = 205;
    /** 边界挡墙: 两下重的连着来, 和过程 tick 明显不一样 */
    private static final long[] LIMIT_TIMINGS = {0L, 14L, 30L, 26L};
    private static final int[] LIMIT_AMPS = {0, 255, 0, 225};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float unitPx;
    private final float tickDistPx;
    private final float maxOverscrollPx;

    private Vibrator vibrator;

    /** 目标滚动位置(px); 帧循环把它平滑逼近当前 scrollY */
    private float targetPx;
    private boolean chasing;

    /** 超限拉伸: 正值=顶部被往下拉, 负值=底部被往上拉 */
    private float overscrollPx;
    private float overscrollTarget;
    /** 最近一次超限输入的时间(旋转或手指拖动共用), 用来判断松手回弹 */
    private long lastOverscrollAt;

    /** 过程 tick 的累计滚动距离(px) */
    private float hapticAccum;
    /** 当前这段旋转累计滚过的距离(px), 给落定震感用 */
    private float burstDistPx;
    private boolean edgeLatched;
    private long lastRotaryAt;
    private boolean rotaryMoved;

    /** true = 当前 scrollY 由旋转输入驱动; 手指拖动时不接管 scrollY */
    private boolean rotaryDriving;

    // ---- 手指拖动超限(橡皮筋) ----
    private float touchLastY;
    private boolean touchTracking;

    /** 惯性滑行速度(px/帧) 与最近一次旋转输入时间 */
    private float velocityPx;
    private long lastInputAt;

    private final Runnable chaseFrame = new Runnable() {
        @Override
        public void run() {
            int y = getScrollY();
            boolean rotaryActive = rotaryDriving && !touchTracking;
            if (rotaryActive) {
                long now = SystemClock.uptimeMillis();
                int max = getMaxScroll();
                // 停手后按速度继续滑行(惯性); 端点处直接停住, 不把多余位移攒成"蓄力"
                if (now - lastInputAt > INPUT_HOLD_MS && Math.abs(velocityPx) > INERTIA_MIN) {
                    float next = targetPx + velocityPx;
                    if (next <= 0f) {
                        next = 0f;
                        velocityPx = 0f;
                    } else if (next >= max) {
                        next = max;
                        velocityPx = 0f;
                    } else {
                        velocityPx *= INERTIA_DECAY;
                    }
                    targetPx = next;
                } else if (Math.abs(velocityPx) <= INERTIA_MIN) {
                    velocityPx = 0f;
                }
                float diff = targetPx - y;
                if (Math.abs(diff) > 0.6f) {
                    float step = diff * 0.42f;
                    if (Math.abs(step) < 1f) {
                        step = Math.signum(diff);
                    }
                    scrollTo(0, Math.round(y + step));
                } else if (Math.abs(velocityPx) <= INERTIA_MIN) {
                    scrollTo(0, Math.round(targetPx));
                }
            }
            updateOverscroll();
            boolean stillMoving = Math.abs(overscrollPx) >= 0.4f
                    || overscrollTarget != 0f
                    || (rotaryActive && (Math.abs(velocityPx) > INERTIA_MIN
                    || Math.abs(targetPx - getScrollY()) > 0.6f));
            if (!stillMoving) {
                chasing = false;
                rotaryDriving = false;
                applyOverscroll();
                return;
            }
            ui.postDelayed(this, 16);
        }
    };

    private final Runnable settleRunnable = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() - lastRotaryAt < SETTLE_DELAY_MS) {
                ui.postDelayed(this, 60L);
                return;
            }
            if (!rotaryMoved) {
                return;
            }
            rotaryMoved = false;
            if (!edgeLatched && burstDistPx >= SETTLE_MIN_UNITS * unitPx) {
                hapticSettle();
            }
            burstDistPx = 0f;
        }
    };

    public AppScrollView(Context context) {
        this(context, null);
    }

    public AppScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        unitPx = DP_PER_UNIT * density;
        tickDistPx = TICK_DIST_DP * density;
        maxOverscrollPx = MAX_OVERSCROLL_DP * density;
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        initVibrator();
        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 返回 false: 只旁听拖动用于边缘震感/橡皮筋, 滚动仍交给 ScrollView 自己
                handleTouch(event);
                return false;
            }
        });
    }

    private void initVibrator() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    vibrator = manager.getDefaultVibrator();
                }
            } else {
                vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (RuntimeException e) {
            vibrator = null;
        }
    }

    // ---------------- 旋转表冠 / 触摸表圈 ----------------

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL || action == MotionEvent.ACTION_HOVER_MOVE) {
            float delta = e.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta == 0f) {
                delta = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            }
            if (delta == 0f) {
                delta = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            }
            if (delta != 0f) {
                onRotary(-delta);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    /**
     * 供 Activity 转发: 旋转事件如果没被焦点视图消费, 会冒泡到 Activity.onGenericMotionEvent,
     * 那边统一交给这里处理。
     *
     * @param delta AXIS_SCROLL 原始值
     * @return 是否消费
     */
    boolean handleRotary(float delta) {
        if (delta == 0f) {
            return false;
        }
        onRotary(-delta);
        return true;
    }

    /**
     * 国产安卓手表实体表冠/旋钮按键: 系统把转动映射成这些按键而不是滚动轴.
     *
     * @return true 表示已消费
     */
    boolean handleRotaryKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            onRotary(-1f);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_NEXT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            onRotary(1f);
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleRotaryKey(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        float base = (chasing && rotaryDriving) ? targetPx : getScrollY();
        float rawTarget = base + capped * unitPx;

        boolean hitEdge = false;
        float target = rawTarget;
        // 用 <= / >=: 正好滚到端点的那一下也要触发挡墙震感, 否则最后一下"没感觉"
        if (rawTarget <= 0f) {
            // 顶部超限: 页面被继续往下拉(橡皮筋)
            target = 0f;
            hitEdge = true;
            overscrollTarget += (0f - rawTarget) * OVERSCROLL_DAMP;
        } else if (rawTarget >= max) {
            // 底部超限: 页面被继续往上拉
            target = max;
            hitEdge = true;
            overscrollTarget -= (rawTarget - max) * OVERSCROLL_DAMP;
        } else {
            overscrollTarget = 0f;
        }
        if (overscrollTarget > maxOverscrollPx) {
            overscrollTarget = maxOverscrollPx;
        } else if (overscrollTarget < -maxOverscrollPx) {
            overscrollTarget = -maxOverscrollPx;
        }

        lastOverscrollAt = SystemClock.uptimeMillis();
        lastInputAt = lastOverscrollAt;
        rotaryDriving = true;
        targetPx = target;
        if (hitEdge) {
            velocityPx = 0f; // 端点不积蓄惯性速度, 避免反向时"蓄力弹回"
        } else {
            // 给惯性一个初速度: 停手后会继续滑一小段再减速停下
            velocityPx = capped * unitPx * INERTIA_GAIN;
        }
        if (!chasing) {
            chasing = true;
            ui.post(chaseFrame);
        }

        lastRotaryAt = SystemClock.uptimeMillis();
        rotaryMoved = true;
        ui.removeCallbacks(settleRunnable);
        ui.postDelayed(settleRunnable, SETTLE_DELAY_MS);

        if (hitEdge) {
            // 边界: 只打一下挡墙, 锁住, 反向离开边界才重新上锁
            if (!edgeLatched) {
                edgeLatched = true;
                hapticLimit();
            }
            burstDistPx = 0f;
            return;
        }

        edgeLatched = false;
        float movedPx = Math.abs(capped) * unitPx;
        burstDistPx += movedPx;

        // 过程震感: 每滚过 TICK_DIST_DP 补一下短脉冲 → 绵密颗粒感
        hapticAccum += movedPx;
        int ticks = 0;
        while (hapticAccum >= tickDistPx && ticks < MAX_TICKS_PER_EVENT) {
            hapticAccum -= tickDistPx;
            ticks++;
        }
        if (hapticAccum > tickDistPx * 3f) {
            hapticAccum = 0f; // 高速时丢掉积压, 不要在马达里排队
        }
        for (int i = 0; i < ticks; i++) {
            hapticTick();
        }
    }

    /**
     * 每帧推进超限拉伸: 松手 140ms 后目标归零, 弹簧把页面拉回原位。
     */
    private void updateOverscroll() {
        // 松手(旋转停手或手指抬起) 140ms 后目标归零, 弹簧把页面拉回原位
        if (SystemClock.uptimeMillis() - lastOverscrollAt > 140L) {
            overscrollTarget = 0f;
        }
        overscrollPx += (overscrollTarget - overscrollPx) * OVERSCROLL_SPRING;
        if (overscrollTarget == 0f && Math.abs(overscrollPx) < 0.4f) {
            overscrollPx = 0f;
        }
        applyOverscroll();
    }

    /**
     * 把超限拉伸应用到页面内容: 纵向轻微拉伸 + 位移(橡皮筋), 只改绘制不改布局。
     */
    private void applyOverscroll() {
        View child = getChildAt(0);
        if (child == null) {
            return;
        }
        if (overscrollPx == 0f) {
            child.setTranslationY(0f);
            child.setScaleY(1f);
            return;
        }
        child.setTranslationY(overscrollPx);
        float stretch = Math.abs(overscrollPx) / maxOverscrollPx * STRETCH_RATIO;
        if (stretch > STRETCH_RATIO) {
            stretch = STRETCH_RATIO;
        }
        child.setPivotY(getScrollY() + getHeight() / 2f);
        child.setScaleY(1f + stretch);
    }

    /**
     * 手指拖动: ScrollView 自己的触摸滚动不会走 onRotary, 所以"到顶/到底"的挡墙震感和
     * 橡皮筋拉伸要在这里单独接一份。只旁听, 返回 false 让 ScrollView 继续滚。
     */
    private void handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchTracking = true;
            touchLastY = e.getY();
            // 手指一碰就接管: 立刻停掉旋转残留的滑动和惯性, 避免"刚滑上去又被拉回最底下"
            rotaryDriving = false;
            velocityPx = 0f;
            targetPx = getScrollY();
            overscrollTarget = 0f;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE && touchTracking) {
            float dy = e.getY() - touchLastY; // 手指往下拨为正
            touchLastY = e.getY();
            int max = getMaxScroll();
            if (max <= 0) {
                return;
            }
            int sy = getScrollY();
            int predicted = sy - Math.round(dy);
            boolean atTop = sy <= 0;
            boolean atBottom = sy >= max;
            // 只要"这一下拨完会到端点"就立刻给挡墙强震: 第一次滑到底/顶就有, 不用第二次
            boolean hitTop = dy > 0f && predicted <= 0;
            boolean hitBottom = dy < 0f && predicted >= max;
            if (hitTop || hitBottom) {
                if (!edgeLatched) {
                    edgeLatched = true;
                    hapticLimit();
                }
                // 已经在端点还继续拉: 累积橡皮筋(页面被拉出一点)
                if (atTop && dy > 0f) {
                    overscrollTarget += dy * OVERSCROLL_DAMP;
                } else if (atBottom && dy < 0f) {
                    overscrollTarget += dy * OVERSCROLL_DAMP;
                }
                if (overscrollTarget > maxOverscrollPx) {
                    overscrollTarget = maxOverscrollPx;
                } else if (overscrollTarget < -maxOverscrollPx) {
                    overscrollTarget = -maxOverscrollPx;
                }
            } else {
                // 已经在往页面里面滚: 立刻解锁并清掉拉伸, 避免残留位移看着像"跳回底部"
                edgeLatched = false;
                overscrollTarget = 0f;
                overscrollPx = 0f;
                applyOverscroll();
            }
            lastOverscrollAt = SystemClock.uptimeMillis();
            startOverscrollLoop();
            return;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchTracking = false;
            overscrollTarget = 0f;
            edgeLatched = false;
            startOverscrollLoop();
        }
    }

    private void startOverscrollLoop() {
        if (!chasing) {
            chasing = true;
            ui.post(chaseFrame);
        }
    }

    private boolean hapticsAllowed() {
        try {
            return Settings.System.getInt(getContext().getContentResolver(),
                    "haptic_feedback_enabled", 1) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private boolean play(VibrationEffect effect) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        if (!hapticsAllowed()) {
            return true;
        }
        try {
            // 不 cancel 前一个脉冲: 8ms 短 tick 连续播时如果每次先 cancel 再 vibrate,
            // 前一下会被系统记为 CANCELLED_SUPERSEDED, 高频转动时几乎打不出完整颗粒。
            vibrator.vibrate(effect);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 旋转过程: 短促轻 tick */
    private void hapticTick() {
        if (play(VibrationEffect.createOneShot(TICK_MS, TICK_AMP))) {
            return;
        }
        boolean ok = false;
        try {
            ok = performHapticFeedback(HAPTIC_ROTARY_TICK);
        } catch (RuntimeException ignored) {
        }
        if (!ok) {
            try {
                performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 一段旋转结束: 稍重一点的落定感 */
    private void hapticSettle() {
        if (play(VibrationEffect.createOneShot(SETTLE_MS, SETTLE_AMP))) {
            return;
        }
        try {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } catch (RuntimeException ignored) {
        }
    }

    /** 到顶/触底: 两下重的挡墙顿挫 */
    private void hapticLimit() {
        if (play(VibrationEffect.createWaveform(LIMIT_TIMINGS, LIMIT_AMPS, -1))) {
            return;
        }
        boolean ok = false;
        try {
            ok = performHapticFeedback(HAPTIC_ROTARY_LIMIT);
        } catch (RuntimeException ignored) {
        }
        if (!ok) {
            try {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } catch (RuntimeException ignored) {
            }
        }
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
        rotaryDriving = false;
        touchTracking = false;
        velocityPx = 0f;
        lastInputAt = 0L;
        View child = getChildAt(0);
        if (child != null) {
            child.setTranslationY(0f);
            child.setScaleY(1f);
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        super.onDetachedFromWindow();
    }
}
