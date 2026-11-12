package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Suprematist x P5R avant-garde architectural backdrop for circular Wear OS sheets.
 * Controlled via android:tag ("paper", "ink", "blue", "hazard", "manifesto").
 * Places high-contrast Constructivist shards, skewed ribbons, halftone dots and
 * off-axis cuts in peripheral zones to balance "ordered chaos" with legibility.
 */
public class SuprematistDecorView extends View {

    static final int THEME_PAPER = 0;
    static final int THEME_INK = 1;
    static final int THEME_BLUE = 2;
    static final int THEME_HAZARD = 3;
    static final int THEME_MANIFESTO = 4;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shardPath = new Path();
    private int theme = THEME_PAPER;

    public SuprematistDecorView(Context context) {
        this(context, null);
    }

    public SuprematistDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        resolveTag();
    }

    private void resolveTag() {
        Object tag = getTag();
        if (tag instanceof String) {
            String s = (String) tag;
            if ("ink".equals(s)) {
                theme = THEME_INK;
            } else if ("blue".equals(s)) {
                theme = THEME_BLUE;
            } else if ("hazard".equals(s)) {
                theme = THEME_HAZARD;
            } else if ("manifesto".equals(s)) {
                theme = THEME_MANIFESTO;
            } else {
                theme = THEME_PAPER;
            }
        }
    }

    public void setTheme(int themeMode) {
        if (theme != themeMode) {
            theme = themeMode;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;

        switch (theme) {
            case THEME_INK:
                drawInkAcoustic(canvas, w, h, d);
                break;
            case THEME_BLUE:
                drawBlueControl(canvas, w, h, d);
                break;
            case THEME_HAZARD:
                drawHazardDev(canvas, w, h, d);
                break;
            case THEME_MANIFESTO:
                drawManifestoNotice(canvas, w, h, d);
                break;
            default:
                drawPaperDial(canvas, w, h, d);
                break;
        }
    }

    private void drawPaperDial(Canvas canvas, int w, int h, float d) {
        canvas.drawColor(0xFFF3F1E8);

        // Bold crimson slash across top-right corner
        fillPaint.setColor(0xFFE5232A);
        setQuad(0.76f * w, 0f, w, 0f, w, 0.34f * h, 0.87f * w, 0.24f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Ink black skewed slab hugging top-left frame
        fillPaint.setColor(0xFF101318);
        setQuad(0f, 0.04f * h, 0.28f * w, 0f, 0.21f * w, 0.16f * h, 0f, 0.23f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Electric cobalt geometric wedge at bottom edge
        fillPaint.setColor(0xFF1A54E5);
        setQuad(0f, 0.84f * h, 0.32f * w, 0.92f * h, 0.24f * w, h, 0f, h);
        canvas.drawPath(shardPath, fillPaint);

        // Cadmium gold triangle along right margin
        fillPaint.setColor(0xFFF4BA00);
        setQuad(0.84f * w, 0.66f * h, w, 0.56f * h, w, 0.82f * h, 0.90f * w, 0.88f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Constructivist cross-hairlines
        strokePaint.setColor(0x28101318);
        strokePaint.setStrokeWidth(Math.max(1f, 1.1f * d));
        canvas.drawLine(0.06f * w, 0.32f * h, 0.36f * w, 0.02f * h, strokePaint);
        canvas.drawLine(0.62f * w, 0.96f * h, 0.98f * w, 0.58f * h, strokePaint);

        // Halftone dot burst bottom-left outer ring
        fillPaint.setColor(0x30101318);
        drawDotCluster(canvas, 0.15f * w, 0.78f * h, d, 3, 3, 4.4f * d, 1.35f * d);
        fillPaint.setColor(0x66E5232A);
        drawDiamond(canvas, 0.86f * w, 0.30f * h, 4.5f * d);
    }

    private void drawInkAcoustic(Canvas canvas, int w, int h, float d) {
        canvas.drawColor(0xFF101216);

        // P5R Phantom Crimson diagonal broad slash behind center stack
        fillPaint.setColor(0xFFBA1C23);
        setQuad(0f, 0.38f * h, w, 0.14f * h, w, 0.32f * h, 0f, 0.56f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Crisp charcoal mask over crimson to create jagged splinter step
        fillPaint.setColor(0xFF191D24);
        setQuad(0.66f * w, 0f, w, 0f, w, 0.21f * h, 0.78f * w, 0.25f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Paper-white sliver bottom-right + cobalt anchor bottom-left
        fillPaint.setColor(0xFFF3F1E8);
        setQuad(0.72f * w, 0.91f * h, w, 0.76f * h, w, 0.87f * h, 0.80f * w, h);
        canvas.drawPath(shardPath, fillPaint);

        fillPaint.setColor(0xFF1A54E5);
        setQuad(0f, 0.78f * h, 0.22f * w, 0.90f * h, 0.16f * w, h, 0f, h);
        canvas.drawPath(shardPath, fillPaint);

        // High-voltage gold outline slash
        strokePaint.setColor(0xFFF4BA00);
        strokePaint.setStrokeWidth(1.6f * d);
        canvas.drawLine(0.08f * w, 0.18f * h, 0.32f * w, 0.08f * h, strokePaint);
        fillPaint.setColor(0xFFF4BA00);
        drawDotCluster(canvas, 0.84f * w, 0.70f * h, d, 2, 4, 4.2f * d, 1.3f * d);
    }

    private void drawBlueControl(Canvas canvas, int w, int h, float d) {
        canvas.drawColor(0xFF163880);

        // Deep obsidian upper-right corner wedge
        fillPaint.setColor(0xFF0E121B);
        setQuad(0.64f * w, 0f, w, 0f, w, 0.34f * h, 0.78f * w, 0.22f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Bold bone-paper ribbon slashing lower left
        fillPaint.setColor(0xFFF3F1E8);
        setQuad(0f, 0.72f * h, 0.28f * w, 0.89f * h, 0.20f * w, h, 0f, h);
        canvas.drawPath(shardPath, fillPaint);

        // Crimson flash accent right
        fillPaint.setColor(0xFFE5232A);
        setQuad(0.84f * w, 0.62f * h, w, 0.50f * h, w, 0.66f * h, 0.88f * w, 0.74f * h);
        canvas.drawPath(shardPath, fillPaint);

        strokePaint.setColor(0x88F4BA00);
        strokePaint.setStrokeWidth(1.5f * d);
        canvas.drawLine(0.10f * w, 0.12f * h, 0.30f * w, 0.04f * h, strokePaint);
        canvas.drawLine(0.70f * w, 0.94f * h, 0.94f * w, 0.82f * h, strokePaint);
    }

    private void drawHazardDev(Canvas canvas, int w, int h, float d) {
        canvas.drawColor(0xFF15161B);

        // Crimson warning shard top-left (contrasts cleanly with ivory DEV title)
        fillPaint.setColor(0xFFE5232A);
        setQuad(0f, 0f, 0.24f * w, 0f, 0.16f * w, 0.12f * h, 0f, 0.16f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Crimson danger corner bottom-right
        fillPaint.setColor(0xFFE5232A);
        setQuad(0.70f * w, h, w, 0.74f * h, w, h, 0.78f * w, h);
        canvas.drawPath(shardPath, fillPaint);

        // Alternating hazard tick stripes top-right
        strokePaint.setColor(0x66F4BA00);
        strokePaint.setStrokeWidth(2.4f * d);
        for (int i = 0; i < 4; i++) {
            float sx = 0.72f * w + i * 7f * d;
            canvas.drawLine(sx, 0.08f * h, sx - 9f * d, 0.19f * h, strokePaint);
        }
    }

    private void drawManifestoNotice(Canvas canvas, int w, int h, float d) {
        canvas.drawColor(0xFF12151C);

        // High-art crimson square rotated top-right
        fillPaint.setColor(0xFFE5232A);
        setQuad(0.76f * w, 0.02f * h, w, 0.08f * h, 0.94f * w, 0.28f * h, 0.72f * w, 0.18f * h);
        canvas.drawPath(shardPath, fillPaint);

        // Cobalt Constructivist column left bottom
        fillPaint.setColor(0xFF1A54E5);
        setQuad(0f, 0.74f * h, 0.18f * w, 0.82f * h, 0.14f * w, h, 0f, h);
        canvas.drawPath(shardPath, fillPaint);

        // Thin cream framing ticks
        strokePaint.setColor(0x4DF3F1E8);
        strokePaint.setStrokeWidth(1.2f * d);
        canvas.drawLine(0.14f * w, 0.12f * h, 0.34f * w, 0.06f * h, strokePaint);
        canvas.drawLine(0.66f * w, 0.93f * h, 0.88f * w, 0.87f * h, strokePaint);
        fillPaint.setColor(0xFFF4BA00);
        drawDiamond(canvas, 0.86f * w, 0.78f * h, 4.2f * d);
    }

    private void setQuad(float x0, float y0, float x1, float y1,
                         float x2, float y2, float x3, float y3) {
        shardPath.reset();
        shardPath.moveTo(x0, y0);
        shardPath.lineTo(x1, y1);
        shardPath.lineTo(x2, y2);
        shardPath.lineTo(x3, y3);
        shardPath.close();
    }

    private void drawDotCluster(Canvas canvas, float startX, float startY, float d,
                                int cols, int rows, float stepPx, float radiusPx) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float px = startX + (c * stepPx) + (r * 0.45f * stepPx);
                float py = startY + (r * stepPx) - (c * 0.25f * stepPx);
                canvas.drawCircle(px, py, radiusPx, fillPaint);
            }
        }
    }

    private void drawDiamond(Canvas canvas, float cx, float cy, float half) {
        shardPath.reset();
        shardPath.moveTo(cx, cy - half);
        shardPath.lineTo(cx + half, cy);
        shardPath.lineTo(cx, cy + half);
        shardPath.lineTo(cx - half, cy);
        shardPath.close();
        canvas.drawPath(shardPath, fillPaint);
    }
}
