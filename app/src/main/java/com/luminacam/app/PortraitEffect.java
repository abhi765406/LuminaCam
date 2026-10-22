package com.luminacam.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.media.FaceDetector;

/**
 * Simulated portrait mode. This phone has no depth sensor, so the subject is found with
 * Android's built-in face detector and a soft head-and-shoulders mask is built around each
 * face; everything outside it is blurred. It looks convincing on a single person against a
 * plain background, and less so on busy scenes - it is a simulation, not true depth.
 */
public final class PortraitEffect {

    private PortraitEffect() {}

    /** Returns true if a face was found and the blur was applied in place. */
    public static boolean apply(Bitmap src) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        int dw = Math.min(480, w) & ~1;
        int dh = Math.max(2, (int) ((long) h * dw / w));

        Bitmap det = Bitmap.createScaledBitmap(src, dw, dh, true);
        Bitmap rgb = det.copy(Bitmap.Config.RGB_565, true);
        if (det != src) det.recycle();
        if (rgb == null) return false;

        FaceDetector.Face[] faces = new FaceDetector.Face[5];
        int found;
        try {
            found = new FaceDetector(dw, dh, 5).findFaces(rgb, faces);
        } catch (Throwable t) {
            Diag.log("FaceDetector failed", t);
            found = 0;
        }
        rgb.recycle();
        if (found <= 0) return false;

        Bitmap mask = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mask);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        for (int i = 0; i < found; i++) {
            FaceDetector.Face f = faces[i];
            if (f == null) continue;
            PointF mid = new PointF();
            f.getMidPoint(mid);
            float e = f.eyesDistance();
            c.drawOval(new RectF(mid.x - 1.8f * e, mid.y - 2.1f * e, mid.x + 1.8f * e, mid.y + 2.3f * e), p);
            c.drawRoundRect(new RectF(mid.x - 3.3f * e, mid.y + 1.6f * e, mid.x + 3.3f * e, dh + e * 4f), e, e, p);
        }
        Bitmap soft = Bitmap.createScaledBitmap(mask, Math.max(2, dw / 10), Math.max(2, dh / 10), true);
        mask.recycle();
        Bitmap maskFull = Bitmap.createScaledBitmap(soft, w, h, true);
        soft.recycle();

        Bitmap s1 = Bitmap.createScaledBitmap(src, Math.max(1, w / 10), Math.max(1, h / 10), true);
        Bitmap s2 = Bitmap.createScaledBitmap(s1, Math.max(1, w / 22), Math.max(1, h / 22), true);
        Bitmap bg = Bitmap.createScaledBitmap(s2, w, h, true);
        s1.recycle();
        s2.recycle();

        int bandH = Math.max(1, 600000 / w);
        int[] a = new int[w * bandH];
        int[] b = new int[w * bandH];
        int[] m = new int[w * bandH];
        for (int y = 0; y < h; y += bandH) {
            int rows = Math.min(bandH, h - y);
            src.getPixels(a, 0, w, 0, y, w, rows);
            bg.getPixels(b, 0, w, 0, y, w, rows);
            maskFull.getPixels(m, 0, w, 0, y, w, rows);
            int n = w * rows;
            for (int i = 0; i < n; i++) {
                int al = m[i] >>> 24;
                if (al >= 250) continue;
                int pa = a[i];
                int pb = b[i];
                int inv = 255 - al;
                int r = (((pa >> 16) & 255) * al + ((pb >> 16) & 255) * inv) / 255;
                int g = (((pa >> 8) & 255) * al + ((pb >> 8) & 255) * inv) / 255;
                int bl = ((pa & 255) * al + (pb & 255) * inv) / 255;
                a[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            src.setPixels(a, 0, w, 0, y, w, rows);
        }
        bg.recycle();
        maskFull.recycle();
        return true;
    }
}
