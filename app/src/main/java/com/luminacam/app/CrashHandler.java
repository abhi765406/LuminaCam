package com.luminacam.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class CrashHandler {
    private CrashHandler() {}

    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String text = "Thread: " + t.getName() + "\n" + sw + "\n--- recent log ---\n" + Diag.dump();
                FileOutputStream fos = new FileOutputStream(new File(app.getFilesDir(), "crash.txt"));
                fos.write(text.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Returns the saved crash report from the previous run, or null if there wasn't one. */
    public static String pending(Context ctx) {
        File f = new File(ctx.getFilesDir(), "crash.txt");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), "crash.txt").delete();
    }
}
