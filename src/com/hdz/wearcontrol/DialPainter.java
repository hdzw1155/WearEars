package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * 磁贴电量区: 一张胶囊里并排三个电量块(圆角矩形 + 液位填充 + 白色数字), 块下小字标签.
 * 画布 148dp x 76dp @3x = 444 x 228 px.
 */
final class DialPainter {

    static final int WIDTH_PX = 444;
    static final int HEIGHT_PX = 228;
    private static final int PAD_PX = 24;
    private static final int GAP_PX = 30;
    private static final int BLOCK_H_PX = 138;
    private static final int BLOCK_RADIUS_PX = 36;
    private static final int CARD_RADIUS_PX = 54;

    private static final int COLOR_CARD = 0xFF141416;
    private static final int COLOR_STROKE = 0x14FFFFFF;
    private static final int COLOR_BLOCK = 0xFF1E1E21;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_FAINT = 0x85F2F2F5;
    private static final int COLOR_OK = 0xFF4CC38A;
    private static final int COLOR_WARN = 0xFFF5A524;
    private static final int COLOR_LOW = 0xFFF0524F;
    private static final int FILL_ALPHA = 0x45;
    private static final int LOW_PCT = 20;

    private DialPainter() {
    }

    static byte[] png(Context ctx, TileSnapshot s) {
        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 胶囊卡
        RectF card = new RectF(2, 2, WIDTH_PX - 2, HEIGHT_PX - 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(COLOR_CARD);
        c.drawRoundRect(card, CARD_RADIUS_PX, CARD_RADIUS_PX, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(COLOR_STROKE);
        c.drawRoundRect(card, CARD_RADIUS_PX, CARD_RADIUS_PX, p);
        p.setStyle(Paint.Style.FILL);

        int inner = WIDTH_PX - PAD_PX * 2;
        int bw = (inner - GAP_PX * 2) / 3;
        int x0 = PAD_PX;
        int top = PAD_PX;
        int labelBase = top + BLOCK_H_PX + 40;

        Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numPaint.setStyle(Paint.Style.FILL);
        numPaint.setColor(COLOR_TEXT);
        numPaint.setTextAlign(Paint.Align.LEFT);
        numPaint.setFakeBoldText(true);

        Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unitPaint.setStyle(Paint.Style.FILL);
        unitPaint.setColor(COLOR_TEXT);
        unitPaint.setTextAlign(Paint.Align.LEFT);
        unitPaint.setFakeBoldText(true);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COLOR_FAINT);
        labelPaint.setTextSize(27f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);

        Path clip = new Path();
        Path bolt = new Path();

        String[] labels = {
                safe(ctx, R.string.tile_ring_left),
                safe(ctx, R.string.tile_ring_right),
                safe(ctx, R.string.tile_ring_case)
        };
        int[] values = {s.battL, s.battR, s.battC};
        int[] flags = {TileSnapshot.FLAG_CHARGE_LEFT, TileSnapshot.FLAG_CHARGE_RIGHT,
                TileSnapshot.FLAG_CHARGE_CASE};

        for (int i = 0; i < 3; i++) {
            int x = x0 + i * (bw + GAP_PX);
            RectF block = new RectF(x, top, x + bw, top + BLOCK_H_PX);
            p.setColor(COLOR_BLOCK);
            c.drawRoundRect(block, BLOCK_RADIUS_PX, BLOCK_RADIUS_PX, p);

            int lvl = values[i];
            boolean known = TileSnapshot.batteryKnown(lvl);
            boolean charging = s.charging(flags[i]);
            int color;
            if (charging && known) {
                color = COLOR_WARN;
            } else if (known && lvl <= LOW_PCT) {
                color = COLOR_LOW;
            } else {
                color = COLOR_OK;
            }

            // 液位填充(底部向上)
            if (known && lvl > 0) {
                fillPaint.setColor((color & 0x00FFFFFF) | (FILL_ALPHA << 24));
                clip.reset();
                clip.addRoundRect(block, BLOCK_RADIUS_PX, BLOCK_RADIUS_PX, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                float topY = block.bottom - (block.height() * lvl / 100f);
                c.drawRect(block.left, topY, block.right, block.bottom, fillPaint);
                c.restore();
            }

            // 充电: 1dp 琥珀描边 + 右上角小闪电
            if (charging && known) {
                borderPaint.setColor(COLOR_WARN);
                borderPaint.setStrokeWidth(3f);
                c.drawRoundRect(block, BLOCK_RADIUS_PX, BLOCK_RADIUS_PX, borderPaint);

                float s2 = 13f;
                float cx = block.right - 20f;
                float cy = block.top + 22f;
                bolt.reset();
                bolt.moveTo(cx + s2 * 0.18f, cy - s2);
                bolt.lineTo(cx - s2 * 0.62f, cy + s2 * 0.12f);
                bolt.lineTo(cx - s2 * 0.06f, cy + s2 * 0.12f);
                bolt.lineTo(cx - s2 * 0.22f, cy + s2);
                bolt.lineTo(cx + s2 * 0.62f, cy - s2 * 0.16f);
                bolt.lineTo(cx + s2 * 0.06f, cy - s2 * 0.16f);
                bolt.close();
                p.setColor(COLOR_WARN);
                c.drawPath(bolt, p);
            }

            // 数字 + 小一号 %
            if (known) {
                String num = String.valueOf(lvl);
                float numSp = 42f;
                float unitSp = 27f;
                float maxW = bw - 16f;
                for (int g = 0; g < 10; g++) {
                    numPaint.setTextSize(numSp);
                    unitPaint.setTextSize(unitSp);
                    float total = numPaint.measureText(num) + unitPaint.measureText("%");
                    if (total <= maxW) {
                        break;
                    }
                    numSp *= 0.94f;
                    unitSp *= 0.94f;
                }
                float numW = numPaint.measureText(num);
                float unitW = unitPaint.measureText("%");
                float startX = block.centerX() - (numW + unitW) / 2f;
                Paint.FontMetrics fm = numPaint.getFontMetrics();
                float baseline = block.centerY() - (fm.ascent + fm.descent) / 2f;
                c.drawText(num, startX, baseline, numPaint);
                Paint.FontMetrics ufm = unitPaint.getFontMetrics();
                float ub = baseline - (fm.descent - ufm.descent);
                c.drawText("%", startX + numW, ub, unitPaint);
            } else {
                numPaint.setTextSize(42f);
                numPaint.setColor(COLOR_FAINT);
                numPaint.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = numPaint.getFontMetrics();
                c.drawText("--", block.centerX(),
                        block.centerY() - (fm.ascent + fm.descent) / 2f, numPaint);
                numPaint.setTextAlign(Paint.Align.LEFT);
                numPaint.setColor(COLOR_TEXT);
            }

            c.drawText(labels[i], block.centerX(), labelBase, labelPaint);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static String safe(Context ctx, int resId) {
        try {
            return ctx.getString(resId);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
