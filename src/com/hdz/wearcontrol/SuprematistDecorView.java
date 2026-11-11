package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight Suprematist architectural background backdrop for Wear OS watch sheets.
 * Controlled via android:tag ("paper", "ink", "blue") so round-screen bezel edges
 * carry subtle drafting hairlines and geometric marks without colliding with text.
 */
public class SuprematistDecorView extends View {

    static final int THEME_PAPER = 0;
    static final int THEME_INK = 1;
    static final int THEME_BLUE = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int theme = THEME_PAPER;

    public SuprematistDecorView(Context context) {
        this(context, null);
    }

    public SuprematistDecorView(Context context, AttributeSet attrs) {
        super(context, attrs);
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

        int bgColor;
        int lineColor;
        int primaryAccent;
        int secondaryAccent;
        if (theme == THEME_INK) {
            bgColor = 0xFF14171C;
            lineColor = 0x1AFFFFFF;
            primaryAccent = 0xFFEAB31A;
            secondaryAccent = 0xFF1E4182;
        } else if (theme == THEME_BLUE) {
            bgColor = 0xFF1E4182;
            lineColor = 0x24F6F6F2;
            primaryAccent = 0xFFEAB31A;
            secondaryAccent = 0xFFF6F6F2;
        } else {
            bgColor = 0xFFF6F6F2;
            lineColor = 0x14101316;
            primaryAccent = 0xFFDF6863;
            secondaryAccent = 0xFF1E4182;
        }

        canvas.drawColor(bgColor);

        // Subtle Constructivist diagonal datum line crossing edge-to-edge behind content
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 0.9f * d));
        paint.setColor(lineColor);
        canvas.drawLine(0f, h * 0.22f, w * 0.22f, 0f, paint);
        canvas.drawLine(w * 0.78f, h, w, h * 0.78f, paint);

        // Peripheral rotated micro-diamond near 10-o'clock bezel zone (safely outside safe box)
        paint.setStyle(Paint.Style.FILL);
        float rx = w * 0.14f;
        float ry = h * 0.14f;
        float sq = 3.2f * d;
        canvas.save();
        canvas.rotate(45f, rx, ry);
        paint.setColor(primaryAccent);
        canvas.drawRect(rx - sq, ry - sq, rx + sq, ry + sq, paint);
        canvas.restore();

        // Counterbalance architectural micro-bar at 7-o'clock outer edge
        paint.setColor(secondaryAccent);
        float bx = w * 0.18f;
        float by = h * 0.87f;
        canvas.drawRect(bx, by, bx + 10f * d, by + 2.2f * d, paint);
    }
}
