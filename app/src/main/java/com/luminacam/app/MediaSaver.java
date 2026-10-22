package com.luminacam.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves photos/videos into DCIM/LuminaCam so they show up in the normal Gallery, on any Android version. */
public final class MediaSaver {

    private MediaSaver() {}

    public static final String FOLDER = "LuminaCam";

    public static Uri savePhoto(Context ctx, Bitmap bmp, int quality, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("Could not open output stream");
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, os);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        File dir = legacyDir();
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos);
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.DATA, f.getAbsolutePath());
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        return cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
    }

    public static Uri saveVideo(Context ctx, File src, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                if (os == null) throw new IOException("Could not open output stream");
                copy(in, os);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            return uri;
        }
        File f = new File(legacyDir(), name);
        try (FileOutputStream os = new FileOutputStream(f); InputStream in = new FileInputStream(src)) {
            copy(in, os);
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.DATA, f.getAbsolutePath());
        v.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        return cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
    }

    private static File legacyDir() throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        return dir;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
