package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * 新系列磁贴电量区位图(444x228px): 底板卡片 + {@link NeoBatteryArt}。
 * 不引用 androidx.wear.tiles, 以便 no-tiles 裁剪校验独立编译通过。
 */
final class NeoTilePainter {

    static final int WIDTH_PX = 444;
    static final int HEIGHT_PX = 228;

    private NeoTilePainter() {
    }

    static byte[] png(Context ctx, TileSnapshot s, int style) {
        int variant = AppPrefs.family(style);
        Bitmap bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);

        NeoBatteryArt.Data d = new NeoBatteryArt.Data();
        d.tile = true;
        d.values[0] = s.battL;
        d.values[1] = s.battR;
        d.values[2] = s.battC;
        d.charging[0] = s.charging(TileSnapshot.FLAG_CHARGE_LEFT);
        d.charging[1] = s.charging(TileSnapshot.FLAG_CHARGE_RIGHT);
        d.charging[2] = s.charging(TileSnapshot.FLAG_CHARGE_CASE);
        d.labels[0] = safe(ctx, R.string.tile_ring_left);
        d.labels[1] = safe(ctx, R.string.tile_ring_right);
        d.labels[2] = safe(ctx, R.string.tile_ring_case);
        float[] shown = {frac(s.battL), frac(s.battR), frac(s.battC)};

        if (variant == NeoBatteryArt.MINIMAL) {
            // 新极简磁贴直接画三张不透明暗金分体卡, 圆角严格对齐下方 12dp (3x 位图 = 36px)
            new NeoBatteryArt().draw(c, 2f, 2f, WIDTH_PX - 4f, HEIGHT_PX - 4f,
                    variant, d, shown, 1.95f, 0.8f, 36f);
        } else {
            float radius = 36f;
            float strokeW = 3f;
            int strokeColor = variant == NeoBatteryArt.LIQUID ? 0x333DDC97 : 0x334C8DFF;
            fill.setColor(variant == NeoBatteryArt.LIQUID ? 0xFF111A16 : 0xFF121824);
            float inset = strokeW / 2f;
            RectF card = new RectF(inset, inset, WIDTH_PX - inset, HEIGHT_PX - inset);
            c.drawRoundRect(card, radius, radius, fill);

            Path clip = new Path();
            clip.addRoundRect(card, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            new NeoBatteryArt().draw(c, 18f, 12f, WIDTH_PX - 36f, HEIGHT_PX - 24f,
                    variant, d, shown, 2.15f, 0.8f);
            c.restore();

            stroke.setStrokeWidth(strokeW);
            stroke.setColor(strokeColor);
            c.drawRoundRect(card, radius, radius, stroke);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static float frac(int v) {
        return TileSnapshot.batteryKnown(v) ? v / 100f : 0f;
    }

    private static String safe(Context ctx, int resId) {
        try {
            return ctx.getString(resId);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
