package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ScrollView;

/**
 * 屏幕右缘的弧形滚动指示条。
 *
 * <p>它必须是滚动容器之外的独立覆盖层: 如果画在 ScrollView 内部, 画布会被滚动位移带着走,
 * 结果就是"指示条跟着内容一起上下跑、压住卡片文字"。这里固定在根布局里, 只监听滚动位置,
 * 自身坐标永远相对屏幕, 不随内容移动。
 *
 * <p>几何: 以屏幕中心为圆心、半径 屏幕半径-2.5dp 画一段 104° 的右弧。整段弧先铺一条
 * 很细的淡色轨道, 让人一眼看出滚动条的范围和两端终点; 轨道上再画一条 2dp 的细滑块,
 * 滑块起始位置由 scrollY/maxScroll 决定。滚到页面最顶/最底时滑块精确停在弧的端点上,
 * 不会飞出弧外, 有明确的"到底了"的截止感。指针只在滚动时亮起, 停手 850ms 后淡回静止亮度,
 * 不消失, 这样"这里能滚"一直看得出来。
 *
 * <p>颜色: 跟随"页面滚到哪儿"变化 —— 页面在最顶部时是亮青 A 色, 在最底部时是科技蓝 B 色,
 * 在页面中间时滑块内部是一段彩虹渐变(炫彩), 滚动过程中颜色跟着位置流动。
 *
 * <p>动效: 滚动位置不再硬跟随, 而是用一根欠阻尼弹簧去追目标比例, 冲过头会回弹几下
 * (Q 弹手感); 追的过程速度越大, 滑块沿弧越长(挤压拉伸), 滚动方向改变/停手时还有一段
 * 衰减的果冻抖动。弹簧的过冲只体现在拉伸上, 滑块本身的位置不会越过弧的两端。
 */
public class ArcIndicatorView extends View {

    /** 指示弧总跨度(度), 以 3 点钟方向为 0 */
    private static final float ARC_SPAN_DEG = 104f;
    /** 指示弧离屏幕边缘的留白(dp) */
    private static final float ARC_INSET_DP = 2.5f;
    /** 停手后多久开始淡出 */
    private static final long FADE_DELAY_MS = 850L;
    /** 静止时保留的亮度(0..1) */
    private static final float REST_LEVEL = 0.55f;

    /** 细轨道宽度(dp): 只是给滚动条一个范围和两端终点 */
    private static final float RAIL_WIDTH_DP = 1.1f;
    /** 主滑块宽度(dp): 细细的一条 */
    private static final float THUMB_WIDTH_DP = 2.0f;
    /** 滑块底下那层微光宽度(dp) */
    private static final float GLOW_WIDTH_DP = 4.2f;

    /** 页面顶部色(A): 亮青 */
    private static final int COLOR_TOP = 0xFF00F5D4;
    /** 页面底部色(B): 科技蓝 */
    private static final int COLOR_BOTTOM = 0xFF2563EB;

    /**
     * 页面位置 → 颜色 的调色板。0 对应页面顶部, 1 对应页面底部;
     * 中间几段是彩虹色, 于是"页面中部 = 炫彩渐变"。
     * 前后各留一小段同色平台, 保证顶部/底部单色读数干净。
     */
    private static final float[] STOP_POS = {
            0.00f, 0.12f, 0.18f, 0.30f, 0.42f, 0.54f, 0.66f, 0.80f, 0.90f, 1.00f
    };
    private static final int[] STOP_COLOR = {
            0xFF00F5D4, 0xFF00F5D4, 0xFF00E676, 0xFFFFE500, 0xFFFF6D00,
            0xFFFF4081, 0xFFD500F9, 0xFF7C4DFF, 0xFF2563EB, 0xFF2563EB
    };

    // ---- Q 弹弹簧参数 ----
    private static final long FRAME_MS = 16L;
    /** 每帧向目标靠近的刚度系数 */
    private static final float SPRING_K = 0.26f;
    /** 每帧速度衰减(阻尼) */
    private static final float SPRING_DAMPING = 0.58f;
    /** 收敛阈值: 位移/速度都小于它就算停稳 */
    private static final float STILL_EPS = 0.0008f;
    /** 果冻拉伸上限(相对滑块长度) */
    private static final float STRETCH_MAX = 0.45f;
    /** 速度→拉伸的放大倍数 */
    private static final float STRETCH_GAIN = 2.6f;
    /** 停手后果冻抖动的时长(ms) */
    private static final long BOUNCE_MS = 420L;
    /** 停手后果冻抖动的幅度(相对滑块长度) */
    private static final float BOUNCE_AMP = 0.25f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint railPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private final float density;
    private final float arcInsetPx;

    private ScrollView target;
    private float level = REST_LEVEL;
    private long lastActivityAt = SystemClock.uptimeMillis();
    private long bounceAt;

    /** 弹簧当前显示的比例(0..1, 允许轻微越界再弹回) */
    private float shownFraction = -1f;
    private float springVelocity;
    private boolean springRunning;

    private final Runnable springRunnable = new Runnable() {
        @Override
        public void run() {
            if (target == null) {
                springRunning = false;
                return;
            }
            float goal = targetFraction();
            if (shownFraction < 0f) {
                shownFraction = goal;
                springVelocity = 0f;
                springRunning = false;
                invalidate();
                return;
            }
            float delta = goal - shownFraction;
            springVelocity = (springVelocity + delta * SPRING_K) * SPRING_DAMPING;
            shownFraction += springVelocity;
            // 内部允许一点点过冲, 但绘制时会把位置夹回 0..1, 不会飞出弧的两端
            if (shownFraction < -0.05f) {
                shownFraction = -0.05f;
                springVelocity = 0f;
            } else if (shownFraction > 1.05f) {
                shownFraction = 1.05f;
                springVelocity = 0f;
            }
            invalidate();
            if (Math.abs(delta) > STILL_EPS || Math.abs(springVelocity) > STILL_EPS) {
                ui.postDelayed(this, FRAME_MS);
            } else {
                shownFraction = goal;
                springVelocity = 0f;
                springRunning = false;
                invalidate();
            }
        }
    };

    private final Runnable fadeRunnable = new Runnable() {
        @Override
        public void run() {
            long idle = SystemClock.uptimeMillis() - lastActivityAt;
            if (idle < FADE_DELAY_MS) {
                ui.postDelayed(this, Math.max(16L, FADE_DELAY_MS - idle));
                return;
            }
            if (level > REST_LEVEL) {
                level = Math.max(REST_LEVEL, level - 0.08f);
                invalidate();
                ui.postDelayed(this, 32);
            }
        }
    };

    public ArcIndicatorView(Context context) {
        this(context, null);
    }

    public ArcIndicatorView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        arcInsetPx = ARC_INSET_DP * density;

        thumbPaint.setStyle(Paint.Style.STROKE);
        thumbPaint.setStrokeWidth(THUMB_WIDTH_DP * density);
        thumbPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(GLOW_WIDTH_DP * density);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        railPaint.setStyle(Paint.Style.STROKE);
        railPaint.setStrokeWidth(RAIL_WIDTH_DP * density);
        railPaint.setStrokeCap(Paint.Cap.ROUND);
        railPaint.setColor(0xFFFFFFFF);

        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 挂到滚动容器上: 只监听滚动位置, 不参与触摸 */
    public void attachTo(ScrollView scrollView) {
        target = scrollView;
        shownFraction = -1f;
        if (scrollView == null) {
            return;
        }
        scrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (scrollY != oldScrollY) {
                    touchActivity();
                }
            }
        });
    }

    /** 当前应该停在哪一格(0..1) */
    private float targetFraction() {
        int max = getMaxScroll();
        if (max <= 0 || target == null) {
            return 0f;
        }
        float f = target.getScrollY() / (float) max;
        if (f < 0f) {
            return 0f;
        }
        if (f > 1f) {
            return 1f;
        }
        return f;
    }

    private void touchActivity() {
        lastActivityAt = SystemClock.uptimeMillis();
        bounceAt = lastActivityAt;
        if (level < 1f) {
            level = Math.min(1f, level + 0.3f);
        }
        ui.removeCallbacks(fadeRunnable);
        ui.postDelayed(fadeRunnable, FADE_DELAY_MS);
        if (!springRunning) {
            springRunning = true;
            ui.post(springRunnable);
        }
        invalidate();
    }

    private int getMaxScroll() {
        if (target == null) {
            return 0;
        }
        View child = target.getChildAt(0);
        if (child == null) {
            return 0;
        }
        return Math.max(0, child.getHeight() - target.getHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int max = getMaxScroll();
        if (max <= 0) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = Math.min(w, h) / 2f - arcInsetPx;
        arcRect.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius);

        if (shownFraction < 0f) {
            shownFraction = targetFraction();
        }
        // 绘制位置严格夹在 0..1: 滚到顶/底时滑块精确停在弧的端点上, 给人明确的"到底了"的
        // 截止感; 弹簧的过冲只体现在速度/拉伸上, 不把滑块带出弧外。
        float pos = shownFraction;
        if (pos < 0f) {
            pos = 0f;
        } else if (pos > 1f) {
            pos = 1f;
        }
        float posForColor = pos;

        float alpha = level;
        float start = -ARC_SPAN_DEG / 2f;
        float viewportPart = Math.min(1f, h / (float) (h + max));
        float baseSweep = Math.max(22f, ARC_SPAN_DEG * viewportPart);

        // 1) 细轨道: 整段弧都画出来, 一眼能看出滚动范围与两端终点
        railPaint.setAlpha((int) (0x26 * alpha));
        canvas.drawArc(arcRect, start, ARC_SPAN_DEG, false, railPaint);

        // 2) Q 弹: 追得越快, 滑块被拉得越长; 停手后还有一段衰减抖动
        float stretch = 1f + Math.min(STRETCH_MAX, Math.abs(springVelocity) * STRETCH_GAIN);
        long sinceBounce = SystemClock.uptimeMillis() - bounceAt;
        if (sinceBounce >= 0L && sinceBounce < BOUNCE_MS) {
            float t = sinceBounce / (float) BOUNCE_MS;
            float wobble = (float) Math.sin(t * 2.2f * Math.PI) * (1f - t) * (1f - t);
            stretch *= 1f + BOUNCE_AMP * wobble;
        }
        if (stretch < 0.55f) {
            stretch = 0.55f;
        }
        float sweep = Math.min(ARC_SPAN_DEG, baseSweep * stretch);
        float from = start + (ARC_SPAN_DEG - sweep) * pos;

        // 沿弧采样页面位置 → 颜色, 滑块内部就是一段随位置流动的炫彩渐变
        int segments = 20;
        float halfSweepFraction = (sweep / ARC_SPAN_DEG) / 2f;

        // 3) 底下那层微光: 同一套色, 低透明度
        glowPaint.setAlpha((int) (0x1C * alpha));
        for (int i = 0; i < segments; i++) {
            float a0 = from + sweep * i / segments;
            float a1 = from + sweep * (i + 1) / segments + 0.4f;
            int color = colorAt(posForColor - halfSweepFraction + halfSweepFraction * 2f * ((i + 0.5f) / segments), posForColor);
            glowPaint.setColor(color);
            canvas.drawArc(arcRect, a0, a1 - a0, false, glowPaint);
        }

        // 4) 主滑块: 细细的一条
        thumbPaint.setAlpha((int) (0xE6 * alpha));
        for (int i = 0; i < segments; i++) {
            float a0 = from + sweep * i / segments;
            float a1 = from + sweep * (i + 1) / segments + 0.5f;
            int color = colorAt(posForColor - halfSweepFraction + halfSweepFraction * 2f * ((i + 0.5f) / segments), posForColor);
            thumbPaint.setColor(color);
            canvas.drawArc(arcRect, a0, a1 - a0, false, thumbPaint);
        }
        // 两端圆头: 颜色取对应端点的采样色, 让细线的头尾干净收口
        drawCap(canvas, from, colorAt(posForColor - halfSweepFraction, posForColor), alpha);
        drawCap(canvas, from + sweep, colorAt(posForColor + halfSweepFraction, posForColor), alpha);
    }

    /**
     * 页面位置 → 颜色。页面最顶部/最底部各保留一小段纯色平台, 中间是彩虹调色板。
     *
     * @param sample 采样点在页面上的位置(0..1), 对应滑块某一段
     * @param pos    滑块中心在页面上的位置(0..1), 用来决定是否已经贴到顶部/底部
     */
    private int colorAt(float sample, float pos) {
        float p = sample;
        if (p < 0f) {
            p = 0f;
        } else if (p > 1f) {
            p = 1f;
        }
        int color = palette(p);
        if (pos < 0.10f) {
            float weight = (0.10f - pos) / 0.10f;
            color = blend(color, COLOR_TOP, weight);
        } else if (pos > 0.90f) {
            float weight = (pos - 0.90f) / 0.10f;
            color = blend(color, COLOR_BOTTOM, weight);
        }
        return color;
    }

    /** 调色板插值 */
    private int palette(float p) {
        for (int i = 0; i < STOP_POS.length - 1; i++) {
            float a = STOP_POS[i];
            float b = STOP_POS[i + 1];
            if (p <= b) {
                float t = b > a ? (p - a) / (b - a) : 0f;
                return blend(STOP_COLOR[i], STOP_COLOR[i + 1], t);
            }
        }
        return STOP_COLOR[STOP_COLOR.length - 1];
    }

    /** 线性插值两个 ARGB 颜色(逐通道) */
    private int blend(int c1, int c2, float t) {
        if (t <= 0f) {
            return c1;
        }
        if (t >= 1f) {
            return c2;
        }
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
        int r = (int) (((c1 >>> 16) & 0xFF) + (((c2 >>> 16) & 0xFF) - ((c1 >>> 16) & 0xFF)) * t);
        int g = (int) (((c1 >>> 8) & 0xFF) + (((c2 >>> 8) & 0xFF) - ((c1 >>> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 在弧的某个角度画一个圆头, 颜色跟着对应位置的采样色走 */
    private void drawCap(Canvas canvas, float angleDeg, int color, float alpha) {
        float cx = arcRect.centerX();
        float cy = arcRect.centerY();
        float r = arcRect.width() / 2f;
        double rad = Math.toRadians(angleDeg);
        float x = cx + (float) (r * Math.cos(rad));
        float y = cy + (float) (r * Math.sin(rad));
        float capR = thumbPaint.getStrokeWidth() / 2f;
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(color);
        thumbPaint.setAlpha((int) (0xE6 * alpha));
        canvas.drawCircle(x, y, capR, thumbPaint);
        thumbPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        springRunning = false;
        super.onDetachedFromWindow();
    }
}
