package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * 至上主义 × 北方工业风格全屏无字磁贴画布 (450x450px):
 * - 暖米白纸底 #F6F5F0, 全屏不出现任何文字
 * - 左上三条汇聚斜光束(蓝/红/橙): 左耳 / 右耳 / 充电盒 电量进度
 * - 中右平行三枚同心圆靶(蓝/橙/红): 关闭 / 通透 / 降噪 切换按钮(当前档加粗黑环强化)
 * - 右下赤红月牙弧块: 打开主控制界面
 */
final class SuprematistTilePainter {

    static final int WIDTH_PX = 450;
    static final int HEIGHT_PX = 450;
    static final int BG_PX = 450;

    static final int COLOR_POSTER_BG = 0xFFF6F5F0;
    private static final int COLOR_PAPER = 0xFFFBFBF8;
    private static final int COLOR_INK = 0xFF111317;
    private static final int COLOR_BLUE = 0xFF0042B8;
    private static final int COLOR_BLUE_TRACK = 0xFFD0DCF2;
    private static final int COLOR_RED = 0xFFE01A22;
    private static final int COLOR_RED_TRACK = 0xFFF5CFD1;
    private static final int COLOR_ORANGE = 0xFFF58200;
    private static final int COLOR_ORANGE_TRACK = 0xFFFCE0C2;

    /** 三条斜光束坐标 [左耳/右耳/盒][lbX, lbY, ltX, ltY, rtX, rtY, rbX, rbY] (归一化 0..1) */
    private static final float[][] BEAM_COORDS = {
            {0.080f, 0.485f, 0.128f, 0.375f, 0.775f, 0.122f, 0.770f, 0.130f},
            {0.095f, 0.578f, 0.135f, 0.492f, 0.780f, 0.134f, 0.775f, 0.142f},
            {0.128f, 0.655f, 0.162f, 0.580f, 0.785f, 0.146f, 0.780f, 0.154f}
    };
    private static final int[] BEAM_COLORS = {COLOR_BLUE, COLOR_RED, COLOR_ORANGE};
    private static final int[] BEAM_TRACKS = {COLOR_BLUE_TRACK, COLOR_RED_TRACK, COLOR_ORANGE_TRACK};

    /** 中右三个同心圆靶中心与对应模式 */
    private static final float[] TARGET_CX = {0.485f, 0.600f, 0.715f};
    private static final float[] TARGET_CY = {0.625f, 0.485f, 0.345f};
    private static final int[] TARGET_MODES = {TwsClient.MODE_OFF, TwsClient.MODE_TRANS, TwsClient.MODE_ANC};
    private static final int[] TARGET_COLORS = {COLOR_BLUE, COLOR_ORANGE, COLOR_RED};

    private SuprematistTilePainter() {
    }

    /** 兜底纯底图 (仅兼容引用; 至上磁贴主体直接用 450x450 的 png()). */
    static byte[] bgPng() {
        Bitmap bmp = Bitmap.createBitmap(BG_PX, BG_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(COLOR_POSTER_BG);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    static byte[] png(Context ctx, TileSnapshot s) {
        int w = WIDTH_PX;
        int h = HEIGHT_PX;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(COLOR_POSTER_BG);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);

        // 1. 左上三条汇聚斜光束 (左 / 右 / 盒 电量)
        boolean single = s.isSingleBattery();
        int[] levels = single
                ? new int[]{s.battTotal, s.battTotal, s.battTotal}
                : new int[]{s.battL, s.battR, s.battC};
        boolean[] charges = single
                ? new boolean[]{s.charging(TileSnapshot.FLAG_CHARGE_LEFT), false, false}
                : new boolean[]{
                        s.charging(TileSnapshot.FLAG_CHARGE_LEFT),
                        s.charging(TileSnapshot.FLAG_CHARGE_RIGHT),
                        s.charging(TileSnapshot.FLAG_CHARGE_CASE)
                };

        Path beamPath = new Path();
        Path fillPath = new Path();
        for (int i = 0; i < 3; i++) {
            drawBeam(c, w, h, BEAM_COORDS[i], levels[i], charges[i],
                    BEAM_COLORS[i], BEAM_TRACKS[i], fill, stroke, beamPath, fillPath);
        }

        // 2. 中右平行三个同心圆靶 (关闭 / 通透 / 降噪)
        int activeMode = s.pendingMode >= 0 ? s.pendingMode : s.mode;
        float baseR = 0.068f * w;
        for (int i = 0; i < 3; i++) {
            float cx = TARGET_CX[i] * w;
            float cy = TARGET_CY[i] * h;
            boolean active = activeMode == TARGET_MODES[i];
            if (active) {
                // 选中态: 轻微阴影 + 外层细黑光晕环 + 加粗黑主环 + 白中环 + 放大彩色内芯
                fill.setColor(0x28111317);
                c.drawCircle(cx + 2.5f, cy + 3.5f, baseR * 1.10f, fill);

                stroke.setColor(COLOR_INK);
                stroke.setStrokeWidth(2.5f);
                c.drawCircle(cx, cy, baseR * 1.22f, stroke);

                fill.setColor(COLOR_INK);
                c.drawCircle(cx, cy, baseR * 1.08f, fill);

                fill.setColor(COLOR_PAPER);
                c.drawCircle(cx, cy, baseR * 0.66f, fill);

                fill.setColor(TARGET_COLORS[i]);
                c.drawCircle(cx, cy, baseR * 0.44f, fill);
            } else {
                fill.setColor(COLOR_INK);
                c.drawCircle(cx, cy, baseR, fill);

                fill.setColor(COLOR_PAPER);
                c.drawCircle(cx, cy, baseR * 0.68f, fill);

                fill.setColor(TARGET_COLORS[i]);
                c.drawCircle(cx, cy, baseR * 0.38f, fill);
            }
        }

        // 3. 右下红色月牙弧块 (打开控制)
        drawCrescent(c, w, h, fill, beamPath);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static void drawBeam(Canvas c, int w, int h, float[] pts, int lvl, boolean charging,
                                 int color, int trackColor, Paint fill, Paint stroke,
                                 Path beamPath, Path fillPath) {
        float lbx = pts[0] * w, lby = pts[1] * h;
        float ltx = pts[2] * w, lty = pts[3] * h;
        float rtx = pts[4] * w, rty = pts[5] * h;
        float rbx = pts[6] * w, rby = pts[7] * h;

        beamPath.reset();
        beamPath.moveTo(lbx, lby);
        beamPath.lineTo(ltx, lty);
        beamPath.lineTo(rtx, rty);
        beamPath.lineTo(rbx, rby);
        beamPath.close();

        boolean known = TileSnapshot.batteryKnown(lvl);
        if (!known) {
            fill.setColor(color);
            c.drawPath(beamPath, fill);
            return;
        }

        // 已知电量: 浅色底槽 + 细暗描边 + 按电量比例裁剪实心主色
        fill.setColor(trackColor);
        c.drawPath(beamPath, fill);

        float frac = lvl <= 0 ? 0f : Math.max(0.08f, Math.min(1f, lvl / 100f));
        if (frac > 0f) {
            float cutBx = lerp(lbx, rbx, frac);
            float cutBy = lerp(lby, rby, frac);
            float cutTx = lerp(ltx, rtx, frac);
            float cutTy = lerp(lty, rty, frac);

            fillPath.reset();
            fillPath.moveTo(lbx, lby);
            fillPath.lineTo(ltx, lty);
            fillPath.lineTo(cutTx, cutTy);
            fillPath.lineTo(cutBx, cutBy);
            fillPath.close();

            c.save();
            c.clipPath(beamPath);
            fill.setColor(color);
            c.drawPath(fillPath, fill);

            if (lvl <= 20) {
                stroke.setColor(COLOR_INK);
                stroke.setStrokeWidth(2.6f);
                c.drawLine(cutBx, cutBy, cutTx, cutTy, stroke);
            }
            c.restore();
        }

        stroke.setColor(0x55111317);
        stroke.setStrokeWidth(1.8f);
        c.drawPath(beamPath, stroke);

        if (charging) {
            float midLx = (lbx + ltx) * 0.5f;
            float midLy = (lby + lty) * 0.5f;
            float midRx = (rbx + rtx) * 0.5f;
            float midRy = (rby + rty) * 0.5f;
            float boltCx = lerp(midLx, midRx, 0.10f);
            float boltCy = lerp(midLy, midRy, 0.10f);
            float bw = 13f;
            float bh = 18f;
            drawBolt(c, boltCx - bw * 0.5f, boltCy - bh * 0.5f, bw, bh, COLOR_INK, fill);
        }
    }

    private static void drawCrescent(Canvas c, int w, int h, Paint fill, Path p) {
        float cx = 0.50f * w;
        float cy = 0.50f * h;
        float r = 0.472f * w;
        float startRad = (float) Math.toRadians(14.0);
        float startX = cx + r * (float) Math.cos(startRad);
        float startY = cy + r * (float) Math.sin(startRad);

        RectF arc = new RectF(cx - r, cy - r, cx + r, cy + r);
        p.reset();
        p.moveTo(startX, startY);
        p.arcTo(arc, 14f, 92f, false);
        p.quadTo(0.68f * w, 0.73f * h, startX, startY);
        p.close();

        fill.setColor(COLOR_RED);
        c.drawPath(p, fill);
    }

    private static void drawBolt(Canvas c, float x, float y, float w, float h, int color, Paint fill) {
        float sx = w / 24f;
        float sy = h / 24f;
        Path p = new Path();
        p.moveTo(x + 13f * sx, y + 2.5f * sy);
        p.lineTo(x + 5.5f * sx, y + 13.5f * sy);
        p.lineTo(x + 10.5f * sx, y + 13.5f * sy);
        p.lineTo(x + 10f * sx, y + 21.5f * sy);
        p.lineTo(x + 19f * sx, y + 10f * sy);
        p.lineTo(x + 13.5f * sx, y + 10f * sy);
        p.close();
        fill.setColor(color);
        c.drawPath(p, fill);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
