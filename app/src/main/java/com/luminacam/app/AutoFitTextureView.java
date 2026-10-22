package com.luminacam.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {

    private int ratioW = 0;
    private int ratioH = 0;

    public AutoFitTextureView(Context c) {
        this(c, null);
    }

    public AutoFitTextureView(Context c, AttributeSet a) {
        this(c, a, 0);
    }

    public AutoFitTextureView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    /** Sets the on-screen aspect ratio (width : height) the view should keep. */
    public void setAspectRatio(int w, int h) {
        if (w <= 0 || h <= 0) return;
        ratioW = w;
        ratioH = h;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(width, height);
        } else if (width < height * ratioW / ratioH) {
            setMeasuredDimension(width, width * ratioH / ratioW);
        } else {
            setMeasuredDimension(height * ratioW / ratioH, height);
        }
    }
}
