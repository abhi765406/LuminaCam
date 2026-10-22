package com.luminacam.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.util.Random;

/** Pixel-level building blocks. Everything works in horizontal bands so a 13MP photo
 *  never needs a second full-size int[] in memory (important on a 2GB phone). */
public final class ImagePipeline {

    private ImagePipeline() {}

    public interface BandOp {
        void apply(int[] px, int w, int rows, int y0);
    }

    public static void forEachBand(Bitmap b, BandOp op) {
        int w = b.getWidth();
        int h = b.getHeight();
        int bandH = Math.max(1, 800000 / w);
        int[] px = new int[w * bandH];
        for (int y = 0; y < h; y += bandH) {
            int rows = Math.min(bandH, h - y);
            b.getPixels(px, 0, w, 0, y, w, rows);
            op.apply(px, w, rows, y);
            b.setPixels(px, 0, w, 0, y, w, rows);
        }
    }

    public static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------- decode / orient / crop ----------

    public static Bitmap decode(byte[] jpeg, long maxPixels) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int sample = 1;
        if (maxPixels > 0) {
            while ((long) (o.outWidth / sample) * (o.outHeight / sample) > maxPixels) sample *= 2;
        }
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inMutable = true;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, d);
    }

    /** Rotation the JPEG's EXIF asks for. If the camera already rotated the pixels, this is 0. */
    public static int exifRotation(byte[] jpeg) {
        try {
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(jpeg));
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    public static Bitmap ensureMutable(Bitmap b) {
        if (b.isMutable()) return b;
        Bitmap c = b.copy(Bitmap.Config.ARGB_8888, true);
        b.recycle();
        return c;
    }

    public static Bitmap rotateFlip(Bitmap src, int deg, boolean flipH) {
        if (deg == 0 && !flipH) return src;
        Matrix m = new Matrix();
        if (deg != 0) m.postRotate(deg);
        if (flipH) m.postScale(-1f, 1f);
        Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (r != src) src.recycle();
        return ensureMutable(r);
    }

    /** Centre-crops to a "long side : short side" ratio, respecting the image's own orientation. */
    public static Bitmap cropToRatio(Bitmap b, double longOverShort) {
        int w = b.getWidth();
        int h = b.getHeight();
        double target = (w <= h) ? 1.0 / longOverShort : longOverShort;
        double cur = (double) w / h;
        if (Math.abs(cur - target) < 0.01) return b;
        int nw = w;
        int nh = h;
        if (cur > target) nw = (int) Math.round(h * target);
        else nh = (int) Math.round(w / target);
        nw &= ~1;
        nh &= ~1;
        Bitmap c = Bitmap.createBitmap(b, (w - nw) / 2, (h - nh) / 2, nw, nh);
        if (c != b) b.recycle();
        return ensureMutable(c);
    }

    // ---------- colour grading ----------

    public static void grade(Bitmap bmp, final Looks.Spec s) {
        final float sat = s.mono ? 0f : s.sat;
        final int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float v = i / 255f;
            float sm = v * v * (3f - 2f * v);
            float o = v * (1f - s.curve) + sm * s.curve;
            o = o * (1f - s.fade / 255f) + s.fade / 255f;
            lut[i] = clamp((int) (o * 255f + 0.5f));
        }
        final boolean tone = s.hasTone();
        forEachBand(bmp, (px, w, rows, y0) -> {
            int n = w * rows;
            for (int i = 0; i < n; i++) {
                int p = px[i];
                float r = (p >> 16) & 255;
                float g = (p >> 8) & 255;
                float b = p & 255;
                float l = 0.299f * r + 0.587f * g + 0.114f * b;
                if (s.mono) {
                    r = l; g = l; b = l;
                } else {
                    r = l + (r - l) * sat;
                    g = l + (g - l) * sat;
                    b = l + (b - l) * sat;
                }
                r *= s.rGain; g *= s.gGain; b *= s.bGain;
                r = (r - 128f) * s.contrast + 128f + s.bright;
                g = (g - 128f) * s.contrast + 128f + s.bright;
                b = (b - 128f) * s.contrast + 128f + s.bright;
                int ri = lut[clamp((int) r)];
                int gi = lut[clamp((int) g)];
                int bi = lut[clamp((int) b)];
                if (tone) {
                    float l2 = (0.299f * ri + 0.587f * gi + 0.114f * bi) / 255f;
                    float sw = (1f - l2) * (1f - l2);
                    float hw = l2 * l2;
                    ri = clamp((int) (ri + s.shR * sw + s.hiR * hw));
                    gi = clamp((int) (gi + s.shG * sw + s.hiG * hw));
                    bi = clamp((int) (bi + s.shB * sw + s.hiB * hw));
                }
                px[i] = (p & 0xFF000000) | (ri << 16) | (gi << 8) | bi;
            }
        });
    }

    /** Unsharp mask. div controls the blur radius: 2 = fine sharpening, 12-16 = local contrast ("clarity"). */
    public static void unsharp(Bitmap bmp, float amount, int div) {
        if (amount <= 0.01f) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        Bitmap small = Bitmap.createScaledBitmap(bmp, Math.max(1, w / div), Math.max(1, h / div), true);
        Bitmap blur = Bitmap.createScaledBitmap(small, w, h, true);
        if (small != bmp && small != blur) small.recycle();
        int bandH = Math.max(1, 800000 / w);
        int[] a = new int[w * bandH];
        int[] c = new int[w * bandH];
        for (int y = 0; y < h; y += bandH) {
            int rows = Math.min(bandH, h - y);
            bmp.getPixels(a, 0, w, 0, y, w, rows);
            blur.getPixels(c, 0, w, 0, y, w, rows);
            int n = w * rows;
            for (int i = 0; i < n; i++) {
                int p = a[i];
                int q = c[i];
                int r = (p >> 16) & 255;
                int g = (p >> 8) & 255;
                int b = p & 255;
                r = clamp(r + (int) (amount * (r - ((q >> 16) & 255))));
                g = clamp(g + (int) (amount * (g - ((q >> 8) & 255))));
                b = clamp(b + (int) (amount * (b - (q & 255))));
                a[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(a, 0, w, 0, y, w, rows);
        }
        blur.recycle();
    }

    /** Lifts shadows and gently compresses highlights based on a heavily blurred copy (a cheap local tone map). */
    public static void localToneMap(Bitmap bmp, final float shadowBoost, final float highlightCompress) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        Bitmap small = Bitmap.createScaledBitmap(bmp, Math.max(1, w / 24), Math.max(1, h / 24), true);
        Bitmap blur = Bitmap.createScaledBitmap(small, w, h, true);
        if (small != bmp && small != blur) small.recycle();
        int bandH = Math.max(1, 800000 / w);
        int[] a = new int[w * bandH];
        int[] c = new int[w * bandH];
        for (int y = 0; y < h; y += bandH) {
            int rows = Math.min(bandH, h - y);
            bmp.getPixels(a, 0, w, 0, y, w, rows);
            blur.getPixels(c, 0, w, 0, y, w, rows);
            int n = w * rows;
            for (int i = 0; i < n; i++) {
                int p = a[i];
                int q = c[i];
                float lb = (0.299f * ((q >> 16) & 255) + 0.587f * ((q >> 8) & 255) + 0.114f * (q & 255)) / 255f;
                float gain;
                if (lb < 0.5f) gain = 1f + (0.5f - lb) * 2f * shadowBoost;
                else gain = 1f - (lb - 0.5f) * 2f * highlightCompress;
                int r = clamp((int) (((p >> 16) & 255) * gain));
                int g = clamp((int) (((p >> 8) & 255) * gain));
                int b = clamp((int) ((p & 255) * gain));
                a[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(a, 0, w, 0, y, w, rows);
        }
        blur.recycle();
    }

    /** Gamma lift - brightens shadows/mid-tones (used by Night mode after stacking). */
    public static void gamma(Bitmap bmp, float g) {
        if (Math.abs(g - 1f) < 0.01f) return;
        final int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = clamp((int) (255f * Math.pow(i / 255f, 1f / g) + 0.5f));
        }
        forEachBand(bmp, (px, w, rows, y0) -> {
            int n = w * rows;
            for (int i = 0; i < n; i++) {
                int p = px[i];
                px[i] = (p & 0xFF000000) | (lut[(p >> 16) & 255] << 16) | (lut[(p >> 8) & 255] << 8) | lut[p & 255];
            }
        });
    }

    public static void vignette(Bitmap bmp, float strength) {
        if (strength <= 0f) return;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        Canvas c = new Canvas(bmp);
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = (float) Math.hypot(cx, cy);
        int edge = ((int) (strength * 200f)) << 24;
        RadialGradient g = new RadialGradient(cx, cy, radius,
                new int[]{0x00000000, 0x00000000, edge},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        Paint p = new Paint();
        p.setShader(g);
        c.drawRect(0, 0, w, h, p);
    }

    private static Bitmap grainTile;

    public static void grain(Bitmap bmp, float strength) {
        if (strength <= 0f) return;
        if (grainTile == null) {
            int n = 256;
            int[] px = new int[n * n];
            Random rnd = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = clamp(128 + (int) (rnd.nextGaussian() * 40));
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            grainTile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        BitmapShader shader = new BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix m = new Matrix();
        float scale = Math.max(1f, Math.max(bmp.getWidth(), bmp.getHeight()) / 1800f);
        m.setScale(scale, scale);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        p.setAlpha((int) (strength * 255f));
        c.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), p);
    }

    public static void stamp(Bitmap bmp, String text) {
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float size = Math.max(bmp.getWidth(), bmp.getHeight()) / 45f;
        p.setTextSize(size);
        p.setColor(0xFFFFB347);
        p.setShadowLayer(size / 10f, 0, 0, 0xFF000000);
        float x = bmp.getWidth() - p.measureText(text) - size;
        float y = bmp.getHeight() - size;
        c.drawText(text, x, y, p);
    }

    public static Bitmap thumbnail(Bitmap src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        float s = (float) size / Math.min(w, h);
        int tw = Math.max(1, Math.round(w * s));
        int th = Math.max(1, Math.round(h * s));
        return Bitmap.createScaledBitmap(src, tw, th, true);
    }
}
