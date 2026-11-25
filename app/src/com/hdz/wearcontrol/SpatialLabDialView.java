package com.hdz.wearcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/**
 * Interactive 360° azimuth dial for WearEars Spatial Lab.
 * Renders a centered head silhouette surrounded by a graduated azimuth ring and a draggable
 * sound-source orb (0° = front/top, 90° = right, 180° = back, 270° = left).
 */
public class SpatialLabDialView extends View {

    public interface OnAzimuthChangeListener {
        void onAzimuthChanged(float azimuthDeg, boolean fromUser);
    }

    private static final int COL_RING = 0x33FFFFFF;
    private static final int COL_GUIDE = 0x1AFFFFFF;
    private static final int COL_TICK = 0x55FFFFFF;
    private static final int COL_TICK_CARDINAL = 0xBBFFFFFF;
    private static final int COL_LABEL = 0x99FFFFFF;
    private static final int COL_HEAD_FILL = 0xFF23262E;
    private static final int COL_HEAD_STROKE = 0xAAFFFFFF;
    private static final int COL_ORB = 0xFF4C8DFF;
    private static final int COL_ORB_CORE = 0xFFFFFFFF;
    private static final int COL_ORB_HALO = 0x444C8DFF;
    private static final int COL_VECTOR = 0x884C8DFF;
    private static final int COL_WAVE = 0x555EDDA6;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path nosePath = new Path();
    private final RectF earRect = new RectF();

    private float density;
    private float azimuthDeg = 0.0f; // [0, 360)
    private boolean playing = false;
    private boolean dragging = false;
    private int lastDetentBucket = 0;
    private OnAzimuthChangeListener listener;

    public SpatialLabDialView(Context context) {
        this(context, null);
    }

    public SpatialLabDialView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpatialLabDialView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.6f * density);
        ringPaint.setColor(COL_RING);

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.0f * density);
        guidePaint.setColor(COL_GUIDE);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COL_LABEL);
        labelPaint.setTextSize(9.5f * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        headFillPaint.setStyle(Paint.Style.FILL);
        headFillPaint.setColor(COL_HEAD_FILL);

        headStrokePaint.setStyle(Paint.Style.STROKE);
        headStrokePaint.setStrokeWidth(1.5f * density);
        headStrokePaint.setColor(COL_HEAD_STROKE);
        headStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        vectorPaint.setStyle(Paint.Style.STROKE);
        vectorPaint.setStrokeWidth(2.0f * density);
        vectorPaint.setStrokeCap(Paint.Cap.ROUND);
        vectorPaint.setColor(COL_VECTOR);

        orbHaloPaint.setStyle(Paint.Style.FILL);
        orbHaloPaint.setColor(COL_ORB_HALO);

        orbPaint.setStyle(Paint.Style.FILL);
        orbPaint.setColor(COL_ORB);

        orbCorePaint.setStyle(Paint.Style.FILL);
        orbCorePaint.setColor(COL_ORB_CORE);

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(1.4f * density);
        wavePaint.setColor(COL_WAVE);
    }

    private int tickColor = COL_TICK;
    private int tickCardinalColor = COL_TICK_CARDINAL;

    public void applyUiStyle(int uiStyle) {
        boolean light = NeoStyle.light(uiStyle);
        int accent = NeoStyle.accentColor(uiStyle);
        int pulse = NeoStyle.pulseColor(uiStyle);
        if (light) {
            ringPaint.setColor(0x44111317);
            guidePaint.setColor(0x22111317);
            tickColor = 0x55111317;
            tickCardinalColor = 0xDD111317;
            labelPaint.setColor(0xCC111317);
            headFillPaint.setColor(0xFFFFFFFF);
            headStrokePaint.setColor(0xFF111317);
            orbCorePaint.setColor(0xFFFFFFFF);
        } else {
            ringPaint.setColor(COL_RING);
            guidePaint.setColor(COL_GUIDE);
            tickColor = COL_TICK;
            tickCardinalColor = COL_TICK_CARDINAL;
            labelPaint.setColor(NeoStyle.textDim(uiStyle));
            headFillPaint.setColor(NeoStyle.cardColor(uiStyle));
            headStrokePaint.setColor(0xAAFFFFFF);
            orbCorePaint.setColor(AppPrefs.family(uiStyle) == AppPrefs.STYLE_NEW ? 0xFF111317 : 0xFFFFFFFF);
        }
        orbPaint.setColor(accent);
        orbHaloPaint.setColor((accent & 0x00FFFFFF) | 0x44000000);
        vectorPaint.setColor((accent & 0x00FFFFFF) | 0x88000000);
        wavePaint.setColor((pulse & 0x00FFFFFF) | 0x66000000);
        invalidate();
    }

    public void setOnAzimuthChangeListener(OnAzimuthChangeListener listener) {
        this.listener = listener;
    }

    public float getAzimuthDeg() {
        return azimuthDeg;
    }

    public void setAzimuthDeg(float deg) {
        setAzimuthInternal(deg, false);
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            invalidate();
        }
    }

    private void setAzimuthInternal(float deg, boolean fromUser) {
        if (!Float.isFinite(deg)) {
            return;
        }
        float norm = deg % 360.0f;
        if (norm < 0.0f) {
            norm += 360.0f;
        }
        if (Math.abs(norm - azimuthDeg) < 0.01f && !fromUser) {
            return;
        }
        azimuthDeg = norm;
        int bucket = Math.round(norm / 15.0f) % 24;
        if (fromUser && bucket != lastDetentBucket) {
            lastDetentBucket = bucket;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } else if (!fromUser) {
            lastDetentBucket = bucket;
        }
        invalidate();
        if (listener != null) {
            listener.onAzimuthChanged(azimuthDeg, fromUser);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = Math.round(164.0f * density);
        int w = resolveSize(desired, widthMeasureSpec);
        int h = resolveSize(desired, heightMeasureSpec);
        int size = Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = true;
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            updateFromTouch(event.getX(), event.getY());
            return true;
        } else if (action == MotionEvent.ACTION_MOVE && dragging) {
            updateFromTouch(event.getX(), event.getY());
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float x, float y) {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float dx = x - cx;
        float dy = y - cy;
        if (dx * dx + dy * dy < (6.0f * density) * (6.0f * density)) {
            return;
        }
        // 0° at top (12 o'clock), increasing clockwise: 90° right, 180° bottom, 270° left
        double rad = Math.atan2(dx, -dy);
        float deg = (float) Math.toDegrees(rad);
        if (deg < 0.0f) {
            deg += 360.0f;
        }
        setAzimuthInternal(deg, true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float minDim = Math.min(w, h);
        float orbRadius = Math.max(8.0f * density, minDim * 0.056f);
        float ringRadius = minDim * 0.5f - orbRadius - 12.0f * density;
        if (ringRadius < 24.0f * density) {
            ringRadius = minDim * 0.36f;
        }

        // Subtle inner guide circle
        canvas.drawCircle(cx, cy, ringRadius * 0.56f, guidePaint);
        // Main 360° orbit track
        canvas.drawCircle(cx, cy, ringRadius, ringPaint);

        // 24 graduated 15° tick marks
        for (int i = 0; i < 24; i++) {
            double rad = Math.toRadians(i * 15.0);
            boolean cardinal = (i % 6 == 0);
            float sin = (float) Math.sin(rad);
            float cos = (float) Math.cos(rad);
            float inner = ringRadius - (cardinal ? 6.5f : 3.5f) * density;
            float outer = ringRadius + (cardinal ? 2.5f : 1.0f) * density;
            tickPaint.setColor(cardinal ? tickCardinalColor : tickColor);
            tickPaint.setStrokeWidth((cardinal ? 1.8f : 1.0f) * density);
            canvas.drawLine(cx + sin * inner, cy - cos * inner, cx + sin * outer, cy - cos * outer, tickPaint);
        }

        // Cardinal labels (0°, R, 180°, L) positioned just outside the ring
        float labelR = ringRadius + 11.0f * density;
        float textHalf = (labelPaint.descent() + labelPaint.ascent()) * 0.5f;
        canvas.drawText("0°", cx, cy - labelR - textHalf, labelPaint);
        canvas.drawText("R", cx + labelR, cy - textHalf, labelPaint);
        canvas.drawText("180°", cx, cy + labelR - textHalf, labelPaint);
        canvas.drawText("L", cx - labelR, cy - textHalf, labelPaint);

        // Sound source coordinates
        double azRad = Math.toRadians(azimuthDeg);
        float ox = cx + (float) Math.sin(azRad) * ringRadius;
        float oy = cy - (float) Math.cos(azRad) * ringRadius;

        // Radial acoustic line from head center to source orb
        canvas.drawLine(cx, cy, ox, oy, vectorPaint);

        // Centered head icon (ears + cranium + front-facing nose indicator)
        float headR = ringRadius * 0.25f;
        float earW = headR * 0.26f;
        float earH = headR * 0.52f;
        // Left & right ear ovals
        earRect.set(cx - headR - earW * 0.7f, cy - earH * 0.5f, cx - headR + earW * 0.4f, cy + earH * 0.5f);
        canvas.drawOval(earRect, headFillPaint);
        canvas.drawOval(earRect, headStrokePaint);
        earRect.set(cx + headR - earW * 0.4f, cy - earH * 0.5f, cx + headR + earW * 0.7f, cy + earH * 0.5f);
        canvas.drawOval(earRect, headFillPaint);
        canvas.drawOval(earRect, headStrokePaint);

        // Nose triangle pointing toward 0° (front)
        nosePath.reset();
        nosePath.moveTo(cx, cy - headR - headR * 0.36f);
        nosePath.lineTo(cx - headR * 0.28f, cy - headR * 0.82f);
        nosePath.lineTo(cx + headR * 0.28f, cy - headR * 0.82f);
        nosePath.close();
        canvas.drawPath(nosePath, headFillPaint);
        canvas.drawPath(nosePath, headStrokePaint);

        // Main head circle
        canvas.drawCircle(cx, cy, headR, headFillPaint);
        canvas.drawCircle(cx, cy, headR, headStrokePaint);

        // Animated soundwave pulse ring around orb when actively playing
        if (playing) {
            long t = SystemClock.uptimeMillis() % 1100L;
            float phase = t / 1100.0f;
            float pulseR = orbRadius + phase * 14.0f * density;
            int alpha = Math.round((1.0f - phase) * 140.0f);
            wavePaint.setAlpha(Math.max(0, Math.min(255, alpha)));
            canvas.drawCircle(ox, oy, pulseR, wavePaint);
            postInvalidateOnAnimation();
        }

        // Movable sound-source orb
        float haloR = orbRadius * (dragging ? 1.85f : 1.55f);
        canvas.drawCircle(ox, oy, haloR, orbHaloPaint);
        canvas.drawCircle(ox, oy, orbRadius, orbPaint);
        canvas.drawCircle(ox, oy, orbRadius * 0.42f, orbCorePaint);
    }
}
