package com.hdz.wearcontrol;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Curved orbital C-arc + alternating folding-fan deck container for Supreme ("至上") UI.
 *
 * <p>Every sheet sweeps along a curved left-bezel C-arc trajectory (top-left 第一屏 -> center ->
 * bottom-left 最后一屏) combined with alternating corner folding-fan pivots matching the 5-tap "S"
 * motion aesthetic. Zero inner scrolling: each sheet displays completely within the round dial.
 */
public class SuprematistFanDeckView extends FrameLayout {

    public interface OnDeckStateListener {
        void onDeckMoved(int settledPage, int pageCount, float position,
                         boolean lightSurface, boolean awake);
    }

    private static final float ROTARY_STEP_THRESHOLD = 0.65f;
    private static final long ROTARY_RESET_MS = 220L;
    private static final long SETTLE_DURATION_MS = 300L;
    private static final long SNAPBACK_DURATION_MS = 230L;

    private final List<View> activePages = new ArrayList<View>();
    private final Set<View> disabledPages = new HashSet<View>();
    private ScrollFeedback haptics;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ViewOutlineProvider circularOutline = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w > 0 && h > 0) {
                outline.setOval(0, 0, w, h);
            }
        }
    };

    private float density;
    private int touchSlop;
    private int minFlingVel;

    private boolean active = true;
    private int currentPage = 0;
    private float deckPos = 0f;
    private float overscroll = 0f;

    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean dragHorizontalIgnored;
    private boolean wallHitThisGesture;
    private int dragStartPage;
    private VelocityTracker velocityTracker;

    private float rotaryAccum = 0f;
    private long lastRotaryAt = 0L;

    private ValueAnimator settleAnim;
    private ValueAnimator overscrollAnim;
    private OnDeckStateListener deckListener;

    private final Runnable rotaryDecayTask = new Runnable() {
        @Override
        public void run() {
            rotaryAccum = 0f;
        }
    };

    public SuprematistFanDeckView(Context context) {
        this(context, null);
    }

    public SuprematistFanDeckView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SuprematistFanDeckView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        haptics = new ScrollFeedback(22f * density);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVel = vc.getScaledMinimumFlingVelocity() * 3;
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setOnDeckStateListener(OnDeckStateListener l) {
        this.deckListener = l;
        notifyDeckState(false);
    }

    public void setActive(boolean enabled) {
        this.active = enabled;
        if (!enabled) {
            cancelAnimations();
            haptics.reset();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setOutlineProvider(circularOutline);
            child.setClipToOutline(true);
        }
        rebuildActivePages(null);
    }

    public void setPageEnabled(View page, boolean enabled) {
        if (page == null) {
            return;
        }
        boolean wasDisabled = disabledPages.contains(page);
        if (enabled && !wasDisabled) {
            return;
        }
        if (!enabled && wasDisabled) {
            return;
        }
        View currentView = getCurrentPageView();
        if (enabled) {
            disabledPages.remove(page);
            page.setVisibility(View.VISIBLE);
        } else {
            disabledPages.add(page);
            resetPageTransform(page);
            page.setVisibility(View.GONE);
        }
        rebuildActivePages(currentView);
    }

    private View getCurrentPageView() {
        if (activePages.isEmpty()) {
            return null;
        }
        int idx = Math.max(0, Math.min(activePages.size() - 1, currentPage));
        return activePages.get(idx);
    }

    private void rebuildActivePages(View preferredView) {
        activePages.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!disabledPages.contains(child)) {
                activePages.add(child);
            }
        }
        if (activePages.isEmpty()) {
            currentPage = 0;
            deckPos = 0f;
            notifyDeckState(false);
            return;
        }
        int newIdx = -1;
        if (preferredView != null) {
            newIdx = activePages.indexOf(preferredView);
        }
        if (newIdx < 0) {
            newIdx = Math.max(0, Math.min(activePages.size() - 1, currentPage));
        }
        currentPage = newIdx;
        deckPos = newIdx;
        overscroll = 0f;
        applyDeckTransforms();
        notifyDeckState(false);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getPageCount() {
        return activePages.size();
    }

    public void setCurrentPage(int index, boolean animate) {
        if (activePages.isEmpty()) {
            return;
        }
        int target = Math.max(0, Math.min(activePages.size() - 1, index));
        if (!animate || getWidth() <= 0 || getHeight() <= 0) {
            cancelAnimations();
            currentPage = target;
            deckPos = target;
            overscroll = 0f;
            applyDeckTransforms();
            notifyDeckState(false);
            return;
        }
        animateToPage(target, SETTLE_DURATION_MS, true);
    }

    public void focusPage(View page, boolean animate) {
        int idx = activePages.indexOf(page);
        if (idx >= 0) {
            setCurrentPage(idx, animate);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            applyDeckTransforms();
            notifyDeckState(false);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (right > left && bottom > top) {
            applyDeckTransforms();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!active || activePages.size() <= 1) {
            return super.onInterceptTouchEvent(ev);
        }
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimations();
                downX = ev.getX();
                downY = ev.getY();
                lastY = downY;
                dragging = false;
                dragHorizontalIgnored = false;
                wallHitThisGesture = false;
                dragStartPage = Math.max(0, Math.min(activePages.size() - 1, Math.round(deckPos)));
                resetVelocityTracker(ev);
                break;

            case MotionEvent.ACTION_MOVE:
                if (dragHorizontalIgnored) {
                    return false;
                }
                trackVelocity(ev);
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop * 1.4f && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    dragHorizontalIgnored = true;
                    return false;
                }
                if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx) * 0.75f) {
                    dragging = true;
                    lastY = ev.getY();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                dragHorizontalIgnored = false;
                recycleVelocityTracker();
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!active || activePages.isEmpty()) {
            return false;
        }
        int maxIdx = activePages.size() - 1;
        if (maxIdx <= 0) {
            return false;
        }
        trackVelocity(ev);
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimations();
                downX = ev.getX();
                downY = ev.getY();
                lastY = downY;
                dragStartPage = Math.max(0, Math.min(maxIdx, Math.round(deckPos)));
                wallHitThisGesture = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - lastY;
                lastY = ev.getY();
                if (!dragging && Math.abs(ev.getY() - downY) > touchSlop) {
                    dragging = true;
                }
                float span = Math.max(150f, getHeight() * 0.68f);
                float nextPos = deckPos - (dy / span);
                if (nextPos < 0f) {
                    deckPos = 0f;
                    overscroll = Math.max(-1f, nextPos * 1.6f);
                    if (!wallHitThisGesture) {
                        wallHitThisGesture = true;
                        playWallFeedback(-1);
                    }
                } else if (nextPos > maxIdx) {
                    deckPos = maxIdx;
                    overscroll = Math.min(1f, (nextPos - maxIdx) * 1.6f);
                    if (!wallHitThisGesture) {
                        wallHitThisGesture = true;
                        playWallFeedback(1);
                    }
                } else {
                    deckPos = nextPos;
                    overscroll = 0f;
                }
                applyDeckTransforms();
                notifyDeckState(true);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float vy = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    vy = velocityTracker.getYVelocity();
                }
                recycleVelocityTracker();
                dragging = false;
                if (Math.abs(overscroll) > 0.001f) {
                    animateOverscrollBack();
                    return true;
                }
                int target = resolveTargetPage(vy, maxIdx);
                boolean pageChanged = target != dragStartPage;
                animateToPage(target, SETTLE_DURATION_MS, pageChanged);
                return true;
        }
        return true;
    }

    private int resolveTargetPage(float velocityY, int maxIdx) {
        if (Math.abs(velocityY) >= minFlingVel) {
            int flingTarget = velocityY < 0f ? dragStartPage + 1 : dragStartPage - 1;
            return Math.max(0, Math.min(maxIdx, flingTarget));
        }
        float delta = deckPos - dragStartPage;
        if (delta >= 0.22f) {
            return Math.min(maxIdx, (int) Math.ceil(deckPos - 0.22f));
        }
        if (delta <= -0.22f) {
            return Math.max(0, (int) Math.floor(deckPos + 0.22f));
        }
        return dragStartPage;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL || action == MotionEvent.ACTION_HOVER_MOVE) {
            float delta = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta == 0f) {
                delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            }
            if (delta == 0f) {
                delta = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            }
            if (delta != 0f && handleRotary(delta)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    boolean handleRotary(float rawDelta) {
        if (!active || activePages.isEmpty() || Math.abs(rawDelta) < 0.001f) {
            return false;
        }
        float units = -rawDelta;
        long now = SystemClock.uptimeMillis();
        if (now - lastRotaryAt > ROTARY_RESET_MS
                || (rotaryAccum != 0f && Math.signum(rotaryAccum) != Math.signum(units))) {
            rotaryAccum = 0f;
        }
        lastRotaryAt = now;
        ui.removeCallbacks(rotaryDecayTask);
        ui.postDelayed(rotaryDecayTask, ROTARY_RESET_MS);
        rotaryAccum += units;

        if (Math.abs(rotaryAccum) < ROTARY_STEP_THRESHOLD) {
            return true;
        }
        int step = rotaryAccum > 0f ? 1 : -1;
        rotaryAccum = 0f;
        stepByRotary(step);
        return true;
    }

    boolean handleRotaryKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            return handleRotary(1f);
        }
        if (keyCode == KeyEvent.KEYCODE_NAVIGATE_NEXT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            return handleRotary(-1f);
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

    private void stepByRotary(int step) {
        int maxIdx = activePages.size() - 1;
        if (maxIdx <= 0) {
            return;
        }
        int next = currentPage + step;
        if (next < 0 || next > maxIdx) {
            playWallFeedback(next < 0 ? -1 : 1);
            nudgeOverscroll(next < 0 ? -0.65f : 0.65f);
            return;
        }
        animateToPage(next, SETTLE_DURATION_MS, true);
    }

    private void animateToPage(final int target, long durationMs, boolean playTick) {
        if (settleAnim != null) {
            settleAnim.cancel();
        }
        if (playTick) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        currentPage = target;
        final float startPos = deckPos;
        if (Math.abs(target - startPos) < 0.001f) {
            deckPos = target;
            applyDeckTransforms();
            notifyDeckState(true);
            return;
        }
        settleAnim = ValueAnimator.ofFloat(startPos, (float) target);
        settleAnim.setDuration(durationMs);
        settleAnim.setInterpolator(new DecelerateInterpolator(1.7f));
        settleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                deckPos = (Float) animation.getAnimatedValue();
                applyDeckTransforms();
                notifyDeckState(true);
            }
        });
        settleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                settleAnim = null;
                deckPos = target;
                currentPage = target;
                applyDeckTransforms();
                notifyDeckState(true);
            }
        });
        settleAnim.start();
    }

    private void nudgeOverscroll(float peak) {
        if (overscrollAnim != null) {
            overscrollAnim.cancel();
        }
        overscroll = peak;
        applyDeckTransforms();
        animateOverscrollBack();
    }

    private void animateOverscrollBack() {
        if (overscrollAnim != null) {
            overscrollAnim.cancel();
        }
        final float start = overscroll;
        overscrollAnim = ValueAnimator.ofFloat(start, 0f);
        overscrollAnim.setDuration(SNAPBACK_DURATION_MS);
        overscrollAnim.setInterpolator(new DecelerateInterpolator(2.0f));
        overscrollAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                overscroll = (Float) animation.getAnimatedValue();
                applyDeckTransforms();
            }
        });
        overscrollAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overscroll = 0f;
                overscrollAnim = null;
                applyDeckTransforms();
            }
        });
        overscrollAnim.start();
    }

    private void cancelAnimations() {
        if (settleAnim != null) {
            settleAnim.cancel();
            settleAnim = null;
        }
        if (overscrollAnim != null) {
            overscrollAnim.cancel();
            overscrollAnim = null;
        }
    }

    private void applyDeckTransforms() {
        int count = activePages.size();
        int w = getWidth();
        int h = getHeight();
        if (count == 0 || w <= 0 || h <= 0) {
            return;
        }
        float clampedPos = Math.max(0f, Math.min(count - 1f, deckPos));
        int baseIdx = Math.max(0, Math.min(count - 1, (int) Math.floor(clampedPos)));
        int nextIdx = Math.min(count - 1, baseIdx + 1);
        float frac = clampedPos - baseIdx;
        if (frac <= 0.0005f) {
            frac = 0f;
            nextIdx = baseIdx;
        } else if (frac >= 0.9995f && nextIdx > baseIdx) {
            baseIdx = nextIdx;
            frac = 0f;
        }

        // Alternating corner folding-fan pivot ("5-tap S" transition) fused with left C-arc orbit
        float dir = (nextIdx % 2 == 1) ? 1f : -1f;
        float u = (float) (0.5 * (1.0 - Math.cos(Math.PI * frac)));

        for (int i = 0; i < count; i++) {
            View page = activePages.get(i);
            if (i == baseIdx || (i == nextIdx && nextIdx != baseIdx)) {
                page.setVisibility(View.VISIBLE);
                if (frac > 0f) {
                    float t = i - clampedPos; // base in [-1, 0], next in [0, 1]
                    double theta = t * 0.78;
                    float arcRx = w * 0.48f;
                    float arcRy = h * 0.94f;
                    float tx = -arcRx * (float) (1.0 - Math.cos(theta));
                    float ty = arcRy * (float) Math.sin(theta);

                    boolean incoming = (i == nextIdx);
                    page.setTranslationZ((incoming ? 8f : 0f) * density);
                    page.setPivotX(dir > 0f ? w * 0.14f : w * 0.86f);
                    page.setPivotY(incoming ? h * 0.88f : h * 0.12f);

                    float fanSwing = (float) Math.sin(Math.PI * frac) * dir * (incoming ? 11f : -6.5f);
                    page.setRotation(-t * 24f + fanSwing);
                    page.setTranslationX(tx);
                    page.setTranslationY(ty);
                    float s = 1f - 0.10f * Math.abs(t);
                    page.setScaleX(s);
                    page.setScaleY(s);
                    page.setAlpha(Math.max(0.25f, 1f - 0.65f * Math.abs(t)));
                } else if (Math.abs(overscroll) > 0.0005f) {
                    double theta = overscroll * 0.28;
                    page.setPivotX(w * 0.12f);
                    page.setPivotY(overscroll < 0f ? h * 0.20f : h * 0.80f);
                    page.setRotation(overscroll * 9.5f);
                    page.setTranslationX(-w * 0.24f * (float) (1.0 - Math.cos(theta)));
                    page.setTranslationY(-h * 0.34f * (float) Math.sin(theta));
                    float s = 1f - Math.abs(overscroll) * 0.03f;
                    page.setScaleX(s);
                    page.setScaleY(s);
                    page.setAlpha(1f);
                } else {
                    resetPageTransform(page);
                }
            } else {
                resetPageTransform(page);
                page.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void resetPageTransform(View page) {
        page.setPivotX(getWidth() * 0.5f);
        page.setPivotY(getHeight() * 0.5f);
        page.setRotation(0f);
        page.setTranslationX(0f);
        page.setTranslationY(0f);
        page.setTranslationZ(0f);
        page.setScaleX(1f);
        page.setScaleY(1f);
        page.setAlpha(1f);
    }

    private void notifyDeckState(boolean awake) {
        if (deckListener == null || activePages.isEmpty()) {
            return;
        }
        int count = activePages.size();
        float pos = Math.max(0f, Math.min(count - 1f, deckPos));
        int domIdx = Math.max(0, Math.min(count - 1, Math.round(pos)));
        View domView = activePages.get(domIdx);
        boolean lightSurface = isLightPage(domView);
        deckListener.onDeckMoved(currentPage, count, pos, lightSurface, awake);
    }

    private static boolean isLightPage(View page) {
        if (page == null) {
            return true;
        }
        Object tag = page.getTag();
        return tag == null || "paper".equals(tag);
    }

    private void resetVelocityTracker(MotionEvent ev) {
        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);
    }

    private void trackVelocity(MotionEvent ev) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void playWallFeedback(int side) {
        if (haptics.limit(side, SystemClock.uptimeMillis()) == ScrollFeedback.LIMIT) {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        cancelAnimations();
        recycleVelocityTracker();
        haptics.reset();
        super.onDetachedFromWindow();
    }
}
