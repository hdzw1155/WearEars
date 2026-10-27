package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * 经典界面的电量块: 把 0.3.x 的圆环「压扁」成圆角矩形(stadium)轮廓.
 * 数字仍然在轮廓中间, 名字在下面(布局里是单独的 TextView).
 * 未知电量只画底槽 + 「--」, 不假装 0%.
 */
public class BatteryRectRingView extends View {

    /** 配色: 默认经典(备份版)取值, 新版界面会用 setPalette 覆盖成视觉统一令牌 */
    private int colorTrack = 0xFF3A3A3C;
    private int colorOk = 0xFF34C759;
    private int colorLow = 0xFFFA5151;
    private int colorCharge = 0xFFFF9F0A;
    private int colorText = 0xFFFFFFFF;
    private int colorFaint = 0xFF6E6E73;

    private static final float STROKE_DP = 3.2f;
    private static final float NUM_SP = 14f;
    private static final float UNIT_SP = 8f;
    private static final float LOW_PCT = 20f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ringPath = new Path();
    private final Path segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();

    private float cornerRadiusDp = 20f;
    private int percent = -1;
    private boolean charging;

    /** 圆角与外层胶囊一致(默认 20dp) */
    public void setCornerRadiusDp(float dp) {
        cornerRadiusDp = dp;
        invalidate();
    }

    public BatteryRectRingView(Context context) {
        this(context, null);
    }

    public BatteryRectRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(STROKE_DP * density);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(colorTrack);

        arcPaint.set(trackPaint);

        numPaint.setStyle(Paint.Style.FILL);
        numPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        numPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 经典界面配色: 旧灰轨道 + 旧绿/红/橙 + 纯白数字 */
    public void setPalette(int track, int ok, int low, int charge, int text, int faint) {
        colorTrack = track;
        colorOk = ok;
        colorLow = low;
        colorCharge = charge;
        colorText = text;
        colorFaint = faint;
        invalidate();
    }

    public void setBattery(int pct, boolean isCharging) {
        this.percent = pct;
        this.charging = isCharging;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            post(new Runnable() {
                @Override
                public void run() {
                    invalidate();
                }
            });
            return;
        }
        invalidate();
    }

    private void buildPath() {
        float density = getResources().getDisplayMetrics().density;
        float half = STROKE_DP * density / 2f;
        float w = getWidth();
        float h = getHeight();
        rect.set(half, half, w - half, h - half);
        float radius = Math.min(Math.min(rect.height(), rect.width()) / 2f,
                cornerRadiusDp * getResources().getDisplayMetrics().density);
        ringPath.reset();
        ringPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        buildPath();
        canvas.drawPath(ringPath, trackPaint);

        boolean known = percent >= 0 && percent <= 100;
        float density = getResources().getDisplayMetrics().density;

        if (known && percent > 0) {
            int color;
            if (charging) {
                color = colorCharge;
            } else if (percent <= LOW_PCT) {
                color = colorLow;
            } else {
                color = colorOk;
            }
            arcPaint.setColor(color);
            measure.setPath(ringPath, false);
            float length = measure.getLength();
            segment.reset();
            measure.getSegment(0f, Math.max(1f, length * percent / 100f), segment, true);
            canvas.drawPath(segment, arcPaint);
        }

        // 数字 + 小一号的「%」, 垂直居中; 未知时画 --
        // 经典样式: 只画数字, 不带 %, 水平+垂直居中
        String num = known ? String.valueOf(percent) : "--";
        float numSize = NUM_SP * density;
        numPaint.setTextSize(numSize);
        numPaint.setColor(known ? colorText : colorFaint);
        float numW = numPaint.measureText(num);
        // numPaint 是 Align.CENTER, 直接给中心 x; 之前的 (w-numW)/2 会再左偏半个字宽
        float centerX = getWidth() / 2f;
        float baseY = getHeight() / 2f - (numPaint.ascent() + numPaint.descent()) / 2f;
        canvas.drawText(num, centerX, baseY, numPaint);
    }
}
