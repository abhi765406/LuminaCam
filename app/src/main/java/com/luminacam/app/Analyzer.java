package com.luminacam.app;

import android.graphics.Bitmap;

/** Looks at a small copy of the viewfinder to produce the histogram, zebra (blown highlights)
 *  and focus-peaking overlays used by Pro mode. */
public final class Analyzer {

    public static final int BINS = 64;

    public static final class Result {
        public final int[] hist = new int[BINS];
        public Bitmap overlay;
    }

    private Analyzer() {}

    public static Result analyze(Bitmap small, boolean zebra, boolean peaking) {
        Result res = new Result();
        int w = small.getWidth();
        int h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        int[] luma = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int l = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
            luma[i] = l;
            res.hist[Math.min(BINS - 1, l * BINS / 256)]++;
        }
        if (zebra || peaking) {
            int[] ov = new int[w * h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    if (zebra && luma[i] >= 238 && ((x + y) & 7) < 4) {
                        ov[i] = 0xCCFF3B30;
                        continue;
                    }
                    if (peaking) {
                        int gx = luma[i + 1] - luma[i - 1];
                        int gy = luma[i + w] - luma[i - w];
                        if (Math.abs(gx) + Math.abs(gy) > 70) ov[i] = 0xDD34E36A;
                    }
                }
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(ov, 0, w, 0, 0, w, h);
            res.overlay = b;
        }
        return res;
    }
}
