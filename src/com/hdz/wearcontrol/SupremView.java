package com.hdz.wearcontrol;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * “至上（Suprematism）”界面: 多屏海报, 一屏一个构成, 横向滑动翻页.
 *
 * <p>P0 状态 + 电量; P1 噪声三态 + DeepX; P2 设置 + 开发者(音量开关/空间音频).
 * 每屏只放很少的东西, 大字, 大留白; 所有元素保持斜向, 没有摆正的方块。
 * 音量条默认隐藏, 由第二屏的开发者入口里的开关决定是否出现。
 */
public class SupremView extends View {

    /** 给 MainActivity 用的两种语义色 */
    public static final int COLOR_INK = 0xFF121417;
    public static final int COLOR_ERROR = 0xFFD96560;

    private static final int PAPER = 0xFFF6F3EC;
    private static final int INK = 0xFF121417;
    private static final int INK_SOFT = 0xFF6A6D72;
    private static final int INK_HAIR = 0x33121417;
    private static final int INK_FAINT = 0x1A121417;
    private static final int ULTRA = 0xFF1F4384;
    private static final int SAGE = 0xFF669C76;
    private static final int MUSTARD = 0xFFE6B022;
    private static final int CORAL = 0xFFD96560;
    /** 电量全长轨道的极淡底色(留白里的第二种灰) */
    private static final int INK_TRACK = 0x1F121417;

    /** 设计稿尺寸: 所有坐标按 432 写, 绘制时整体缩放 */
    private static final float DW = 432f;
    private static final float DH = 432f;
    private static final int PAGES = 3;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final Typeface tfBlack = Typeface.create("sans-serif-black", Typeface.NORMAL);
    private final Typeface tfBold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface tfRegular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface tfLight = Typeface.create("sans-serif-light", Typeface.NORMAL);

    // ---- 状态 ----
    private String statusText = "";
    private String subText = "";
    private int statusColor = INK;
    private int battL = -1;
    private int battR = -1;
    private int battC = -1;
    private int chargeFlags;
    private boolean singleBatt;
    /** 0=降噪 1=关闭 2=通透, -1=未知 */
    private int mode = -1;
    private int deepxMode = -1;
    private int capsMask;
    private boolean spatialOn;
    private boolean showVolume;
    private int volumeLevel;
    private int volumeMax = 15;
    private boolean devUnlocked;
    private boolean devOpen;
    private int devTaps;
    private long lastDevTapAt;
    private Listener listener;

    // ---- 翻页 ----
    private int page;
    private float dragPx;
    private ValueAnimator animator;
    private boolean dragging;
    private float downX;
    private float downY;

    // ---- 命中区(设计坐标, 每页独立) ----
    private final RectF hitRefresh = new RectF(300f, 50f, 414f, 152f);
    private final RectF[] hitNoise = {new RectF(), new RectF(), new RectF()};
    private final RectF hitSettings = new RectF(50f, 136f, 382f, 226f);
    private final RectF hitDev = new RectF(26f, 228f, 250f, 322f);
    private final RectF panel = new RectF(76f, 138f, 356f, 386f);
    private final RectF hitVolumeToggle = new RectF(252f, 218f, 330f, 264f);
    private final RectF hitSpatialToggle = new RectF(252f, 286f, 330f, 332f);

    private static final float[] ANG = {-78f, -60f, -42f};
    private static final float BASE_X = 120f;
    private static final float BASE_Y = 384f;
    private static final float BATT_MAX = 190f;
    private static final int[] BATT_COLOR = {ULTRA, SAGE, MUSTARD};

    public SupremView(Context context) {
        this(context, null);
    }

    public SupremView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        hitNoise[0].set(46f, 152f, 386f, 218f);
        hitNoise[1].set(46f, 226f, 386f, 292f);
        hitNoise[2].set(46f, 300f, 386f, 366f);
    }

    // ---------------- 公开 API ----------------

    public interface Listener {
        void onRefresh();

        void onModeSelected(int mode);

        void onVolumeChanged(int level, int max);

        void onDeepxSelected(int index);

        void onSpatialToggled(boolean on);

        void onVolumeToggle(boolean on);

        void onSettings();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setStatus(String status, String sub, int color) {
        statusText = status == null ? "" : status;
        subText = sub == null ? "" : sub;
        statusColor = color;
        invalidate();
    }

    public void setSub(String sub) {
        subText = sub == null ? "" : sub;
        invalidate();
    }

    public void setBattery(int left, int right, int box, int flags) {
        battL = left;
        battR = right;
        battC = box;
        chargeFlags = flags;
        singleBatt = false;
        invalidate();
    }

    public void setSingleBattery(int total) {
        battL = total;
        battR = -1;
        battC = -1;
        singleBatt = true;
        invalidate();
    }

    public void setSingle(boolean single) {
        singleBatt = single;
        invalidate();
    }

    public void setMode(int m) {
        mode = m;
        invalidate();
    }

    public void setDeepx(int index) {
        deepxMode = index;
        invalidate();
    }

    public void setCaps(int caps) {
        capsMask = caps;
        invalidate();
    }

    public void setSpatial(boolean on) {
        spatialOn = on;
        invalidate();
    }

    public void setVolume(int level, int max) {
        volumeLevel = level;
        volumeMax = Math.max(1, max);
        invalidate();
    }

    public void setShowVolume(boolean show) {
        showVolume = show;
        invalidate();
    }

    public void setVolumeToggleOn(boolean on) {
        showVolume = on;
        invalidate();
    }

    // ---------------- 测量 ----------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthSpec)
                : (int) (DW * getResources().getDisplayMetrics().density / 3f);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : w;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float sx = getWidth() / DW;
        float sy = getHeight() / DH;
        canvas.save();
        canvas.scale(sx, sy);
        canvas.drawColor(PAPER);
        for (int p = 0; p < PAGES; p++) {
            float off = (p - page) * DH + dragPx;
            if (off <= -DH || off >= DH) {
                continue;
            }
            canvas.save();
            canvas.translate(0f, off);
            drawPage(canvas, p);
            canvas.restore();
        }
        drawPageDots(canvas);
        if (devOpen) {
            drawDevPanel(canvas);
        }
        canvas.restore();
    }

    private void drawPage(Canvas c, int p) {
        if (p == 0) {
            drawStatusPage(c);
        } else if (p == 1) {
            drawNoisePage(c);
        } else {
            drawSetupPage(c);
        }
    }

    // ---------------- P0: 状态 + 电量 ----------------

    private void drawStatusPage(Canvas c) {
        // 顶部骨架: 斜向细线 + 两枚配重色块, 只做节奏
        line(c, 22f, 102f, 176f, 78f, 1.3f, INK_HAIR);
        fillPoly(c, parallelogramPath(58f, 60f, 30f, 18f, 9f, -18f), MUSTARD);
        fillPoly(c, parallelogramPath(110f, 36f, 36f, 10f, 11f, 26f), INK);
        fillPoly(c, parallelogramPath(196f, 70f, 14f, 6f, 5f, -20f), ULTRA);

        drawText(c, statusText, 52f, 146f, 48f, statusColor, tfBlack, -12f, Paint.Align.LEFT);
        if (subText.length() > 0) {
            drawText(c, subText, 58f, 188f, 17f, INK_SOFT, tfLight, -12f, Paint.Align.LEFT);
        }
        line(c, 54f, 164f, 282f, 122f, 1.9f, INK);

        // 刷新: 红楔 + 墨折箭头 + 单字“刷”
        path.reset();
        path.moveTo(342f, 54f);
        path.lineTo(412f, 90f);
        path.lineTo(342f, 126f);
        path.close();
        fillPoly(c, path, CORAL);
        path.reset();
        path.moveTo(350f, 70f);
        path.lineTo(370f, 86f);
        path.lineTo(350f, 102f);
        path.moveTo(368f, 70f);
        path.lineTo(388f, 86f);
        path.lineTo(368f, 102f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3.4f);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setColor(INK);
        c.drawPath(path, stroke);
        drawText(c, getContext().getString(R.string.suprem_refresh_short), 320f, 196f, 26f,
                INK, tfBlack, -22f, Paint.Align.LEFT);
        line(c, 300f, 204f, 352f, 188f, 2.2f, INK);

        // 电量: 三条粗斜杠(浅色全长轨道 + 彩色实心段), 单字标签 + 小号数字
        drawBattery(c);

        // 信息密度: 底部一行小字(模式 / 音量), 轻排, 不抢主体
        String info = noiseLabel(currentNoise()) + "  ·  " + volumeLevel + " / " + volumeMax;
        drawText(c, info, 50f, 414f, 13f, INK_SOFT, tfLight, -8f, Paint.Align.LEFT);
        line(c, 44f, 420f, 212f, 402f, 1f, INK_HAIR);
    }

    private void drawBattery(Canvas c) {
        int[] values = {battL, battR, battC};
        // 起点沿垂直方向错开 + 三个墨点
        float[] ox = new float[3];
        float[] oy = new float[3];
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        for (int i = 0; i < 3; i++) {
            float rad = (float) Math.toRadians(ANG[i]);
            ox[i] = BASE_X - (float) Math.sin(rad) * 10f * i;
            oy[i] = BASE_Y + (float) Math.cos(rad) * 10f * i;
            c.drawCircle(ox[i], oy[i], 5f, fill);
        }
        // 斜向极细量角线, 给画面一个不规则的骨架
        line(c, BASE_X - 14f, BASE_Y + 12f, BASE_X + 206f, BASE_Y - 176f, 1.1f, INK_HAIR);

        for (int i = 0; i < 3; i++) {
            int v = values[i];
            float ang = ANG[i];
            float rad = (float) Math.toRadians(ang);
            float dx = (float) Math.cos(rad);
            float dy = (float) Math.sin(rad);
            // 全长极淡轨道(实色粗线 + 中线), 低电量也能看清条的方向与长度
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            stroke.setStrokeWidth(26f);
            stroke.setColor(INK_TRACK);
            c.drawLine(ox[i], oy[i], ox[i] + dx * BATT_MAX, oy[i] + dy * BATT_MAX, stroke);
            stroke.setStrokeWidth(1.4f);
            stroke.setColor(INK_HAIR);
            c.drawLine(ox[i], oy[i], ox[i] + dx * BATT_MAX, oy[i] + dy * BATT_MAX, stroke);
            if (v >= 0) {
                float frac = Math.max(0f, Math.min(1f, v / 100f));
                float len = Math.max(BATT_MAX * 0.16f, BATT_MAX * frac);
                int color = v <= 10 ? CORAL : BATT_COLOR[i];
                stroke.setStrokeWidth(26f);
                stroke.setColor(color);
                c.drawLine(ox[i], oy[i], ox[i] + dx * len, oy[i] + dy * len, stroke);
                if ((chargeFlags & (1 << i)) != 0) {
                    fillPoly(c, parallelogramPath(ox[i] + dx * (len + 22f), oy[i] + dy * (len + 22f),
                            15f, 24f, 7f, ang + 90f), MUSTARD);
                }
            }
            // 单字标签 + 小号百分比: 贴着各自斜杠的侧面, 不会互相叠
            float midT = 0.58f;
            float mx = ox[i] + dx * (BATT_MAX * midT);
            float my = oy[i] + dy * (BATT_MAX * midT);
            float nx = -dy;
            float ny = dx;
            float lx = mx + nx * 34f;
            float ly = my + ny * 34f;
            drawText(c, batteryLabel(i), lx, ly, 22f, INK, tfBlack, ang + 70f, Paint.Align.CENTER);
            String num = v >= 0 ? String.valueOf(v) : "--";
            drawText(c, num, lx, ly + 20f, 13f, INK_SOFT, tfLight, ang + 70f, Paint.Align.CENTER);
        }
    }

    private String batteryLabel(int i) {
        if (singleBatt) {
            return getContext().getString(R.string.label_total);
        }
        String[] shortLabels = {
                getContext().getString(R.string.label_left_short),
                getContext().getString(R.string.label_right_short),
                getContext().getString(R.string.label_case_short)};
        return shortLabels[i];
    }

    // ---------------- P1: 噪声三态 + DeepX ----------------

    private void drawNoisePage(Canvas c) {
        fillPoly(c, parallelogramPath(344f, 76f, 36f, 13f, 11f, -26f), MUSTARD);
        line(c, 24f, 160f, 152f, 132f, 1.3f, INK_HAIR);
        drawText(c, "噪声", 50f, 118f, 34f, INK, tfBlack, -10f, Paint.Align.LEFT);
        drawText(c, noiseLabel(currentNoise()), 54f, 154f, 17f, INK_SOFT, tfLight, -10f,
                Paint.Align.LEFT);

        for (int i = 0; i < 3; i++) {
            float cy = 186f + i * 74f;
            boolean selected = mode == noiseTarget(i);
            Path p = parallelogramPath(216f, cy, 326f, 60f, 22f, -6f);
            if (selected) {
                fillPoly(c, p, ULTRA);
            } else {
                fillPoly(c, p, PAPER);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(2.8f);
                stroke.setColor(INK);
                c.drawPath(p, stroke);
            }
            drawText(c, noiseLabel(i), 216f, cy + 9f, 23f,
                    selected ? PAPER : INK, selected ? tfBlack : tfBold, -6f, Paint.Align.CENTER);
        }
        // 角落小色块, 补节奏
        fillPoly(c, parallelogramPath(64f, 420f, 22f, 9f, 6f, -18f), ULTRA);
        fillPoly(c, parallelogramPath(330f, 424f, 26f, 7f, 7f, 18f), INK);

        // DeepX: 斜排一行, 选中加芥末黄游标
        String[] labels = deepxLabels();
        if (deepxMode >= 0 || (capsMask & 8) != 0) {
            for (int i = 0; i < labels.length; i++) {
                float lx = 66f + i * 72f;
                float ly = 408f + (i - 2) * 2f;
                boolean selected = i == deepxMode;
                drawText(c, labels[i], lx, ly, selected ? 16f : 14f,
                        selected ? INK : INK_SOFT,
                        selected ? tfBlack : tfRegular, -8f, Paint.Align.CENTER);
                if (selected) {
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(MUSTARD);
                    c.drawRect(lx - 15f, ly + 8f, lx + 15f, ly + 13f, fill);
                }
            }
        }
    }

    private int currentNoise() {
        if (mode == 1) return 0;
        if (mode == 2) return 1;
        if (mode == 0) return 2;
        return -1;
    }

    private int noiseTarget(int index) {
        if (index == 0) return 1;
        if (index == 1) return 2;
        return 0;
    }

    private String noiseLabel(int index) {
        if (index < 0) {
            return "--";
        }
        if (index == 0) return getContext().getString(R.string.tile_mode_off);
        if (index == 1) return getContext().getString(R.string.tile_mode_trans);
        return getContext().getString(R.string.tile_mode_anc);
    }

    private String[] deepxLabels() {
        return new String[]{
                getContext().getString(R.string.deepx_std),
                getContext().getString(R.string.deepx_vocal),
                getContext().getString(R.string.deepx_bass),
                getContext().getString(R.string.deepx_treble),
                getContext().getString(R.string.deepx_book)};
    }

    // ---------------- P2: 设置 + 开发者 + 音量 ----------------

    private void drawSetupPage(Canvas c) {
        fillPoly(c, parallelogramPath(344f, 88f, 40f, 14f, 12f, -26f), MUSTARD);
        line(c, 24f, 150f, 190f, 120f, 1.3f, INK_HAIR);
        drawText(c, getContext().getString(R.string.cta_settings), 54f, 118f, 32f, INK,
                tfBlack, -10f, Paint.Align.LEFT);

        // 设置按钮: 炭黑斜条 + 白字
        Path p = parallelogramPath(216f, 184f, 300f, 72f, 30f, -6f);
        fillPoly(c, p, INK);
        drawText(c, getContext().getString(R.string.cta_settings), 216f, 196f, 26f, PAPER,
                tfBold, -6f, Paint.Align.CENTER);
        fillPoly(c, parallelogramPath(358f, 178f, 26f, 18f, 7f, -16f), MUSTARD);

        // 开发者入口: 三道粗斜杠 + 文字
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(7f);
        stroke.setStrokeCap(Paint.Cap.SQUARE);
        stroke.setColor(devUnlocked ? ULTRA : INK);
        for (int i = 0; i < 3; i++) {
            float off = i * 15f;
            c.save();
            c.rotate(-34f, 80f + off, 272f);
            c.drawLine(80f + off, 244f, 80f + off, 300f, stroke);
            c.restore();
        }
        drawText(c, getContext().getString(R.string.dev_section), 160f, 282f, 18f,
                INK_SOFT, tfLight, -14f, Paint.Align.LEFT);
        fillPoly(c, parallelogramPath(330f, 300f, 18f, 7f, 5f, -18f), MUSTARD);

        // 音量(默认隐藏): 斜线 + 大滑块; 隐藏时显示空间音频状态
        if (showVolume) {
            drawVolume(c);
        } else {
            line(c, 64f, 356f, 210f, 340f, 1.2f, INK_HAIR);
            drawText(c, getContext().getString(R.string.spatial_title_short) + "  ·  "
                            + (spatialOn ? "开" : "关"),
                    72f, 344f, 15f, INK_SOFT, tfLight, -10f, Paint.Align.LEFT);
        }
    }

    private static final float VOL_X1 = 66f;
    private static final float VOL_Y1 = 392f;
    private static final float VOL_X2 = 352f;
    private static final float VOL_Y2 = 360f;

    private void drawVolume(Canvas c) {
        line(c, VOL_X1, VOL_Y1, VOL_X2, VOL_Y2, 3.4f, INK);
        float f = volumeMax > 0 ? Math.max(0f, Math.min(1f, volumeLevel / (float) volumeMax)) : 0f;
        float px = VOL_X1 + (VOL_X2 - VOL_X1) * f;
        float py = VOL_Y1 + (VOL_Y2 - VOL_Y1) * f;
        line(c, VOL_X1, VOL_Y1, px, py, 10f, ULTRA);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(PAPER);
        c.drawCircle(px, py, 18f, fill);
        fill.setColor(ULTRA);
        c.drawCircle(px, py, 10f, fill);
        drawText(c, getContext().getString(R.string.volume_title), 58f, 356f, 19f, INK,
                tfBold, -8f, Paint.Align.LEFT);
        drawText(c, volumeLevel + " / " + volumeMax, VOL_X2 - 44f, VOL_Y2 - 16f,
                14f, INK_SOFT, tfLight, -8f, Paint.Align.LEFT);
    }

    // ---------------- 开发者面板 ----------------

    private void drawDevPanel(Canvas c) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(PAPER);
        rect.set(panel);
        c.drawRect(rect, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        stroke.setColor(INK);
        c.drawRect(rect, stroke);
        fillPoly(c, parallelogramPath(146f, 158f, 130f, 18f, 22f, -8f), MUSTARD);
        drawText(c, getContext().getString(R.string.dev_section), 100f, 186f, 20f, INK,
                tfBlack, -6f, Paint.Align.LEFT);
        drawText(c, getContext().getString(R.string.volume_title), 100f, 246f, 16f, INK,
                tfRegular, -6f, Paint.Align.LEFT);
        drawToggle(c, 291f, 241f, showVolume);
        drawText(c, getContext().getString(R.string.spatial_title_short), 100f, 314f, 16f,
                INK, tfRegular, -6f, Paint.Align.LEFT);
        drawToggle(c, 291f, 309f, spatialOn);
        drawText(c, "点空白收起", 100f, 370f, 12f, INK_SOFT, tfLight, -6f, Paint.Align.LEFT);
    }

    private void drawToggle(Canvas c, float cx, float cy, boolean on) {
        Path p = parallelogramPath(cx, cy, 62f, 26f, 10f, -6f);
        if (on) {
            fillPoly(c, p, ULTRA);
        } else {
            fillPoly(c, p, PAPER);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.4f);
            stroke.setColor(INK);
            c.drawPath(p, stroke);
        }
        fillPoly(c, parallelogramPath(cx + (on ? 11f : -11f), cy - 1f, 16f, 20f, 5f, -6f),
                on ? PAPER : INK_SOFT);
    }

    // ---------------- 页码 ----------------

    private void drawPageDots(Canvas c) {
        if (devOpen) {
            return;
        }
        for (int i = 0; i < PAGES; i++) {
            float x = 196f + i * 22f;
            boolean active = i == page;
            line(c, x, 414f, x + (active ? 18f : 9f), 414f - (active ? 6f : 3f),
                    active ? 5f : 3f, active ? INK : INK_HAIR);
        }
    }

    // ---------------- 触摸: 翻页 + 点击 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / (getWidth() / DW);
        float y = e.getY() / (getHeight() / DH);
        int action = e.getActionMasked();
        if (animator != null && animator.isRunning()) {
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            downX = x;
            downY = y;
            dragging = false;
            dragPx = 0f;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - downX;
            float dy = y - downY;
            // 竖着翻页: 左滑是系统返回手势, 不能和它抢
            if (!dragging && Math.abs(dy) > 18f && Math.abs(dy) > Math.abs(dx)) {
                dragging = true;
            }
            if (dragging) {
                float d = dy;
                if ((page == 0 && d > 0) || (page == PAGES - 1 && d < 0)) {
                    d = d * 0.30f; // 两端阻尼
                }
                dragPx = d;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                dragging = false;
                float threshold = DH * 0.16f;
                if (dragPx < -threshold && page < PAGES - 1) {
                    animatePage(page + 1);
                } else if (dragPx > threshold && page > 0) {
                    animatePage(page - 1);
                } else {
                    animateBack();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                handleTap(x, y);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void animatePage(final int target) {
        final float start = dragPx;
        final float end = (target > page) ? -DH : DH;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(260L);
        animator.setInterpolator(new OvershootInterpolator(0.8f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                dragPx = start + (end - start) * a.getAnimatedFraction();
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                page = target;
                dragPx = 0f;
                animator = null;
                invalidate();
            }
        });
        animator.start();
    }

    private void animateBack() {
        final float start = dragPx;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(200L);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                dragPx = start * (1f - a.getAnimatedFraction());
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                dragPx = 0f;
                animator = null;
                invalidate();
            }
        });
        animator.start();
    }

    private void handleTap(float x, float y) {
        if (devOpen) {
            if (hitVolumeToggle.contains(x, y)) {
                tapHaptic();
                if (listener != null) listener.onVolumeToggle(!showVolume);
                return;
            }
            if (hitSpatialToggle.contains(x, y)) {
                tapHaptic();
                if (listener != null) listener.onSpatialToggled(!spatialOn);
                return;
            }
            devOpen = false;
            invalidate();
            return;
        }
        if (page == 0) {
            if (hitRefresh.contains(x, y)) {
                tapHaptic();
                if (listener != null) listener.onRefresh();
            }
            return;
        }
        if (page == 1) {
            for (int i = 0; i < 3; i++) {
                if (hitNoise[i].contains(x, y)) {
                    tapHaptic();
                    if (listener != null) listener.onModeSelected(noiseTarget(i));
                    return;
                }
            }
            int dx = hitDeepx(x, y);
            if (dx >= 0) {
                tapHaptic();
                if (listener != null) listener.onDeepxSelected(dx);
            }
            return;
        }
        // page 2
        if (hitSettings.contains(x, y)) {
            tapHaptic();
            if (listener != null) listener.onSettings();
            return;
        }
        if (hitDev.contains(x, y)) {
            long now = System.currentTimeMillis();
            devTaps = (now - lastDevTapAt > 2000L) ? 1 : devTaps + 1;
            lastDevTapAt = now;
            if (devUnlocked) {
                devOpen = true;
                invalidate();
            } else if (devTaps >= 5) {
                devUnlocked = true;
                devOpen = true;
                devTaps = 0;
                try {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                } catch (RuntimeException ignored) {
                }
                invalidate();
            }
            return;
        }
        if (showVolume && nearVolumeLine(x, y)) {
            updateVolumeAt(x, y);
        }
    }

    private int hitDeepx(float x, float y) {
        if (deepxMode < 0) {
            return -1;
        }
        for (int i = 0; i < 5; i++) {
            float lx = 84f + i * 66f;
            float ly = 396f + (i - 2) * 3f;
            if (Math.abs(x - lx) < 30f && Math.abs(y - ly) < 22f) {
                return i;
            }
        }
        return -1;
    }

    private boolean nearVolumeLine(float x, float y) {
        float dx = VOL_X2 - VOL_X1;
        float dy = VOL_Y2 - VOL_Y1;
        float len2 = dx * dx + dy * dy;
        if (len2 <= 0f) {
            return false;
        }
        float t = ((x - VOL_X1) * dx + (y - VOL_Y1) * dy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float px = VOL_X1 + dx * t;
        float py = VOL_Y1 + dy * t;
        return (float) Math.hypot(x - px, y - py) < 30f;
    }

    private void updateVolumeAt(float x, float y) {
        float dx = VOL_X2 - VOL_X1;
        float dy = VOL_Y2 - VOL_Y1;
        float len2 = dx * dx + dy * dy;
        if (len2 <= 0f) {
            return;
        }
        float t = ((x - VOL_X1) * dx + (y - VOL_Y1) * dy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        int level = Math.round(t * volumeMax);
        if (level != volumeLevel) {
            volumeLevel = level;
            invalidate();
            if (listener != null) {
                listener.onVolumeChanged(level, volumeMax);
            }
        }
    }

    private void tapHaptic() {
        try {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------- 小工具 ----------------

    private void drawText(Canvas c, String s, float x, float y, float size, int color,
                          Typeface tf, float angle, Paint.Align align) {
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTypeface(tf);
        textPaint.setTextAlign(align);
        c.save();
        c.rotate(angle, x, y);
        c.drawText(s, x, y, textPaint);
        c.restore();
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, float width, int color) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(width);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setColor(color);
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    private void fillPoly(Canvas c, Path p, int color) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        c.drawPath(p, fill);
    }

    private Path parallelogramPath(float cx, float cy, float w, float h, float shear, float angle) {
        path.reset();
        float rad = (float) Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float hw = w / 2f;
        float hh = h / 2f;
        float sh = shear / 2f;
        float[][] pts = {
                {-hw - sh, -hh}, {hw - sh, -hh}, {hw + sh, hh}, {-hw + sh, hh}};
        for (int i = 0; i < 4; i++) {
            float px = pts[i][0] * cos - pts[i][1] * sin + cx;
            float py = pts[i][0] * sin + pts[i][1] * cos + cy;
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        return path;
    }

    /** 外部(例如侧键/表圈)翻页: -1 上一屏, +1 下一屏 */
    public void turnPage(int delta) {
        if (animator != null && animator.isRunning()) {
            return;
        }
        int target = page + delta;
        if (target < 0 || target >= PAGES) {
            return;
        }
        animatePage(target);
    }
}
