package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/** 磁贴电量区·极简: 胶囊内三行(标签 + 进度条 + 数字). 444x228px. */
final class BarsTilePainter {

    static final int WIDTH_PX = 444;
    static final int HEIGHT_PX = 228;
    private static final int CARD_RADIUS = 54;
    private static final int PAD = 26;
    private static final int ROW_GAP = 16;
    private static final int BAR_H = 26;
    private static final int COLOR_CARD = 0xFF141416;
    private static final int COLOR_STROKE = 0x14FFFFFF;
    private static final int COLOR_TRACK = 0xFF1E1E21;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0x99F2F2F5;
    private static final int COLOR_OK = 0xFF4CC38A;
    private static final int COLOR_WARN = 0xFFF5A524;
    private static final int COLOR_LOW = 0xFFF0524F;
    private static final float LOW_PCT = 20f;

    private BarsTilePainter() {
    }

    static byte[] png(Context ctx, TileSnapshot s) {
        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RectF card = new RectF(2, 2, WIDTH_PX - 2, HEIGHT_PX - 2);
        p.setColor(COLOR_CARD);
        c.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(COLOR_STROKE);
        c.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, p);
        p.setStyle(Paint.Style.FILL);

        int rowH = BAR_H;
        int totalH = 3 * rowH + 2 * ROW_GAP;
        int top = (HEIGHT_PX - totalH) / 2;

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(COLOR_DIM);
        label.setTextSize(26f);
        label.setTextAlign(Paint.Align.LEFT);

        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setFakeBoldText(true);
        num.setColor(COLOR_TEXT);
        num.setTextSize(34f);
        num.setTextAlign(Paint.Align.RIGHT);

        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(COLOR_TRACK);

        String[] labels = {safe(ctx, R.string.tile_ring_left), safe(ctx, R.string.tile_ring_right), safe(ctx, R.string.tile_ring_case)};
        int[] values = {s.battL, s.battR, s.battC};
        int[] flags = {TileSnapshot.FLAG_CHARGE_LEFT, TileSnapshot.FLAG_CHARGE_RIGHT, TileSnapshot.FLAG_CHARGE_CASE};

        int barLeft = PAD + 78;
        int barRight = WIDTH_PX - PAD - 86;

        for (int i = 0; i < 3; i++) {
            int cy = top + i * (rowH + ROW_GAP) + rowH / 2;
            Paint.FontMetrics fm = label.getFontMetrics();
            float baseY = cy - (fm.ascent + fm.descent) / 2f;
            c.drawText(labels[i], PAD, baseY, label);

            int lvl = values[i];
            boolean known = TileSnapshot.batteryKnown(lvl);
            boolean charging = s.charging(flags[i]);
            int color = charging && known ? COLOR_WARN : (known && lvl <= LOW_PCT ? COLOR_LOW : COLOR_OK);

            RectF bar = new RectF(barLeft, cy - BAR_H / 2f, barRight, cy + BAR_H / 2f);
            c.drawRoundRect(bar, BAR_H / 2f, BAR_H / 2f, track);
            if (known && lvl > 0) {
                RectF fill = new RectF(bar.left, bar.top,
                        bar.left + bar.width() * Math.min(100, lvl) / 100f, bar.bottom);
                p.setColor(color);
                c.drawRoundRect(fill, BAR_H / 2f, BAR_H / 2f, p);
            }

            String text = known ? String.valueOf(lvl) : "--";
            num.setColor(known ? COLOR_TEXT : 0x85F2F2F5);
            Paint.FontMetrics nfm = num.getFontMetrics();
            c.drawText(text, WIDTH_PX - PAD, cy - (nfm.ascent + nfm.descent) / 2f, num);
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
