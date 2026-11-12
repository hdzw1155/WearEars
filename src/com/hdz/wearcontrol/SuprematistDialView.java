package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * Suprematist x P5R main circular dial replacing flat horizontal cards.
 *
 * <ul>
 *   <li>3 slanted dashed/segmented battery tracks (Left / Right / Case, or 1 hero diagonal track
 *       for single-battery devices) with ZERO percentage digits and varied vivid P5R colors;</li>
 *   <li>4th lower-right slanted SOLID Volume track with deliberate anti-accidental-touch guard:
 *       locked by default so casual drags/swipes always turn pages cleanly; long-press (~260ms)
 *       or double-tap unlocks volume adjustment (drag along ribbon or +/- paddles) for 4.0s.</li>
 * </ul>
 */
public class SuprematistDialView extends View {

    private static final int DASH_COUNT = 7;
    private static final long UNLOCK_HOLD_MS = 250L;
    private static final long AUTO_LOCK_MS = 4000L;
    private static final long DOUBLE_TAP_MS = 320L;

    /** Varied, intentionally clashing Suprematist / P5R palettes per battery track */
    private static final int[] COLORS_LEFT = {
            0xFFE5232A, 0xFFFF4938, 0xFFE5232A, 0xFFC81A22, 0xFFE5232A, 0xFFFF4938, 0xFF1A54E5
    };
    private static final int[] COLORS_RIGHT = {
            0xFF1A54E5, 0xFF0094FF, 0xFF1A54E5, 0xFF286CF2, 0xFF1A54E5, 0xFF0094FF, 0xFFF4BA00
    };
    private static final int[] COLORS_CASE = {
            0xFFF4BA00, 0xFFFF8800, 0xFFF4BA00, 0xFF00B489, 0xFFF4BA00, 0xFFFF8800, 0xFFE5232A
    };
    private static final int[] COLORS_SINGLE = {
            0xFFE5232A, 0xFFF4BA00, 0xFF1A54E5, 0xFFE5232A, 0xFFF4BA00, 0xFF1A54E5, 0xFF101318
    };

    private static final int COL_INK = 0xFF101318;
    private static final int COL_PAPER = 0xFFF7F5EE;
    private static final int COL_VOL_RED = 0xFFE5232A;
    private static final int COL_VOL_YELLOW = 0xFFF4BA00;
    private static final int COL_VOL_LOCKED_FILL = 0xFF1F2735;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path workPath = new Path();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int battL = -1;
    private int battR = -1;
    private int battC = -1;
    private boolean chgL;
    private boolean chgR;
    private boolean chgC;
    private boolean singleBattery;

    private int volLevel = 5;
    private int volMax = 15;
    private boolean volUnlocked;
    private long lockPulseUntil;

    private float density;
    private int touchSlop;
    private boolean useEnglish;

    // Volume geometry (updated in onSizeChanged / onDraw prep)
    private float vStartX;
    private float vStartY;
    private float vEndX;
    private float vEndY;
    private float vUnitX;
    private float vUnitY;
    private float vLen;
    private float vNodeR;
    private float vMinusX;
    private float vMinusY;
    private float vPlusX;
    private float vPlusY;
    private float vBtnR;

    // Touch / anti-accidental-touch state machine
    private boolean trackingVolZone;
    private boolean draggingVolBar;
    private boolean pressedMinus;
    private boolean pressedPlus;
    private boolean pendingHoldUnlock;
    private float downX;
    private float downY;
    private long lastTapOnVolAt;
    private int lastSentVol = -1;

    private OnVolumeControlListener volumeListener;

    public interface OnVolumeControlListener {
        void onVolumeChanged(int level, int max);

        void onVolumeGuardHint(boolean unlocked);
    }

    private final Runnable holdUnlockTask = new Runnable() {
        @Override
        public void run() {
            if (trackingVolZone && pendingHoldUnlock && !volUnlocked) {
                pendingHoldUnlock = false;
                setVolumeUnlocked(true, true);
                draggingVolBar = true;
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            }
        }
    };

    private final Runnable autoLockTask = new Runnable() {
        @Override
        public void run() {
            if (volUnlocked) {
                setVolumeUnlocked(false, false);
            }
        }
    };

    public SuprematistDialView(Context context) {
        this(context, null);
    }

    public SuprematistDialView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SuprematistDialView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        useEnglish = AppPrefs.LANG_EN.equals(AppPrefs.lang(context));

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        hintPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnVolumeControlListener(OnVolumeControlListener l) {
        this.volumeListener = l;
    }

    public void setLanguageEnglish(boolean en) {
        if (useEnglish != en) {
            useEnglish = en;
            invalidate();
        }
    }

    public void setBattery(int left, int right, int box, boolean chargeLeft, boolean chargeRight,
                           boolean chargeBox, boolean single) {
        this.battL = left;
        this.battR = right;
        this.battC = box;
        this.chgL = chargeLeft;
        this.chgR = chargeRight;
        this.chgC = chargeBox;
        this.singleBattery = single;
        invalidate();
    }

    public void setSingleBattery(int total, boolean charging) {
        this.battL = total;
        this.chgL = charging;
        this.singleBattery = true;
        invalidate();
    }

    public void setVolume(int level, int max) {
        int safeMax = Math.max(1, max);
        int safeLevel = Math.max(0, Math.min(safeMax, level));
        if (this.volLevel != safeLevel || this.volMax != safeMax) {
            this.volLevel = safeLevel;
            this.volMax = safeMax;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateVolumeGeometry(w, h);
    }

    private void updateVolumeGeometry(int w, int h) {
        float d = Math.min(w, h);
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        // Bottom-right diagonal track matching hand sketch ("音" badge + solid slant bar)
        vStartX = cx + 0.070f * d;
        vStartY = cy + 0.305f * d;
        vEndX = cx + 0.335f * d;
        vEndY = cy + 0.045f * d;

        float dx = vEndX - vStartX;
        float dy = vEndY - vStartY;
        vLen = Math.max(1f, (float) Math.hypot(dx, dy));
        vUnitX = dx / vLen;
        vUnitY = dy / vLen;
        vNodeR = 11.6f * density;
        vBtnR = 10.5f * density;

        // Flanking - / + paddles tucked cleanly on outer lower-right flank (zero overlap with 盒)
        float outPerpX = -vUnitY;
        float outPerpY = vUnitX;
        vMinusX = vStartX + vUnitX * (vNodeR + 12f * density) + outPerpX * (17f * density);
        vMinusY = vStartY + vUnitY * (vNodeR + 12f * density) + outPerpY * (17f * density);
        vPlusX = vEndX - vUnitX * (6f * density) + outPerpX * (17f * density);
        vPlusY = vEndY - vUnitY * (6f * density) + outPerpY * (17f * density);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        updateVolumeGeometry(w, h);
        float d = Math.min(w, h);
        float cx = w * 0.5f;
        float cy = h * 0.5f;

        // Ordered-chaos background micro drafting lines bridging diagonals
        strokePaint.setColor(0x1E101318);
        strokePaint.setStrokeWidth(Math.max(1f, 1.0f * density));
        canvas.drawLine(cx - 0.32f * d, cy + 0.14f * d, cx + 0.24f * d, cy - 0.28f * d, strokePaint);
        canvas.drawLine(cx - 0.04f * d, cy + 0.36f * d, cx + 0.36f * d, cy + 0.02f * d, strokePaint);

        // Slanted dashed battery tracks ("虚线为电量条，不显数字，颜色不一致，杂乱")
        if (singleBattery) {
            drawBatteryTrack(canvas,
                    cx - 0.22f * d, cy + 0.16f * d,
                    cx + 0.14f * d, cy - 0.14f * d,
                    12.4f * density, 7.0f * density,
                    useEnglish ? "A" : "\u603b",
                    battL, chgL, COLORS_SINGLE, 0xFFE5232A);
        } else {
            // Track 1: Left ("左") — steep Crimson slash (cleared cleanly below subtitle)
            drawBatteryTrack(canvas,
                    cx - 0.245f * d, cy + 0.050f * d,
                    cx + 0.045f * d, cy - 0.175f * d,
                    11.0f * density, 5.2f * density,
                    useEnglish ? "L" : "\u5de6",
                    battL, chgL, COLORS_LEFT, 0xFFE5232A);

            // Track 2: Right ("右") — staggered Electric Cobalt slash
            drawBatteryTrack(canvas,
                    cx - 0.195f * d, cy + 0.190f * d,
                    cx + 0.120f * d, cy - 0.075f * d,
                    11.0f * density, 5.2f * density,
                    useEnglish ? "R" : "\u53f3",
                    battR, chgR, COLORS_RIGHT, 0xFF1A54E5);

            // Track 3: Case ("盒") — lower-angle Cadmium Gold slash
            drawBatteryTrack(canvas,
                    cx - 0.105f * d, cy + 0.315f * d,
                    cx + 0.235f * d, cy + 0.060f * d,
                    11.0f * density, 5.2f * density,
                    useEnglish ? "C" : "\u76d2",
                    battC, chgC, COLORS_CASE, 0xFFF4BA00);
        }

        // Decorative floating Suprematist shards in empty gaps (kept clear of subtitle band)
        fillPaint.setColor(0xFFE5232A);
        drawTiltedFragment(canvas, cx + 0.20f * d, cy - 0.21f * d, 5.0f * density, 2.2f * density, -42f);
        fillPaint.setColor(0xFF1A54E5);
        drawTiltedFragment(canvas, cx + 0.23f * d, cy - 0.09f * d, 4.4f * density, 4.4f * density, 28f);
        fillPaint.setColor(0xFF101318);
        drawTiltedFragment(canvas, cx - 0.24f * d, cy + 0.28f * d, 6.0f * density, 1.9f * density, -36f);

        // 4th Track: Solid Volume Bar ("音", Anti-Accidental-Touch Guard)
        drawVolumeTrack(canvas);
    }

    private void drawBatteryTrack(Canvas canvas,
                                  float sx, float sy, float ex, float ey,
                                  float nodeRadius, float dashThickness,
                                  String badgeLabel, int pct, boolean charging,
                                  int[] palette, int nodeAccent) {
        float dx = ex - sx;
        float dy = ey - sy;
        float totalLen = Math.max(1f, (float) Math.hypot(dx, dy));
        float ux = dx / totalLen;
        float uy = dy / totalLen;

        // Start dashes cleanly clear of circular letter node
        float trackStart = nodeRadius + 5.5f * density;
        float usableLen = Math.max(12f * density, totalLen - trackStart);
        float gap = 3.6f * density;
        float dashLen = (usableLen - gap * (DASH_COUNT - 1)) / DASH_COUNT;
        float halfH = dashThickness * 0.5f;
        float skew = 2.6f * density; // P5R parallelogram slant along track tangent

        boolean known = pct >= 0 && pct <= 100;
        boolean low = known && pct <= 20 && !charging;
        float filledUnits = known ? (pct / 100f) * DASH_COUNT : 0f;

        for (int i = 0; i < DASH_COUNT; i++) {
            float d0 = trackStart + i * (dashLen + gap);
            float d1 = d0 + dashLen;
            float unitFill = known ? Math.max(0f, Math.min(1f, filledUnits - i)) : 0f;

            // 1) Crisp hard offset ink shadow for P5R depth
            if (unitFill > 0.02f) {
                fillPaint.setColor(0xFF101318);
                buildParallelogram(sx, sy, ux, uy, d0 + 1.3f * density, d1 + 1.3f * density,
                        halfH, skew, 1.6f * density);
                canvas.drawPath(workPath, fillPaint);
            }

            // 2) Hollow paper/wireframe trough for each dash
            fillPaint.setColor(known ? 0xFFE6E2D5 : 0x33101318);
            buildParallelogram(sx, sy, ux, uy, d0, d1, halfH, skew, 0f);
            canvas.drawPath(workPath, fillPaint);

            // 3) Vivid chaotic colored fill up to exact analog ratio (still ZERO digits)
            if (unitFill > 0.01f) {
                float fillEnd = d0 + dashLen * unitFill;
                fillPaint.setColor(low ? 0xFFE5232A : palette[i % palette.length]);
                buildParallelogram(sx, sy, ux, uy, d0, fillEnd, halfH, skew, 0f);
                canvas.drawPath(workPath, fillPaint);
            }

            // 4) Stark ink outline around each dash block
            strokePaint.setColor(known ? COL_INK : 0x80101318);
            strokePaint.setStrokeWidth(Math.max(1f, (known ? 1.3f : 1.0f) * density));
            buildParallelogram(sx, sy, ux, uy, d0, d1, halfH, skew, 0f);
            canvas.drawPath(workPath, strokePaint);
        }

        // Circular Suprematist letter stamp ("左" / "右" / "盒") with hard offset shadow
        fillPaint.setColor(COL_INK);
        canvas.drawCircle(sx + 1.5f * density, sy + 1.8f * density, nodeRadius, fillPaint);

        int badgeBg = !known ? 0xFFDDD8C8 : (low ? 0xFFE5232A : nodeAccent);
        fillPaint.setColor(badgeBg);
        canvas.drawCircle(sx, sy, nodeRadius, fillPaint);

        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.8f * density);
        canvas.drawCircle(sx, sy, nodeRadius, strokePaint);

        boolean darkBadge = (badgeBg == 0xFF1A54E5 || badgeBg == 0xFFE5232A);
        textPaint.setColor(darkBadge ? COL_PAPER : COL_INK);
        textPaint.setTextSize(10.8f * density);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = sy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(badgeLabel, sx, textY, textPaint);

        // Charging jagged lightning icon attached at top-left shoulder of node
        if (charging && known) {
            drawBolt(canvas, sx - nodeRadius * 0.78f, sy - nodeRadius * 0.78f, 5.8f * density);
        }
    }

    private void drawVolumeTrack(Canvas canvas) {
        float trackStart = vNodeR + 5.5f * density;
        float trackLen = Math.max(14f * density, vLen - trackStart);
        float halfH = (volUnlocked ? 4.4f : 3.2f) * density;
        float skew = 3.0f * density;
        float ratio = volMax > 0 ? Math.max(0f, Math.min(1f, volLevel / (float) volMax)) : 0f;
        boolean pulsing = System.currentTimeMillis() < lockPulseUntil;

        // Hard offset shadow beneath solid bar
        fillPaint.setColor(COL_INK);
        buildParallelogram(vStartX, vStartY, vUnitX, vUnitY,
                trackStart + 1.5f * density, vLen + 1.5f * density, halfH, skew, 1.8f * density);
        canvas.drawPath(workPath, fillPaint);

        // Solid background channel
        fillPaint.setColor(volUnlocked ? 0xFF101318 : 0xFFE4E0D4);
        buildParallelogram(vStartX, vStartY, vUnitX, vUnitY, trackStart, vLen, halfH, skew, 0f);
        canvas.drawPath(workPath, fillPaint);

        // Solid continuous fill ribbon (distinct from dashed battery!)
        if (ratio > 0.01f) {
            float fillEnd = trackStart + trackLen * ratio;
            fillPaint.setColor(volUnlocked ? COL_VOL_RED : COL_VOL_LOCKED_FILL);
            buildParallelogram(vStartX, vStartY, vUnitX, vUnitY, trackStart, fillEnd, halfH, skew, 0f);
            canvas.drawPath(workPath, fillPaint);
        }

        // Bold border (yellow glow when unlocked or guard-pulsed)
        strokePaint.setColor(volUnlocked ? COL_VOL_YELLOW : (pulsing ? COL_VOL_RED : COL_INK));
        strokePaint.setStrokeWidth((volUnlocked || pulsing ? 2.0f : 1.5f) * density);
        buildParallelogram(vStartX, vStartY, vUnitX, vUnitY, trackStart, vLen, halfH, skew, 0f);
        canvas.drawPath(workPath, strokePaint);

        // If locked, draw diagonal safety guard ticks over solid bar to signal "防误触"
        if (!volUnlocked) {
            strokePaint.setColor(pulsing ? COL_VOL_RED : 0x55101318);
            strokePaint.setStrokeWidth(1.2f * density);
            float perpX = -vUnitY;
            float perpY = vUnitX;
            for (int k = 1; k <= 3; k++) {
                float d = trackStart + trackLen * (k * 0.25f);
                float mx = vStartX + vUnitX * d;
                float my = vStartY + vUnitY * d;
                canvas.drawLine(mx - perpX * (halfH + 1.5f * density) - vUnitX * 2f * density,
                        my - perpY * (halfH + 1.5f * density) - vUnitY * 2f * density,
                        mx + perpX * (halfH + 1.5f * density) + vUnitX * 2f * density,
                        my + perpY * (halfH + 1.5f * density) + vUnitY * 2f * density,
                        strokePaint);
            }
        } else {
            // Crisp sliding diamond knob on active solid track when unlocked
            float kx = vStartX + vUnitX * (trackStart + trackLen * ratio);
            float ky = vStartY + vUnitY * (trackStart + trackLen * ratio);
            fillPaint.setColor(COL_VOL_YELLOW);
            drawTiltedFragment(canvas, kx, ky, 5.2f * density, 5.2f * density, 45f);
            strokePaint.setColor(COL_INK);
            strokePaint.setStrokeWidth(1.4f * density);
            canvas.drawCircle(kx, ky, 5.4f * density, strokePaint);

            // Render safe - / + step paddles so users can fine-tune without accidental swipes
            drawStepButton(canvas, vMinusX, vMinusY, "-", pressedMinus);
            drawStepButton(canvas, vPlusX, vPlusY, "+", pressedPlus);
        }

        // Circular "音" stamp + shield/lock micro-emblem
        if (volUnlocked || pulsing) {
            strokePaint.setColor(volUnlocked ? COL_VOL_YELLOW : COL_VOL_RED);
            strokePaint.setStrokeWidth(1.8f * density);
            canvas.drawCircle(vStartX, vStartY, vNodeR + 3.4f * density, strokePaint);
        }
        fillPaint.setColor(COL_INK);
        canvas.drawCircle(vStartX + 1.4f * density, vStartY + 1.6f * density, vNodeR, fillPaint);

        fillPaint.setColor(volUnlocked ? COL_VOL_RED : 0xFF101318);
        canvas.drawCircle(vStartX, vStartY, vNodeR, fillPaint);

        strokePaint.setColor(volUnlocked ? COL_VOL_YELLOW : COL_PAPER);
        strokePaint.setStrokeWidth(1.5f * density);
        canvas.drawCircle(vStartX, vStartY, vNodeR, strokePaint);

        textPaint.setColor(COL_PAPER);
        textPaint.setTextSize(10.5f * density);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(useEnglish ? "V" : "\u97f3", vStartX,
                vStartY - (fm.ascent + fm.descent) * 0.5f, textPaint);

        // Padlock shield glyph at bottom-left edge of "音" circle when locked
        if (!volUnlocked) {
            drawPadlockBadge(canvas, vStartX + vNodeR * 0.72f, vStartY + vNodeR * 0.68f, pulsing);
        } else {
            invalidateAfterPulse();
        }
        if (pulsing) {
            postInvalidateOnAnimation();
        }
    }

    private void drawStepButton(Canvas canvas, float bx, float by, String sym, boolean pressed) {
        fillPaint.setColor(pressed ? COL_VOL_RED : COL_VOL_YELLOW);
        drawTiltedFragment(canvas, bx, by, vBtnR * 0.92f, vBtnR * 0.92f, 45f);
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.6f * density);
        canvas.drawCircle(bx, by, vBtnR * 0.86f, strokePaint);
        textPaint.setColor(pressed ? COL_PAPER : COL_INK);
        textPaint.setTextSize(12.5f * density);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(sym, bx, by - (fm.ascent + fm.descent) * 0.5f, textPaint);
    }

    private void drawPadlockBadge(Canvas canvas, float cx, float cy, boolean alert) {
        float r = 5.4f * density;
        fillPaint.setColor(alert ? COL_VOL_RED : COL_VOL_YELLOW);
        canvas.drawCircle(cx, cy, r, fillPaint);
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.1f * density);
        canvas.drawCircle(cx, cy, r, strokePaint);
        // Tiny lock shackle + body
        strokePaint.setColor(alert ? COL_PAPER : COL_INK);
        strokePaint.setStrokeWidth(1.1f * density);
        canvas.drawArc(cx - 1.8f * density, cy - 3.3f * density,
                cx + 1.8f * density, cy + 0.3f * density, 180f, 180f, false, strokePaint);
        fillPaint.setColor(alert ? COL_PAPER : COL_INK);
        canvas.drawRect(cx - 2.4f * density, cy - 1.0f * density,
                cx + 2.4f * density, cy + 2.5f * density, fillPaint);
    }

    private void drawBolt(Canvas canvas, float cx, float cy, float size) {
        workPath.reset();
        workPath.moveTo(cx + 0.15f * size, cy - size);
        workPath.lineTo(cx - 0.55f * size, cy + 0.08f * size);
        workPath.lineTo(cx + 0.05f * size, cy + 0.08f * size);
        workPath.lineTo(cx - 0.25f * size, cy + size);
        workPath.lineTo(cx + 0.58f * size, cy - 0.10f * size);
        workPath.lineTo(cx - 0.04f * size, cy - 0.10f * size);
        workPath.close();

        fillPaint.setColor(0xFFF4BA00);
        canvas.drawPath(workPath, fillPaint);
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.0f * density);
        canvas.drawPath(workPath, strokePaint);
    }

    private void buildParallelogram(float originX, float originY, float ux, float uy,
                                    float dStart, float dEnd, float halfThick,
                                    float skew, float perpShift) {
        float px = -uy;
        float py = ux;
        float topDx = ux * skew + px * (halfThick + perpShift);
        float topDy = uy * skew + py * (halfThick + perpShift);
        float botDx = -ux * skew - px * (halfThick - perpShift);
        float botDy = -uy * skew - py * (halfThick - perpShift);

        float xA = originX + ux * dStart;
        float yA = originY + uy * dStart;
        float xB = originX + ux * dEnd;
        float yB = originY + uy * dEnd;

        workPath.reset();
        workPath.moveTo(xA + topDx, yA + topDy);
        workPath.lineTo(xB + topDx, yB + topDy);
        workPath.lineTo(xB + botDx, yB + botDy);
        workPath.lineTo(xA + botDx, yA + botDy);
        workPath.close();
    }

    private void drawTiltedFragment(Canvas canvas, float cx, float cy, float hw, float hh, float deg) {
        canvas.save();
        canvas.rotate(deg, cx, cy);
        canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, fillPaint);
        canvas.restore();
    }

    private void invalidateAfterPulse() {
        // Auto-lock timer keeps redraw minimal; no idle spin needed.
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                pressedMinus = volUnlocked && dist(x, y, vMinusX, vMinusY) <= 18f * density;
                pressedPlus = volUnlocked && dist(x, y, vPlusX, vPlusY) <= 18f * density;
                if (pressedMinus || pressedPlus) {
                    scheduleAutoLock();
                    invalidate();
                    return true;
                }
                boolean hitNode = dist(x, y, vStartX, vStartY) <= vNodeR + 10f * density;
                boolean hitRibbon = distanceToSegment(x, y, vStartX, vStartY, vEndX, vEndY) <= 18f * density;
                if (!hitNode && !hitRibbon) {
                    trackingVolZone = false;
                    return false;
                }
                trackingVolZone = true;
                draggingVolBar = false;
                pendingHoldUnlock = !volUnlocked;
                if (volUnlocked) {
                    scheduleAutoLock();
                    // Allow immediate drag on the solid bar only after explicit unlock!
                    if (hitRibbon && !hitNode) {
                        ViewParent p = getParent();
                        if (p != null) {
                            p.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                } else {
                    ui.removeCallbacks(holdUnlockTask);
                    ui.postDelayed(holdUnlockTask, UNLOCK_HOLD_MS);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pressedMinus || pressedPlus) {
                    return true;
                }
                if (!trackingVolZone) {
                    return false;
                }
                float moveDist = dist(x, y, downX, downY);
                if (!volUnlocked) {
                    // Anti-accidental-touch: if finger drags/swipes before holding ~250ms, cancel lock wait
                    // and release touch intent cleanly so page arc-swipe wins!
                    if (moveDist > touchSlop) {
                        cancelHoldUnlock();
                        trackingVolZone = false;
                        return false;
                    }
                    return true;
                }
                if (!draggingVolBar && moveDist > touchSlop * 0.5f) {
                    draggingVolBar = true;
                    ViewParent p = getParent();
                    if (p != null) {
                        p.requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (draggingVolBar) {
                    scheduleAutoLock();
                    updateVolumeFromPoint(x, y);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelHoldUnlock();
                if (pressedMinus || pressedPlus) {
                    if (action == MotionEvent.ACTION_UP) {
                        int delta = pressedPlus ? 1 : -1;
                        pressedMinus = false;
                        pressedPlus = false;
                        stepVolume(delta);
                    } else {
                        pressedMinus = false;
                        pressedPlus = false;
                    }
                    invalidate();
                    return true;
                }
                if (!trackingVolZone) {
                    return false;
                }
                boolean wasDragging = draggingVolBar;
                trackingVolZone = false;
                draggingVolBar = false;
                if (action == MotionEvent.ACTION_UP && !wasDragging) {
                    long now = System.currentTimeMillis();
                    if (volUnlocked) {
                        if (dist(x, y, vStartX, vStartY) <= vNodeR + 10f * density) {
                            setVolumeUnlocked(false, true);
                        } else {
                            scheduleAutoLock();
                            updateVolumeFromPoint(x, y);
                        }
                    } else if (now - lastTapOnVolAt <= DOUBLE_TAP_MS) {
                        lastTapOnVolAt = 0L;
                        setVolumeUnlocked(true, true);
                    } else {
                        lastTapOnVolAt = now;
                        lockPulseUntil = now + 520L;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (volumeListener != null) {
                            volumeListener.onVolumeGuardHint(false);
                        }
                        invalidate();
                    }
                }
                return true;
        }
        return false;
    }

    private void cancelHoldUnlock() {
        pendingHoldUnlock = false;
        ui.removeCallbacks(holdUnlockTask);
    }

    private void scheduleAutoLock() {
        ui.removeCallbacks(autoLockTask);
        ui.postDelayed(autoLockTask, AUTO_LOCK_MS);
    }

    private void setVolumeUnlocked(boolean unlock, boolean notifyUser) {
        volUnlocked = unlock;
        if (unlock) {
            scheduleAutoLock();
            lastSentVol = volLevel;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            ui.removeCallbacks(autoLockTask);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (notifyUser && volumeListener != null) {
            volumeListener.onVolumeGuardHint(unlock);
        }
        invalidate();
    }

    private void stepVolume(int delta) {
        scheduleAutoLock();
        int next = Math.max(0, Math.min(volMax, volLevel + delta));
        if (next != volLevel) {
            volLevel = next;
            lastSentVol = next;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (volumeListener != null) {
                volumeListener.onVolumeChanged(next, volMax);
            }
            invalidate();
        }
    }

    private void updateVolumeFromPoint(float x, float y) {
        float trackStart = vNodeR + 5.5f * density;
        float trackLen = Math.max(14f * density, vLen - trackStart);
        float proj = (x - vStartX) * vUnitX + (y - vStartY) * vUnitY;
        float ratio = Math.max(0f, Math.min(1f, (proj - trackStart) / trackLen));
        int next = Math.round(ratio * volMax);
        if (next != volLevel || next != lastSentVol) {
            volLevel = next;
            lastSentVol = next;
            if (volumeListener != null) {
                volumeListener.onVolumeChanged(next, volMax);
            }
            invalidate();
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        return (float) Math.hypot(x1 - x0, y1 - y0);
    }

    private static float distanceToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float len2 = dx * dx + dy * dy;
        if (len2 <= 0.0001f) {
            return dist(px, py, ax, ay);
        }
        float t = Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / len2));
        return dist(px, py, ax + t * dx, ay + t * dy);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
