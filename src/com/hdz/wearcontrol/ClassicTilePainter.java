package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * 磁贴主视觉: 一张 528 x 276 px 的复合仪表盘 PNG(透明底), 一次画完三层同心环 + 全部文字。
 *
 * <p>规格来源: DSH\_存档\design\tile-a\spec.md 第 3 节, 视觉在 a2.x 之后按用户反馈持续收敛:
 * <ul>
 *   <li>a2.0: 配色统一到主 App(翠绿/薄荷青/暖白), 橙色圆底白闪电徽章, 去掉直角描边方框;</li>
 *   <li>a2.1: 环线再细 12%, 三环间距拉大; 圆心去掉"盒"字与充电徽章, 只留居中的百分比,
 *       充电时在数字正上方画一枚"纯橙色小闪电"; 弧长严格 = pct / 100 * 360(不再做帽宽补偿),
 *       以免"31% 看起来像 48%"的观感; 顶部与底部胶囊的字号/间距/颜色也一起收敛。</li>
 * </ul>
 *
 * <p>被真机坑过、改之前先看清楚的三条:
 * <ul>
 *   <li>画完弧后 Paint 停在 {@link Paint.Style#STROKE}, <b>写字前必须 setStyle(FILL)</b>,
 *       否则百分比会被画成空心描边(0.3.2 糊成一坨的根因)。</li>
 *   <li>电量未知(-1 / 上报 0xFF)不画弧, 数字位置画 "--"; 充电标志未知时一张闪电都不画。</li>
 *   <li>PNG 是透明底, 磁贴底色由系统画; 不要给画布填任何背景色。</li>
 * </ul>
 */
final class ClassicTilePainter {

    /** 出图尺寸(px); {@link TileResources} 里的 widthPx/heightPx 必须与它一致 */
    static final int WIDTH_PX = 528;
    static final int HEIGHT_PX = 276;

    // ---- 几何(px, 1 dp = 3 px) ----
    private static final float CX = 264f;   // dp 88
    private static final float CY = 138f;   // dp 46

    /** 三层环半径: 外=左耳 / 中=右耳 / 内=充电盒(环间距比 a2.0 再大 2px/侧) */
    private static final float R_LEFT = 116f;
    private static final float R_RIGHT = 85f;
    private static final float R_CASE = 54f;
    /** 线宽 5dp(15px, 比 a2.0 再细 1dp): 越纤巧越有器械感 */
    private static final float STROKE = 15f;

    /** 环外读数锚点: 左列右对齐 / 右列左对齐; 与环外缘(半径+半线宽=123.5px)留 23.5px≈8dp */
    private static final float X_LEFT_TEXT = 117f;
    private static final float X_RIGHT_TEXT = 411f;
    /** 环外读数中心 y: 标签 / 百分比 */
    private static final float Y_SIDE_LABEL = 104f;
    private static final float Y_SIDE_PCT = 168f;

    /** 字号(px): 侧标签 11sp / 数字 15sp 粗 / 环心数字 14sp */
    private static final float SIZE_SIDE_LABEL = 33f;
    private static final float SIZE_PCT = 45f;
    private static final float SIZE_HUB_PCT = 42f;
    private static final float SIZE_PCT_MIN = 36f;   // "100%" 放不下时缩到这里

    /** 进度弧: 1% = 3.6°; 低电阈值 20% */
    private static final float FULL_SWEEP = 360f;
    private static final int LOW_BATTERY_PCT = 20;

    /** 左右耳充电徽章: 半径 11px(3.7dp), 落在该环 12 点 */
    private static final float BADGE_R = 11f;
    private static final float BADGE_BOLT_HALF_H = 6.5f;

    // ---- 配色 ----
    /** 未填充底轨: 比磁贴底 #101316 亮一档, 有存在感但不抢戏 */
    private static final int COLOR_TRACK = 0xFF1C1C1E;
    private static final int COLOR_DIM = 0xFF8E8E93;
    private static final int COLOR_FAINT = 0xFF6E6E73;
    /** 三环品牌色(与主界面同源: 翠绿 -> 薄荷青 -> 暖白) */
    private static final int COLOR_LEFT = 0xFF34C759;
    private static final int COLOR_RIGHT = 0xFF00C7BE;
    private static final int COLOR_CASE = 0xFFE5E5EA;
    private static final int COLOR_LOW = 0xFFDF6863;
    /** 充电徽章/小闪电: 橙底白闪电(与主界面 BatteryRingView 同色) */
    private static final int COLOR_CHARGE_BG = 0xFFFF9F0A;
    private static final int COLOR_CHARGE_FG = 0xFFFFFFFF;

    private ClassicTilePainter() {
    }

    /**
     * 画一张复合仪表盘并编码成 PNG(后台线程调用; 内部自建自回收 Bitmap)。
     */
    static byte[] png(Context ctx, TileSnapshot s) {
        String labelLeft = safeLabel(ctx, R.string.tile_ring_left, "左");
        String labelRight = safeLabel(ctx, R.string.tile_ring_right, "右");

        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Canvas canvas = new Canvas(bmp);
            // 透明底: 磁贴底色由系统画, 这里绝不能填背景色
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setDither(true);
            paint.setSubpixelText(true);

            RectF oval = new RectF();
            Path bolt = new Path();

            drawRings(canvas, paint, oval, s);
            drawSideChargeBadges(canvas, paint, bolt, s);
            drawTexts(canvas, paint, s, labelLeft, labelRight);

            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            // 528x276 ARGB 约 583KB, 编码抛异常也不能漏回收
            bmp.recycle();
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- 环

    /** 三环: 先画整圈轨道, 再画进度弧(12 点起、顺时针) */
    private static void drawRings(Canvas c, Paint p, RectF oval, TileSnapshot s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(COLOR_TRACK);
        c.drawCircle(CX, CY, R_LEFT, p);
        c.drawCircle(CX, CY, R_RIGHT, p);
        c.drawCircle(CX, CY, R_CASE, p);

        arc(c, p, oval, R_LEFT, s.battL, arcColor(s.battL, COLOR_LEFT));
        arc(c, p, oval, R_RIGHT, s.battR, arcColor(s.battR, COLOR_RIGHT));
        arc(c, p, oval, R_CASE, s.battC, caseColor(s));
    }

    /**
     * 充电盒充电时整圈变暖橙(与主界面充电徽章同色, 也是"正在充电"的通用语义);
     * 充电时不降级成低电珊瑚 —— 正在充的盒子不该报警。
     */
    private static int caseColor(TileSnapshot s) {
        if (s.chargeFlags != TileSnapshot.UNKNOWN
                && s.charging(TileSnapshot.FLAG_CHARGE_CASE)) {
            return COLOR_CHARGE_BG;
        }
        return arcColor(s.battC, COLOR_CASE);
    }

    /** 弧/数字的最终颜色: 未知不参与(调用点会先判未知), 低电转珊瑚 */
    private static int arcColor(int pct, int baseColor) {
        if (!TileSnapshot.batteryKnown(pct)) {
            return baseColor;
        }
        return pct <= LOW_BATTERY_PCT ? COLOR_LOW : baseColor;
    }

    /** 环心数字颜色: 未知灰 / 充电中暖橙 / 低电珊瑚 / 正常暖白 */
    private static int hubTextColor(TileSnapshot s) {
        if (!TileSnapshot.batteryKnown(s.battC)) {
            return COLOR_FAINT;
        }
        if (s.chargeFlags != TileSnapshot.UNKNOWN
                && s.charging(TileSnapshot.FLAG_CHARGE_CASE)) {
            return COLOR_CHARGE_BG;
        }
        return s.battC <= LOW_BATTERY_PCT ? COLOR_LOW : COLOR_CASE;
    }

    /**
     * 进度弧: sweep 严格 = pct / 100 * 360, 没有任何保底角度、没有帽宽修正。
     * 圆头帽在 5dp 线宽 + 15dp 半径下只多出 ≈±2°, 肉眼可忽略; 用户明确要求弧长与百分比严格对应。
     * 颜色由调用方算好(含低电/充电语义), 这里不猜。
     */
    private static void arc(Canvas c, Paint p, RectF oval, float r, int pct, int color) {
        if (!TileSnapshot.batteryKnown(pct) || pct <= 0) {
            return;   // 未知 / 0%: 只留轨道
        }
        float sweep = Math.min(FULL_SWEEP, pct * 3.6f);
        oval.set(CX - r, CY - r, CX + r, CY + r);
        p.setColor(color);
        c.drawArc(oval, -90f, sweep, false, p);
    }

    // ---------------------------------------------------------------- 充电提示

    /**
     * 左右耳充电: 在该环带的 12 点画"橙底白闪电"小徽章(与主界面语言一致)。
     * 充电盒的充电提示不走这里 —— 它不画闪电, 直接让内圈整圈变暖橙(见 {@link #caseColor}),
     * 这样圆心只有百分比, 不会又出现一枚"贴纸"。
     */
    private static void drawSideChargeBadges(Canvas c, Paint p, Path bolt, TileSnapshot s) {
        if (s.chargeFlags == TileSnapshot.UNKNOWN) {
            return;   // 不知道就不画, 别凭空说耳机在充电
        }
        if (s.charging(TileSnapshot.FLAG_CHARGE_LEFT)) {
            chargeBadge(c, p, bolt, R_LEFT);
        }
        if (s.charging(TileSnapshot.FLAG_CHARGE_RIGHT)) {
            chargeBadge(c, p, bolt, R_RIGHT);
        }
    }

    private static void chargeBadge(Canvas c, Paint p, Path bolt, float ringRadius) {
        float x = CX;
        float y = CY - ringRadius;   // 环带 12 点(进度弧起点)

        p.setStyle(Paint.Style.FILL);
        p.setColor(COLOR_CHARGE_BG);
        c.drawCircle(x, y, BADGE_R, p);
        boltAt(c, p, bolt, x, y, BADGE_BOLT_HALF_H, COLOR_CHARGE_FG);
    }

    /** 在 (x,y) 处画一枚居中的闪电; halfH 控制高度 */
    private static void boltAt(Canvas c, Paint p, Path bolt, float x, float y,
                               float halfH, int color) {
        float h = halfH;
        float w = h * 0.75f;
        bolt.reset();
        // 一道细长闪电: 长度 2h + 40% 横向错位
        bolt.moveTo(x + w, y - h);
        bolt.lineTo(x - w, y + h * 0.72f);
        bolt.lineTo(x - w * 0.42f, y + h * 0.05f);
        bolt.lineTo(x - w * 0.82f, y + h);
        bolt.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(bolt, p);
    }

    // ---------------------------------------------------------------- 文字

    private static void drawTexts(Canvas c, Paint p, TileSnapshot s,
                                  String labelLeft, String labelRight) {
        // 上一步画完弧, Paint 还停在 STROKE: 写字之前必须切回 FILL, 否则字变空心
        p.setStyle(Paint.Style.FILL);

        float sideMaxW = X_LEFT_TEXT - 4f;   // 左列可用宽度(右对齐, 从画布左边到锚点)
        float sideMaxWRight = WIDTH_PX - 4f - X_RIGHT_TEXT;

        // 左耳: 标签 + 数字都右对齐贴着外环
        text(c, p, labelLeft, X_LEFT_TEXT, Y_SIDE_LABEL, SIZE_SIDE_LABEL,
                COLOR_DIM, Paint.Align.RIGHT, false);
        drawPct(c, p, s.battL, readoutColor(s.battL, COLOR_LEFT), X_LEFT_TEXT, Y_SIDE_PCT,
                SIZE_PCT, Paint.Align.RIGHT, sideMaxW);

        // 右耳: 左对齐
        text(c, p, labelRight, X_RIGHT_TEXT, Y_SIDE_LABEL, SIZE_SIDE_LABEL,
                COLOR_DIM, Paint.Align.LEFT, false);
        drawPct(c, p, s.battR, readoutColor(s.battR, COLOR_RIGHT), X_RIGHT_TEXT, Y_SIDE_PCT,
                SIZE_PCT, Paint.Align.LEFT, sideMaxWRight);

        // 环心: 只放盒电量, 去掉"盒"字(内圈默认就是充电盒), 让数字四周留出干净的黑色;
        // 充电时数字跟着整圈变暖橙, 不额外画闪电
        float hubMaxW = 2f * (R_CASE - STROKE / 2f) - 12f;
        drawPct(c, p, s.battC, hubTextColor(s), CX, CY, SIZE_HUB_PCT,
                Paint.Align.CENTER, hubMaxW);
    }

    /**
     * 画百分比数字: 先按目标字号测量, 放不下就按比例缩到上限内(不低于 SIZE_PCT_MIN)。
     * 这样 "100%" 既不会被环带切掉, 也不会因为预案不足而溢出画布。
     */
    private static void drawPct(Canvas c, Paint p, int pct, int color,
                                float x, float centerY, float sizePx, Paint.Align align,
                                float maxWidth) {
        p.setStyle(Paint.Style.FILL);
        p.setFakeBoldText(true);
        p.setTextSize(sizePx);
        String s = pctText(pct);
        float w = p.measureText(s);
        if (w > maxWidth && w > 0f) {
            float fitted = Math.max(SIZE_PCT_MIN, sizePx * maxWidth / w);
            p.setTextSize(fitted);
        }
        p.setColor(color);
        p.setTextAlign(align);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = centerY - (fm.ascent + fm.descent) / 2f;
        c.drawText(s, x, baseline, p);
    }

    /**
     * 以 (x, centerY) 为视觉中心画一行字: baseline 用 FontMetrics 算, 不手填。
     * 注意 Android 的 ascent 是负数, 所以这里是 {@code cy - (ascent + descent) / 2},
     * 等价于设计稿 Java2D 里的 {@code cy + (ascent - descent) / 2}(Java2D 的 ascent 为正)。
     */
    private static void text(Canvas c, Paint p, String s, float x, float centerY, float sizePx,
                             int color, Paint.Align align, boolean bold) {
        p.setStyle(Paint.Style.FILL);
        p.setFakeBoldText(bold);
        p.setColor(color);
        p.setTextSize(sizePx);
        p.setTextAlign(align);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = centerY - (fm.ascent + fm.descent) / 2f;
        c.drawText(s, x, baseline, p);
    }

    /** 数字颜色: 未知灰 / 低电珊瑚 / 正常用该部件本色 */
    private static int readoutColor(int pct, int baseColor) {
        if (!TileSnapshot.batteryKnown(pct)) {
            return COLOR_FAINT;
        }
        if (pct <= LOW_BATTERY_PCT) {
            return COLOR_LOW;
        }
        return baseColor;
    }

    private static String pctText(int pct) {
        return TileSnapshot.batteryKnown(pct) ? pct + "%" : "--";
    }

    /** 画图时绝不因为一条字符串资源缺失就把整张资源表拖垮 */
    private static String safeLabel(Context ctx, int resId, String fallback) {
        try {
            return ctx.getString(resId);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
