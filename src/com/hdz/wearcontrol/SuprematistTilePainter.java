package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * Suprematist Wear OS Tile battery panel (444x228px): warm-paper sharp cards
 * framed in heavy ink lines with primary-color Constructivist corner blocks.
 */
final class SuprematistTilePainter {

    static final int WIDTH_PX = 444;
    static final int HEIGHT_PX = 228;

    private static final float GAP_X = 12f;
    private static final float GAP_Y = 12f;
    private static final float CARD_TOP_W = (WIDTH_PX - GAP_X) / 2f;
    private static final float CARD_TOP_H = 130f;
    private static final float CARD_RADIUS = 6f;

    private static final float PAD_X = 17f;
    private static final float BAR_H = 11f;
    private static final float LOW_PCT = 20f;

    private static final int COLOR_PAPER = 0xFFF6F6F2;
    private static final int COLOR_INK = 0xFF101316;
    private static final int COLOR_DIM = 0xFF5E646E;
    private static final int COLOR_UNKNOWN = 0xFF8C939E;
    private static final int COLOR_TRACK = 0xFFE2E2DC;
    private static final int COLOR_OK = 0xFF2E7D52;
    private static final int COLOR_WARN = 0xFFD97706;
    private static final int COLOR_LOW = 0xFFC9433E;
    private static final int COLOR_BLUE = 0xFF1E4182;
    private static final int COLOR_YELLOW = 0xFFEAB31A;

    private static final float LABEL_SIZE = 24f;
    private static final float NUM_SIZE = 44f;
    private static final float PCT_SIZE = 26f;
    private static final float CASE_NUM_SIZE = 40f;
    private static final float CASE_PCT_SIZE = 25f;

    private SuprematistTilePainter() {
    }

    static byte[] png(Context ctx, TileSnapshot s) {
        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(COLOR_PAPER);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3.2f);
        strokePaint.setColor(COLOR_INK);

        Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(COLOR_TRACK);
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setFakeBoldText(true);
        labelPaint.setColor(COLOR_DIM);
        labelPaint.setTextSize(LABEL_SIZE);

        Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numPaint.setFakeBoldText(true);
        numPaint.setTextSize(NUM_SIZE);

        Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pctPaint.setFakeBoldText(true);
        pctPaint.setTextSize(PCT_SIZE);
        pctPaint.setColor(COLOR_INK);

        RectF rectL = new RectF(1.5f, 1.5f, CARD_TOP_W, CARD_TOP_H);
        RectF rectR = new RectF(CARD_TOP_W + GAP_X, 1.5f, WIDTH_PX - 1.5f, CARD_TOP_H);
        RectF rectC = new RectF(1.5f, CARD_TOP_H + GAP_Y, WIDTH_PX - 1.5f, HEIGHT_PX - 1.5f);

        drawCardBg(c, rectL, cardPaint, strokePaint, accentPaint, COLOR_LOW, false);
        drawCardBg(c, rectR, cardPaint, strokePaint, accentPaint, COLOR_BLUE, true);
        drawCardBg(c, rectC, cardPaint, strokePaint, accentPaint, COLOR_YELLOW, false);

        // Left bud (aligned left)
        int lvlL = s.battL;
        boolean knownL = TileSnapshot.batteryKnown(lvlL);
        boolean chgL = s.charging(TileSnapshot.FLAG_CHARGE_LEFT);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(safe(ctx, R.string.tile_ring_left), rectL.left + PAD_X + 10f, rectL.top + 40f, labelPaint);

        if (knownL) {
            numPaint.setTextAlign(Paint.Align.LEFT);
            numPaint.setColor(COLOR_INK);
            String valStr = String.valueOf(lvlL);
            float numX = rectL.left + PAD_X;
            float baseY = rectL.top + 91f;
            c.drawText(valStr, numX, baseY, numPaint);
            float numW = numPaint.measureText(valStr);
            pctPaint.setTextAlign(Paint.Align.LEFT);
            float pctX = numX + numW + 3f;
            c.drawText("%", pctX, baseY, pctPaint);
            if (chgL) {
                float pctW = pctPaint.measureText("%");
                drawBolt(c, pctX + pctW + 7f, baseY - 25f, 15f, 21f, COLOR_WARN);
            }
        } else {
            numPaint.setTextAlign(Paint.Align.LEFT);
            numPaint.setColor(COLOR_UNKNOWN);
            c.drawText("--", rectL.left + PAD_X, rectL.top + 91f, numPaint);
        }
        drawBar(c, rectL.left + PAD_X, rectL.right - PAD_X, rectL.top + 104f,
                BAR_H, lvlL, knownL, chgL, trackPaint, fillPaint);

        // Right bud (mirrored right)
        int lvlR = s.battR;
        boolean knownR = TileSnapshot.batteryKnown(lvlR);
        boolean chgR = s.charging(TileSnapshot.FLAG_CHARGE_RIGHT);

        labelPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(safe(ctx, R.string.tile_ring_right), rectR.right - PAD_X - 10f, rectR.top + 40f, labelPaint);

        if (knownR) {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_INK);
            String valStr = String.valueOf(lvlR);
            float rightX = rectR.right - PAD_X;
            float baseY = rectR.top + 91f;
            c.drawText(valStr, rightX, baseY, numPaint);
            float numW = numPaint.measureText(valStr);
            pctPaint.setTextAlign(Paint.Align.RIGHT);
            float pctRight = rightX - numW - 4f;
            c.drawText("%", pctRight, baseY, pctPaint);
            if (chgR) {
                float pctW = pctPaint.measureText("%");
                drawBolt(c, pctRight - pctW - 7f - 15f, baseY - 25f, 15f, 21f, COLOR_WARN);
            }
        } else {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_UNKNOWN);
            c.drawText("--", rectR.right - PAD_X, rectR.top + 91f, numPaint);
        }
        drawBar(c, rectR.left + PAD_X, rectR.right - PAD_X, rectR.top + 104f,
                BAR_H, lvlR, knownR, chgR, trackPaint, fillPaint);

        // Case (full width)
        int lvlC = s.battC;
        boolean knownC = TileSnapshot.batteryKnown(lvlC);
        boolean chgC = s.charging(TileSnapshot.FLAG_CHARGE_CASE);

        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(safe(ctx, R.string.tile_ring_case), rectC.left + PAD_X + 10f, rectC.top + 41f, labelPaint);

        float caseRightX = rectC.right - PAD_X;
        numPaint.setTextSize(CASE_NUM_SIZE);
        pctPaint.setTextSize(CASE_PCT_SIZE);
        if (knownC) {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_INK);
            String valStr = String.valueOf(lvlC);
            float baseY = rectC.top + 43f;
            c.drawText(valStr, caseRightX, baseY, numPaint);
            float numW = numPaint.measureText(valStr);
            pctPaint.setTextAlign(Paint.Align.RIGHT);
            float pctRight = caseRightX - numW - 4f;
            c.drawText("%", pctRight, baseY, pctPaint);
            if (chgC) {
                float pctW = pctPaint.measureText("%");
                drawBolt(c, pctRight - pctW - 8f - 17f, baseY - 24f, 17f, 23f, COLOR_WARN);
            }
        } else {
            numPaint.setTextAlign(Paint.Align.RIGHT);
            numPaint.setColor(COLOR_UNKNOWN);
            c.drawText("--", caseRightX, rectC.top + 43f, numPaint);
        }
        drawBar(c, rectC.left + PAD_X, rectC.right - PAD_X, rectC.top + 56f,
                BAR_H, lvlC, knownC, chgC, trackPaint, fillPaint);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static void drawCardBg(Canvas c, RectF r, Paint fill, Paint stroke,
                                   Paint accentPaint, int accentColor, boolean rightCorner) {
        c.drawRoundRect(r, CARD_RADIUS, CARD_RADIUS, fill);
        c.drawRoundRect(r, CARD_RADIUS, CARD_RADIUS, stroke);

        accentPaint.setColor(accentColor);
        float blockW = 8f;
        float blockH = 14f;
        float y = r.top + 24f;
        if (rightCorner) {
            c.drawRect(r.right - PAD_X - 4f, y, r.right - PAD_X + 4f, y + blockH, accentPaint);
        } else {
            c.drawRect(r.left + PAD_X - 4f, y, r.left + PAD_X - 4f + blockW, y + blockH, accentPaint);
        }
    }

    private static void drawBar(Canvas c, float x1, float x2, float y, float h,
                                int lvl, boolean known, boolean charging,
                                Paint trackPaint, Paint fillPaint) {
        RectF track = new RectF(x1, y, x2, y + h);
        c.drawRoundRect(track, 2f, 2f, trackPaint);

        if (known && lvl > 0) {
            int color = charging ? COLOR_WARN : (lvl <= LOW_PCT ? COLOR_LOW : COLOR_OK);
            fillPaint.setColor(color);
            float fillW = (x2 - x1) * Math.min(100, lvl) / 100f;
            RectF fill = new RectF(x1, y, Math.min(x2, Math.max(x1 + 5f, x1 + fillW)), y + h);
            c.drawRoundRect(fill, 2f, 2f, fillPaint);
        }
    }

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
