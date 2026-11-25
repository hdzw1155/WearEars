package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * El Lissitzky / Suprematist poster backdrop matching WearEars hero reference art.
 * Keeps all sharp crimson/cobalt/gold/sage beams and slashes tucked around the outer
 * bezel margin so central interactive controls stay completely crisp and never clipped.
 */
public class SuprematistDecorView extends View {

    static final int THEME_PAPER = 0;
    static final int THEME_CONTROL = 1;
    static final int THEME_HAZARD = 2;
    static final int THEME_MANIFESTO = 3;

    private static final int COL_BG = 0xFFF4F3EE;
    private static final int COL_RED = 0xFFDF1B22;
    private static final int COL_BLUE = 0xFF0D4FB8;
    private static final int COL_YELLOW = 0xFFF6C100;
    private static final int COL_SAGE = 0xFF68976D;
    private static final int COL_INK = 0xFF111317;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path polyPath = new Path();
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
            if ("control".equals(s) || "blue".equals(s)) {
                theme = THEME_CONTROL;
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
        float d = Math.min(w, h);
        float den = getResources().getDisplayMetrics().density;

        canvas.drawColor(COL_BG);

        if (theme == THEME_PAPER) {
            drawHeroPosterFrame(canvas, w, h, d, den);
        } else if (theme == THEME_CONTROL) {
            drawControlPosterFrame(canvas, w, h, d, den);
        } else if (theme == THEME_HAZARD) {
            drawDevPosterFrame(canvas, w, h, d, den);
        } else {
            drawManifestoPosterFrame(canvas, w, h, d, den);
        }
    }

    /** Exact El Lissitzky composition from user's reference poster */
    private void drawHeroPosterFrame(Canvas canvas, int w, int h, float d, float den) {
        // 1) Top crimson inverted wedge
        fillPaint.setColor(COL_RED);
        setTri(0.46f * w, 0f, 0.68f * w, 0f, 0.39f * w, 0.14f * h);
        canvas.drawPath(polyPath, fillPaint);

        // 2) Top-right yellow corner wedge framing cleanly behind refresh box
        fillPaint.setColor(COL_YELLOW);
        setTri(0.69f * w, 0.18f * h, 0.90f * w, 0.06f * h, 0.94f * w, 0.34f * h);
        canvas.drawPath(polyPath, fillPaint);

        // Crisp diagonal cutting hairline top-right
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.4f * den));
        canvas.drawLine(0.64f * w, 0.24f * h, 0.88f * w, 0.03f * h, strokePaint);

        // 3) Subtle left Constructivist edge accent clear of C-arc rail
        fillPaint.setColor(COL_INK);
        setQuad(0f, 0.46f * h, 0.018f * w, 0.43f * h, 0.018f * w, 0.56f * h, 0f, 0.59f * h);
        canvas.drawPath(polyPath, fillPaint);

        // 4) Bottom-right geometric beam symphony (Blue ribbon + Gold wedge + Black beam + Sage + Red)
        fillPaint.setColor(COL_BLUE);
        setQuad(0.79f * w, 0.58f * h, 0.90f * w, 0.38f * h, 0.96f * w, 0.38f * h, 0.72f * w, 0.74f * h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_YELLOW);
        setTri(0.75f * w, 0.87f * h, w, 0.52f * h, w, 0.94f * h);
        canvas.drawPath(polyPath, fillPaint);

        // Sweeping diagonal black structural beam across lower-right
        fillPaint.setColor(COL_INK);
        setQuad(0.69f * w, h, 0.97f * w, 0.36f * h, w, 0.37f * h, 0.73f * w, h);
        canvas.drawPath(polyPath, fillPaint);

        // Bottom black base wedge
        fillPaint.setColor(COL_INK);
        setQuad(0f, h, 0.28f * w, 0.94f * h, 0.76f * w, 0.85f * h, 0.79f * w, h);
        canvas.drawPath(polyPath, fillPaint);

        // Sage green parallelogram piercing bottom bar
        fillPaint.setColor(COL_SAGE);
        setQuad(0.56f * w, 0.91f * h, 0.67f * w, 0.83f * h, 0.74f * w, 0.83f * h, 0.63f * w, 0.95f * h);
        canvas.drawPath(polyPath, fillPaint);

        // Small vermilion block bottom-right
        fillPaint.setColor(COL_RED);
        setQuad(0.76f * w, 0.85f * h, 0.86f * w, 0.82f * h, 0.88f * w, 0.92f * h, 0.78f * w, 0.95f * h);
        canvas.drawPath(polyPath, fillPaint);

        // Bottom hairline + triple /// hatch marks
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(Math.max(1f, 1.3f * den));
        canvas.drawLine(0.16f * w, 0.96f * h, 0.39f * w, 0.87f * h, strokePaint);
        for (int i = 0; i < 3; i++) {
            float hx = 0.88f * w + i * 5.2f * den;
            canvas.drawLine(hx, 0.95f * h, hx + 3.5f * den, 0.90f * h, strokePaint);
        }
    }

    /** Reference Image #2 composition: rotated yellow diamond + bold cobalt/black/sage/red beams */
    private void drawControlPosterFrame(Canvas canvas, int w, int h, float d, float den) {
        // Large rotated gold diamond top-right + black framing corner beam
        fillPaint.setColor(COL_YELLOW);
        setQuad(0.55f * w, 0.08f * h, 0.78f * w, 0f, 0.90f * w, 0.24f * h, 0.69f * w, 0.39f * h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_INK);
        setQuad(0.76f * w, 0f, 0.83f * w, 0f, 0.83f * w, 0.24f * h, 0.76f * w, 0.24f * h);
        canvas.drawPath(polyPath, fillPaint);

        // Sweeping bold cobalt blue diagonal ribbon rising across right half
        fillPaint.setColor(COL_BLUE);
        setQuad(0.50f * w, h, w, 0.08f * h, w, 0.34f * h, 0.67f * w, h);
        canvas.drawPath(polyPath, fillPaint);

        // Black stepped diagonal rail below cobalt beam
        fillPaint.setColor(COL_INK);
        setQuad(0.70f * w, h, w, 0.44f * h, w, 0.50f * h, 0.75f * w, h);
        canvas.drawPath(polyPath, fillPaint);

        // Sage green trapezoid + blazing red bottom-right triangle
        fillPaint.setColor(COL_SAGE);
        setQuad(0.76f * w, h, 0.86f * w, 0.85f * h, 0.94f * w, 0.85f * h, 0.85f * w, h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_RED);
        setTri(0.82f * w, h, w, 0.66f * h, w, h);
        canvas.drawPath(polyPath, fillPaint);

        // Crisp Constructivist hairlines top-center & bottom-left
        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.4f * den));
        canvas.drawLine(0.45f * w, 0.14f * h, 0.61f * w, 0.01f * h, strokePaint);
        canvas.drawLine(0.32f * w, 0.98f * h, 0.46f * w, 0.84f * h, strokePaint);
    }

    private void drawDevPosterFrame(Canvas canvas, int w, int h, float d, float den) {
        fillPaint.setColor(COL_YELLOW);
        setTri(0f, 0f, 0.34f * w, 0f, 0f, 0.28f * h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_RED);
        setTri(0.74f * w, h, w, 0.72f * h, w, h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_INK);
        setQuad(0.72f * w, 0f, 0.82f * w, 0f, 0.98f * w, 0.22f * h, 0.88f * w, 0.22f * h);
        canvas.drawPath(polyPath, fillPaint);

        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.4f * den);
        for (int i = 0; i < 3; i++) {
            float x = 0.12f * w + i * 5.5f * den;
            canvas.drawLine(x, 0.94f * h, x + 4.5f * den, 0.88f * h, strokePaint);
        }
    }

    private void drawManifestoPosterFrame(Canvas canvas, int w, int h, float d, float den) {
        fillPaint.setColor(COL_RED);
        setQuad(0.68f * w, 0f, w, 0f, w, 0.26f * h, 0.80f * w, 0.18f * h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_SAGE);
        setQuad(0f, 0.76f * h, 0.22f * w, 0.88f * h, 0.14f * w, h, 0f, h);
        canvas.drawPath(polyPath, fillPaint);

        fillPaint.setColor(COL_BLUE);
        setTri(0.76f * w, h, w, 0.78f * h, w, h);
        canvas.drawPath(polyPath, fillPaint);

        strokePaint.setColor(COL_INK);
        strokePaint.setStrokeWidth(1.3f * den);
        canvas.drawLine(0.16f * w, 0.11f * h, 0.36f * w, 0.04f * h, strokePaint);
    }

    private void setTri(float x0, float y0, float x1, float y1, float x2, float y2) {
        polyPath.reset();
        polyPath.moveTo(x0, y0);
        polyPath.lineTo(x1, y1);
        polyPath.lineTo(x2, y2);
        polyPath.close();
    }

    private void setQuad(float x0, float y0, float x1, float y1,
                         float x2, float y2, float x3, float y3) {
        polyPath.reset();
        polyPath.moveTo(x0, y0);
        polyPath.lineTo(x1, y1);
        polyPath.lineTo(x2, y2);
        polyPath.lineTo(x3, y3);
        polyPath.close();
    }
}
