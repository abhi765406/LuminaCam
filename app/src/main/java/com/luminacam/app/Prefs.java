package com.luminacam.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String LOOK = "look";
    public static final String ASPECT = "aspect";
    public static final String GRID = "grid";
    public static final String QUALITY = "quality";
    public static final String FLASH = "flash";
    public static final String TIMER = "timer";
    public static final String VOLKEY = "volkey";
    public static final String SOUND = "sound";
    public static final String STAMP = "stamp";
    public static final String MIRROR_SAVE = "mirror_save";
    public static final String HIST = "hist";
    public static final String ZEBRA = "zebra";
    public static final String PEAK = "peak";
    public static final String VRES = "vres";
    public static final String VFPS = "vfps";
    public static final String VQUALITY = "vquality";
    public static final String VSTAB = "vstab";
    public static final String VAUDIO = "vaudio";
    public static final String VCINE = "vcine";
    public static final String TL_INT = "tl_int";
    public static final String MODE = "mode";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("lumina_prefs", Context.MODE_PRIVATE);
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void putInt(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void putBool(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }
}
