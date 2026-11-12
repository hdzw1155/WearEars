package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Custom Constructivist Control Poster ("02 / 控制") matching Reference Image #2.
 * Features skewed crimson Settings slab with hard black shadow, obsidian/gold Dev Lab slab,
 * gear & code emblems, WearEars header lockup, and 3-color corner micro squares — all strictly
 * inscribed within r <= 0.425 D so nothing clips on round Watch displays.
 */
public class SuprematistControlView extends View {

    private static final int COL_INK = 0xFF111317;
    private static final int COL_WHITE = 0xFFFFFFFF;
    private static final int COL_RED = 0xFFDF1B22;
    private static final int COL_BLUE = 0xFF0D4FB8;
    private static final int COL_YELLOW = 0xFFF6C100;
    private static final long TAP_RESET_MS = 1200L;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint italicPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path slabPath = new Path();
    private final RectF btnSettingsRect = new RectF();
    private final RectF btnDevRect = new RectF();

    private float density;
    private int touchSlop;
    private boolean useEnglish;
    private boolean devUnlocked;
    private int devTaps = 0;
    private long lastTapAt = 0L;
    private int pressedHit = 0; // 1=settings, 2=dev
    private float downX;
    private float downY;

    private OnControlActionListener listener;

    public interface OnControlActionListener {
        void onOpenSettings();

        void onDevUnlocked();

        void onJumpToDev();

        void onDevTick();
    }

    public SuprematistControlView(Context context) {
        this(context, null);
    }

    public SuprematistControlView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SuprematistControlView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        useEnglish = AppPrefs.LANG_EN.equals(AppPrefs.lang(context));

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        italicPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
    }

    public void setOnControlActionListener(OnControlActionListener l) {
        this.listener = l;
    }

    public void setLanguageEnglish(boolean en) {
        if (useEnglish != en) {
            useEnglish = en;
            invalidate();
        }
    }

    public void setDevUnlocked(boolean unlocked) {
        if (devUnlocked != unlocked) {
            devUnlocked = unlocked;
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
        boolean round = getResources().getConfiguration().isScreenRound();
        float d = round ? Math.min(w, h) : Math.min(w * 1.04f, h * 0.80f);
        float ox = round ? (w - d) * 0.5f : (w - d) * 0.5f + 8f * density;
        float oy = (h - d) * 0.5f;

        // 1) Top-left enlarged WearEars brand lockup matching Reference Image #2
        float bx = ox + 0.235f * d;
        float by = oy + 0.148f * d;
        float bw = 0.044f * d;
        float bh = 0.040f * d;
        fillPaint.setColor(COL_RED);
        setSkewRect(bx, by, bx + bw, by + bh, 0.014f * d);
        canvas.drawPath(slabPath, fillPaint);

        italicPaint.setColor(COL_INK);
        italicPaint.setTextAlign(Paint.Align.LEFT);
        italicPaint.setTextSize(0.058f * d);
        canvas.drawText("WearEars", bx + bw + 0.015f * d, oy + 0.184f * d, italicPaint);

        textPaint.setColor(0xFF454A54);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(0.022f * d);
        canvas.drawText("CROSS-BRAND TWS CONTROL", bx, oy + 0.214f * d, textPaint);

        fillPaint.setColor(COL_INK);
        canvas.drawRect(bx, oy + 0.229f * d, bx + 0.100f * d, oy + 0.235f * d, fillPaint);

        // 2) Enlarged section heading: vertical gold bar + "02 / 控制" + "CONTROL"
        float hx = ox + 0.145f * d;
        fillPaint.setColor(COL_YELLOW);
        setSkewRect(hx, oy + 0.266f * d, hx + 0.023f * d, oy + 0.342f * d, 0.009f * d);
        canvas.drawPath(slabPath, fillPaint);

        italicPaint.setColor(COL_INK);
        italicPaint.setTextSize(0.070f * d);
        canvas.drawText(useEnglish ? "02 / CTRL" : "02 / \u63a7\u5236",
                hx + 0.038f * d, oy + 0.328f * d, italicPaint);

        textPaint.setColor(0xFF555B66);
        textPaint.setTextSize(0.025f * d);
        canvas.drawText("CONTROL", hx + 0.038f * d, oy + 0.364f * d, textPaint);

        // 3) Enlarged crimson skewed Settings slab + hard black offset shadow
        float sLeft = ox + 0.112f * d;
        float sRight = ox + 0.835f * d;
        float sTop = oy + 0.408f * d;
        float sBot = oy + 0.532f * d;
        float skew = 0.038f * d;
        btnSettingsRect.set(sLeft, sTop, sRight, sBot);

        fillPaint.setColor(COL_INK);
        setSkewRect(sLeft + 0.018f * d, sTop + 0.016f * d,
                sRight + 0.018f * d, sBot + 0.016f * d, skew);
        canvas.drawPath(slabPath, fillPaint);

        fillPaint.setColor(pressedHit == 1 ? 0xFFB8141A : COL_RED);
        setSkewRect(sLeft, sTop, sRight, sBot, skew);
        canvas.drawPath(slabPath, fillPaint);

        drawGearGlyph(canvas, sLeft + 0.086f * d, (sTop + sBot) * 0.5f, 0.030f * d, COL_WHITE);
        strokePaint.setColor(0x88FFFFFF);
        strokePaint.setStrokeWidth(Math.max(1f, 1.2f * density));
        canvas.drawLine(sLeft + 0.136f * d, sBot - 0.022f * d,
                sLeft + 0.158f * d, sTop + 0.022f * d, strokePaint);

        textPaint.setColor(COL_WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(0.043f * d);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        String setLabel = useEnglish ? "Settings / Style \u2192"
                : "\u6253\u5f00\u8bbe\u7f6e \u00b7 \u98ce\u683c/\u8bbe\u5907 \u2192";
        canvas.drawText(setLabel, sLeft + 0.180f * d,
                (sTop + sBot) * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);

        // 4) Enlarged obsidian skewed Dev Lab slab + gold label + "</>" glyph
        float dLeft = ox + 0.114f * d;
        float dRight = ox + 0.745f * d;
        float dTop = oy + 0.580f * d;
        float dBot = oy + 0.692f * d;
        btnDevRect.set(dLeft, dTop, dRight, dBot);

        fillPaint.setColor(pressedHit == 2 ? 0xFF282D36 : COL_INK);
        setSkewRect(dLeft, dTop, dRight, dBot, skew);
        canvas.drawPath(slabPath, fillPaint);

        textPaint.setColor(COL_WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(0.035f * d);
        fm = textPaint.getFontMetrics();
        canvas.drawText("</>", dLeft + 0.082f * d,
                (dTop + dBot) * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);

        strokePaint.setColor(0x66FFFFFF);
        strokePaint.setStrokeWidth(Math.max(1f, 1.2f * density));
        canvas.drawLine(dLeft + 0.134f * d, dBot - 0.020f * d,
                dLeft + 0.154f * d, dTop + 0.020f * d, strokePaint);

        textPaint.setColor(COL_YELLOW);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(0.042f * d);
        fm = textPaint.getFontMetrics();
        String devLabel = devUnlocked
                ? (useEnglish ? "Dev Sheet Ready \u2192" : "\u5b9e\u9a8c\u9875\u5df2\u89e3\u9501 \u2192")
                : (useEnglish ? "Developer Lab" : "\u5f00\u53d1\u8005\u8bbe\u7f6e");
        canvas.drawText(devLabel, dLeft + 0.176f * d,
                (dTop + dBot) * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);

        // 5) Bottom-left signature 3 colored micro-squares matching Reference Image #2
        float sqY = oy + 0.785f * d;
        float sqSize = 0.021f * d;
        float sqStep = 0.034f * d;
        float sqStart = ox + 0.250f * d;
        int[] cols = {COL_RED, COL_BLUE, COL_YELLOW};
        for (int i = 0; i < 3; i++) {
            fillPaint.setColor(cols[i]);
            float sx = sqStart + i * sqStep;
            canvas.drawRect(sx, sqY, sx + sqSize, sqY + sqSize, fillPaint);
        }
    }

    private void drawGearGlyph(Canvas canvas, float cx, float cy, float r, int color) {
        fillPaint.setColor(color);
        canvas.drawCircle(cx, cy, r * 0.58f, fillPaint);
        for (int i = 0; i < 8; i++) {
            double ang = i * (Math.PI / 4.0);
            float tx = cx + (float) (r * 0.78f * Math.cos(ang));
            float ty = cy + (float) (r * 0.78f * Math.sin(ang));
            canvas.drawCircle(tx, ty, r * 0.24f, fillPaint);
        }
        fillPaint.setColor(COL_RED);
        canvas.drawCircle(cx, cy, r * 0.26f, fillPaint);
    }

    private void setSkewRect(float left, float top, float right, float bottom, float skew) {
        slabPath.reset();
        slabPath.moveTo(left + skew, top);
        slabPath.lineTo(right, top);
        slabPath.lineTo(right - skew, bottom);
        slabPath.lineTo(left, bottom);
        slabPath.close();
    }

    private int hitTest(float x, float y) {
        float pad = 6f * density;
        if (x >= btnSettingsRect.left - pad && x <= btnSettingsRect.right + pad
                && y >= btnSettingsRect.top - pad && y <= btnSettingsRect.bottom + pad) {
            return 1;
        }
        if (x >= btnDevRect.left - pad && x <= btnDevRect.right + pad
                && y >= btnDevRect.top - pad && y <= btnDevRect.bottom + pad) {
            return 2;
        }
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                pressedHit = hitTest(x, y);
                if (pressedHit == 0) {
                    return false;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pressedHit != 0 && Math.hypot(x - downX, y - downY) > touchSlop) {
                    pressedHit = 0;
                    invalidate();
                    return false;
                }
                return pressedHit != 0;

            case MotionEvent.ACTION_UP:
                int hit = pressedHit;
                pressedHit = 0;
                invalidate();
                if (hit == 1 && hitTest(x, y) == 1) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) {
                        listener.onOpenSettings();
                    }
                } else if (hit == 2 && hitTest(x, y) == 2) {
                    handleDevTap();
                }
                return hit != 0;

            case MotionEvent.ACTION_CANCEL:
                pressedHit = 0;
                invalidate();
                return false;
        }
        return false;
    }

    private void handleDevTap() {
        if (devUnlocked) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) {
                listener.onJumpToDev();
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        devTaps = (now - lastTapAt > TAP_RESET_MS) ? 1 : devTaps + 1;
        lastTapAt = now;
        if (devTaps >= 5) {
            devUnlocked = true;
            invalidate();
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (listener != null) {
                listener.onDevUnlocked();
            }
        } else {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) {
                listener.onDevTick();
            }
        }
    }
}
