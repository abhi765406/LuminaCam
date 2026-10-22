package com.luminacam.app;

import android.graphics.Bitmap;

/** Estimates how far a hand-held frame drifted from a reference frame, so frames can be
 *  lined up before merging. Translation only - it covers normal hand shake. */
public final class Aligner {

    private Aligner() {}

    private static final class G {
        int w, h;
        int[] y;
    }

    private static G gray(Bitmap b, int targetW) {
        int w = b.getWidth();
        int h = b.getHeight();
        int tw = Math.min(targetW, w);
        int th = Math.max(1, (int) ((long) h * tw / w));
        Bitmap s = Bitmap.createScaledBitmap(b, tw, th, true);
        int[] px = new int[tw * th];
        s.getPixels(px, 0, tw, 0, 0, tw, th);
        if (s != b) s.recycle();
        int[] y = new int[tw * th];
        double sum = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int l = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
            y[i] = l;
            sum += l;
        }
        double mean = sum / y.length;
        double var = 0;
        for (int v : y) var += (v - mean) * (v - mean);
        double std = Math.sqrt(var / y.length);
        if (std < 1.0) std = 1.0;
        for (int i = 0; i < y.length; i++) {
            y[i] = (int) Math.round((y[i] - mean) * 64.0 / std);
        }
        G g = new G();
        g.w = tw;
        g.h = th;
        g.y = y;
        return g;
    }

    private static int[] search(G ref, G cur, int cx, int cy, int range, int step) {
        int w = ref.w;
        int h = ref.h;
        int margin = Math.max(range + 2, w / 10);
        float best = Float.MAX_VALUE;
        int bx = cx;
        int by = cy;
        for (int dy = cy - range; dy <= cy + range; dy++) {
            for (int dx = cx - range; dx <= cx + range; dx++) {
                long sad = 0;
                int count = 0;
                for (int y = margin; y < h - margin; y += step) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int x = margin; x < w - margin; x += step) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        sad += Math.abs(ref.y[y * w + x] - cur.y[yy * w + xx]);
                        count++;
                    }
                }
                if (count > 0) {
                    float v = (float) sad / count;
                    if (v < best) {
                        best = v;
                        bx = dx;
                        by = dy;
                    }
                }
            }
        }
        return new int[]{bx, by};
    }

    /** Returns {dx, dy} in full-resolution pixels such that cur(x+dx, y+dy) lines up with ref(x, y). */
    public static int[] estimate(Bitmap ref, Bitmap cur) {
        try {
            G r1 = gray(ref, 160);
            G c1 = gray(cur, 160);
            int[] s1 = search(r1, c1, 0, 0, 10, 1);
            G r2 = gray(ref, 640);
            G c2 = gray(cur, 640);
            float f = (float) r2.w / r1.w;
            int cx = Math.round(s1[0] * f);
            int cy = Math.round(s1[1] * f);
            int[] s2 = search(r2, c2, cx, cy, 3, 2);
            float scale = (float) ref.getWidth() / r2.w;
            return new int[]{Math.round(s2[0] * scale), Math.round(s2[1] * scale)};
        } catch (Throwable t) {
            Diag.log("Aligner failed", t);
            return new int[]{0, 0};
        }
    }
}
