package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.HorizontalScrollView;

/**
 * DeepX 档位横滑列表: 松手磁吸居中 + 选中项 Q 弹缩放 + 左右羽化渐隐.
 *
 * <p>只是"看得舒服"的容器: 磁吸与缩放不会自动改档, 切档仍只由点击触发,
 * 避免手指滑一下就把实验性指令发出去.
 */
public class SnapChipRow extends HorizontalScrollView {

    /** 卡片底色, 羽化渐变的起点; 两套界面用 setCardColor 切换 */
    private int cardColor = 0xFF141416;
    private static final float FADE_WIDTH_DP = 16f;
    private static final int SNAP_DURATION_MS = 240;

    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int fadeWidth;
    private boolean applying;          // 磁吸动画进行中, 别自己再触发
    private int currentIndex = -1;
    private boolean pendingCenter;     // 布局还没量好时, 等 onLayout 再补一次居中

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingCenter && currentIndex >= 0 && getWidth() > 0) {
            pendingCenter = false;
            post(new Runnable() {
                @Override
                public void run() {
                    centerOn(currentIndex);
                }
            });
        }
    }

    private final Runnable snapTask = new Runnable() {
        @Override
        public void run() {
            snapToNearest();
        }
    };

    public SnapChipRow(Context context) {
        this(context, null);
    }

    public SnapChipRow(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SnapChipRow(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        fadeWidth = Math.round(FADE_WIDTH_DP * getResources().getDisplayMetrics().density);
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        setPadding(pad, 0, pad, 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildFades(w, h);
    }

    private void buildFades(int w, int h) {
        fadePaint.setShader(new LinearGradient(0, 0, fadeWidth, 0,
                new int[]{cardColor, cardColor & 0x00FFFFFF}, new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
        fadePaint.setDither(true);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // 注意: HorizontalScrollView 的 dispatchDraw 画布已按 scrollX 平移过,
        // 要画"钉在视口上"的渐变, 坐标必须自己加回 getScrollX().
        int h = getHeight();
        int sx = getScrollX();
        // 左右各 16dp 羽化: 从卡片底色渐到全透明, 消掉"胶囊被垂直切断"的硬边
        fadePaint.setShader(new LinearGradient(sx, 0, sx + fadeWidth, 0,
                new int[]{cardColor, cardColor, cardColor & 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(sx, 0, sx + fadeWidth, h, fadePaint);
        int right = sx + getWidth();
        fadePaint.setShader(new LinearGradient(right - fadeWidth, 0, right, 0,
                new int[]{cardColor & 0x00FFFFFF, cardColor, cardColor},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(right - fadeWidth, 0, right, h, fadePaint);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            removeCallbacks(snapTask);
            postDelayed(snapTask, 60);
        }
        return handled;
    }
    /** 经典界面用: 卡片底色换回旧灰 */
    public void setCardColor(int color) {
        cardColor = color;
        invalidate();
    }

    @Override
    public void fling(int velocityX) {
        super.fling(velocityX);
        removeCallbacks(snapTask);
        postDelayed(snapTask, 480);
    }

    private View chipAt(int index) {
        if (getChildCount() == 0) {
            return null;
        }
        View child = getChildAt(0);
        if (!(child instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) child;
        if (index < 0 || index >= group.getChildCount()) {
            return null;
        }
        return group.getChildAt(index);
    }

    /** 让第 index 个 chip 居中并放大; 传 -1 表示无选中(全部还原) */
    public void select(int index, boolean animate) {
        currentIndex = index;
        pendingCenter = true;
        applyScales(animate);
        removeCallbacks(snapTask);
        // 等一帧让 chip 量好尺寸再吸附(首次进入时刚 inflate, 宽度还是 0)
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentIndex >= 0) {
                    centerOn(currentIndex);
                } else {
                    snapToNearest();
                }
            }
        }, animate ? 60L : 0L);
    }

    /** 把第 index 个 chip 滚到视口正中 */
    private void centerOn(int index) {
        View chip = chipAt(index);
        if (chip == null || getWidth() == 0) {
            return;
        }
        // ScrollView 的子 View 从 paddingLeft 开始排, 所以屏幕坐标 = paddingLeft + childLeft - scrollX
        int target = getPaddingLeft() + chip.getLeft() + chip.getWidth() / 2 - getWidth() / 2;
        pendingCenter = false;
        android.util.Log.d("SnapDbg", "centerOn idx=" + index + " left=" + chip.getLeft() + " w=" + chip.getWidth() + " vw=" + getWidth() + " pad=" + getPaddingLeft() + " target=" + target + " sx=" + getScrollX());
        animateScrollTo(clampScroll(target));
    }

    private int clampScroll(int target) {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child == null) {
            return 0;
        }
        int max = child.getWidth() + getPaddingLeft() + getPaddingRight() - getWidth();
        if (target < 0) {
            return 0;
        }
        if (target > max) {
            return Math.max(0, max);
        }
        return target;
    }

    public void snapToNearest() {
        if (applying || getWidth() == 0 || getChildCount() == 0) {
            return;
        }
        android.view.ViewGroup group = null;
        if (getChildAt(0) instanceof android.view.ViewGroup) {
            group = (android.view.ViewGroup) getChildAt(0);
        }
        if (group == null || group.getChildCount() == 0) {
            return;
        }
        float viewportCenter = getWidth() / 2f;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < group.getChildCount(); i++) {
            View chip = group.getChildAt(i);
            float center = getPaddingLeft() + chip.getLeft() + chip.getWidth() / 2f - getScrollX();
            float dist = Math.abs(center - viewportCenter);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        View chip = group.getChildAt(best);
        int target = chip.getLeft() + chip.getWidth() / 2 - getWidth() / 2;
        animateScrollTo(clampScroll(target));
    }

    /** 滚到指定位置: 240ms 轻微过冲, 带一点"吸过去"的 Q 弹感 */
    private void animateScrollTo(final int target) {
        final int start = getScrollX();
        if (start == target) {
            return;
        }
        // 先掐掉可能还在跑的惯性滑行, 否则它和吸附动画会互相拉扯
        super.fling(0);
        applying = true;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SNAP_DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                scrollTo((int) (start + (target - start) * f), 0);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                applying = false;
            }

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                applying = false;
            }
        });
        anim.start();
    }

    private void applyScales(boolean animate) {
        android.view.ViewGroup group = null;
        if (getChildAt(0) instanceof android.view.ViewGroup) {
            group = (android.view.ViewGroup) getChildAt(0);
        }
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View chip = group.getChildAt(i);
            float target = currentIndex < 0 ? 1f : (i == currentIndex ? 1.05f : 0.92f);
            if (animate) {
                chip.animate()
                        .scaleX(target)
                        .scaleY(target)
                        .setDuration(220)
                        .setInterpolator(new OvershootInterpolator(1.4f))
                        .start();
            } else {
                chip.setScaleX(target);
                chip.setScaleY(target);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(snapTask);
    }
}
