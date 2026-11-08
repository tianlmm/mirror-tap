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
    public static final String KEY_DIRECTION = "direction";
    public static final String KEY_DRAG_MODE = "drag_mode";
    public static final String KEY_TAP_ORIGIN = "tap_origin";
    public static final String KEY_MAP_MODE = "map_mode";

    /** 对称镜像：10% ↔ 90% */
    public static final String MAP_MIRROR = "mirror";
    /** 同样相对位置：10% → 那一半的 10% */
    public static final String MAP_OFFSET = "offset";

    /** 上半屏点、下半屏镜像（默认） */
    public static final String DIR_DOWN = "down";
    /** 下半屏点、上半屏镜像 */
    public static final String DIR_UP = "up";

    /** 滑动：直接还给原 App（能正常滚动，默认） */
    public static final String DRAG_PASS = "pass";
    /** 滑动：镜像到另一半 */
    public static final String DRAG_MIRROR = "mirror";
    /** 滑动：忽略 */
    public static final String DRAG_IGNORE = "ignore";

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

    public static String direction(Context c) {
        return sp(c).getString(KEY_DIRECTION, DIR_DOWN);
    }

    public static String dragMode(Context c) {
        String v = sp(c).getString(KEY_DRAG_MODE, DRAG_PASS);
        if (!DRAG_PASS.equals(v) && !DRAG_MIRROR.equals(v) && !DRAG_IGNORE.equals(v)) {
            return DRAG_PASS;
        }
        return v;
    }

    public static String mapMode(Context c) {
        return MAP_OFFSET.equals(sp(c).getString(KEY_MAP_MODE, MAP_MIRROR)) ? MAP_OFFSET : MAP_MIRROR;
    }

    public static boolean isDownDirection(Context c) {
        return !DIR_UP.equals(direction(c));
    }

    public static void putString(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    /** 点按时是否也点一下原位置（默认只镜像）。 */
    public static boolean tapOrigin(Context c) {
        return sp(c).getBoolean(KEY_TAP_ORIGIN, false);
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
