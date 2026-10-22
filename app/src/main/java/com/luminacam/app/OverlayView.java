package com.luminacam.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Everything drawn on top of the viewfinder: grid, level, focus ring, crop guide,
 *  histogram, zebra and focus-peaking. */
public class OverlayView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint();
    private final RectF previewRect = new RectF();
    private final RectF tmp = new RectF();
    private final float dp;

    private int gridType = 0; // 0 off, 1 thirds, 2 thirds + level
    private float roll = 0f;
    private boolean levelValid = true;
    private float focusX = -1f;
    private float focusY = -1f;
    private long focusTime = 0L;
    private int[] hist;
    private Bitmap analysis;
    private float cropTarget = 0f; // desired on-screen width/height of the saved image, 0 = none

    public OverlayView(Context c) {
        this(c, null);
    }

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp * 1f);
        line.setColor(0x88FFFFFF);
        dim.setColor(0x99000000);
        bar.setColor(0xAAFFFFFF);
    }

    public void setPreviewRect(float l, float t, float r, float b) {
        previewRect.set(l, t, r, b);
        invalidate();
    }

    public void setGridType(int type) {
        gridType = type;
        invalidate();
    }

    public void setRoll(float degrees, boolean valid) {
        roll = degrees;
        levelValid = valid;
        if (gridType == 2) invalidate();
    }

    public void showFocus(float x, float y) {
        focusX = x;
        focusY = y;
        focusTime = System.currentTimeMillis();
        invalidate();
    }

    public void setHistogram(int[] h) {
        hist = h;
        invalidate();
    }

    public void setAnalysis(Bitmap b) {
        analysis = b;
        invalidate();
    }

    public void setCropTarget(float widthOverHeight) {
        cropTarget = widthOverHeight;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (previewRect.width() <= 0) return;
        float l = previewRect.left;
        float t = previewRect.top;
        float r = previewRect.right;
        float b = previewRect.bottom;

        if (analysis != null) canvas.drawBitmap(analysis, null, previewRect, bmpPaint);

        if (cropTarget > 0f) {
            float srcRatio = previewRect.width() / previewRect.height();
            if (Math.abs(cropTarget - srcRatio) > 0.01f) {
                if (cropTarget < srcRatio) {
                    float cw = previewRect.height() * cropTarget;
                    float cl = l + (previewRect.width() - cw) / 2f;
                    canvas.drawRect(l, t, cl, b, dim);
                    canvas.drawRect(cl + cw, t, r, b, dim);
                } else {
                    float ch = previewRect.width() / cropTarget;
                    float ct = t + (previewRect.height() - ch) / 2f;
                    canvas.drawRect(l, t, r, ct, dim);
                    canvas.drawRect(l, ct + ch, r, b, dim);
                }
            }
        }

        if (gridType >= 1) {
            float w = r - l;
            float h = b - t;
            canvas.drawLine(l + w / 3f, t, l + w / 3f, b, line);
            canvas.drawLine(l + 2 * w / 3f, t, l + 2 * w / 3f, b, line);
            canvas.drawLine(l, t + h / 3f, r, t + h / 3f, line);
            canvas.drawLine(l, t + 2 * h / 3f, r, t + 2 * h / 3f, line);
        }

        if (gridType == 2 && levelValid) {
            float cx = (l + r) / 2f;
            float cy = (t + b) / 2f;
            float half = dp * 46f;
            boolean level = Math.abs(roll) < 1.2f;
            line.setColor(level ? 0xFF34E36A : 0xFFFFFFFF);
            line.setStrokeWidth(dp * 2f);
            canvas.save();
            canvas.rotate(-roll, cx, cy);
            canvas.drawLine(cx - half, cy, cx + half, cy, line);
            canvas.restore();
            line.setStrokeWidth(dp * 1f);
            line.setColor(0x88FFFFFF);
        }

        if (focusX >= 0f) {
            long age = System.currentTimeMillis() - focusTime;
            if (age < 1600) {
                float a = 1f - age / 1600f;
                line.setColor(0xFFFFC83D);
                line.setAlpha((int) (255 * a));
                float rad = dp * (46f - 10f * Math.min(1f, age / 300f));
                canvas.drawCircle(focusX, focusY, rad, line);
                line.setColor(0x88FFFFFF);
                line.setAlpha(0x88);
                postInvalidateDelayed(40);
            }
        }

        if (hist != null) {
            int max = 1;
            for (int v : hist) if (v > max) max = v;
            float hw = dp * 120f;
            float hh = dp * 56f;
            float x0 = l + dp * 10f;
            float y1 = b - dp * 10f;
            tmp.set(x0 - dp * 4f, y1 - hh - dp * 4f, x0 + hw + dp * 4f, y1 + dp * 4f);
            canvas.drawRect(tmp, dim);
            float bw = hw / hist.length;
            for (int i = 0; i < hist.length; i++) {
                float bh = hh * (float) Math.sqrt((double) hist[i] / max);
                canvas.drawRect(x0 + i * bw, y1 - bh, x0 + (i + 1) * bw - 1f, y1, bar);
            }
        }
    }
}
