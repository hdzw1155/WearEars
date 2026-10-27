package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;

/**
 * 屏幕右缘的弧形滚动指示条。
 *
 * <p>它必须是滚动容器之外的独立覆盖层: 如果画在 ScrollView 内部, 画布会被滚动位移带着走,
 * 结果就是"指示条跟着内容一起上下跑、压住卡片文字"。这里固定在根布局里, 只监听滚动位置,
 * 自身坐标永远相对屏幕, 不随内容移动。
 *
 * <p>几何: 以屏幕中心为圆心、半径 屏幕半径-2.5dp 画一段 104° 的右弧; 弧上滑块的起始角
 * 由 scrollY/maxScroll 决定。指针只在滚动时亮起, 停手 850ms 后淡回静止亮度(0.30), 不消失,
 * 这样"这里能滚"一直看得出来。
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

    private static final int COLOR_THUMB_TOP = 0xFF2B6CB0;   // 主色蓝
    private static final int COLOR_THUMB_BOTTOM = 0xFF4A8FD4; // 同主色提亮, 不再混绿

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private final float density;
    private final float arcInsetPx;

    private ScrollView target;
    private float level = REST_LEVEL;
    private long lastActivityAt;
    private LinearGradient thumbGradient;
    private int gradientHeight;

    private final Runnable fadeRunnable = new Runnable() {
        @Override
        public void run() {
            long idle = System.currentTimeMillis() - lastActivityAt;
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
        thumbPaint.setStrokeWidth(4.2f * density);
        thumbPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(9f * density);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(0xFF2B6CB0);

        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 挂到滚动容器上: 只监听滚动位置, 不参与触摸 */
    public void attachTo(ScrollView scrollView) {
        target = scrollView;
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

    private void touchActivity() {
        lastActivityAt = System.currentTimeMillis();
        if (level < 1f) {
            level = Math.min(1f, level + 0.3f);
        }
        ui.removeCallbacks(fadeRunnable);
        ui.postDelayed(fadeRunnable, FADE_DELAY_MS);
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

        float alpha = level;
        float start = -ARC_SPAN_DEG / 2f;
        float fraction = Math.max(0f, Math.min(1f, target.getScrollY() / (float) max));

        float viewportPart = Math.min(1f, h / (float) (h + max));
        float sweep = Math.max(22f, ARC_SPAN_DEG * viewportPart);
        float from = start + (ARC_SPAN_DEG - sweep) * fraction;

        if (thumbGradient == null || gradientHeight != h) {
            gradientHeight = h;
            thumbGradient = new LinearGradient(0f, h * 0.5f - radius,
                    0f, h * 0.5f + radius,
                    COLOR_THUMB_TOP, COLOR_THUMB_BOTTOM, Shader.TileMode.CLAMP);
        }

        glowPaint.setAlpha((int) (0x1E * alpha));
        canvas.drawArc(arcRect, from, sweep, false, glowPaint);

        thumbPaint.setShader(thumbGradient);
        thumbPaint.setAlpha((int) (0xE6 * alpha));
        canvas.drawArc(arcRect, from, sweep, false, thumbPaint);
        thumbPaint.setShader(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
