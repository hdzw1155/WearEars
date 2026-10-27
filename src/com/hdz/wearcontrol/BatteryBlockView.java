package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * 电量块: 圆角矩形 + 液位填充(从底部向上, 语义色低透明度) + 居中白色数字.
 * 充电时块底与 1dp 描边变琥珀橙, 右上角一颗小线性闪电.
 */
public class BatteryBlockView extends View {

    private static final float RADIUS_DP = 18f;
    private static final float TEXT_NUM_SP = 23f;
    private static final float TEXT_UNIT_SP = 15f;
    private static final float BORDER_DP = 1f;
    private static final float FILL_ALPHA = 0.27f;

    private static final int COLOR_BLOCK = 0xFF1E1E21;
    private static final int COLOR_OK = 0xFF4CC38A;
    private static final int COLOR_WARN = 0xFFF5A524;
    private static final int COLOR_LOW = 0xFFF0524F;
    private static final int COLOR_TEXT = 0xFFFFFFFF;

    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rectPath = new Path();
    private final Path boltPath = new Path();
    private final RectF rect = new RectF();

    private int percent = -1;
    private boolean charging;

    public BatteryBlockView(Context context) {
        super(context);
        init();
    }

    public BatteryBlockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryBlockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        blockPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(COLOR_BLOCK);
        fillPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        numPaint.setStyle(Paint.Style.FILL);
        numPaint.setColor(COLOR_TEXT);
        numPaint.setTextSize(TEXT_NUM_SP * getResources().getDisplayMetrics().scaledDensity);
        numPaint.setFakeBoldText(true);
        numPaint.setTextAlign(Paint.Align.LEFT);
        unitPaint.setStyle(Paint.Style.FILL);
        unitPaint.setColor(COLOR_TEXT);
        unitPaint.setTextSize(TEXT_UNIT_SP * getResources().getDisplayMetrics().scaledDensity);
        unitPaint.setFakeBoldText(true);
        unitPaint.setTextAlign(Paint.Align.LEFT);
    }

    public void setBattery(final int pct, final boolean isCharging) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(new Runnable() {
                @Override
                public void run() {
                    setBattery(pct, isCharging);
                }
            });
            return;
        }
        this.percent = pct;
        this.charging = isCharging;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = dp(RADIUS_DP);
        rect.set(0, 0, w, h);

        // 块底
        rectPath.reset();
        rectPath.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.drawPath(rectPath, blockPaint);

        boolean known = percent >= 0 && percent <= 100;
        int color;
        if (charging && known) {
            color = COLOR_WARN;
        } else if (known && percent <= 20) {
            color = COLOR_LOW;
        } else {
            color = COLOR_OK;
        }

        // 液位填充: 从底部向上
        if (known && percent > 0) {
            int alpha = (int) (FILL_ALPHA * 255f);
            fillPaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.save();
            canvas.clipPath(rectPath);
            float top = h * (1f - percent / 100f);
            canvas.drawRect(0, top, w, h, fillPaint);
            canvas.restore();
        }

        // 充电: 1dp 琥珀描边
        if (charging && known) {
            borderPaint.setColor(color);
            borderPaint.setStrokeWidth(dp(BORDER_DP));
            float inset = dp(BORDER_DP) / 2f;
            rect.set(inset, inset, w - inset, h - inset);
            rectPath.reset();
            rectPath.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.drawPath(rectPath, borderPaint);
        }

        // 数字 + 小一号的 % (超宽时自动缩字号, 保证 100% 不顶边)
        if (known) {
            String num = String.valueOf(percent);
            float sd = getResources().getDisplayMetrics().scaledDensity;
            float numSp = TEXT_NUM_SP;
            float unitSp = TEXT_UNIT_SP;
            float maxW = w - dp(8f);
            for (int guard = 0; guard < 12; guard++) {
                numPaint.setTextSize(numSp * sd);
                unitPaint.setTextSize(unitSp * sd);
                float total = numPaint.measureText(num) + unitPaint.measureText("%");
                if (total <= maxW) {
                    break;
                }
                numSp *= 0.94f;
                unitSp *= 0.94f;
            }
            float numW = numPaint.measureText(num);
            float unitW = unitPaint.measureText("%");
            float total = numW + unitW;
            float startX = (w - total) / 2f;
            Paint.FontMetrics fm = numPaint.getFontMetrics();
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            numPaint.setColor(COLOR_TEXT);
            unitPaint.setColor(COLOR_TEXT);
            canvas.drawText(num, startX, baseline, numPaint);
            Paint.FontMetrics ufm = unitPaint.getFontMetrics();
            float ubaseline = baseline - (fm.descent - ufm.descent);
            canvas.drawText("%", startX + numW, ubaseline, unitPaint);
        } else {
            Paint.FontMetrics fm = numPaint.getFontMetrics();
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            numPaint.setColor(0x85FFFFFF);
            numPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("--", w / 2f, baseline, numPaint);
            numPaint.setTextAlign(Paint.Align.LEFT);
        }

        // 充电: 右上角线性小闪电
        if (charging && known) {
            float s = dp(6.5f);
            float cx = w - dp(11f);
            float cy = dp(11f);
            boltPath.reset();
            boltPath.moveTo(cx + s * 0.18f, cy - s);
            boltPath.lineTo(cx - s * 0.62f, cy + s * 0.12f);
            boltPath.lineTo(cx - s * 0.06f, cy + s * 0.12f);
            boltPath.lineTo(cx - s * 0.22f, cy + s);
            boltPath.lineTo(cx + s * 0.62f, cy - s * 0.16f);
            boltPath.lineTo(cx + s * 0.06f, cy - s * 0.16f);
            boltPath.close();
            Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            boltPaint.setStyle(Paint.Style.FILL);
            boltPaint.setColor(COLOR_WARN);
            canvas.drawPath(boltPath, boltPaint);
        }
    }
}
