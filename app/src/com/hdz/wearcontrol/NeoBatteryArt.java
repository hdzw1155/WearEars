package com.hdz.wearcontrol;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * 新系列三种风格电量画法(各自从旧风格演化而来, 整体视觉统一):
 * <ul>
 *   <li>MINIMAL (新极简): 从旧极简的 2+1 卡片+进度条演化, 统一暗金不透明卡片与细胶囊条;</li>
 *   <li>CLASSIC (新经典): 旧经典的横向胶囊环(BatteryRectRingView)放进卡片, 加玻璃内胆、渐变柔光与弹跳反馈;</li>
 *   <li>LIQUID  (新液位): 玻璃胶囊双层波浪, 去掉 % 并将数字居于胶囊中部。</li>
 * </ul>
 */
final class NeoBatteryArt {

    static final int MINIMAL = 0;
    static final int CLASSIC = 1;
    static final int LIQUID = 2;

    /** 各画法的设计高度/宽度(dp), 区域不够时整体等比缩小 */
    private static final float[] DESIGN_H = {118f, 60f, 112f};
    /** 新经典在卡片内画三枚胶囊环, 按卡片内宽(128dp)设计, 避免在 432px 手表上被二次缩小 */
    private static final float[] DESIGN_W = {180f, 128f, 180f};

    static final int DARK_OK = 0xFFF5B841;
    static final int DARK_WARN = 0xFFFF9F0A;
    static final int DARK_LOW = 0xFFF0524F;
    static final int CLASSIC_OK = 0xFF4C8DFF;
    static final int CLASSIC_WARN = 0xFFFF9F0A;
    static final int CLASSIC_LOW = 0xFFFA5151;

    /** 一次绘制所需的数据; values 为 -1 表示未知 */
    static final class Data {
        final int[] values = {-1, -1, -1};
        final boolean[] charging = new boolean[3];
        final String[] labels = {"", "", ""};
        /** 每格的弹跳缩放(新经典点按/数值变化时的 Q 弹反馈), 静止为 1 */
        final float[] scale = {1f, 1f, 1f};
        boolean single;
        /** 磁贴与主界面新经典比例偏好不同: 磁贴用宽胶囊(48x36dp), 主界面用紧凑胶囊(38x34dp) */
        boolean tile;

        int count() {
            return single ? 1 : 3;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path path = new Path();
    private final Path segment = new Path();
    private final Path clip = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    private final RectF arcRect = new RectF();
    private final float[] pos = new float[2];
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);

    NeoBatteryArt() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    static boolean known(int v) {
        return v >= 0 && v <= 100;
    }

    static float designHeightDp(int variant) {
        return DESIGN_H[Math.max(MINIMAL, Math.min(LIQUID, variant))];
    }

    /**
     * @param shown 当前显示的填充比例(0..1), 用于入场/变化动画
     * @param u     px/dp
     * @param phase 液位波面相位(弧度)
     */
    void draw(Canvas c, float left, float top, float w, float h, int variant,
              Data d, float[] shown, float u, float phase) {
        draw(c, left, top, w, h, variant, d, shown, u, phase, 20f * u);
    }

    void draw(Canvas c, float left, float top, float w, float h, int variant,
              Data d, float[] shown, float u, float phase, float cardRadiusPx) {
        if (w <= 0f || h <= 0f) return;
        int v = Math.max(MINIMAL, Math.min(LIQUID, variant));
        float designH = (v == CLASSIC && d.tile) ? 64f : DESIGN_H[v];
        float designW = (v == CLASSIC && d.tile) ? 150f : DESIGN_W[v];
        float s = Math.min(u, Math.min(h / designH, w / designW));
        if (v == MINIMAL) {
            drawMinimalGrid(c, left, top, w, h, d, shown, s, u, cardRadiusPx);
            return;
        }
        int n = d.count();
        float cellW = d.single ? Math.min(w, Math.max(w / 3f, 96f * s)) : w / 3f;
        float startX = left + (w - cellW * n) / 2f;
        float cellH = v == CLASSIC ? Math.min(h, designH * s) : h;
        float cellTop = top + (h - cellH) / 2f;
        for (int i = 0; i < n; i++) {
            float x = startX + cellW * i;
            float frac = clamp01(shown[i]);
            if (v == CLASSIC) drawClassicCell(c, x, cellTop, cellW, cellH, d, i, frac, s);
            else drawLiquidCell(c, x, cellTop, cellW, cellH, d, i, frac, s, phase);
        }
    }

    // ---------------- 新极简: 2+1 琥珀暗金不透明卡片 + 横向电量条 ----------------

    private void drawMinimalGrid(Canvas c, float left, float top, float w, float h,
                                 Data d, float[] shown, float s, float u, float cardRadiusPx) {
        float gap = Math.max(4f, 6f * s);
        if (d.single) {
            drawMinimalBentoCard(c, left, top, w, h, d, 0, clamp01(shown[0]), s, u, cardRadiusPx, false, false);
            return;
        }
        float topH = (h - gap) * 0.54f;
        float botH = h - gap - topH;
        float colW = (w - gap) / 2f;
        drawMinimalBentoCard(c, left, top, colW, topH, d, 0, clamp01(shown[0]), s, u, cardRadiusPx, false, false);
        drawMinimalBentoCard(c, left + colW + gap, top, colW, topH, d, 1, clamp01(shown[1]), s, u, cardRadiusPx, true, false);
        drawMinimalBentoCard(c, left, top + topH + gap, w, botH, d, 2, clamp01(shown[2]), s, u, cardRadiusPx, false, true);
    }

    private void drawMinimalBentoCard(Canvas c, float x, float y, float w, float h, Data d, int i,
                                      float frac, float s, float u, float cardRadiusPx,
                                      boolean rightAlign, boolean wideRow) {
        float radius = Math.min(cardRadiusPx, Math.min(w, h) * 0.48f);
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(0xFF18150E);
        c.drawRoundRect(rect, radius, radius, fill);
        float strokeW = Math.max(1f, 1f * u);
        float half = strokeW / 2f;
        rect.set(x + half, y + half, x + w - half, y + h - half);
        stroke.setShader(null);
        stroke.setStrokeWidth(strokeW);
        stroke.setColor(0x33F5B841);
        c.drawRoundRect(rect, Math.max(0f, radius - half), Math.max(0f, radius - half), stroke);

        int value = d.values[i];
        boolean ok = known(value);
        boolean chg = ok && d.charging[i];
        float padX = 11f * s;
        float barH = 4.5f * s;
        float barBottom = y + h - 9f * s;
        float barTop = barBottom - barH;

        text.setShader(null);
        text.clearShadowLayer();
        text.setLetterSpacing(0f);

        if (wideRow) {
            float midY = y + (barTop - y) * 0.62f;
            text.setTypeface(regular);
            text.setTextSize(10.5f * s);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(0xFFD8C79A);
            c.drawText(d.labels[i], x + padX, midY, text);
            if (chg) {
                float lw = text.measureText(d.labels[i]);
                drawBolt(c, x + padX + lw + 4f * s, midY - 9.5f * s, 7.5f * s, 10.5f * s, DARK_WARN);
            }
            float numSize = 16.5f * s;
            if (ok) {
                String num = String.valueOf(value);
                text.setTypeface(bold);
                text.setTextSize(numSize);
                float numW = text.measureText(num);
                text.setTextAlign(Paint.Align.RIGHT);
                text.setColor(0xFFFFF8E8);
                c.drawText(num, x + w - padX, midY, text);
                text.setTypeface(regular);
                text.setTextSize(9.5f * s);
                text.setColor(0xFFB8A77A);
                c.drawText("%", x + w - padX - numW - 1.5f * s, midY, text);
            } else {
                text.setTypeface(bold);
                text.setTextSize(numSize);
                text.setTextAlign(Paint.Align.RIGHT);
                text.setColor(0xFF7A6F52);
                c.drawText("--", x + w - padX, midY, text);
            }
        } else {
            float labelY = y + 16.5f * s;
            text.setTypeface(regular);
            text.setTextSize(10f * s);
            text.setColor(0xFFD8C79A);
            text.setTextAlign(rightAlign ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(d.labels[i], rightAlign ? (x + w - padX) : (x + padX), labelY, text);

            float numSize = Math.min(20f * s, w * 0.25f);
            float numY = y + (barTop - labelY) * 0.64f + labelY;
            if (ok) {
                String num = String.valueOf(value);
                text.setTypeface(bold);
                text.setTextSize(numSize);
                float numW = text.measureText(num);
                text.setTypeface(regular);
                text.setTextSize(9.5f * s);
                float pctW = text.measureText("%");
                float anchorX = rightAlign ? (x + w - padX) : (x + padX);
                if (rightAlign) {
                    text.setTextAlign(Paint.Align.RIGHT);
                    text.setTypeface(bold);
                    text.setTextSize(numSize);
                    text.setColor(0xFFFFF8E8);
                    c.drawText(num, anchorX, numY, text);
                    text.setTypeface(regular);
                    text.setTextSize(9.5f * s);
                    text.setColor(0xFFB8A77A);
                    c.drawText("%", anchorX - numW - 1.5f * s, numY, text);
                    if (chg) {
                        drawBolt(c, anchorX - numW - pctW - 11f * s, numY - numSize * 0.58f,
                                7.5f * s, 10.5f * s, DARK_WARN);
                    }
                } else {
                    text.setTextAlign(Paint.Align.LEFT);
                    text.setTypeface(bold);
                    text.setTextSize(numSize);
                    text.setColor(0xFFFFF8E8);
                    c.drawText(num, anchorX, numY, text);
                    text.setTypeface(regular);
                    text.setTextSize(9.5f * s);
                    text.setColor(0xFFB8A77A);
                    c.drawText("%", anchorX + numW + 1.5f * s, numY, text);
                    if (chg) {
                        drawBolt(c, anchorX + numW + pctW + 4.5f * s, numY - numSize * 0.58f,
                                7.5f * s, 10.5f * s, DARK_WARN);
                    }
                }
            } else {
                text.setTypeface(bold);
                text.setTextSize(numSize);
                text.setTextAlign(rightAlign ? Paint.Align.RIGHT : Paint.Align.LEFT);
                text.setColor(0xFF7A6F52);
                c.drawText("--", rightAlign ? (x + w - padX) : (x + padX), numY, text);
            }
        }

        // Bottom horizontal pill track + opaque warm gold/semantic progress bar
        float bx1 = x + padX, bx2 = x + w - padX;
        rect.set(bx1, barTop, bx2, barBottom);
        fill.setShader(null);
        fill.setColor(0xFF2A2416);
        c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
        if (ok && frac > 0.003f) {
            int col = levelColor(value, chg, DARK_OK, DARK_WARN, DARK_LOW);
            float rightX = Math.min(bx2, Math.max(bx1 + barH, bx1 + (bx2 - bx1) * frac));
            fill.setColor(0xFFFFFFFF);
            fill.setShader(new LinearGradient(bx1, 0f, rightX, 0f,
                    col, blend(col, 0xFFFFFFFF, 0.25f), Shader.TileMode.CLAMP));
            rect.set(bx1, barTop, rightX, barBottom);
            c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
            fill.setShader(null);
        }
    }

    // ---------------- 新经典: 旧经典的横向胶囊环, 加玻璃内胆/渐变光晕/端点珠/充电角标 ----------------

    private void drawClassicCell(Canvas c, float x, float y, float cw, float h, Data d, int i,
                                 float frac, float s) {
        int value = d.values[i];
        boolean ok = known(value);
        boolean chg = ok && d.charging[i];
        boolean tile = d.tile;
        float topPad = (tile ? 4f : 6f) * s;
        float sw = (tile ? 3.6f : 3.4f) * s;
        float gaugeW = tile
                ? Math.min(cw - 4f * s, 48f * s)
                : Math.min(cw - 6f * s, d.single ? 56f * s : 38f * s);
        float gaugeH = (tile ? 36f : 34f) * s;
        float cx = x + cw / 2f;
        float cy = y + topPad + gaugeH / 2f;
        float halfW = (gaugeW - sw) / 2f;
        float halfH = (gaugeH - sw) / 2f;
        float cornerR = tile ? Math.min(halfW, halfH) : Math.min(14.5f * s, Math.min(halfW, halfH));

        int save = c.save();
        float k = d.scale[i];
        if (k != 1f) c.scale(k, k, cx, cy);

        // 玻璃内胆: 上亮下暗的胶囊底, 让环像一颗可以按的软糖按钮
        float inner = sw * 0.5f;
        rect.set(cx - halfW + inner, cy - halfH + inner, cx + halfW - inner, cy + halfH - inner);
        float innerR = Math.max(0f, cornerR - inner);
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                0xFF1F2B44, 0xFF131A29, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, innerR, innerR, fill);
        fill.setShader(null);
        // 顶部一道细高光
        fill.setColor(0x14FFFFFF);
        rect.set(cx - halfW * 0.52f, cy - halfH + inner + 2.2f * s,
                cx + halfW * 0.52f, cy - halfH + inner + 3.6f * s);
        c.drawRoundRect(rect, 0.7f * s, 0.7f * s, fill);

        arcRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        path.reset();
        path.addRoundRect(arcRect, cornerR, cornerR, Path.Direction.CW);

        stroke.setShader(null);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(sw);
        stroke.setColor(0xFF1E293D);
        c.drawPath(path, stroke);

        if (ok && frac > 0.004f) {
            int col = levelColor(value, chg, CLASSIC_OK, CLASSIC_WARN, CLASSIC_LOW);
            measure.setPath(path, false);
            float total = measure.getLength();
            float endDist = Math.max(1f, total * Math.min(1f, frac));
            segment.reset();
            measure.getSegment(0f, endDist, segment, true);
            // 柔光底: 同色加粗低透明, 深蓝卡片上像发光的果冻边
            stroke.setStrokeWidth(sw * (tile ? 2.3f : 2.0f));
            stroke.setColor((col & 0x00FFFFFF) | (tile ? 0x2E000000 : 0x28000000));
            c.drawPath(segment, stroke);
            stroke.setStrokeWidth(sw);
            stroke.setColor(0xFFFFFFFF);
            stroke.setShader(new LinearGradient(arcRect.left, 0f, arcRect.right, 0f,
                    col, blend(col, 0xFFFFFFFF, 0.32f), Shader.TileMode.CLAMP));
            c.drawPath(segment, stroke);
            stroke.setShader(null);
            // 进度端点的小亮珠
            if (frac < 0.985f) {
                measure.getPosTan(endDist, pos, null);
                fill.setColor(0xFFF2F7FF);
                c.drawCircle(pos[0], pos[1], sw * 0.36f, fill);
            }
        }

        text.setShader(null);
        text.clearShadowLayer();
        text.setLetterSpacing(0f);
        float numSize = tile ? Math.min(15f * s, gaugeW * 0.34f) : Math.min(14.5f * s, gaugeW * 0.36f);
        text.setTypeface(bold);
        text.setTextSize(numSize);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(ok ? 0xFFF2F7FF : 0xFF637899);
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2f;
        c.drawText(ok ? String.valueOf(value) : "--", cx, base, text);

        // 充电角标: 右上角的小圆徽章, 描一圈卡片底色把它从环上"抠"出来
        if (chg) {
            float br = (tile ? 6.2f : 5.4f) * s;
            float bx = cx + halfW - (tile ? 1.5f : 1.8f) * s;
            float by = cy - halfH + (tile ? 1.5f : 1.2f) * s;
            fill.setColor(0xFF121824);
            c.drawCircle(bx, by, br + (tile ? 1.8f : 1.4f) * s, fill);
            fill.setColor(CLASSIC_WARN);
            c.drawCircle(bx, by, br, fill);
            drawBolt(c, bx - br * 0.7f, by - br * 0.75f, br * 1.4f, br * 1.5f, 0xFF121824);
        }
        c.restoreToCount(save);

        text.setTypeface(regular);
        text.setTextSize(10f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(chg ? 0xFFFFC46B : 0xFFB8CEEE);
        c.drawText(d.labels[i], cx, y + topPad + gaugeH + 15f * s, text);
    }

    // ---------------- 新液位: 玻璃胶囊波浪(去掉 % 并居中数字) ----------------

    private void drawLiquidCell(Canvas c, float x, float y, float cw, float h, Data d, int i,
                                float frac, float s, float phase) {
        int value = d.values[i];
        boolean ok = known(value);
        boolean chg = ok && d.charging[i];
        float labelSpace = 20f * s;
        float capW = Math.min(cw * 0.64f, 54f * s);
        float capH = h - labelSpace - 2f * s;
        float cx = x + cw / 2f;
        float left = cx - capW / 2f, right = cx + capW / 2f;
        float top = y + 1f * s, bottom = top + capH;
        float radius = capW * 0.46f;

        rect.set(left, top, right, bottom);
        fill.setShader(null);
        fill.setColor(0xFF182620);
        c.drawRoundRect(rect, radius, radius, fill);

        if (ok && frac > 0.003f) {
            int[] pair = liquidColors(value, chg);
            float level = bottom - capH * frac;
            float amp = 2.4f * s * Math.min(1f, Math.min(frac, 1f - frac) * 8f + 0.15f);
            float wave = capW * 1.15f;
            c.save();
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            // 后层波: 不透明深绿底波, 制造层次
            fill.setShader(null);
            fill.setColor(blend(pair[1], 0xFF182620, 0.42f));
            wavePath(left, right, bottom, level - 1.2f * s, amp, wave, phase + (float) Math.PI, s);
            c.drawPath(path, fill);
            fill.setColor(0xFFFFFFFF);
            fill.setShader(new LinearGradient(0f, level, 0f, bottom, pair[0], pair[1], Shader.TileMode.CLAMP));
            wavePath(left, right, bottom, level, amp, wave, phase, s);
            c.drawPath(path, fill);
            fill.setShader(null);
            c.restore();
        }

        // 高光条 + 描边
        fill.setShader(null);
        fill.setColor(0xFF263830);
        rect.set(left + capW * 0.16f, top + radius * 0.55f, left + capW * 0.16f + 2.5f * s, top + capH * 0.42f);
        c.drawRoundRect(rect, 1.25f * s, 1.25f * s, fill);
        stroke.setShader(null);
        stroke.setStrokeWidth(Math.max(1f, 1f * s));
        stroke.setColor(0x333DDC97);
        rect.set(left, top, right, bottom);
        c.drawRoundRect(rect, radius, radius, stroke);

        float numSize = Math.min(18f * s, capW * 0.38f);
        float cyNum = top + capH * 0.52f;
        text.setShader(null);
        text.setLetterSpacing(0f);
        text.setTypeface(bold);
        text.setTextSize(numSize);
        text.setTextAlign(Paint.Align.CENTER);
        if (ok) {
            text.setShadowLayer(3f * s, 0f, 1f * s, 0x80000000);
            text.setColor(0xFFFFFFFF);
            c.drawText(String.valueOf(value), cx, cyNum + numSize * 0.35f, text);
            text.clearShadowLayer();
        } else {
            text.clearShadowLayer();
            text.setColor(0xFF628275);
            c.drawText("--", cx, cyNum + numSize * 0.35f, text);
        }
        if (chg) {
            float bw = 7f * s, bh = 10f * s;
            drawBolt(c, cx - bw / 2f, top + 7f * s, bw, bh, 0xFFFFFFFF);
        }

        text.setTypeface(regular);
        text.setTextSize(10f * s);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFB8D8CA);
        c.drawText(d.labels[i], cx, y + h - 5f * s, text);
    }

    private void wavePath(float left, float right, float bottom, float level, float amp,
                          float wave, float phase, float s) {
        path.reset();
        path.moveTo(left, bottom);
        float step = Math.max(1f, 2f * s);
        for (float x = left; x <= right + step; x += step) {
            float xx = Math.min(x, right);
            float yy = level + amp * (float) Math.sin(2.0 * Math.PI * (xx - left) / wave + phase);
            path.lineTo(xx, yy);
        }
        path.lineTo(right, bottom);
        path.close();
    }

    private static int[] liquidColors(int value, boolean charging) {
        if (charging) return new int[]{0xFFFFC46B, 0xFFC77A0A};
        if (value <= 20) return new int[]{0xFFFF8A87, 0xFFB3302E};
        return new int[]{0xFF6BE4AE, 0xFF1C8A5A};
    }

    // ---------------- 工具 ----------------

    private static int levelColor(int value, boolean charging, int ok, int warn, int low) {
        if (charging) return warn;
        return value <= 20 ? low : ok;
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static float clamp01(float f) {
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    /** 闪电: 与 ic_bolt.xml 同几何 */
    private void drawBolt(Canvas c, float x, float y, float w, float h, int color) {
        float sx = w / 24f, sy = h / 24f;
        path.reset();
        path.moveTo(x + 13f * sx, y + 2.5f * sy);
        path.lineTo(x + 5.5f * sx, y + 13.5f * sy);
        path.lineTo(x + 10.5f * sx, y + 13.5f * sy);
        path.lineTo(x + 10f * sx, y + 21.5f * sy);
        path.lineTo(x + 19f * sx, y + 10f * sy);
        path.lineTo(x + 13.5f * sx, y + 10f * sy);
        path.close();
        fill.setShader(null);
        fill.setColor(color);
        c.drawPath(path, fill);
    }
}
