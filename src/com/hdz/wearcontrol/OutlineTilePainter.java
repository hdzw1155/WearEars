package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/** 磁贴电量区·经典: 胶囊内三块横向圆角矩形, 进度沿轮廓走一圈. 444x228px (148x76dp@3x). */
final class OutlineTilePainter {

    static final int WIDTH_PX = 444;
    static final int HEIGHT_PX = 228;
    private static final int PAD = 24;
    private static final int GAP = 28;
    private static final int BLOCK_TOP = 40;
    private static final int BLOCK_H = 64;
    private static final int CARD_RADIUS = 54;
    private static final int BLOCK_RADIUS = 36;
    private static final int STROKE = 8;

    private static final int COLOR_CARD = 0xFF141416;
    private static final int COLOR_STROKE = 0x14FFFFFF;
    private static final int COLOR_BLOCK = 0xFF1E1E21;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_FAINT = 0x85F2F2F5;
    private static final int COLOR_OK = 0xFF4CC38A;
    private static final int COLOR_WARN = 0xFFF5A524;
    private static final int COLOR_LOW = 0xFFF0524F;
    private static final float LOW_PCT = 20f;

    private OutlineTilePainter() {
    }

    static byte[] png(Context ctx, TileSnapshot s) {
        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RectF card = new RectF(2, 2, WIDTH_PX - 2, HEIGHT_PX - 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(COLOR_CARD);
        c.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(COLOR_STROKE);
        c.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, p);
        p.setStyle(Paint.Style.FILL);

        int inner = WIDTH_PX - PAD * 2;
        int bw = (inner - GAP * 2) / 3;
        int labelBase = BLOCK_TOP + BLOCK_H + 44;

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(COLOR_FAINT);
        labelPaint.setTextSize(27f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        String[] labels = {safe(ctx, R.string.tile_ring_left), safe(ctx, R.string.tile_ring_right), safe(ctx, R.string.tile_ring_case)};
        int[] values = {s.battL, s.battR, s.battC};
        int[] flags = {TileSnapshot.FLAG_CHARGE_LEFT, TileSnapshot.FLAG_CHARGE_RIGHT, TileSnapshot.FLAG_CHARGE_CASE};

        Path blockPath = new Path();
        Path segment = new Path();
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure();
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(STROKE);
        outline.setStrokeCap(Paint.Cap.ROUND);
        Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numPaint.setFakeBoldText(true);
        numPaint.setColor(COLOR_TEXT);
        numPaint.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < 3; i++) {
            int x = PAD + i * (bw + GAP);
            RectF block = new RectF(x, BLOCK_TOP, x + bw, BLOCK_TOP + BLOCK_H);

            blockPath.reset();
            blockPath.addRoundRect(block, BLOCK_RADIUS, BLOCK_RADIUS, Path.Direction.CW);
            pm.setPath(blockPath, false);
            float total = pm.getLength();

            outline.setColor(COLOR_BLOCK);
            c.drawPath(blockPath, outline);

            int lvl = values[i];
            boolean known = TileSnapshot.batteryKnown(lvl);
            boolean charging = s.charging(flags[i]);
            int color = charging && known ? COLOR_WARN : (known && lvl <= LOW_PCT ? COLOR_LOW : COLOR_OK);

            if (known && lvl > 0) {
                pm.getSegment(0f, total * (Math.min(100, lvl) / 100f), segment, true);
                outline.setColor(color);
                c.drawPath(segment, outline);
            } else if (charging && known) {
                outline.setColor(COLOR_WARN);
                c.drawPath(blockPath, outline);
            }

            if (charging && known) {
                Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
                bolt.setColor(COLOR_WARN);
                Path b = new Path();
                float cx = block.right - 18f, cy = block.top + 16f, s2 = 10f;
                b.moveTo(cx + s2 * 0.18f, cy - s2);
                b.lineTo(cx - s2 * 0.62f, cy + s2 * 0.12f);
                b.lineTo(cx - s2 * 0.06f, cy + s2 * 0.12f);
                b.lineTo(cx - s2 * 0.22f, cy + s2);
                b.lineTo(cx + s2 * 0.62f, cy - s2 * 0.16f);
                b.lineTo(cx + s2 * 0.06f, cy - s2 * 0.16f);
                b.close();
                c.drawPath(b, bolt);
            }

            String num = known ? String.valueOf(lvl) : "--";
            numPaint.setTextSize(known ? 30f : 28f);
            numPaint.setColor(known ? COLOR_TEXT : COLOR_FAINT);
            float numW = numPaint.measureText(num);
            Paint.FontMetrics fm = numPaint.getFontMetrics();
            float baseY = block.centerY() - (fm.ascent + fm.descent) / 2f;
            float startX = block.centerX() - numW / 2f - (known ? 9f : 0f);
            c.drawText(num, startX, baseY, numPaint);
            if (known) {
                Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
                unit.setFakeBoldText(true);
                unit.setColor(COLOR_TEXT);
                unit.setTextSize(19f);
                unit.setTextAlign(Paint.Align.LEFT);
                c.drawText("%", startX + numW + 2f, baseY, unit);
            }
            c.drawText(labels[i], block.centerX(), labelBase, labelPaint);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static String safe(Context ctx, int resId) {
        try { return ctx.getString(resId); } catch (RuntimeException e) { return ""; }
    }
}
