package com.luminacam.app;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/** Tiny in-memory log so that when something goes wrong on a phone I can't test on, the
 *  exact cause can be copied out of the app's Camera info screen. */
public final class Diag {
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final int MAX = 120;

    private Diag() {}

    public static synchronized void log(String msg) {
        Log.i("LuminaCam", msg);
        if (LINES.size() >= MAX) LINES.removeFirst();
        LINES.addLast(msg);
    }

    public static void log(String msg, Throwable t) {
        log(msg + " :: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }
}
