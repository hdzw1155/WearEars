package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * 极简样式磁贴电量区: 上方左右耳双卡并排 + 下方充电盒通栏长卡. 444x228px.
 *
 * <p>按用户提供的设计图重排: 左卡左对齐"数字%"、右卡镜像"%数字"、盒卡右侧"⚡%数字",
 * 三条横向进度条均贴卡片底部; 其余布局(顶栏/模式胶囊/打开控制)不动.
 */
final class BarsTilePainter {

    static final int WIDTH_PX = 444;
    static final int HEIGHT_PX = 228;

    private static final float GAP_X = 12f;
    private static final float GAP_Y = 12f;
    private static final float CARD_TOP_W = (WIDTH_PX - GAP_X) / 2f; // 216f
    private static final float CARD_TOP_H = 130f;
    private static final float CARD_BOT_H = HEIGHT_PX - CARD_TOP_H - GAP_Y; // 86f
    private static final float CARD_RADIUS = 32f;

    /** 卡片左右内边距(设计图像素) */
    private static final float PAD_X = 17f;
    /** 进度条粗细 */
    private static final float BAR_H = 13f;

    private static final int COLOR_CARD = 0xFF141416;
    private static final int COLOR_STROKE = 0x14FFFFFF;
    private static final int COLOR_TRACK = 0xFF1E1E21;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0x99F2F2F5;
    private static final int COLOR_OK = 0xFF4CC38A;
    private static final int COLOR_WARN = 0xFFF5A524;
    private static final int COLOR_LOW = 0xFFF0524F;
    private static final int COLOR_UNKNOWN = 0x85F2F2F5;
    private static final float LOW_PCT = 20f;

    private static final float LABEL_SIZE = 25f;
    private static final float NUM_SIZE = 44f;
    private static final float PCT_SIZE = 28f;
    private static final float CASE_NUM_SIZE = 42f;
    private static final float CASE_PCT_SIZE = 27f;

    private BarsTilePainter() {
    }

    static byte[] png(Context ctx, TileSnapshot s) {
        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(COLOR_CARD);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.5f);
        strokePaint.setColor(COLOR_STROKE);

        Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(COLOR_TRACK);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(COLOR_DIM);
        labelPaint.setTextSize(LABEL_SIZE);

        Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numPaint.setFakeBoldText(true);
        numPaint.setTextSize(NUM_SIZE);
        numPaint.setColor(COLOR_TEXT);

        Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pctPaint.setTextSize(PCT_SIZE);
        pctPaint.setColor(COLOR_TEXT);

        RectF rectL = new RectF(0, 0, CARD_TOP_W, CARD_TOP_H);
        RectF rectR = new RectF(CARD_TOP_W + GAP_X, 0, WIDTH_PX, CARD_TOP_H);
        RectF rectC = new RectF(0, CARD_TOP_H + GAP_Y, WIDTH_PX, HEIGHT_PX);

        drawCardBg(c, rectL, cardPaint, strokePaint);
        drawCardBg(c, rectR, cardPaint, strokePaint);
        drawCardBg(c, rectC, cardPaint, strokePaint);

        // ---- 左耳: 左对齐 "数字%" ----
        int lvlL = s.battL;
        boolean knownL = TileSnapshot.batteryKnown(lvlL);
        boolean chgL = s.charging(TileSnapshot.FLAG_CHARGE_LEFT);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(safe(ctx, R.string.tile_ring_left), rectL.left + PAD_X, rectL.top + 40f, labelPaint);

        if (knownL) {
            numPaint.setTextAlign(Paint.Align.LEFT);
            numPaint.setColor(COLOR_TEXT);
            String valStr = String.valueOf(lvlL);
            float numX = rectL.left + PAD_X;
            float baselineY = rectL.top + 92f;
            c.drawText(valStr, numX, baselineY, numPaint);
            float numW = numPaint.measureText(valStr);
            pctPaint.setTextAlign(Paint.Align.LEFT);
            pctPaint.setColor(COLOR_TEXT);
            float pctX = numX + numW + 3f;
            c.drawText("%", pctX, baselineY, pctPaint);

            if (chgL) {
                float pctW = pctPaint.measureText("%");
                drawBolt(c, pctX + pctW + 7f, baselineY - 26f, 15f, 21f, COLOR_WARN);
            }
        } else {
            numPaint.setTextAlign(Paint.Align.LEFT);
            numPaint.setColor(COLOR_UNKNOWN);
            c.drawText("--", rectL.left + PAD_X, rectL.top + 92f, numPaint);
        }

        drawBar(c, rectL.left + PAD_X, rectL.right - PAD_X, rectL.top + 105f,
                BAR_H, lvlL, knownL, chgL, trackPaint, fillPaint);

        // ---- 右耳: 右对齐 "%数字" ----
        int lvlR = s.battR;
        boolean knownR = TileSnapshot.batteryKnown(lvlR);
        boolean chgR = s.charging(TileSnapshot.FLAG_CHARGE_RIGHT);

        labelPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(safe(ctx, R.string.tile_ring_right), rectR.right - PAD_X, rectR.top + 40f, labelPaint);

        if (knownR) {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_TEXT);
            String valStr = String.valueOf(lvlR);
            float rightX = rectR.right - PAD_X;
            float baselineY = rectR.top + 92f;
            c.drawText(valStr, rightX, baselineY, numPaint);
            float numW = numPaint.measureText(valStr);
            pctPaint.setTextAlign(Paint.Align.RIGHT);
            pctPaint.setColor(COLOR_TEXT);
            float pctRight = rightX - numW - 4f;
            c.drawText("%", pctRight, baselineY, pctPaint);

            if (chgR) {
                float pctW = pctPaint.measureText("%");
                drawBolt(c, pctRight - pctW - 7f - 15f, baselineY - 26f, 15f, 21f, COLOR_WARN);
            }
        } else {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_UNKNOWN);
            c.drawText("--", rectR.right - PAD_X, rectR.top + 92f, numPaint);
        }

        drawBar(c, rectR.left + PAD_X, rectR.right - PAD_X, rectR.top + 105f,
                BAR_H, lvlR, knownR, chgR, trackPaint, fillPaint);

        // ---- 充电盒: 左上"盒", 右侧"⚡%数字", 通栏条 ----
        int lvlC = s.battC;
        boolean knownC = TileSnapshot.batteryKnown(lvlC);
        boolean chgC = s.charging(TileSnapshot.FLAG_CHARGE_CASE);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(safe(ctx, R.string.tile_ring_case), rectC.left + PAD_X, rectC.top + 42f, labelPaint);

        float caseRightX = rectC.right - PAD_X;

        numPaint.setTextSize(CASE_NUM_SIZE);
        pctPaint.setTextSize(CASE_PCT_SIZE);

        if (knownC) {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_TEXT);
            String valStr = String.valueOf(lvlC);
            float baselineY = rectC.top + 44f;
            c.drawText(valStr, caseRightX, baselineY, numPaint);
            float numW = numPaint.measureText(valStr);
            pctPaint.setTextAlign(Paint.Align.RIGHT);
            pctPaint.setColor(COLOR_TEXT);
            float pctRight = caseRightX - numW - 4f;
            c.drawText("%", pctRight, baselineY, pctPaint);

            if (chgC) {
                float pctW = pctPaint.measureText("%");
                drawBolt(c, pctRight - pctW - 8f - 17f, baselineY - 25f, 17f, 24f, COLOR_WARN);
            }
        } else {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_UNKNOWN);
            c.drawText("--", caseRightX, rectC.top + 44f, numPaint);
        }

        drawBar(c, rectC.left + PAD_X, rectC.right - PAD_X, rectC.top + 58f,
                BAR_H, lvlC, knownC, chgC, trackPaint, fillPaint);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static void drawCardBg(Canvas c, RectF r, Paint fill, Paint stroke) {
        c.drawRoundRect(r, CARD_RADIUS, CARD_RADIUS, fill);
        c.drawRoundRect(r, CARD_RADIUS, CARD_RADIUS, stroke);
    }

    private static void drawBar(Canvas c, float x1, float x2, float y, float h,
                                int lvl, boolean known, boolean charging,
                                Paint trackPaint, Paint fillPaint) {
        float r = h / 2f;
        RectF track = new RectF(x1, y, x2, y + h);
        c.drawRoundRect(track, r, r, trackPaint);

        if (known && lvl > 0) {
            int color = charging ? COLOR_WARN : (lvl <= LOW_PCT ? COLOR_LOW : COLOR_OK);
            fillPaint.setColor(color);
            float fillW = (x2 - x1) * Math.min(100, lvl) / 100f;
            RectF fill = new RectF(x1, y, Math.max(x1 + h, x1 + fillW), y + h);
            c.drawRoundRect(fill, r, r, fillPaint);
        }
    }

    /** 绘制闪电矢量图标: 依据 ic_bolt.xml 几何 (M13,2.5 L5.5,13.5 L10.5,13.5 L10,21.5 L19,10 L13.5,10 Z) */
    private static void drawBolt(Canvas c, float x, float y, float w, float h, int color) {
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

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    private static String safe(Context ctx, int resId) {
        try {
            return ctx.getString(resId);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
