package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 电池电量: 圆角横向进度条.
 *
 * 独立自绘而不是用 ProgressBar, 原因:
 *  - 高度只有 6dp, 系统 ProgressBar 的进度层会被拉伸/裁切, 圆角也会变形;
 *  - 进度色要按语义切换 (正常绿 / 充电橙 / 低电红), 自绘不用折腾 tint;
 *  - 磁贴里是同一套画法 (DialPainter), 两边视觉必须一致.
 */
public class BatteryBarView extends View {

    private static final float RADIUS_DP = 3f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int percent = -1;
    private int fillColor;

    public BatteryBarView(Context context) {
        super(context);
        init();
    }

    public BatteryBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setColor(0xFF1E1E21);
        fillPaint.setColor(0xFF4CC38A);
        fillColor = 0xFF4CC38A;
    }

    /** percent < 0 表示未知, 只画底槽 */
    public void setPercent(int percent) {
        this.percent = percent;
        invalidate();
    }

    public void setFillColor(int color) {
        this.fillColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.min(h / 2f, RADIUS_DP * getResources().getDisplayMetrics().density);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        if (percent <= 0) {
            return;
        }
        float p = Math.min(100, percent) / 100f;
        rect.set(0, 0, Math.max(h, w * p), h); // 至少留一个圆头, 避免 1% 时看不见
        fillPaint.setColor(fillColor);
        canvas.drawRoundRect(rect, r, r, fillPaint);
    }
}
