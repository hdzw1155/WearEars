package com.hdz.wearcontrol;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Folding-fan card deck implementing alternating left/right bottom-pivot page reveals
 * (matching the website's 5-click "S" fan-deck interaction) adapted for Wear OS circular
 * watches with 1:1 vertical swipe physics, inner ScrollView cooperation, and rotary detents.
 */
public class SuprematistFanDeckView extends FrameLayout {

    public interface OnDeckStateListener {
        void onDeckMoved(int settledPage, int pageCount, float position, boolean lightSurface, boolean awake);
    }

    private static final float ROTARY_STEP_THRESHOLD = 0.65f;
    private static final long ROTARY_RESET_MS = 320L;
    private static final long SETTLE_DURATION_MS = 275L;
    private static final long SNAPBACK_DURATION_MS = 220L;

    private final List<View> activePages = new ArrayList<View>();
    private final Set<View> disabledPages = new HashSet<View>();
    private final int[] tmpLoc = new int[2];
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final int touchSlop;
    private final int minFlingVel;
    private final WatchHaptics haptics;

    private OnDeckStateListener deckListener;
    private boolean active = true;

    private float deckPos = 0f;
    private int currentPage = 0;
    private float overscroll = 0f;
    private ValueAnimator settleAnim;
    private ValueAnimator overscrollAnim;

    private boolean dragging = false;
    private boolean dragHorizontalIgnored = false;
    private boolean wallHitThisGesture = false;
    private int dragStartPage = 0;
    private float downX;
    private float downY;
    private float lastY;
    private float rotaryAccum = 0f;
    private long lastRotaryAt = 0L;
    private VelocityTracker velocityTracker;

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
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVel = Math.max(420, vc.getScaledMinimumFlingVelocity() * 4);
        haptics = new WatchHaptics(context);
        setClipChildren(true);
        setClipToPadding(true);
    }

    public void setOnDeckStateListener(OnDeckStateListener listener) {
        deckListener = listener;
        notifyDeckState(false);
    }

    public void setActive(boolean enabled) {
        active = enabled;
        if (!enabled) {
            cancelAnimations();
            haptics.cancel();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
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
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    dragHorizontalIgnored = true;
                    return false;
                }
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    boolean settled = Math.abs(deckPos - Math.round(deckPos)) < 0.015f;
                    if (settled) {
                        ScrollView scroller = findInnerScroller(getCurrentPageView(), downX, downY);
                        int scrollDir = dy < 0f ? 1 : -1;
                        if (scroller != null && scroller.canScrollVertically(scrollDir)) {
                            return false;
                        }
                    }
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
                float span = Math.max(160f, getHeight() * 0.72f);
                float nextPos = deckPos - (dy / span);
                if (nextPos < 0f) {
                    deckPos = 0f;
                    overscroll = Math.max(-1f, nextPos * 1.6f);
                    if (!wallHitThisGesture) {
                        wallHitThisGesture = true;
                        haptics.playWall(this);
                    }
                } else if (nextPos > maxIdx) {
                    deckPos = maxIdx;
                    overscroll = Math.min(1f, (nextPos - maxIdx) * 1.6f);
                    if (!wallHitThisGesture) {
                        wallHitThisGesture = true;
                        haptics.playWall(this);
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
        if (delta >= 0.24f) {
            return Math.min(maxIdx, (int) Math.ceil(deckPos - 0.24f));
        }
        if (delta <= -0.24f) {
            return Math.max(0, (int) Math.floor(deckPos + 0.24f));
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
        // Align sign polarity with AppScrollView: negative AXIS_SCROLL advances down/forward.
        float units = -rawDelta;
        ScrollView scroller = findInnerScroller(getCurrentPageView(), getWidth() * 0.5f, getHeight() * 0.5f);
        int dir = units > 0f ? 1 : -1;
        if (scroller != null && scroller.canScrollVertically(dir)) {
            int stepPx = Math.round(units * 26f * density);
            scroller.smoothScrollBy(0, stepPx);
            haptics.playTick(this);
            return true;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastRotaryAt > ROTARY_RESET_MS || (rotaryAccum != 0f && Math.signum(rotaryAccum) != Math.signum(units))) {
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
            haptics.playWall(this);
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
            haptics.playTick(this);
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

        // Alternating bottom corners matching the website's 5-click "S" fan-deck sweeps:
        // Odd sheet indices sweep from bottom-left; even sheet indices sweep from bottom-right.
        float dir = (nextIdx % 2 == 1) ? 1f : -1f;
        float u = (float) (0.5 * (1.0 - Math.cos(Math.PI * frac)));
        float rem = 1f - u;

        for (int i = 0; i < count; i++) {
            View page = activePages.get(i);
            if (i == baseIdx) {
                page.setVisibility(View.VISIBLE);
                page.setTranslationZ(0f);
                if (frac > 0f) {
                    page.setPivotX(dir > 0f ? w * 0.88f : w * 0.12f);
                    page.setPivotY(h);
                    page.setRotation(-dir * 3.8f * u);
                    float s = 1f - 0.045f * u;
                    page.setScaleX(s);
                    page.setScaleY(s);
                    page.setTranslationX(0f);
                    page.setTranslationY(0f);
                    page.setAlpha(1f - 0.22f * u);
                } else if (Math.abs(overscroll) > 0.0005f) {
                    page.setPivotX(w * 0.5f);
                    page.setPivotY(overscroll < 0f ? 0f : h);
                    page.setRotation(overscroll * 2.8f);
                    page.setTranslationX(0f);
                    page.setTranslationY(-overscroll * 14f * density);
                    float s = 1f - Math.abs(overscroll) * 0.02f;
                    page.setScaleX(s);
                    page.setScaleY(s);
                    page.setAlpha(1f);
                } else {
                    resetPageTransform(page);
                }
            } else if (i == nextIdx && nextIdx != baseIdx) {
                page.setVisibility(View.VISIBLE);
                page.setTranslationZ(8f * density);
                page.setPivotX(dir > 0f ? w * 0.08f : w * 0.92f);
                page.setPivotY(h);
                page.setRotation(dir * 13.5f * rem);
                page.setTranslationX(dir * 0.055f * w * rem);
                page.setTranslationY(h * (1f - frac));
                float s = 1f - 0.055f * rem;
                page.setScaleX(s);
                page.setScaleY(s);
                page.setAlpha(Math.min(1f, 0.35f + 0.65f * frac));
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

    private ScrollView findInnerScroller(View root, float deckX, float deckY) {
        if (!(root instanceof ViewGroup) || root.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (root instanceof ScrollView) {
            return isPointInsideView(root, deckX, deckY) ? (ScrollView) root : null;
        }
        ViewGroup vg = (ViewGroup) root;
        for (int i = vg.getChildCount() - 1; i >= 0; i--) {
            View child = vg.getChildAt(i);
            ScrollView found = findInnerScroller(child, deckX, deckY);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isPointInsideView(View v, float deckX, float deckY) {
        getLocationOnScreen(tmpLoc);
        float screenX = tmpLoc[0] + deckX;
        float screenY = tmpLoc[1] + deckY;
        v.getLocationOnScreen(tmpLoc);
        return screenX >= tmpLoc[0] && screenX <= tmpLoc[0] + v.getWidth()
                && screenY >= tmpLoc[1] && screenY <= tmpLoc[1] + v.getHeight();
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

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        cancelAnimations();
        recycleVelocityTracker();
        haptics.cancel();
        super.onDetachedFromWindow();
    }
}
