package com.luminacam.app;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * HDR: blends an under-exposed, a normal and an over-exposed frame, favouring whichever
 * frame is best exposed at each location (exposure fusion). Weight maps are computed at
 * quarter resolution and blurred so the seams between frames are invisible.
 * If the phone can't bracket exposures, a single frame gets a local tone-map instead.
 */
public final class HdrFusion {

    private HdrFusion() {}

    public static Bitmap fuse(List<byte[]> jpegs) {
        if (jpegs.size() < 2) {
            Bitmap one = ImagePipeline.decode(jpegs.get(0), 0);
            if (one != null) ImagePipeline.localToneMap(one, 0.55f, 0.35f);
            return one;
        }

        List<Bitmap> frames = new ArrayList<>();
        for (byte[] j : jpegs) {
            Bitmap b = ImagePipeline.decode(j, 0);
            if (b != null) frames.add(b);
        }
        if (frames.isEmpty()) return null;
        int refIdx = frames.size() / 2;
        Bitmap ref = frames.get(refIdx);
        final int w = ref.getWidth();
        final int h = ref.getHeight();
        int n = frames.size();

        int[][] px = new int[n][];
        int[][] shift = new int[n][];
        for (int k = 0; k < n; k++) {
            Bitmap f = frames.get(k);
            if (f.getWidth() != w || f.getHeight() != h) {
                px[k] = null;
                continue;
            }
            shift[k] = (k == refIdx) ? new int[]{0, 0} : Aligner.estimate(ref, f);
            px[k] = new int[w * h];
            f.getPixels(px[k], 0, w, 0, 0, w, h);
        }
        for (int k = 0; k < n; k++) {
            if (k != refIdx) frames.get(k).recycle();
        }

        final int qw = Math.max(1, w / 4);
        final int qh = Math.max(1, h / 4);
        float[][] weights = new float[n][];
        for (int k = 0; k < n; k++) {
            if (px[k] == null) continue;
            float[] wm = new float[qw * qh];
            for (int qy = 0; qy < qh; qy++) {
                for (int qx = 0; qx < qw; qx++) {
                    int sx = Math.min(w - 1, qx * 4 + 2);
                    int sy = Math.min(h - 1, qy * 4 + 2);
                    int p = px[k][sy * w + sx];
                    float l = (0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255)) / 255f;
                    float d = l - 0.5f;
                    wm[qy * qw + qx] = (float) Math.exp(-(d * d) / (2f * 0.2f * 0.2f)) + 0.0005f;
                }
            }
            boxBlur(wm, qw, qh, 3);
            boxBlur(wm, qw, qh, 3);
            weights[k] = wm;
        }

        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            float fy = Math.min(qh - 1f, y / 4f);
            int y0 = (int) fy;
            int y1 = Math.min(qh - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = Math.min(qw - 1f, x / 4f);
                int x0 = (int) fx;
                int x1 = Math.min(qw - 1, x0 + 1);
                float tx = fx - x0;
                float wr = 0, wg = 0, wb = 0, ws = 0;
                for (int k = 0; k < n; k++) {
                    if (px[k] == null) continue;
                    int sx = x + shift[k][0];
                    int sy = y + shift[k][1];
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) {
                        sx = x;
                        sy = y;
                    }
                    int p = px[k][sy * w + sx];
                    float[] wm = weights[k];
                    float a = wm[y0 * qw + x0] * (1 - tx) + wm[y0 * qw + x1] * tx;
                    float b = wm[y1 * qw + x0] * (1 - tx) + wm[y1 * qw + x1] * tx;
                    float wt = a * (1 - ty) + b * ty;
                    wr += wt * ((p >> 16) & 255);
                    wg += wt * ((p >> 8) & 255);
                    wb += wt * (p & 255);
                    ws += wt;
                }
                int r = ImagePipeline.clamp((int) (wr / ws));
                int g = ImagePipeline.clamp((int) (wg / ws));
                int b = ImagePipeline.clamp((int) (wb / ws));
                out[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        ref.setPixels(out, 0, w, 0, 0, w, h);
        ImagePipeline.localToneMap(ref, 0.20f, 0.10f);
        return ref;
    }

    private static void boxBlur(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int c = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    s += a[y * w + xx];
                    c++;
                }
                tmp[y * w + x] = s / c;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int c = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    s += tmp[yy * w + x];
                    c++;
                }
                a[y * w + x] = s / c;
            }
        }
    }
}
