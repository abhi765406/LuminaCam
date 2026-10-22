package com.luminacam.app;

import android.graphics.ColorMatrix;

/**
 * Colour looks. "Natural" aims for balanced, true-to-life tones; "Vivid" for punchy,
 * high-saturation, high-sharpness results; the rest are creative grades. The same
 * numbers drive both the live preview (a colour matrix) and the saved photo (a
 * per-pixel pipeline that also does split-toning, vignette and grain).
 */
public final class Looks {
    public static final String[] NAMES = {"Natural", "Vivid", "Cinematic", "Film", "Matte", "Warm", "Cool", "Mono"};

    public static final class Spec {
        public float sat = 1f, contrast = 1f, bright = 0f;
        public float rGain = 1f, gGain = 1f, bGain = 1f;
        public float curve = 0f;   // 0..1 strength of S-curve
        public float fade = 0f;    // 0..255 lifted blacks
        public int shR, shG, shB;  // shadow tint offsets
        public int hiR, hiG, hiB;  // highlight tint offsets
        public float vignette = 0f, grain = 0f;
        public float sharpen = 0f, clarity = 0f;
        public boolean mono = false;

        public boolean hasTone() {
            return (shR | shG | shB | hiR | hiG | hiB) != 0;
        }
    }

    private Looks() {}

    public static Spec spec(int look) {
        Spec s = new Spec();
        switch (look) {
            case 0: // Natural
                s.sat = 1.04f; s.contrast = 1.05f; s.curve = 0.15f;
                s.rGain = 1.01f; s.bGain = 0.99f;
                s.sharpen = 0.35f; s.clarity = 0.15f;
                break;
            case 1: // Vivid
                s.sat = 1.30f; s.contrast = 1.12f; s.curve = 0.30f;
                s.sharpen = 0.60f; s.clarity = 0.30f;
                break;
            case 2: // Cinematic
                s.sat = 0.92f; s.contrast = 1.12f; s.curve = 0.35f; s.fade = 6f;
                s.shR = -8; s.shG = 2; s.shB = 10;
                s.hiR = 10; s.hiG = 3; s.hiB = -10;
                s.vignette = 0.35f; s.grain = 0.12f; s.sharpen = 0.30f;
                break;
            case 3: // Film
                s.sat = 0.88f; s.contrast = 1.05f; s.curve = 0.20f; s.fade = 14f;
                s.rGain = 1.06f; s.bGain = 0.92f;
                s.vignette = 0.20f; s.grain = 0.22f; s.sharpen = 0.20f;
                break;
            case 4: // Matte
                s.sat = 0.85f; s.contrast = 0.95f; s.curve = 0.10f; s.fade = 22f;
                s.sharpen = 0.20f;
                break;
            case 5: // Warm
                s.sat = 1.05f; s.rGain = 1.08f; s.bGain = 0.90f; s.curve = 0.12f;
                s.sharpen = 0.30f;
                break;
            case 6: // Cool
                s.sat = 1.02f; s.rGain = 0.93f; s.bGain = 1.08f; s.curve = 0.12f;
                s.sharpen = 0.30f;
                break;
            default: // Mono
                s.mono = true; s.contrast = 1.20f; s.curve = 0.35f;
                s.grain = 0.10f; s.sharpen = 0.35f;
                break;
        }
        return s;
    }

    /** Colour matrix for the live viewfinder. Approximates the saved look (no vignette/grain/split-toning). */
    public static ColorMatrix previewMatrix(int look) {
        Spec s = spec(look);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(s.mono ? 0f : s.sat);
        float c = s.contrast;
        float t = 128f * (1f - c) + s.bright;
        ColorMatrix contrast = new ColorMatrix(new float[]{
                c * s.rGain, 0, 0, 0, t,
                0, c * s.gGain, 0, 0, t,
                0, 0, c * s.bGain, 0, t,
                0, 0, 0, 1, 0});
        cm.postConcat(contrast);
        if (s.fade > 0f) {
            float k = 1f - s.fade / 255f;
            float f = s.fade;
            ColorMatrix fade = new ColorMatrix(new float[]{
                    k, 0, 0, 0, f,
                    0, k, 0, 0, f,
                    0, 0, k, 0, f,
                    0, 0, 0, 1, 0});
            cm.postConcat(fade);
        }
        return cm;
    }
}
