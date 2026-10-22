package com.luminacam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class ShutterButton extends View {

    public static final int STYLE_PHOTO = 0;
    public static final int STYLE_VIDEO = 1;

    private int style = STYLE_PHOTO;
    private boolean recording = false;
    private boolean busy = false;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ShutterButton(Context c) {
        this(c, null);
    }

    public ShutterButton(Context c, AttributeSet a) {
        super(c, a);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(getResources().getDisplayMetrics().density * 4f);
        fill.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setStyle(int style) {
        this.style = style;
        invalidate();
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
        invalidate();
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        setEnabled(!busy);
        invalidate();
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        float s = pressed ? 0.92f : 1f;
        setScaleX(s);
        setScaleY(s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - ring.getStrokeWidth();
        ring.setAlpha(busy ? 110 : 255);
        canvas.drawCircle(cx, cy, r, ring);

        if (style == STYLE_PHOTO) {
            fill.setColor(busy ? 0x88FFFFFF : 0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r * 0.80f, fill);
        } else if (recording) {
            fill.setColor(0xFFFF3B30);
            float half = r * 0.42f;
            rect.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(rect, half * 0.3f, half * 0.3f, fill);
        } else {
            fill.setColor(0xFFFF3B30);
            canvas.drawCircle(cx, cy, r * 0.78f, fill);
        }
    }
}
