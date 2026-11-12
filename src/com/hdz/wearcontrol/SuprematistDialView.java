package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * El Lissitzky / Malevich Suprematist Hero Dial matching the user's design specification.
 *
 * <p>Every single interactive region is scaled proportionally by min(w, h) and mathematically
 * inscribed within radial distance <= 0.428 * D from center so NOTHING is clipped on round
 * Wear OS screens:
 * <ul>
 *   <li>Top-left: slanted Crimson mark + italic WearEars + CROSS-BRAND TWS CONTROL + rule</li>
 *   <li>Top-right: crisp black-framed square Refresh button</li>
 *   <li>Upper-left: Yellow/state square + connection status + device subtitle + Reconnect pill</li>
 *   <li>Upper-right: 3 side-by-side skewed parallelograms [降噪 | 通透 | 关闭]</li>
 *   <li>Middle: 3 horizontal skewed 7-segment battery rows [左 / 右 / 盒] with NO numbers</li>
 *   <li>Bottom: [♪] solid Cobalt Blue Volume bar with anti-accidental-touch guard (locked by
 *       default; long-press >= 250ms or double-tap unlocks drag & -/+ diamond paddles for 4s)</li>
 * </ul>
 */
public class SuprematistDialView extends View {

    private static final int SEGMENTS = 7;
    private static final int[] NOISE_MODES = {
            TwsClient.MODE_ANC, TwsClient.MODE_TRANS, TwsClient.MODE_OFF
    };

    private static final int COL_INK = 0xFF111317;
    private static final int COL_PAPER = 0xFFF4F3EE;
    private static final int COL_WHITE = 0xFFFFFFFF;
    private static final int COL_RED = 0xFFDF1B22;
    private static final int COL_BLUE = 0xFF0D4FB8;
    private static final int COL_YELLOW = 0xFFF6C100;
    private static final int COL_TROUGH = 0xFFD6D6D3;
    private static final int COL_MUTED = 0xFF343840;

    private static final long UNLOCK_HOLD_MS = 250L;
    private static final long AUTO_LOCK_MS = 4000L;
    private static final long DOUBLE_TAP_MS = 320L;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint italicPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path workPath = new Path();
    private final RectF refreshRect = new RectF();
    private final RectF reconnectRect = new RectF();
    private final RectF volBarRect = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Drawable refreshIcon;
    private final Drawable[] noiseIcons = new Drawable[3];
    private final String[] noiseLabels = new String[3];

    // Live model state
    private int battL = -1;
    private int battR = -1;
    private int battC = -1;
    private boolean chgL;
    private boolean chgR;
    private boolean chgC;
    private boolean singleBattery;

    private String statusText = "";
    private String subText = "";
    private String chipText = "";
    private boolean connected;
    private boolean connecting;
    private boolean idleReconnect;
    private int stateDotColor = COL_MUTED;

    private int noiseMode = -1;
    private boolean hasNoiseCap = false;

    private int volLevel = 5;
    private int volMax = 15;
    private boolean volUnlocked;
    private long lockPulseUntil;

    private float density;
    private int touchSlop;
    private boolean useEnglish;

    // Interactive hit-test geometry (updated on layout/draw in px)
    private float dSize;
    private float ancTop;
    private float ancBot;
    private float ancLeft;
    private float ancRight;
    private float ancSkew;
    private float vBadgeLeft;
    private float vBadgeRight;
    private float vBarLeft;
    private float vBarRight;
    private float vTop;
    private float vBot;
    private float vMinusCx;
    private float vPlusCx;
    private float vBtnCy;
    private float vBtnR;

    // Touch handling state
    private static final int HIT_NONE = 0;
    private static final int HIT_REFRESH = 1;
    private static final int HIT_RECONNECT = 2;
    private static final int HIT_ANC_0 = 10;
    private static final int HIT_ANC_1 = 11;
    private static final int HIT_ANC_2 = 12;
    private static final int HIT_VOL_ZONE = 20;
    private static final int HIT_VOL_MINUS = 21;
    private static final int HIT_VOL_PLUS = 22;

    private int pressedTarget = HIT_NONE;
    private boolean draggingVolBar;
    private boolean pendingHoldUnlock;
    private float downX;
    private float downY;
    private long lastTapVolAt;
    private int lastSentVol = -1;

    private OnHeroActionListener heroListener;

    public interface OnHeroActionListener {
        void onRefreshRequested();

        void onReconnectRequested();

        void onNoiseModeSelected(int mode);

        void onVolumeChanged(int level, int max);

        void onVolumeGuardHint(boolean unlocked);
    }

    private final Runnable holdUnlockTask = new Runnable() {
        @Override
        public void run() {
            if (pressedTarget == HIT_VOL_ZONE && pendingHoldUnlock && !volUnlocked) {
                pendingHoldUnlock = false;
                setVolumeUnlocked(true, true);
                draggingVolBar = true;
                ViewParent p = getParent();
                if (p != null) {
                    p.requestDisallowInterceptTouchEvent(true);
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
        italicPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        subTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        statusText = context.getString(R.string.status_idle);
        refreshIcon = loadDrawable(context, R.drawable.ic_refresh);
        noiseIcons[0] = loadDrawable(context, R.drawable.ic_anc);
        noiseIcons[1] = loadDrawable(context, R.drawable.ic_trans);
        noiseIcons[2] = loadDrawable(context, R.drawable.ic_off);

        updateNoiseLabels(context);
    }

    private void updateNoiseLabels(Context ctx) {
        if (useEnglish) {
            noiseLabels[0] = "ANC";
            noiseLabels[1] = "TRANS";
            noiseLabels[2] = "OFF";
        } else {
            noiseLabels[0] = "\u964d\u566a";
            noiseLabels[1] = "\u901a\u900f";
            noiseLabels[2] = "\u5173\u95ed";
        }
    }

    private static Drawable loadDrawable(Context ctx, int resId) {
        Drawable d = ctx.getDrawable(resId);
        return d != null ? d.mutate() : null;
    }

    public void setOnHeroActionListener(OnHeroActionListener l) {
        this.heroListener = l;
    }

    public void setLanguageEnglish(boolean en) {
        if (useEnglish != en) {
            useEnglish = en;
            updateNoiseLabels(getContext());
            invalidate();
        }
    }

    public void setConnectionState(String status, String sub, int dotColor,
                                   boolean isConnected, boolean isConnecting, boolean showReconnect) {
        this.statusText = status == null ? "" : status;
        this.subText = sub == null ? "" : sub;
        this.stateDotColor = dotColor;
        this.connected = isConnected;
        this.connecting = isConnecting;
        this.idleReconnect = showReconnect;
        invalidate();
    }

    public void setSubText(String sub) {
        String s = sub == null ? "" : sub;
        if (!s.equals(this.subText)) {
            this.subText = s;
            invalidate();
        }
    }

    public void setChipText(String chip) {
        String c = chip == null ? "" : chip;
        if (!c.equals(this.chipText)) {
            this.chipText = c;
            invalidate();
        }
    }

    public void setNoiseState(int mode, boolean noiseCap) {
        if (this.noiseMode != mode || this.hasNoiseCap != noiseCap) {
            this.noiseMode = mode;
            this.hasNoiseCap = noiseCap;
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

    private void updateGeometry(int w, int h) {
        dSize = Math.min(w, h);
        float ox = (w - dSize) * 0.5f;
        float oy = (h - dSize) * 0.5f;

        // Top-right enlarged refresh square strictly within safe circular radius (r <= 0.442 D)
        float refSize = 0.122f * dSize;
        refreshRect.set(ox + 0.632f * dSize, oy + 0.138f * dSize,
                ox + 0.632f * dSize + refSize, oy + 0.138f * dSize + refSize);

        // Enlarged 3 ANC parallelograms side-by-side inside r <= 0.439 D
        ancLeft = ox + 0.432f * dSize;
        ancRight = ox + 0.874f * dSize;
        ancTop = oy + 0.272f * dSize;
        ancBot = oy + 0.404f * dSize;
        ancSkew = 0.030f * dSize;

        // Bottom Volume row shortened slightly on right so colored beams shine cleanly (r <= 0.422 D)
        vBadgeLeft = ox + 0.194f * dSize;
        vBadgeRight = ox + 0.304f * dSize;
        vBarLeft = ox + 0.324f * dSize;
        vBarRight = ox + 0.706f * dSize;
        vTop = oy + 0.722f * dSize;
        vBot = oy + 0.800f * dSize;
        volBarRect.set(vBarLeft, vTop, vBarRight, vBot);

        // Unlocked - / + paddles tucked safely along bottom chord
        vBtnCy = oy + 0.852f * dSize;
        vMinusCx = ox + 0.415f * dSize;
        vPlusCx = ox + 0.575f * dSize;
        vBtnR = 0.046f * dSize;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        updateGeometry(w, h);
        float d = dSize;
        float ox = (w - d) * 0.5f;
        float oy = (h - d) * 0.5f;

        // 1) Top-left Brand Lockup ("WearEars" + "CROSS-BRAND TWS CONTROL")
        drawBrandHeader(canvas, ox, oy, d);

        // 2) Top-right Refresh Box
        drawRefreshBox(canvas, d);

        // 3) Middle-left Status + Device Name (+ Reconnect CTA if idle)
        drawStatusCluster(canvas, ox, oy, d);

        // 4) Middle-right 3 Skewed Parallelograms [降噪 | 通透 | 关闭]
        drawAncParallelograms(canvas, d);

        // 5) Middle Horizontal Skewed Battery Rows [左 / 右 / 盒] (ZERO digits)
        drawBatteryRows(canvas, ox, oy, d);

        // 6) Bottom Solid Cobalt Volume Bar + Anti-Accidental-Touch Guard
        drawVolumeRow(canvas, ox, oy, d);
    }

    private void drawBrandHeader(Canvas canvas, float ox, float oy, float d) {
        float bx = ox + 0.240f * d;
        float by = oy + 0.146f * d;
        float bw = 0.045f * d;
        float bh = 0.042f * d;
        float skew = 0.015f * d;

        fillPaint.setColor(COL_RED);
        setSkewRect(bx, by, bx + bw, by + bh, skew);
        canvas.drawPath(workPath, fillPaint);

        italicPaint.setColor(COL_INK);
        italicPaint.setTextAlign(Paint.Align.LEFT);
        italicPaint.setTextSize(0.062f * d);
        canvas.drawText("WearEars", bx + bw + 0.016f * d, oy + 0.186f * d, italicPaint);

        textPaint.setColor(0xFF424752);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(0.024f * d);
        canvas.drawText("CROSS-BRAND TWS CONTROL", bx, oy + 0.218f * d, textPaint);

        // Signature thin black underline rule
        fillPaint.setColor(COL_INK);
        canvas.drawRect(bx, oy + 0.233f * d, bx + 0.110f * d, oy + 0.240f * d, fillPaint);
    }

    private void drawRefreshBox(Canvas canvas, float d) {
        boolean pressed = (pressedTarget == HIT_REFRESH);
        float rx = refreshRect.left;
        float ry = refreshRect.top;
        float rw = refreshRect.width();
        float radius = 0.018f * d;

        fillPaint.setColor(pressed ? 0xFFE5E2D6 : COL_WHITE);
        canvas.drawRoundRect(refreshRect, radius, radius, fillPaint);

        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(Math.max(1.6f, 2.0f * density));
        canvas.drawRoundRect(refreshRect, radius, radius, strokePaint);

        if (refreshIcon != null) {
            int pad = Math.round(rw * 0.20f);
            refreshIcon.setBounds(Math.round(rx) + pad, Math.round(ry) + pad,
                    Math.round(refreshRect.right) - pad, Math.round(refreshRect.bottom) - pad);
            refreshIcon.setTint(COL_INK);
            refreshIcon.draw(canvas);
        }
    }

    private void drawStatusCluster(Canvas canvas, float ox, float oy, float d) {
        float sx = ox + 0.132f * d;
        float sqY = oy + 0.274f * d;
        float sqSize = 0.042f * d;

        fillPaint.setColor(connected ? COL_YELLOW : (connecting ? COL_BLUE : stateDotColor));
        canvas.drawRect(sx, sqY, sx + sqSize, sqY + sqSize, fillPaint);

        textPaint.setColor(COL_INK);
        textPaint.setTextAlign(Paint.Align.LEFT);
        float statusX = sx + sqSize + 0.016f * d;
        float maxStatusW = Math.max(0.10f * d, ancLeft - statusX - 0.022f * d);
        float statusSz = 0.062f * d;
        textPaint.setTextSize(statusSz);
        float measuredW = textPaint.measureText(statusText);
        if (measuredW > maxStatusW && measuredW > 1f) {
            statusSz = Math.max(0.038f * d, statusSz * (maxStatusW / measuredW));
            textPaint.setTextSize(statusSz);
        }
        Paint.FontMetrics sfm = textPaint.getFontMetrics();
        float statusCy = sqY + sqSize * 0.5f;
        canvas.drawText(statusText, statusX, statusCy - (sfm.ascent + sfm.descent) * 0.5f, textPaint);

        // Device subtitle or temp guard hint (enlarged & clearly legible on wrist)
        float maxSubW = Math.max(0.10f * d, ancLeft - sx - 0.020f * d);
        subTextPaint.setColor(0xFF232730);
        subTextPaint.setTextSize(0.035f * d);
        CharSequence clipped = TextUtils.ellipsize(subText, subTextPaint, maxSubW, TextUtils.TruncateAt.END);
        canvas.drawText(clipped, 0, clipped.length(), sx, oy + 0.358f * d, subTextPaint);

        // Optional idle Reconnect skewed CTA tucked cleanly under device line
        if (idleReconnect && !connected) {
            float btnTop = oy + 0.372f * d;
            float btnBot = oy + 0.418f * d;
            float btnLeft = sx;
            float btnRight = sx + 0.215f * d;
            reconnectRect.set(btnLeft, btnTop, btnRight, btnBot);

            boolean pressed = (pressedTarget == HIT_RECONNECT);
            fillPaint.setColor(pressed ? COL_RED : COL_BLUE);
            setSkewRect(btnLeft, btnTop, btnRight, btnBot, 0.018f * d);
            canvas.drawPath(workPath, fillPaint);

            textPaint.setColor(COL_WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(0.032f * d);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(useEnglish ? "Reconnect" : "\u70b9\u51fb\u91cd\u8fde",
                    (btnLeft + btnRight) * 0.5f,
                    (btnTop + btnBot) * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);
        } else {
            reconnectRect.setEmpty();
            // Thin black accent underline under status cluster
            fillPaint.setColor(COL_INK);
            canvas.drawRect(sx, oy + 0.378f * d, sx + 0.095f * d, oy + 0.385f * d, fillPaint);
        }
    }

    private void drawAncParallelograms(Canvas canvas, float d) {
        float gap = 0.010f * d;
        float totalW = ancRight - ancLeft;
        float slabW = (totalW - gap * 2f) / 3f;
        float iconSz = Math.round(0.052f * d);

        for (int i = 0; i < 3; i++) {
            float l = ancLeft + i * (slabW + gap);
            float r = l + slabW;
            boolean active = connected && hasNoiseCap && (noiseMode == NOISE_MODES[i]);
            boolean pressed = (pressedTarget == HIT_ANC_0 + i);
            boolean dimmed = !connected || !hasNoiseCap;

            int bg;
            if (active) {
                bg = COL_RED;
            } else if (pressed) {
                bg = 0xFF282D36;
            } else {
                bg = dimmed ? COL_MUTED : COL_INK;
            }

            fillPaint.setColor(bg);
            setSkewRect(l, ancTop, r, ancBot, ancSkew);
            canvas.drawPath(workPath, fillPaint);

            int fg = dimmed && !active ? 0xFFB8BDC7 : COL_WHITE;
            float midX = (l + r) * 0.5f;
            float iconCy = ancTop + (ancBot - ancTop) * 0.34f;

            Drawable icon = noiseIcons[i];
            if (icon != null) {
                int hs = Math.round(iconSz * 0.5f);
                int ix = Math.round(midX + ancSkew * 0.18f);
                int iy = Math.round(iconCy);
                icon.setBounds(ix - hs, iy - hs, ix + hs, iy + hs);
                icon.setTint(fg);
                icon.draw(canvas);
            }

            textPaint.setColor(fg);
            textPaint.setTextAlign(Paint.Align.CENTER);
            float maxLblW = Math.max(8f, slabW - ancSkew * 1.25f);
            float lblSz = 0.038f * d;
            textPaint.setTextSize(lblSz);
            float lw = textPaint.measureText(noiseLabels[i]);
            if (lw > maxLblW && lw > 1f) {
                textPaint.setTextSize(Math.max(0.024f * d, lblSz * (maxLblW / lw)));
            }
            canvas.drawText(noiseLabels[i], midX - ancSkew * 0.15f,
                    ancBot - (ancBot - ancTop) * 0.14f, textPaint);
        }
    }

    private void drawBatteryRows(Canvas canvas, float ox, float oy, float d) {
        if (singleBattery) {
            drawBatteryRow(canvas, ox, oy + 0.520f * d, 0.130f * d, 0.480f * d, d,
                    useEnglish ? "A" : "\u603b", battL, chgL, COL_RED, false);
        } else {
            // Staggered kinetic geometry + shortened Box bar so right-side colorful beams stay open:
            // 左 standard -> 右 pushed far left -> 盒 shifted right of 左/右 with compact shortened bar
            drawBatteryRow(canvas, ox, oy + 0.432f * d, 0.132f * d, 0.490f * d, d,
                    useEnglish ? "L" : "\u5de6", battL, chgL, COL_RED, false);
            drawBatteryRow(canvas, ox, oy + 0.526f * d, 0.082f * d, 0.480f * d, d,
                    useEnglish ? "R" : "\u53f3", battR, chgR, COL_BLUE, false);
            drawBatteryRow(canvas, ox, oy + 0.620f * d, 0.165f * d, 0.365f * d, d,
                    useEnglish ? "C" : "\u76d2", battC, chgC, COL_YELLOW, true);
        }
    }

    private void drawBatteryRow(Canvas canvas, float ox, float top, float badgeLeftOff, float barWidth, float d,
                                String label, int pct, boolean charging,
                                int accentCol, boolean darkTextOnBadge) {
        float rowH = 0.075f * d;
        float bot = top + rowH;
        float badgeSkew = 0.026f * d;

        float badgeL = ox + badgeLeftOff;
        float badgeR = badgeL + 0.118f * d;
        boolean known = pct >= 0 && pct <= 100;
        boolean low = known && pct <= 20 && !charging;

        // 1) Colored skewed parallelogram label badge ("左" / "右" / "盒")
        fillPaint.setColor(low ? COL_RED : accentCol);
        setSkewRect(badgeL, top, badgeR, bot, badgeSkew);
        canvas.drawPath(workPath, fillPaint);

        textPaint.setColor(darkTextOnBadge && !low ? COL_INK : COL_WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(0.042f * d);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = (top + bot) * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, (badgeL + badgeR) * 0.5f, textY, textPaint);

        if (charging && known) {
            drawBolt(canvas, badgeR + 0.004f * d, top + 0.012f * d, 0.020f * d);
        }

        // 2) Thin Constructivist diagonal slash "/" separating badge & bar
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(Math.max(1.4f, 1.7f * density));
        float slashX = badgeR + 0.015f * d;
        canvas.drawLine(slashX, bot - 0.004f * d, slashX + 0.026f * d, top + 0.004f * d, strokePaint);

        // 3) Unified 7-cell skewed battery frame matching reference poster (ZERO numbers)
        float barL = badgeL + 0.172f * d;
        float barR = barL + barWidth;
        float cellSkew = 0.022f * d;
        float barSpan = (barR - barL) - cellSkew;
        float cellW = barSpan / SEGMENTS;
        float filledUnits = known ? (pct / 100f) * SEGMENTS : 0f;
        int fillCol = low ? COL_RED : accentCol;

        // White trough background across whole slanted bar
        fillPaint.setColor(known ? COL_WHITE : 0xFFEAE8DF);
        setSkewRect(barL, top, barR, bot, cellSkew);
        canvas.drawPath(workPath, fillPaint);

        // Fill active slanted segments cleanly up to percentage
        for (int i = 0; i < SEGMENTS; i++) {
            float unit = known ? Math.max(0f, Math.min(1f, filledUnits - i)) : 0f;
            if (unit <= 0.01f) {
                continue;
            }
            float cl = barL + i * cellW;
            float cr = cl + cellW * unit + cellSkew;
            fillPaint.setColor(fillCol);
            setSkewRect(cl, top, cr, bot, cellSkew);
            canvas.drawPath(workPath, fillPaint);
        }

        // Crisp black outer frame + slanted cell dividers
        strokePaint.setColor(known ? COL_INK : 0x80111317);
        strokePaint.setStrokeWidth(Math.max(1.4f, 1.7f * density));
        setSkewRect(barL, top, barR, bot, cellSkew);
        canvas.drawPath(workPath, strokePaint);

        for (int i = 1; i < SEGMENTS; i++) {
            float bx = barL + i * cellW;
            canvas.drawLine(bx, bot, bx + cellSkew, top, strokePaint);
        }
    }

    private void drawVolumeRow(Canvas canvas, float ox, float oy, float d) {
        float vSkew = 0.020f * d;
        boolean pulsing = System.currentTimeMillis() < lockPulseUntil;

        // 1) Black skewed [♪] badge + safety padlock shield
        fillPaint.setColor(volUnlocked ? COL_RED : COL_INK);
        setSkewRect(vBadgeLeft, vTop, vBadgeRight, vBot, vSkew);
        canvas.drawPath(workPath, fillPaint);

        if (volUnlocked || pulsing) {
            strokePaint.setColor(volUnlocked ? COL_YELLOW : COL_RED);
            strokePaint.setStrokeWidth(1.6f * density);
            canvas.drawPath(workPath, strokePaint);
        }

        // Vector musical note glyph ♪ (never relies on system font box behavior)
        drawMusicNote(canvas, (vBadgeLeft + vBadgeRight) * 0.5f, (vTop + vBot) * 0.5f,
                0.025f * d, COL_WHITE);

        // Padlock shield badge on shoulder of ♪ when locked ("防误触")
        if (!volUnlocked) {
            drawPadlockBadge(canvas, vBadgeRight - 0.004f * d, vBot - 0.004f * d, pulsing, d);
        }

        // 2) Wide black-framed slanted Cobalt Blue volume bar (#0D4FB8 on #D6D6D3 trough)
        fillPaint.setColor(COL_TROUGH);
        setSkewRect(vBarLeft, vTop, vBarRight, vBot, vSkew);
        canvas.drawPath(workPath, fillPaint);

        float ratio = volMax > 0 ? Math.max(0f, Math.min(1f, volLevel / (float) volMax)) : 0f;
        if (ratio > 0.01f) {
            float barUsable = (vBarRight - vBarLeft) - vSkew;
            float fillEnd = vBarLeft + barUsable * ratio + vSkew;
            fillPaint.setColor(volUnlocked ? COL_RED : COL_BLUE);
            setSkewRect(vBarLeft, vTop, fillEnd, vBot, vSkew);
            canvas.drawPath(workPath, fillPaint);

            // Crisp top poster highlight line matching reference image
            strokePaint.setColor(0x88FFFFFF);
            strokePaint.setStrokeWidth(Math.max(1f, 1.3f * density));
            canvas.drawLine(vBarLeft + vSkew * 0.85f, vTop + 2.2f * density,
                    fillEnd - 1.5f * density, vTop + 2.2f * density, strokePaint);
        }

        strokePaint.setColor(volUnlocked ? COL_YELLOW : (pulsing ? COL_RED : COL_INK));
        strokePaint.setStrokeWidth(Math.max(1.5f, (volUnlocked || pulsing ? 2.0f : 1.7f) * density));
        setSkewRect(vBarLeft, vTop, vBarRight, vBot, vSkew);
        canvas.drawPath(workPath, strokePaint);

        // Subtle diagonal safety ticks while locked
        if (!volUnlocked) {
            strokePaint.setColor(pulsing ? COL_RED : 0x45111317);
            strokePaint.setStrokeWidth(1.1f * density);
            for (int k = 1; k <= 3; k++) {
                float sx = vBarLeft + (vBarRight - vBarLeft) * (k * 0.25f);
                canvas.drawLine(sx - 3f * density, vBot, sx + 5f * density, vTop, strokePaint);
            }
        } else {
            // Safe - / + diamond paddles strictly inside bottom chord
            drawStepDiamond(canvas, vMinusCx, vBtnCy, "-", pressedTarget == HIT_VOL_MINUS);
            drawStepDiamond(canvas, vPlusCx, vBtnCy, "+", pressedTarget == HIT_VOL_PLUS);
        }

        if (pulsing) {
            postInvalidateOnAnimation();
        }
    }

    private void drawMusicNote(Canvas canvas, float cx, float cy, float size, int color) {
        fillPaint.setColor(color);
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(Math.max(1.4f, 0.22f * size));

        float bx = cx - 0.22f * size;
        float by = cy + 0.42f * size;
        canvas.drawCircle(bx, by, 0.28f * size, fillPaint);

        float sx = bx + 0.22f * size;
        float topY = cy - 0.56f * size;
        canvas.drawLine(sx, by, sx, topY, strokePaint);

        workPath.reset();
        workPath.moveTo(sx, topY);
        workPath.lineTo(sx + 0.62f * size, topY + 0.22f * size);
        workPath.lineTo(sx + 0.62f * size, topY + 0.48f * size);
        workPath.lineTo(sx, topY + 0.26f * size);
        workPath.close();
        canvas.drawPath(workPath, fillPaint);
    }

    private void drawPadlockBadge(Canvas canvas, float cx, float cy, boolean alert, float d) {
        float r = 0.022f * d;
        fillPaint.setColor(alert ? COL_RED : COL_YELLOW);
        canvas.drawCircle(cx, cy, r, fillPaint);
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.0f * density);
        canvas.drawCircle(cx, cy, r, strokePaint);

        strokePaint.setColor(alert ? COL_WHITE : COL_INK);
        strokePaint.setStrokeWidth(1.0f * density);
        canvas.drawArc(cx - 0.35f * r, cy - 0.65f * r,
                cx + 0.35f * r, cy + 0.05f * r, 180f, 180f, false, strokePaint);
        fillPaint.setColor(alert ? COL_WHITE : COL_INK);
        canvas.drawRect(cx - 0.45f * r, cy - 0.18f * r,
                cx + 0.45f * r, cy + 0.48f * r, fillPaint);
    }

    private void drawStepDiamond(Canvas canvas, float cx, float cy, String sym, boolean pressed) {
        fillPaint.setColor(pressed ? COL_RED : COL_YELLOW);
        canvas.save();
        canvas.rotate(45f, cx, cy);
        canvas.drawRect(cx - vBtnR, cy - vBtnR, cx + vBtnR, cy + vBtnR, fillPaint);
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.4f * density);
        canvas.drawRect(cx - vBtnR, cy - vBtnR, cx + vBtnR, cy + vBtnR, strokePaint);
        canvas.restore();

        textPaint.setColor(pressed ? COL_WHITE : COL_INK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(0.044f * dSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(sym, cx, cy - (fm.ascent + fm.descent) * 0.5f, textPaint);
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

        fillPaint.setColor(COL_YELLOW);
        canvas.drawPath(workPath, fillPaint);
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.0f * density);
        canvas.drawPath(workPath, strokePaint);
    }

    private void setSkewRect(float left, float top, float right, float bottom, float skew) {
        workPath.reset();
        workPath.moveTo(left + skew, top);
        workPath.lineTo(right, top);
        workPath.lineTo(right - skew, bottom);
        workPath.lineTo(left, bottom);
        workPath.close();
    }

    private int hitTest(float x, float y) {
        float pad = 6f * density;
        if (x >= refreshRect.left - pad && x <= refreshRect.right + pad
                && y >= refreshRect.top - pad && y <= refreshRect.bottom + pad) {
            return HIT_REFRESH;
        }
        if (!reconnectRect.isEmpty() && x >= reconnectRect.left - pad
                && x <= reconnectRect.right + pad
                && y >= reconnectRect.top - pad && y <= reconnectRect.bottom + pad) {
            return HIT_RECONNECT;
        }
        if (x >= ancLeft - 4f * density && x <= ancRight + 4f * density
                && y >= ancTop - pad && y <= ancBot + pad) {
            float slabW = (ancRight - ancLeft) / 3f;
            int idx = Math.max(0, Math.min(2, (int) ((x - ancLeft) / slabW)));
            return HIT_ANC_0 + idx;
        }
        if (volUnlocked) {
            if (Math.hypot(x - vMinusCx, y - vBtnCy) <= vBtnR + 8f * density) {
                return HIT_VOL_MINUS;
            }
            if (Math.hypot(x - vPlusCx, y - vBtnCy) <= vBtnR + 8f * density) {
                return HIT_VOL_PLUS;
            }
        }
        if (x >= vBadgeLeft - pad && x <= vBarRight + pad
                && y >= vTop - pad && y <= vBot + pad) {
            return HIT_VOL_ZONE;
        }
        return HIT_NONE;
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
                pressedTarget = hitTest(x, y);
                draggingVolBar = false;
                pendingHoldUnlock = false;
                if (pressedTarget == HIT_NONE) {
                    return false;
                }
                if (pressedTarget == HIT_VOL_ZONE) {
                    if (volUnlocked) {
                        scheduleAutoLock();
                        if (x >= vBarLeft) {
                            ViewParent p = getParent();
                            if (p != null) {
                                p.requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    } else {
                        pendingHoldUnlock = true;
                        ui.removeCallbacks(holdUnlockTask);
                        ui.postDelayed(holdUnlockTask, UNLOCK_HOLD_MS);
                    }
                } else if (pressedTarget == HIT_VOL_MINUS || pressedTarget == HIT_VOL_PLUS) {
                    scheduleAutoLock();
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pressedTarget == HIT_NONE) {
                    return false;
                }
                float dist = (float) Math.hypot(x - downX, y - downY);
                if (pressedTarget == HIT_VOL_ZONE) {
                    if (!volUnlocked) {
                        // Swipe away cleanly releases touch so arc page-swipe wins without lock misfire
                        if (dist > touchSlop) {
                            cancelHoldUnlock();
                            pressedTarget = HIT_NONE;
                            return false;
                        }
                        return true;
                    }
                    if (!draggingVolBar && dist > touchSlop * 0.5f) {
                        draggingVolBar = true;
                        ViewParent p = getParent();
                        if (p != null) {
                            p.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    if (draggingVolBar) {
                        scheduleAutoLock();
                        updateVolumeFromX(x);
                    }
                    return true;
                }
                if (dist > touchSlop) {
                    pressedTarget = HIT_NONE;
                    invalidate();
                    return false;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelHoldUnlock();
                int hit = pressedTarget;
                boolean wasDragging = draggingVolBar;
                pressedTarget = HIT_NONE;
                draggingVolBar = false;
                invalidate();
                if (action != MotionEvent.ACTION_UP || hit == HIT_NONE) {
                    return false;
                }
                int upHit = hitTest(x, y);
                if (hit == HIT_REFRESH && upHit == HIT_REFRESH) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (heroListener != null) {
                        heroListener.onRefreshRequested();
                    }
                } else if (hit == HIT_RECONNECT && upHit == HIT_RECONNECT) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (heroListener != null) {
                        heroListener.onReconnectRequested();
                    }
                } else if (hit >= HIT_ANC_0 && hit <= HIT_ANC_2 && upHit == hit) {
                    int idx = hit - HIT_ANC_0;
                    if (!connected) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (heroListener != null) {
                            heroListener.onReconnectRequested();
                        }
                    } else if (hasNoiseCap) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (heroListener != null) {
                            heroListener.onNoiseModeSelected(NOISE_MODES[idx]);
                        }
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.REJECT);
                    }
                } else if (hit == HIT_VOL_MINUS && upHit == HIT_VOL_MINUS) {
                    stepVolume(-1);
                } else if (hit == HIT_VOL_PLUS && upHit == HIT_VOL_PLUS) {
                    stepVolume(1);
                } else if (hit == HIT_VOL_ZONE && !wasDragging) {
                    long now = System.currentTimeMillis();
                    if (volUnlocked) {
                        if (x <= vBadgeRight + 4f * density) {
                            setVolumeUnlocked(false, true);
                        } else {
                            scheduleAutoLock();
                            updateVolumeFromX(x);
                        }
                    } else if (now - lastTapVolAt <= DOUBLE_TAP_MS) {
                        lastTapVolAt = 0L;
                        setVolumeUnlocked(true, true);
                    } else {
                        lastTapVolAt = now;
                        lockPulseUntil = now + 520L;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (heroListener != null) {
                            heroListener.onVolumeGuardHint(false);
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
        if (notifyUser && heroListener != null) {
            heroListener.onVolumeGuardHint(unlock);
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
            if (heroListener != null) {
                heroListener.onVolumeChanged(next, volMax);
            }
            invalidate();
        }
    }

    private void updateVolumeFromX(float x) {
        float ratio = Math.max(0f, Math.min(1f, (x - vBarLeft) / Math.max(1f, vBarRight - vBarLeft)));
        int next = Math.round(ratio * volMax);
        if (next != volLevel || next != lastSentVol) {
            volLevel = next;
            lastSentVol = next;
            if (heroListener != null) {
                heroListener.onVolumeChanged(next, volMax);
            }
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
