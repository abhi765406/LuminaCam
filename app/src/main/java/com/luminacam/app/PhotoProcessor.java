package com.luminacam.app;

import android.graphics.Bitmap;

import java.util.List;

/** Turns the raw captured JPEG(s) into the finished photo for whichever mode was used. */
public final class PhotoProcessor {

    private PhotoProcessor() {}

    public static final class Job {
        public int mode;
        public int look;
        public double aspect;       // long side : short side
        public boolean stamp;
        public boolean mirrorSave;
        public boolean front;
    }

    public static final class Output {
        public Bitmap bitmap;
        public String note;
    }

    public static Output process(List<byte[]> jpegs, Job job) {
        Output out = new Output();
        int rot = ImagePipeline.exifRotation(jpegs.get(0));
        try {
            Bitmap bmp;
            switch (job.mode) {
                case CameraController.MODE_NIGHT:
                    bmp = NightStack.stack(jpegs);
                    break;
                case CameraController.MODE_HDR:
                    bmp = HdrFusion.fuse(jpegs);
                    break;
                default:
                    bmp = ImagePipeline.decode(jpegs.get(0), 0);
                    break;
            }
            if (bmp == null) return null;
            bmp = ImagePipeline.rotateFlip(bmp, rot, job.front && job.mirrorSave);
            if (job.aspect > 0) bmp = ImagePipeline.cropToRatio(bmp, job.aspect);

            if (job.mode == CameraController.MODE_PORTRAIT) {
                if (!PortraitEffect.apply(bmp)) out.note = "No face found - saved without background blur";
            }

            Looks.Spec s = Looks.spec(job.look);
            ImagePipeline.grade(bmp, s);
            ImagePipeline.unsharp(bmp, s.clarity, 16);
            ImagePipeline.unsharp(bmp, s.sharpen, 2);
            ImagePipeline.vignette(bmp, s.vignette);
            ImagePipeline.grain(bmp, s.grain);
            if (job.stamp) {
                ImagePipeline.stamp(bmp, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date()));
            }
            out.bitmap = bmp;
            return out;
        } catch (OutOfMemoryError e) {
            Diag.log("OOM while processing - falling back to a lighter path");
            System.gc();
            Bitmap bmp = ImagePipeline.decode(jpegs.get(0), 3_000_000L);
            if (bmp == null) return null;
            bmp = ImagePipeline.rotateFlip(bmp, rot, job.front && job.mirrorSave);
            out.bitmap = bmp;
            out.note = "Low memory - saved at reduced size without effects";
            return out;
        }
    }
}
