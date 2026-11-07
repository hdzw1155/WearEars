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

/** 固定在屏幕右缘的炫彩弹性圆弧，只观察滚动，不拦截触摸或表圈输入。 */
public class ArcIndicatorView extends View {
    private static final float ARC_SPAN = 104f;
    private static final float INSET_DP = 3f;
    private static final float REST_LEVEL = 0.55f;
    private static final long FADE_DELAY = 850L;
    private static final long FADE_DURATION = 220L;

    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Matrix gradientMatrix = new Matrix();
    private final ScrollIndicatorMotion motion = new ScrollIndicatorMotion();
    private final LinearGradient gradient = new LinearGradient(0f, 0f, 0f, 1f,
            new int[]{0xFF35D6ED, 0xFF41B5FF, 0xFF4E86F7, 0xFF8C99DF,
                    0xFFDDF5FF, 0xFF65BDF2, 0xFF35D6ED},
            new float[]{0f, 0.20f, 0.43f, 0.57f, 0.67f, 0.82f, 1f}, Shader.TileMode.MIRROR);
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
        // 使用设备默认主题的滚动条宽度，而不是再次猜一个固定 dp；导轨和滑块一致。
        inset = Math.max(INSET_DP * density, stroke / 2f + density);
        rail.setStyle(Paint.Style.STROKE);
        rail.setStrokeCap(Paint.Cap.ROUND);
        rail.setStrokeWidth(stroke);
        rail.setColor(0xFF7AADC8);
        thumb.setStyle(Paint.Style.STROKE);
        thumb.setStrokeCap(Paint.Cap.ROUND);
        thumb.setStrokeWidth(stroke);
        thumb.setShader(gradient);
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

    /** 页面暂停时同时停止装饰动画；回来直接同步当前位置。 */
    void setActive(boolean enabled) {
        active = enabled;
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

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float radius = Math.max(0f, Math.min(w, h) / 2f - inset);
        arc.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int max = maxScroll();
        int height = viewport();
        if (max <= 0 || height <= 0 || arc.isEmpty()) return;
        float pos = motion.position();
        float baseSweep = Math.max(22f, ARC_SPAN * height / (height + (float) max));
        float sweep = Math.min(ARC_SPAN, baseSweep * motion.stretch());
        float start = -ARC_SPAN / 2f;
        float from = start + (ARC_SPAN - sweep) * pos;
        float radius = arc.height() / 2f;
        float top = arc.centerY() + radius * (float) Math.sin(Math.toRadians(from));
        float bottom = arc.centerY() + radius * (float) Math.sin(Math.toRadians(from + sweep));
        float length = Math.max(1f, bottom - top);
        // 渐变绑定滑块自身，短滑块也能看到多种颜色；页面位置改变时平移色带。
        gradientMatrix.setScale(1f, length);
        gradientMatrix.postTranslate(0f, top - length * pos * 0.8f);
        gradient.setLocalMatrix(gradientMatrix);
        rail.setAlpha(Math.round(46f * level));
        thumb.setAlpha(Math.round(240f * level));
        // 每帧仅两条圆弧；透明度不会再被随后设置的颜色覆盖。
        canvas.drawArc(arc, start, ARC_SPAN, false, rail);
        canvas.drawArc(arc, from, sweep, false, thumb);
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
