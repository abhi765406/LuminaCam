package com.luminacam.app;

import android.graphics.Bitmap;

import java.util.List;

/**
 * Night mode: merges several frames of the same scene to cut sensor noise (noise falls
 * roughly with the square root of the number of frames). Frames are aligned first, and
 * pixels that differ too much from the reference (something moved) are down-weighted so
 * people or leaves don't turn into ghosts.
 */
public final class NightStack {

    private NightStack() {}

    public static Bitmap stack(List<byte[]> jpegs) {
        Bitmap ref = ImagePipeline.decode(jpegs.get(0), 0);
        if (ref == null) return null;
        if (jpegs.size() == 1) return ref;

        final int w = ref.getWidth();
        final int h = ref.getHeight();
        int total = w * h;
        int[] refPx = new int[total];
        ref.getPixels(refPx, 0, w, 0, 0, w, h);

        int[] sum = new int[total * 3];
        byte[] wsum = new byte[total];
        for (int i = 0; i < total; i++) {
            int p = refPx[i];
            sum[i * 3] = ((p >> 16) & 255) * 2;
            sum[i * 3 + 1] = ((p >> 8) & 255) * 2;
            sum[i * 3 + 2] = (p & 255) * 2;
            wsum[i] = 2;
        }

        int[] cur = new int[total];
        for (int k = 1; k < jpegs.size(); k++) {
            Bitmap f = ImagePipeline.decode(jpegs.get(k), 0);
            if (f == null || f.getWidth() != w || f.getHeight() != h) {
                if (f != null) f.recycle();
                continue;
            }
            int[] shift = Aligner.estimate(ref, f);
            int dx = shift[0];
            int dy = shift[1];
            f.getPixels(cur, 0, w, 0, 0, w, h);
            f.recycle();

            for (int y = 0; y < h; y++) {
                int sy = y + dy;
                if (sy < 0 || sy >= h) continue;
                for (int x = 0; x < w; x++) {
                    int sx = x + dx;
                    if (sx < 0 || sx >= w) continue;
                    int i = y * w + x;
                    int c = cur[sy * w + sx];
                    int r0 = refPx[i];
                    int cr = (c >> 16) & 255;
                    int cg = (c >> 8) & 255;
                    int cb = c & 255;
                    int diff = Math.abs(cr - ((r0 >> 16) & 255))
                            + Math.abs(cg - ((r0 >> 8) & 255))
                            + Math.abs(cb - (r0 & 255));
                    int wt = diff < 48 ? 2 : (diff < 96 ? 1 : 0);
                    if (wt == 0) continue;
                    sum[i * 3] += cr * wt;
                    sum[i * 3 + 1] += cg * wt;
                    sum[i * 3 + 2] += cb * wt;
                    wsum[i] += wt;
                }
            }
        }

        for (int i = 0; i < total; i++) {
            int wt = wsum[i];
            if (wt <= 0) wt = 1;
            int r = ImagePipeline.clamp(sum[i * 3] / wt);
            int g = ImagePipeline.clamp(sum[i * 3 + 1] / wt);
            int b = ImagePipeline.clamp(sum[i * 3 + 2] / wt);
            refPx[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        ref.setPixels(refPx, 0, w, 0, 0, w, h);

        // Stacked frames are cleaner, so we can afford to lift the shadows without amplifying noise.
        ImagePipeline.gamma(ref, 1.28f);
        ImagePipeline.localToneMap(ref, 0.35f, 0.15f);
        return ref;
    }
}
