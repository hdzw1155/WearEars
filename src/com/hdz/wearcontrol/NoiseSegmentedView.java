package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 噪声控制: 一体式胶囊滑档器(与磁贴 segmented 同一套视觉).
 *
 * <p>交互:
 * <ul>
 *   <li>点一下: 选中被点的那一段, 滑块用弹簧滑过去(一次轻微回弹);</li>
 *   <li>按住左右拖: 滑块跟着手指走, 跨段时给一次触觉; 松手弹到最近的一段;</li>
 *   <li>没连过耳机(没有档位数据)时: 只画槽, 不画滑块, 三段全灰.</li>
 * </ul>
 * 纯 Java 8, 不引任何第三方库: 弹簧是手写的阻尼积分, 每帧由 Choreographer 推进.
 */
public class NoiseSegmentedView extends View {

    /** 三段顺序与磁贴一致: 关闭 / 通透 / 降噪 */
    private static final int[] MODES = {
            TwsClient.MODE_OFF, TwsClient.MODE_TRANS, TwsClient.MODE_ANC};

    /** 槽/滑块/未选中文字, 两套界面用 setPalette 切换 */
    private int colorTrough = 0xFF1E1E21;
    private int colorPill = 0xFF2B6CB0;
    private static final int COLOR_SELECTED = 0xFFFFFFFF;
    private int colorIdle = 0x99F2F2F5;

    private static final float PILL_INSET_DP = 2f;
    /** 选中块圆角: 与 DeepX chip / 磁贴 segmented 统一为 12dp */
    private static final float CHIP_RADIUS_DP = 12f;
    private static final float ICON_DP = 12f;
    private static final float ICON_GAP_DP = 2f;
    private static final float TEXT_SP = 10f;

    /** 弹簧: 阻尼比 0.5(轻微回弹), 刚度 1200(约 0.25s 落位) */
    private static final double SPRING_K = 1200.0;
    private static final double SPRING_DAMPING = 0.5;
    private static final double SPRING_STOP_V = 4.0;
    private static final double SPRING_STOP_X = 0.35;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF troughRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Drawable[] icons = new Drawable[3];
    private final String[] labels = new String[3];

    private float density;
    private float textSizePx;
    private float pillInsetPx;
    private float iconPx;
    private float iconGapPx;
    private float pillHeightPx;

    private int mode = -1;
    private float pillCenter;
    private double pillVelocity;
    private long lastFrameNanos;

    private boolean dragging;
    private boolean tracking;
    private int dragIndex;
    private float downX;
    private float downY;
    private float draggingCenter;
    private int touchSlop;
    private boolean framePosted;

    private OnModeChangeListener listener;

    public interface OnModeChangeListener {
        void onModeSelected(int mode);
    }

    private final Choreographer.FrameCallback springFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            double dt = lastFrameNanos == 0 ? 1.0 / 60.0
                    : Math.min(1.0 / 30.0, (frameTimeNanos - lastFrameNanos) / 1e9);
            lastFrameNanos = frameTimeNanos;
            float target = targetCenter();
            double accel = -SPRING_K * (pillCenter - target)
                    - 2.0 * SPRING_DAMPING * Math.sqrt(SPRING_K) * pillVelocity;
            pillVelocity += accel * dt;
            pillCenter += (float) (pillVelocity * dt);
            if (Math.abs(pillVelocity) < SPRING_STOP_V
                    && Math.abs(pillCenter - target) < SPRING_STOP_X) {
                pillCenter = target;
                pillVelocity = 0;
                lastFrameNanos = 0;
                invalidate();
                return;
            }
            invalidate();
            if (!framePosted) {
                framePosted = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public NoiseSegmentedView(Context context) {
        this(context, null);
    }

    public NoiseSegmentedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NoiseSegmentedView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = getResources().getDisplayMetrics().density;
        textSizePx = TEXT_SP * getResources().getDisplayMetrics().scaledDensity;
        pillInsetPx = PILL_INSET_DP * density;
        iconPx = ICON_DP * density;
        iconGapPx = ICON_GAP_DP * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        icons[0] = context.getResources().getDrawable(R.drawable.ic_off, null);
        icons[1] = context.getResources().getDrawable(R.drawable.ic_trans, null);
        icons[2] = context.getResources().getDrawable(R.drawable.ic_anc, null);
        labels[0] = context.getString(R.string.tile_mode_off);
        labels[1] = context.getString(R.string.tile_mode_trans);
        labels[2] = context.getString(R.string.tile_mode_anc);
        paint.setTextSize(textSizePx);
        setClickable(true);
    }

    /** 经典界面用: 深色凹槽 + vivo 蓝滑块 + 灰色未选中文字 */
    public void setPalette(int trough, int pill, int idle) {
        colorTrough = trough;
        colorPill = pill;
        colorIdle = idle;
        invalidate();
    }

    public void setOnModeChangeListener(OnModeChangeListener l) {
        listener = l;
    }

    /** 程序化设置档位(不触发回调); mode 传 -1 表示无数据 */
    public void setMode(int newMode, boolean animate) {
        int index = indexOfMode(newMode);
        if (index < 0) {
            mode = -1;
            stopSpring();
            pillVelocity = 0;
            invalidate();
            return;
        }
        if (index == indexOfMode(mode)) {
            mode = newMode;
            return;
        }
        mode = newMode;
        if (!animate || getWidth() == 0) {
            stopSpring();
            pillVelocity = 0;
            pillCenter = centerOf(index);
            invalidate();
            return;
        }
        startSpring();
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    private void stopSpring() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(springFrame);
            framePosted = false;
        }
        lastFrameNanos = 0;
    }

    private void startSpring() {
        if (framePosted) {
            return;
        }
        framePosted = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(springFrame);
    }

    private static int indexOfMode(int m) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i] == m) {
                return i;
            }
        }
        return -1;
    }

    private float segmentWidth() {
        return getWidth() / (float) MODES.length;
    }

    private float centerOf(int index) {
        if (getWidth() == 0) {
            return 0;
        }
        return segmentWidth() * (index + 0.5f);
    }

    private int indexAt(float x) {
        if (getWidth() == 0) {
            return 0;
        }
        int index = (int) (x / segmentWidth());
        if (index < 0) {
            index = 0;
        }
        if (index >= MODES.length) {
            index = MODES.length - 1;
        }
        return index;
    }

    private float clampPillCenter(float c) {
        if (getWidth() == 0) {
            return 0;
        }
        float half = (segmentWidth() - 2 * pillInsetPx) / 2f;
        float min = pillInsetPx + half;
        float max = getWidth() - pillInsetPx - half;
        return Math.max(min, Math.min(max, c));
    }

    private float targetCenter() {
        if (dragging) {
            return clampPillCenter(draggingCenter);
        }
        int index = indexOfMode(mode);
        return index < 0 ? 0 : centerOf(index);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        troughRect.set(0, 0, w, h);
        pillHeightPx = h - 2 * pillInsetPx;
        if (framePosted) {
            return;
        }
        int index = indexOfMode(mode);
        if (index >= 0) {
            pillCenter = centerOf(index);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = Math.min(getHeight() / 2f, CHIP_RADIUS_DP * density);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorTrough);
        canvas.drawRoundRect(troughRect, radius, radius, paint);

        int selected = indexOfMode(mode);
        float pillW = segmentWidth() - 2 * pillInsetPx;
        float pillRadius = Math.min(pillHeightPx / 2f, CHIP_RADIUS_DP * density);
        if ((selected >= 0 || dragging) && pillW > 0) {
            float left = clampPillCenter(pillCenter) - pillW / 2f;
            pillRect.set(left, pillInsetPx, left + pillW, getHeight() - pillInsetPx);
            paint.setColor(colorPill);
            canvas.drawRoundRect(pillRect, pillRadius, pillRadius, paint);
        }

        float segW = segmentWidth();
        for (int i = 0; i < MODES.length; i++) {
            boolean sel = i == selected;
            int color = sel ? COLOR_SELECTED : colorIdle;
            paint.setTextSize(textSizePx);
            paint.setColor(color);
            float labelW = paint.measureText(labels[i]);
            float contentW = iconPx + iconGapPx + labelW;
            float startX = segW * i + (segW - contentW) / 2f;
            float iconTop = (getHeight() - iconPx) / 2f;
            Drawable icon = icons[i];
            if (icon != null) {
                icon.setTint(color);
                icon.setBounds((int) startX, (int) iconTop,
                        (int) (startX + iconPx), (int) (iconTop + iconPx));
                icon.draw(canvas);
            }
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(labels[i], startX + iconPx + iconGapPx, baseline, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopSpring();
                pillVelocity = 0;
                downX = event.getX();
                downY = event.getY();
                tracking = true;
                dragging = false;
                dragIndex = indexAt(downX);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !dragging
                        && (Math.abs(event.getX() - downX) > touchSlop * 1.5f
                        || Math.abs(event.getY() - downY) > touchSlop * 1.5f)) {
                    dragging = true;
                    draggingCenter = clampPillCenter(centerOf(indexAt(downX)));
                    pillCenter = draggingCenter;
                }
                if (dragging) {
                    float target = clampPillCenter(event.getX());
                    int idx = indexAt(event.getX());
                    if (idx != dragIndex) {
                        dragIndex = idx;
                        performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                    }
                    draggingCenter = target;
                    pillCenter += (draggingCenter - pillCenter) * 0.85f;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int index = indexAt(event.getX());
                int newMode = MODES[index];
                boolean changed = newMode != mode;
                dragging = false;
                tracking = false;
                mode = newMode;
                if (changed) {
                    performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                }
                startSpring();
                invalidate();
                if (changed && listener != null) {
                    listener.onModeSelected(newMode);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                tracking = false;
                startSpring();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSpring();
    }
}
