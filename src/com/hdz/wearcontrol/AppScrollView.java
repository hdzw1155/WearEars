package com.hdz.wearcontrol;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.AttributeSet;
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
 *   <li>到顶/触底: 页面被继续往外拉一点(橡皮筋拉伸), 同时打一下更重的
 *       "挡墙"反馈, 并用锁限制只打一次, 反向离开边界后才重新上锁。
 *       顶部和底部两个方向都生效。</li>
 * </ul>
 *
 * <p>仅表圈产生触感：优先低频 LOW_TICK，回退为低振幅短包络；边界才使用强冲击。
 * 手指拖动、甩动和停手都不补震，强震期间不插入微震，遵循系统触感开关。
 */
public class AppScrollView extends ScrollView {

    /** 旋转输入里 1 个原始单位(一格)对应的滚动距离(dp) */
    private static final float DP_PER_UNIT = 22f;

    /** 每滚过多少 dp 补一下过程 tick */
    private static final float TICK_DIST_DP = 8f;

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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float unitPx;
    private final ScrollFeedback feedback;
    private final float maxOverscrollPx;

    private Vibrator vibrator;
    private VibrationEffect softTick, wallImpact;
    private final AudioAttributes hapticAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private long settingsCheckedAt = -1000L;
    private boolean hapticsEnabled = true;
    private Runnable edgeEffect;

    /** 目标滚动位置(px); 帧循环把它平滑逼近当前 scrollY */
    private float targetPx;
    private boolean chasing;

    /** 超限拉伸: 正值=顶部被往下拉, 负值=底部被往上拉 */
    private float overscrollPx;
    private float overscrollTarget;
    /** 最近一次超限输入的时间(旋转或手指拖动共用), 用来判断松手回弹 */
    private long lastOverscrollAt;

    private boolean active = true;

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

    public AppScrollView(Context context) {
        this(context, null);
    }

    public AppScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        unitPx = DP_PER_UNIT * density;
        feedback = new ScrollFeedback(TICK_DIST_DP * density);
        maxOverscrollPx = MAX_OVERSCROLL_DP * density;
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        initVibrator();
        initEffects();
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

    private void initEffects() {
        if (vibrator == null) return;
        boolean amplitude = false;
        try { amplitude = vibrator.hasAmplitudeControl(); } catch (RuntimeException ignored) { }
        softTick = amplitude
                ? VibrationEffect.createWaveform(new long[]{0L, 2L, 4L, 3L}, new int[]{0, 30, 68, 24}, -1)
                : VibrationEffect.createOneShot(5L, VibrationEffect.DEFAULT_AMPLITUDE);
        wallImpact = amplitude
                ? VibrationEffect.createWaveform(new long[]{0L, 4L, 12L, 5L}, new int[]{0, 140, 245, 90}, -1)
                : VibrationEffect.createOneShot(21L, VibrationEffect.DEFAULT_AMPLITUDE);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                int low = VibrationEffect.Composition.PRIMITIVE_LOW_TICK;
                int thud = VibrationEffect.Composition.PRIMITIVE_THUD;
                boolean[] supported = vibrator.arePrimitivesSupported(low, thud);
                int[] duration = vibrator.getPrimitiveDurations(low, thud);
                // 只采用能在下一个颗粒前完成的短效果，避免长马达被密集事件反复截断。
                if (supported[0] && duration[0] > 0 && duration[0] <= 20)
                    softTick = VibrationEffect.startComposition().addPrimitive(low, 0.30f).compose();
                if (supported[1] && duration[1] > 0 && duration[1] <= 70)
                    wallImpact = VibrationEffect.startComposition().addPrimitive(thud, 0.90f).compose();
            } catch (RuntimeException ignored) { }
        }
    }

    void setEdgeEffect(Runnable effect) { edgeEffect = effect; }

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
        if (!active || Math.abs(units) < 0.001f) {
            return;
        }
        int max = getMaxScroll();
        if (max <= 0) {
            return; // 内容不够一屏, 不响应, 免得看着像 bug
        }

        if (!rotaryDriving) feedback.beginGesture();

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

        if (hitEdge) {
            // 已在端点时立刻反馈；尚未到达则由 onScrollChanged 在实际抵达时反馈。
            if (getScrollY() <= 0 && rawTarget <= 0f)
                playFeedback(feedback.limit(-1, lastInputAt));
            else if (getScrollY() >= max && rawTarget >= max)
                playFeedback(feedback.limit(1, lastInputAt));
            return;
        }
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (feedback == null || !active || !hasWindowFocus()) return;
        if (rotaryDriving && !touchTracking) {
            playFeedback(feedback.scrolled(y, oldY, getMaxScroll(), SystemClock.uptimeMillis()));
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
        if (!active) return;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchTracking = true;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            // 从可点击的设置项开始拖动时，ScrollView 可能到 MOVE 才接管触摸。
            touchTracking = true;
        }
        if (action == MotionEvent.ACTION_MOVE && touchTracking) {
            float dy = e.getY() - touchLastY; // 手指往下拨为正
            touchLastY = e.getY();
            int max = getMaxScroll();
            if (max <= 0) {
                return;
            }
            int sy = getScrollY();
            boolean atTop = sy <= 0;
            boolean atBottom = sy >= max;
            boolean hitTop = dy > 0f && atTop;
            boolean hitBottom = dy < 0f && atBottom;
            if (hitTop || hitBottom) {
                // 已经在端点还继续拉: 累积橡皮筋(页面被拉出一点)
                overscrollTarget += dy * OVERSCROLL_DAMP;
                overscrollTarget = Math.max(-maxOverscrollPx, Math.min(maxOverscrollPx, overscrollTarget));
            } else {
                overscrollTarget = 0f;
                overscrollPx = 0f;
                applyOverscroll();
            }
            lastOverscrollAt = SystemClock.uptimeMillis();
            if (overscrollTarget != 0f || overscrollPx != 0f) startOverscrollLoop();
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (active && action == MotionEvent.ACTION_DOWN) {
            touchTracking = false;
            feedback.beginGesture();
            touchLastY = event.getY();
            // 手指一碰就接管: 立刻停掉旋转残留的滑动和惯性, 避免"刚滑上去又被拉回最底下"
            rotaryDriving = false;
            velocityPx = 0f;
            targetPx = getScrollY();
            overscrollTarget = 0f;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchTracking = false;
            overscrollTarget = 0f;
            if (active && overscrollPx != 0f) startOverscrollLoop();
        }
        return handled;
    }

    private void startOverscrollLoop() {
        if (!chasing) {
            chasing = true;
            ui.post(chaseFrame);
        }
    }

    private boolean hapticsAllowed() {
        if (!active || !isHapticFeedbackEnabled() || !isShown() || !hasWindowFocus()) return false;
        long now = SystemClock.uptimeMillis();
        if (now - settingsCheckedAt >= 200L) {
            settingsCheckedAt = now;
            try {
                hapticsEnabled = Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
            } catch (RuntimeException ignored) { }
        }
        return hapticsEnabled;
    }

    private void play(VibrationEffect effect) {
        if (vibrator == null || effect == null) {
            return;
        }
        if (!hapticsAllowed()) {
            return;
        }
        try {
            vibrator.vibrate(effect, hapticAttributes);
        } catch (RuntimeException e) {
        }
    }

    private void playFeedback(int effect) {
        if (effect == ScrollFeedback.LIMIT) {
            if (edgeEffect != null) edgeEffect.run();
            play(wallImpact);
        } else if (effect == ScrollFeedback.TICK) {
            play(softTick);
        }
    }

    private int getMaxScroll() {
        View child = getChildAt(0);
        if (child == null) {
            return 0;
        }
        android.view.ViewGroup.LayoutParams params = child.getLayoutParams();
        int height = child.getHeight();
        if (params instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams) params;
            height += margins.topMargin + margins.bottomMargin;
        }
        return Math.max(0, height - (getHeight() - getPaddingTop() - getPaddingBottom()));
    }

    void setActive(boolean enabled) {
        active = enabled;
        if (!enabled) stopMotion();
    }

    private void stopMotion() {
        ui.removeCallbacksAndMessages(null);
        chasing = false;
        rotaryDriving = false;
        touchTracking = false;
        feedback.reset();
        settingsCheckedAt = -1000L;
        overscrollPx = overscrollTarget = 0f;
        targetPx = getScrollY();
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
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopMotion();
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }
}
