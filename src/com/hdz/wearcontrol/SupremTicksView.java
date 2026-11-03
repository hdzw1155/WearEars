package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 「至上」界面的边缘滚动指示: 贴屏幕右缘一排短斜刻度(与主斜轴 -34° 平行) + 一个珊瑚红斜杠标记当前位置。
 * 和内容分离, 固定在屏幕上, 不随页面滚动(由 MainActivity 根据滚动比例调用 setFraction)。
 *
 * <p>按要求不使用任何横平竖直的方块: 刻度是一簇同角度的短斜线, 当前位置用珊瑚红斜杠强调。
 */
public class SupremTicksView extends View {

    private static final int INK = 0xFF121417;
    private static final int CORAL = 0xFFD96560;

    private static final int TICKS = 18;
    private static final float TOP_DP = 96f;
    private static final float BOTTOM_DP = 70f;
    private static final float ANGLE_DEG = -34f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float cos;
    private final float sin;
    private float fraction;

    public SupremTicksView(Context context) {
        this(context, null);
    }

    public SupremTicksView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        double rad = Math.toRadians(ANGLE_DEG);
        cos = (float) Math.cos(rad);
        sin = (float) Math.sin(rad);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setFraction(float f) {
        fraction = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float top = TOP_DP * density;
        float bottom = h - BOTTOM_DP * density;
        float span = bottom - top;
        float anchorX = w - 7f * density;
        for (int i = 0; i < TICKS; i++) {
            float y = top + span * i / (TICKS - 1);
            boolean major = i % 5 == 0;
            paint.setColor(major ? INK : 0x55121417);
            paint.setStrokeWidth((major ? 1.7f : 1f) * density);
            float len = (major ? 6.5f : 3.8f) * density;
            // 统一沿 -34° 方向拉出短斜线, 右端锚在一条隐形的斜向基线上
            canvas.drawLine(anchorX - len * cos, y - len * sin, anchorX, y, paint);
        }
        // 唯一焦点色: 珊瑚红当前位置斜杠(全局只有极小块用珊瑚红)
        float my = top + span * fraction;
        paint.setColor(CORAL);
        paint.setStrokeWidth(2.6f * density);
        float len = 10f * density;
        canvas.drawLine(anchorX - len * cos, my - len * sin,
                anchorX + 2f * density, my + 2f * density, paint);
    }
}
