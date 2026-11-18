package com.hdz.wearcontrol;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 新系列音量条画法, 和各自的电量区同源:
 * <ul>
 *   <li>新极简: 电量条同款暗金胶囊槽 + 渐变金填充 + 奶油色圆钮(按下放大带光晕);</li>
 *   <li>新经典: 旧经典的描边 stadium 轨道, 内嵌渐变蓝胶囊和白色圆钮, 底色与胶囊环的玻璃内胆一致;</li>
 *   <li>新液位: 玻璃胶囊里的绿色液体, 液面是会晃的竖向波浪, 带几颗气泡。</li>
 * </ul>
 * 锁统一放在右侧独立圆钮里, 配色与各自的刷新键一致; 解锁后圆钮填成主题色。
 * 触控与几何换算留在 {@link VolumeBarView}, 这里只负责画。
 */
final class NeoVolumeArt {

    private static final int AMBER = 0xFFF5B841;
    private static final int CLASSIC_BLUE = 0xFF4C8DFF;
    private static final int CLASSIC_BLUE_DEEP = 0xFF3A80F5;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path clip = new Path();

    NeoVolumeArt() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * @param family    AppPrefs 家族 0..2
     * @param fillX     当前填充终点(圆钮中心)的 x
     * @param trackRight 轨道右边界(其右侧是锁钮)
     * @param press     按下程度 0..1(可略超 1 形成回弹)
     * @param lockScale 锁钮弹跳缩放
     * @param phase     液位波面相位
     * @param energy    液位晃动强度 0..1
     */
    void draw(Canvas c, int family, float w, float h, float frac, float fillX, float trackRight,
              float press, boolean unlocked, float lockScale, float phase, float energy, float d) {
        if (w <= 0f || h <= 0f || trackRight <= 0f) return;
        if (family == AppPrefs.STYLE_CLASSIC) drawClassic(c, h, frac, fillX, trackRight, press, d);
        else if (family == AppPrefs.STYLE_LIQUID) drawLiquid(c, h, frac, fillX, trackRight, phase, energy, d);
        else drawMinimal(c, h, frac, fillX, trackRight, press, d);
        drawLockButton(c, family, w, h, unlocked, lockScale, d);
    }

    // ---------------- 新极简 ----------------

    private void drawMinimal(Canvas c, float h, float frac, float fillX, float trackRight,
                             float press, float d) {
        float cy = h / 2f;
        float half = 4f * d;
        float left = 4f * d, right = trackRight - 4f * d;
        rect.set(left, cy - half, right, cy + half);
        fill.setShader(null);
        fill.setColor(0xFF2A2416);
        c.drawRoundRect(rect, half, half, fill);
        stroke.setShader(null);
        stroke.setStrokeWidth(Math.max(1f, d * 0.8f));
        stroke.setColor(0x26F5B841);
        c.drawRoundRect(rect, half, half, stroke);

        if (frac > 0.002f) {
            float fr = Math.max(left + half * 2f, fillX);
            fill.setColor(0xFFFFFFFF);
            fill.setShader(new LinearGradient(left, 0f, fr, 0f,
                    AMBER, NeoBatteryArt.blend(AMBER, 0xFFFFFFFF, 0.25f), Shader.TileMode.CLAMP));
            rect.set(left, cy - half, fr, cy + half);
            c.drawRoundRect(rect, half, half, fill);
            fill.setShader(null);
        }

        float p = Math.max(0f, press);
        float r = 6.5f * d * (1f + 0.18f * p);
        if (p > 0.01f) {
            fill.setColor((Math.min(255, Math.round(0x48 * Math.min(1f, p))) << 24) | (AMBER & 0x00FFFFFF));
            c.drawCircle(fillX, cy, r * 1.45f, fill);
        }
        fill.setColor(0x66000000);
        c.drawCircle(fillX, cy + 0.8f * d, r, fill);
        fill.setColor(0xFFFFF8E8);
        c.drawCircle(fillX, cy, r, fill);
        stroke.setStrokeWidth(2f * d);
        stroke.setColor(AMBER);
        c.drawCircle(fillX, cy, r - d, stroke);
    }

    // ---------------- 新经典 ----------------

    private void drawClassic(Canvas c, float h, float frac, float fillX, float trackRight,
                             float press, float d) {
        float sw = 3f * d;
        float inset = sw / 2f;
        rect.set(inset, inset, trackRight - inset, h - inset);
        float radius = rect.height() / 2f;
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                0xFF1F2B44, 0xFF131A29, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);

        float pad = sw + 1.8f * d;
        float pillTop = pad, pillBottom = h - pad;
        float pillR = (pillBottom - pillTop) / 2f;
        float knobR = Math.max(2f * d, pillR - 1.2f * d);
        if (frac > 0.002f) {
            float pillRight = Math.max(pad + pillR * 2f, fillX + pillR);
            rect.set(pad, pillTop, pillRight, pillBottom);
            fill.setColor(0xFFFFFFFF);
            fill.setShader(new LinearGradient(pad, 0f, pillRight, 0f,
                    CLASSIC_BLUE_DEEP, NeoBatteryArt.blend(CLASSIC_BLUE, 0xFFFFFFFF, 0.32f),
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, pillR, pillR, fill);
            fill.setShader(null);
            // 胶囊上沿的一道柔光
            fill.setColor(0x2EFFFFFF);
            rect.set(pad + pillR * 0.6f, pillTop + 1.2f * d, Math.max(pad + pillR * 0.6f, pillRight - pillR),
                    pillTop + 2.4f * d);
            if (rect.width() > d) c.drawRoundRect(rect, 0.6f * d, 0.6f * d, fill);
        }

        stroke.setShader(null);
        stroke.setStrokeWidth(sw);
        stroke.setColor(0xFF2B3C59);
        rect.set(inset, inset, trackRight - inset, h - inset);
        c.drawRoundRect(rect, radius, radius, stroke);

        float p = Math.max(0f, press);
        float r = knobR * (1f + 0.18f * p);
        float cy = h / 2f;
        fill.setColor(0x55000000);
        c.drawCircle(fillX, cy + 0.8f * d, r, fill);
        fill.setColor(0xFFF2F7FF);
        c.drawCircle(fillX, cy, r, fill);
    }

    // ---------------- 新液位 ----------------

    private void drawLiquid(Canvas c, float h, float frac, float fillX, float trackRight,
                            float phase, float energy, float d) {
        float half = 0.5f * d;
        rect.set(half, half, trackRight - half, h - half);
        float radius = rect.height() / 2f;
        fill.setShader(null);
        fill.setColor(0xFF182620);
        c.drawRoundRect(rect, radius, radius, fill);

        if (frac > 0.002f && fillX > rect.left + d) {
            int save = c.save();
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            float room = Math.min(fillX - rect.left, rect.right - fillX);
            float edge = Math.max(0f, Math.min(1f, room / (6f * d)));
            float amp = (1.1f + 2.4f * energy) * d * edge;
            float wave = h * 1.15f;
            // 后层波: 深绿, 相位错开, 液面更有厚度
            fill.setColor(NeoBatteryArt.blend(0xFF1C8A5A, 0xFF182620, 0.42f));
            wavePath(rect.left - d, rect.top, rect.bottom, fillX + 1.6f * d, amp, wave,
                    phase + (float) Math.PI, d);
            c.drawPath(path, fill);
            fill.setColor(0xFFFFFFFF);
            fill.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                    0xFF6BE4AE, 0xFF1C8A5A, Shader.TileMode.CLAMP));
            wavePath(rect.left - d, rect.top, rect.bottom, fillX, amp, wave, phase, d);
            c.drawPath(path, fill);
            fill.setShader(null);
            if (edge > 0.08f) {
                waveEdge(rect.top, rect.bottom, fillX, amp, wave, phase, d);
                stroke.setShader(null);
                stroke.setStrokeWidth(1.4f * d);
                stroke.setColor(0x88B8FFE0);
                c.drawPath(path, stroke);
            }

            // 气泡: 只在液体里, 随相位缓慢上浮
            float[] bx = {0.22f, 0.5f, 0.78f};
            float[] by = {0.68f, 0.42f, 0.6f};
            float[] br = {1.3f, 1.0f, 1.6f};
            fill.setColor(0x4DFFFFFF);
            for (int i = 0; i < 3; i++) {
                float x = rect.left + (fillX - rect.left) * bx[i];
                if (x > fillX - 4f * d || x < rect.left + radius * 0.6f) continue;
                float rise = (float) ((phase * 0.12f + i * 0.33f) % 1.0);
                float y = rect.top + rect.height() * by[i] - rise * 3f * d * energy;
                c.drawCircle(x, y, br[i] * d, fill);
            }
            c.restoreToCount(save);
        }

        // 玻璃高光 + 描边
        fill.setColor(0x1AFFFFFF);
        float hlLeft = rect.left + radius * 0.8f, hlRight = rect.left + (rect.right - rect.left) * 0.55f;
        if (hlRight - hlLeft > d) {
            float l = rect.left, t = rect.top, r = rect.right, b = rect.bottom;
            rect.set(hlLeft, t + 2.6f * d, hlRight, t + 4f * d);
            c.drawRoundRect(rect, 0.7f * d, 0.7f * d, fill);
            rect.set(l, t, r, b);
        }
        stroke.setShader(null);
        stroke.setStrokeWidth(Math.max(1f, d));
        stroke.setColor(0x333DDC97);
        c.drawRoundRect(rect, radius, radius, stroke);
    }

    /** 竖向液面: 左侧整块填满, 右边界按正弦摆动 */
    private void wavePath(float left, float top, float bottom, float x, float amp, float wave,
                          float phase, float d) {
        path.reset();
        path.moveTo(left, top);
        float step = Math.max(1f, 1.5f * d);
        for (float y = top; y <= bottom + step; y += step) {
            float yy = Math.min(y, bottom);
            float xx = x + amp * (float) Math.sin(2.0 * Math.PI * (yy - top) / wave + phase);
            path.lineTo(xx, yy);
        }
        path.lineTo(left, bottom);
        path.close();
    }

    /** 液面右侧波纹高光线 */
    private void waveEdge(float top, float bottom, float x, float amp, float wave,
                          float phase, float d) {
        path.reset();
        float step = Math.max(1f, 1.5f * d);
        boolean first = true;
        for (float y = top; y <= bottom + step; y += step) {
            float yy = Math.min(y, bottom);
            float xx = x + amp * (float) Math.sin(2.0 * Math.PI * (yy - top) / wave + phase);
            if (first) {
                path.moveTo(xx, yy);
                first = false;
            } else {
                path.lineTo(xx, yy);
            }
        }
    }

    // ---------------- 锁钮 ----------------

    private void drawLockButton(Canvas c, int family, float w, float h, boolean unlocked,
                                float scale, float d) {
        float r = (h / 2f - 1.5f * d) * scale;
        float cx = w - h / 2f, cy = h / 2f;
        int bg, border, icon;
        if (family == AppPrefs.STYLE_CLASSIC) {
            bg = unlocked ? CLASSIC_BLUE_DEEP : 0xFF1B2436;
            border = unlocked ? 0x00000000 : 0x2E4C8DFF;
            icon = unlocked ? 0xFFFFFFFF : 0xFFB8CEEE;
        } else if (family == AppPrefs.STYLE_LIQUID) {
            bg = unlocked ? 0xFF22996A : 0xFF111A16;
            border = unlocked ? 0x00000000 : 0x333DDC97;
            icon = unlocked ? 0xFFFFFFFF : 0xFFB8D8CA;
        } else {
            bg = unlocked ? AMBER : 0xFF18150E;
            border = unlocked ? 0x00000000 : 0x33F5B841;
            icon = unlocked ? 0xFF111317 : 0xFFD8C79A;
        }
        fill.setShader(null);
        fill.setColor(bg);
        c.drawCircle(cx, cy, r, fill);
        if ((border >>> 24) != 0) {
            stroke.setShader(null);
            stroke.setStrokeWidth(Math.max(1f, d));
            stroke.setColor(border);
            c.drawCircle(cx, cy, r - 0.5f * d, stroke);
        }
        drawLock(c, cx, cy + 0.5f * d, unlocked, icon, d * Math.min(1f, h / (24f * d)) * scale);
    }

    /** 线条锁, 与旧风格同几何; u 为缩放后的 dp */
    private void drawLock(Canvas c, float x, float y, boolean unlocked, int color, float u) {
        stroke.setShader(null);
        stroke.setColor(color);
        stroke.setStrokeWidth(1.4f * u);
        rect.set(x - 4.5f * u, y - 0.5f * u, x + 4.5f * u, y + 5.5f * u);
        c.drawRoundRect(rect, 1.5f * u, 1.5f * u, stroke);
        float shift = unlocked ? 2.6f * u : 0f;
        rect.set(x - 2.8f * u + shift, y - 6.5f * u, x + 2.8f * u + shift, y + 2f * u);
        c.drawArc(rect, 180f, 180f, false, stroke);
    }
}
