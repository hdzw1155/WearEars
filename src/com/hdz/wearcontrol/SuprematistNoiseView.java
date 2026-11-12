package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Suprematist x P5R 3-way ANC switcher ("降噪 / 关闭 / 通透") designed to always remain visible
 * and full-fit inside Sheet 1 of Supreme UI without disappearing or needing internal scrolling.
 */
public class SuprematistNoiseView extends View {

    /** Matches protocol mode ordering */
    private static final int[] MODES = {
            TwsClient.MODE_ANC, TwsClient.MODE_OFF, TwsClient.MODE_TRANS
    };
    private static final float[] ROW_ROTATIONS = { -3.4f, 2.6f, -2.1f };
    private static final int[] ACTIVE_FILLS = { 0xFFE5232A, 0xFFF3F1E8, 0xFFF4BA00 };
    private static final int[] ACTIVE_TEXTS = { 0xFFF7F5EE, 0xFF101318, 0xFF101318 };

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path slabPath = new Path();
    private final Drawable[] icons = new Drawable[3];
    private final String[] labels = new String[3];

    private int mode = -1;
    private boolean capabilityReady = true;
    private boolean connected = false;
    private int pressedIdx = -1;
    private float downX;
    private float downY;
    private float density;
    private int touchSlop;

    private OnModeActionListener listener;

    public interface OnModeActionListener {
        void onModeSelected(int mode);

        void onReconnectRequested();
    }

    public SuprematistNoiseView(Context context) {
        this(context, null);
    }

    public SuprematistNoiseView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SuprematistNoiseView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.LEFT);

        labels[0] = context.getString(R.string.btn_anc);
        labels[1] = context.getString(R.string.btn_off);
        labels[2] = context.getString(R.string.btn_trans);

        icons[0] = loadDrawable(context, R.drawable.ic_anc);
        icons[1] = loadDrawable(context, R.drawable.ic_off);
        icons[2] = loadDrawable(context, R.drawable.ic_trans);
    }

    private static Drawable loadDrawable(Context ctx, int resId) {
        Drawable raw = ctx.getDrawable(resId);
        return raw != null ? raw.mutate() : null;
    }

    public void setOnModeActionListener(OnModeActionListener l) {
        this.listener = l;
    }

    public void setState(int selectedMode, boolean isConnected, boolean hasNoiseCap) {
        if (this.mode != selectedMode || this.connected != isConnected
                || this.capabilityReady != hasNoiseCap) {
            this.mode = selectedMode;
            this.connected = isConnected;
            this.capabilityReady = hasNoiseCap;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float rowStep = h / 3f;
        float slabW = w - 18f * density;
        float slabH = Math.max(22f * density, rowStep - 7f * density);
        float skew = 6.5f * density;
        float leftInset = 9f * density;

        for (int i = 0; i < 3; i++) {
            float cy = rowStep * (i + 0.5f);
            float cx = w * 0.5f + ((i == 1) ? 4f * density : -2f * density);
            boolean active = (mode == MODES[i]) && connected && capabilityReady;
            boolean pressed = (pressedIdx == i);
            boolean dimmed = !connected || !capabilityReady;

            canvas.save();
            canvas.rotate(ROW_ROTATIONS[i], cx, cy);

            float x0 = leftInset + ((i == 0) ? 0f : (i == 1 ? 6f * density : 2f * density));
            float x1 = x0 + slabW - ((i == 1) ? 6f * density : 2f * density);
            float top = cy - slabH * 0.5f;
            float bot = cy + slabH * 0.5f;

            // Offset hard P5R drop shadow
            fillPaint.setColor(active ? 0xFFF4BA00 : 0xFF06070A);
            setSkewSlab(x0 + 2.2f * density, top + 2.4f * density,
                    x1 + 2.2f * density, bot + 2.4f * density, skew);
            canvas.drawPath(slabPath, fillPaint);

            // Body polygon
            int fillCol;
            if (active) {
                fillCol = ACTIVE_FILLS[i];
            } else if (pressed) {
                fillCol = 0xFF2B3240;
            } else {
                fillCol = dimmed ? 0xFF151921 : 0xFF1C212C;
            }
            fillPaint.setColor(fillCol);
            setSkewSlab(x0, top, x1, bot, skew);
            canvas.drawPath(slabPath, fillPaint);

            // High-contrast ink or accent border
            strokePaint.setColor(active ? 0xFF101318 : (dimmed ? 0x55F3F1E8 : 0xCCFFFFFF));
            strokePaint.setStrokeWidth((active ? 2.2f : 1.2f) * density);
            canvas.drawPath(slabPath, strokePaint);

            // Active corner diamond stamp inside slab
            if (active) {
                fillPaint.setColor(i == 0 ? 0xFFF4BA00 : 0xFFE5232A);
                float sx = x1 - 15f * density;
                canvas.save();
                canvas.rotate(45f, sx, cy);
                float sHalf = 4.2f * density;
                canvas.drawRect(sx - sHalf, cy - sHalf, sx + sHalf, cy + sHalf, fillPaint);
                canvas.restore();
            }

            // Mode icon + bold crisp label
            int fg = active ? ACTIVE_TEXTS[i] : (dimmed ? 0x88CFD6E3 : 0xFFF3F1E8);
            Drawable icon = icons[i];
            int iconSize = Math.round(14f * density);
            int iconLeft = Math.round(x0 + 16f * density);
            int iconTop = Math.round(cy - iconSize * 0.5f);
            if (icon != null) {
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                icon.setTint(fg);
                icon.draw(canvas);
            }

            textPaint.setColor(fg);
            textPaint.setTextSize(12.2f * density);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float ty = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(labels[i], iconLeft + iconSize + 8f * density, ty, textPaint);

            canvas.restore();
        }
    }

    private void setSkewSlab(float left, float top, float right, float bottom, float skew) {
        slabPath.reset();
        slabPath.moveTo(left + skew, top);
        slabPath.lineTo(right, top);
        slabPath.lineTo(right - skew, bottom);
        slabPath.lineTo(left, bottom);
        slabPath.close();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        int idx = rowIndexAt(y);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                pressedIdx = idx;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pressedIdx >= 0 && Math.hypot(x - downX, y - downY) > touchSlop) {
                    pressedIdx = -1;
                    invalidate();
                    return false;
                }
                return true;

            case MotionEvent.ACTION_UP:
                int hit = pressedIdx;
                pressedIdx = -1;
                invalidate();
                if (hit >= 0 && hit == idx) {
                    if (!connected) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (listener != null) {
                            listener.onReconnectRequested();
                        }
                    } else if (capabilityReady) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (listener != null) {
                            listener.onModeSelected(MODES[hit]);
                        }
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.REJECT);
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressedIdx = -1;
                invalidate();
                return false;
        }
        return false;
    }

    private int rowIndexAt(float y) {
        int h = getHeight();
        if (h <= 0) {
            return -1;
        }
        int idx = (int) (y / (h / 3f));
        return Math.max(0, Math.min(2, idx));
    }
}
