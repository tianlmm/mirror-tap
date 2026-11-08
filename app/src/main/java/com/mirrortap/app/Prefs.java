package com.mirrortap.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有开关都放在 SharedPreferences 里，服务和界面共用。 */
public final class Prefs {

    public static final String KEY_SPLIT = "split";
    public static final String KEY_DRAG = "mirror_drag";
    public static final String KEY_HAPTIC = "haptic";
    public static final String KEY_TINT = "tint";
    public static final String KEY_GHOST = "ghost";
    public static final String KEY_BOOT = "boot";

    /** 分隔线允许的范围：20% ~ 80% */
    public static final float MIN_SPLIT = 0.20f;
    public static final float MAX_SPLIT = 0.80f;
    public static final float DEFAULT_SPLIT = 0.50f;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("mirrortap", Context.MODE_PRIVATE);
    }

    public static float split(Context c) {
        return clamp(sp(c).getFloat(KEY_SPLIT, DEFAULT_SPLIT));
    }

    public static void setSplit(Context c, float v) {
        sp(c).edit().putFloat(KEY_SPLIT, clamp(v)).apply();
    }

    public static boolean mirrorDrag(Context c) {
        return sp(c).getBoolean(KEY_DRAG, true);
    }

    public static boolean haptic(Context c) {
        return sp(c).getBoolean(KEY_HAPTIC, true);
    }

    public static boolean tint(Context c) {
        return sp(c).getBoolean(KEY_TINT, true);
    }

    public static boolean ghost(Context c) {
        return sp(c).getBoolean(KEY_GHOST, true);
    }

    public static boolean bootStart(Context c) {
        return sp(c).getBoolean(KEY_BOOT, false);
    }

    public static void put(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }

    public static boolean get(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static float clamp(float v) {
        return Math.max(MIN_SPLIT, Math.min(MAX_SPLIT, v));
    }
}
