package com.hdz.wearcontrol;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * 固定在屏幕右缘的紧凑弹性圆弧指示器。
 * 导轨总跨度占右半圆的约 1/4 (45度)，随页面滚动在顶绿、中科技炫彩、底蓝之间动态渐变，
 * 也支持在设置中切换为经典青蓝配色。只观察滚动，不拦截触摸或表圈输入。
 */
public class ArcIndicatorView extends View {
    /** 导轨总跨度：45度（刚好占屏幕右侧180度半圆的 1/4） */
    private static final float ARC_SPAN = 45f;
    private static final float INSET_DP = 3f;
    private static final float REST_LEVEL = 0.55f;
    private static final long FADE_DELAY = 850L;
    private static final long FADE_DURATION = 220L;

    // ---- 动态科技流光色系 ----
    // 顶部 (pos = 0.0): 科技翠绿
    private static final int[] TOP_COLORS = new int[]{
            0xFF00E5A3, 0xFF00E5A3, 0xFF00D287, 0xFF00D287
    };
    // 中部 (pos = 0.5): 科技炫彩 (翠绿 -> 电光青 -> 赛博紫 -> 科技蓝，克制科技美学)
    private static final int[] MID_COLORS = new int[]{
            0xFF00E5A3, 0xFF00E5FF, 0xFF9333EA, 0xFF3B82F6
    };
    // 底部 (pos = 1.0): 科技深蓝
    private static final int[] BOT_COLORS = new int[]{
            0xFF3B82F6, 0xFF3B82F6, 0xFF1D4ED8, 0xFF1D4ED8
    };
    private static final float[] DYNAMIC_STOPS = new float[]{0f, 0.33f, 0.67f, 1f};

    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Matrix gradientMatrix = new Matrix();
    private final ScrollIndicatorMotion motion = new ScrollIndicatorMotion();

    // 经典青蓝静态渐变 (用于设置切换)
    private final LinearGradient classicGradient = new LinearGradient(0f, 0f, 0f, 1f,
            new int[]{0xFF35D6ED, 0xFF41B5FF, 0xFF4E86F7, 0xFF8C99DF,
                    0xFFDDF5FF, 0xFF65BDF2, 0xFF35D6ED},
            new float[]{0f, 0.20f, 0.43f, 0.57f, 0.67f, 0.82f, 1f}, Shader.TileMode.MIRROR);

    private final int[] dynamicColors = new int[4];
    private int colorStyle = AppPrefs.SCROLL_COLOR_DYNAMIC;

    private final float inset;
    private ScrollView target;
    private View content;
    private boolean listening, active = true, animating;
    private float level = REST_LEVEL;
    private long lastActivity;
    private final Runnable edgePulse = new Runnable() {
        @Override public void run() { onScrolled(); }
    };

    private final View.OnScrollChangeListener scrollListener = new View.OnScrollChangeListener() {
        @Override public void onScrollChange(View view, int x, int y, int oldX, int oldY) {
            if (y != oldY) onScrolled();
        }
    };
    private final View.OnLayoutChangeListener layoutListener = new View.OnLayoutChangeListener() {
        @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                int oldL, int oldT, int oldR, int oldB) {
            syncPosition();
        }
    };
    private final Runnable fade = new Runnable() {
        @Override public void run() { startFrames(); }
    };
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!canAnimate()) { stopFrames(); return; }
            long now = SystemClock.uptimeMillis();
            boolean moving = motion.advance(fraction(), now);
            long idle = now - lastActivity;
            if (idle >= FADE_DELAY) {
                float progress = Math.min(1f, (idle - FADE_DELAY) / (float) FADE_DURATION);
                level = 1f - (1f - REST_LEVEL) * progress;
            }
            invalidate();
            if (moving
                    || (idle >= FADE_DELAY && idle < FADE_DELAY + FADE_DURATION)) {
                postOnAnimation(this);
            } else {
                animating = false;
            }
        }
    };

    public ArcIndicatorView(Context context) { this(context, null); }

    public ArcIndicatorView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        float stroke = ViewConfiguration.get(context).getScaledScrollBarSize();
        TypedArray sizes = context.obtainStyledAttributes(new int[]{android.R.attr.scrollbarSize});
        try { stroke = sizes.getDimension(0, stroke); }
        finally { sizes.recycle(); }
        inset = Math.max(INSET_DP * density, stroke / 2f + density);
        rail.setStyle(Paint.Style.STROKE);
        rail.setStrokeCap(Paint.Cap.ROUND);
        rail.setStrokeWidth(stroke);
        rail.setColor(0xFF2A3B4C);
        thumb.setStyle(Paint.Style.STROKE);
        thumb.setStrokeCap(Paint.Cap.ROUND);
        thumb.setStrokeWidth(stroke);
        colorStyle = AppPrefs.scrollColorStyle(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void attachTo(ScrollView scrollView) {
        unbind();
        stopFrames();
        target = scrollView;
        if (isAttachedToWindow()) bind();
        syncPosition();
    }

    public void refreshColorStyle() {
        colorStyle = AppPrefs.scrollColorStyle(getContext());
        invalidate();
    }

    /** 页面暂停时同时停止装饰动画；回来直接同步当前位置和配色。 */
    void setActive(boolean enabled) {
        active = enabled;
        if (enabled) {
            colorStyle = AppPrefs.scrollColorStyle(getContext());
        }
        syncPosition();
    }

    private void bind() {
        if (listening || target == null) return;
        target.setOnScrollChangeListener(scrollListener);
        if (target instanceof AppScrollView) ((AppScrollView) target).setEdgeEffect(edgePulse);
        target.addOnLayoutChangeListener(layoutListener);
        content = target.getChildAt(0);
        if (content != null) content.addOnLayoutChangeListener(layoutListener);
        listening = true;
    }

    private void unbind() {
        if (!listening) return;
        target.setOnScrollChangeListener(null);
        if (target instanceof AppScrollView) ((AppScrollView) target).setEdgeEffect(null);
        target.removeOnLayoutChangeListener(layoutListener);
        if (content != null) content.removeOnLayoutChangeListener(layoutListener);
        content = null;
        listening = false;
    }

    private boolean canAnimate() {
        return active && target != null && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && hasWindowFocus();
    }

    private void onScrolled() {
        if (!canAnimate()) { syncPosition(); return; }
        lastActivity = SystemClock.uptimeMillis();
        motion.pulse(lastActivity);
        level = 1f;
        removeCallbacks(fade);
        postDelayed(fade, FADE_DELAY);
        startFrames();
    }

    private void startFrames() {
        if (animating || !canAnimate()) return;
        animating = true;
        postOnAnimation(frame);
    }

    private void stopFrames() {
        removeCallbacks(frame);
        removeCallbacks(fade);
        animating = false;
    }

    private void syncPosition() {
        stopFrames();
        motion.reset(fraction());
        level = REST_LEVEL;
        lastActivity = 0L;
        invalidate();
    }

    private int viewport() {
        return target == null ? 0 : Math.max(0,
                target.getHeight() - target.getPaddingTop() - target.getPaddingBottom());
    }

    private int maxScroll() {
        if (target == null || target.getChildCount() == 0) return 0;
        View child = target.getChildAt(0);
        int height = child.getHeight();
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            height += margins.topMargin + margins.bottomMargin;
        }
        return Math.max(0, height - viewport());
    }

    private float fraction() {
        int max = maxScroll();
        return max == 0 ? 0f : Math.max(0f, Math.min(1f, target.getScrollY() / (float) max));
    }

    private boolean isRoundScreen() {
        return getResources().getConfiguration().isScreenRound();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float radius = Math.max(0f, Math.min(w, h) / 2f - inset);
        arc.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int max = maxScroll();
        int height = viewport();
        if (max <= 0 || height <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        float pos = motion.position();

        if (!isRoundScreen()) {
            drawLinearIndicator(canvas, max, height, pos);
            return;
        }
        if (arc.isEmpty()) return;

        float baseSweep = Math.max(12f, ARC_SPAN * height / (height + (float) max));
        float sweep = Math.min(ARC_SPAN, baseSweep * motion.stretch());
        float start = -ARC_SPAN / 2f;
        float from = start + (ARC_SPAN - sweep) * pos;
        float radius = arc.height() / 2f;
        float top = arc.centerY() + radius * (float) Math.sin(Math.toRadians(from));
        float bottom = arc.centerY() + radius * (float) Math.sin(Math.toRadians(from + sweep));
        applyThumbGradient(pos, top, bottom);

        rail.setAlpha(Math.round(46f * level));
        thumb.setAlpha(Math.round(240f * level));
        canvas.drawArc(arc, start, ARC_SPAN, false, rail);
        canvas.drawArc(arc, from, sweep, false, thumb);
    }

    private void drawLinearIndicator(Canvas canvas, int max, int height, float pos) {
        int w = getWidth();
        int h = getHeight();
        float x = w - inset - thumb.getStrokeWidth() * 0.5f;
        float railTop = Math.max(h * 0.18f, getPaddingTop() + inset * 3f);
        float railBot = Math.min(h * 0.84f, h - getPaddingBottom() - inset * 3f);
        float railLen = Math.max(1f, railBot - railTop);

        float baseLen = Math.max(railLen * 0.16f, railLen * height / (height + (float) max));
        float thumbLen = Math.min(railLen, baseLen * motion.stretch());
        float top = railTop + (railLen - thumbLen) * pos;
        float bottom = top + thumbLen;
        applyThumbGradient(pos, top, bottom);

        rail.setAlpha(Math.round(46f * level));
        thumb.setAlpha(Math.round(240f * level));
        canvas.drawLine(x, railTop, x, railBot, rail);
        canvas.drawLine(x, top, x, bottom, thumb);
    }

    private void applyThumbGradient(float pos, float top, float bottom) {
        float length = Math.max(1f, bottom - top);
        if (colorStyle == AppPrefs.SCROLL_COLOR_CLASSIC) {
            gradientMatrix.setScale(1f, length);
            gradientMatrix.postTranslate(0f, top - length * pos * 0.8f);
            classicGradient.setLocalMatrix(gradientMatrix);
            thumb.setShader(classicGradient);
            rail.setColor(0xFF7AADC8);
        } else {
            // 顶绿 -> 中科技炫彩 -> 底蓝 的动态插值
            float colorPos = Math.max(0f, Math.min(1f, pos));
            if (colorPos <= 0.5f) {
                float t = colorPos / 0.5f;
                for (int i = 0; i < 4; i++) {
                    dynamicColors[i] = lerpColor(TOP_COLORS[i], MID_COLORS[i], t);
                }
            } else {
                float t = (colorPos - 0.5f) / 0.5f;
                for (int i = 0; i < 4; i++) {
                    dynamicColors[i] = lerpColor(MID_COLORS[i], BOT_COLORS[i], t);
                }
            }
            LinearGradient dynGrad = new LinearGradient(
                    0f, top, 0f, bottom, dynamicColors, DYNAMIC_STOPS, Shader.TileMode.CLAMP);
            thumb.setShader(dynGrad);
            rail.setColor(0xFF2A3B4C);
        }
    }

    private static int lerpColor(int c0, int c1, float t) {
        if (t <= 0f) return c0;
        if (t >= 1f) return c1;
        int a = (int) (((c0 >>> 24) & 0xFF) + t * (((c1 >>> 24) & 0xFF) - ((c0 >>> 24) & 0xFF)));
        int r = (int) (((c0 >>> 16) & 0xFF) + t * (((c1 >>> 16) & 0xFF) - ((c0 >>> 16) & 0xFF)));
        int g = (int) (((c0 >>> 8) & 0xFF) + t * (((c1 >>> 8) & 0xFF) - ((c0 >>> 8) & 0xFF)));
        int b = (int) ((c0 & 0xFF) + t * ((c1 & 0xFF) - (c0 & 0xFF)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bind();
        syncPosition();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncPosition();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        syncPosition();
    }

    @Override protected void onDetachedFromWindow() {
        stopFrames();
        unbind();
        super.onDetachedFromWindow();
    }
}
