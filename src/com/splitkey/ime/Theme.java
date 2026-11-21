package com.splitkey.ime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 主题：白天 / 黑夜两套配色 + 三种取用方式。
 *
 *   自动  → 跟随系统（config 的 uiMode 位）
 *   白天  → 固定亮色
 *   黑夜  → 固定暗色
 *
 * 所有界面（键盘、设置页、许可页）都从这里取色，不要在别处写死 ARGB。
 */
public final class Theme {

    public enum Mode {
        AUTO,   // 跟随系统
        LIGHT,  // 固定白天
        DARK;   // 固定黑夜

        public static Mode from(String s) {
            for (Mode m : values()) if (m.name().equals(s)) return m;
            return AUTO;
        }
    }

    /** 一套配色。字段名保持和 KeyboardView 原来的常量语义一致。 */
    public static final class Colors {
        public final int bg;        // 屏幕底色（键缝）
        public final int key;       // 普通字符键
        public final int func;      // 功能键（⌫ ⏎ ⇧ 修饰键）
        public final int down;      // 按下
        public final int text;      // 主文字
        public final int sub;       // 副文字（长按数字提示）
        public final int armed;     // 修饰键 armed（一次性）
        public final int locked;    // 修饰键 locked
        public final int faint;     // 未生效的那一半双字符
        public final int textFaint; // 未生效的主标签
        public final int pad;       // 触摸板板面
        public final int wheel;     // 滚轮条

        Colors(int bg, int key, int func, int down, int text, int sub,
               int armed, int locked, int faint, int textFaint,
               int pad, int wheel) {
            this.bg = bg; this.key = key; this.func = func; this.down = down;
            this.text = text; this.sub = sub; this.armed = armed;
            this.locked = locked; this.faint = faint; this.textFaint = textFaint;
            this.pad = pad; this.wheel = wheel;
        }

        /**
         * 按比例把每个颜色往黑里压（0..1）—— 用于整体亮度 / 闲置变暗。
         *
         * 为什么不是 setAlpha()：降低 View alpha 是「变透明」，会透出桌面，
         * 而且 OLED 上透出的底部内容照样发光。压 RGB 才是真省电、真不刺眼。
         * 文字色一起压：亮底上深字变浅仍可读，暗底上浅字变暗也仍可读，
         * 逐通道乘同一个系数不会把对比翻转。
         */
        public Colors scale(float k) {
            if (k >= 0.999f) return this;
            if (k <= 0f) k = 0f;
            return new Colors(
                    mul(bg, k), mul(key, k), mul(func, k), mul(down, k),
                    mul(text, k), mul(sub, k), mul(armed, k), mul(locked, k),
                    mul(faint, k), mul(textFaint, k), mul(pad, k), mul(wheel, k));
        }

        private static int mul(int argb, float k) {
            int a = (argb >>> 24) & 0xFF;
            int r = (int) (((argb >> 16) & 0xFF) * k);
            int g = (int) (((argb >> 8) & 0xFF) * k);
            int b = (int) ((argb & 0xFF) * k);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    /** 白天（现行配色，保持不变） */
    public static final Colors LIGHT = new Colors(
            0xFFD4D4D4,   // bg
            0xFFFFFFFF,   // key
            0xFFE3E3E3,   // func
            0xFFB8B8B8,   // down
            0xFF1A1A1A,   // text
            0xFF5A5A5A,   // sub
            0xFF7FB3E8,   // armed
            0xFF0F6CBD,   // locked
            0xFFAFAFAF,   // faint
            0xFFB0B0B0,   // textFaint
            0xFFC8C8C8,   // pad
            0xFFE3E3E3    // wheel
    );

    /** 黑夜 */
    public static final Colors DARK = new Colors(
            0xFF1B1B1E,   // bg
            0xFF2C2C31,   // key
            0xFF3A3A40,   // func
            0xFF4A4A52,   // down
            0xFFEDEDED,   // text
            0xFF9A9AA2,   // sub
            0xFF7FB3E8,   // armed（沿用蓝，暗底上仍清晰）
            0xFF2F8FE0,   // locked
            0xFF6A6A72,   // faint
            0xFF7A7A82,   // textFaint
            0xFF232327,   // pad
            0xFF2E2E34    // wheel
    );

    private static final String PREF = "splitkey";
    private static final String KEY_MODE = "theme_mode";

    private Theme() {}

    public static Mode mode(Context c) {
        return Mode.from(prefs(c).getString(KEY_MODE, Mode.AUTO.name()));
    }

    public static void setMode(Context c, Mode m) {
        // 同进程内的界面（键盘悬浮窗等）靠 prefs 监听自己刷新，不需要广播
        prefs(c).edit().putString(KEY_MODE, m.name()).apply();
    }

    /** 监听主题变化。注册者自己持有引用以便注销。 */
    public interface Listener {
        void onThemeChanged();
    }

    private static final java.util.List<Listener> listeners = new java.util.ArrayList<>();
    private static SharedPreferences sp;
    private static SharedPreferences.OnSharedPreferenceChangeListener spListener;

    /**
     * 注册主题监听。键盘是常驻悬浮窗、不在任何 Activity 生命周期里，
     * 用同进程的 prefs 回调让它跟设置页保持一致。
     */
    public static void addListener(Context c, Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                sp = prefs(c);
                spListener = (p, key) -> {
                    if (!KEY_MODE.equals(key)) return;
                    Listener[] arr;
                    synchronized (listeners) { arr = listeners.toArray(new Listener[0]); }
                    for (Listener x : arr) x.onThemeChanged();
                };
                sp.registerOnSharedPreferenceChangeListener(spListener);
            }
            listeners.add(l);
        }
    }

    /**
     * 系统日/夜切换时通知。AUTO 模式下必须靠这个，
     * 因为键盘是悬浮窗、不在 Activity 配置重载范围内。
     * 注册者需同时注册 ComponentCallbacks（uiMode 变化只发给它）。
     */
    public static void notifySystemModeChanged() {
        Listener[] arr;
        synchronized (listeners) { arr = listeners.toArray(new Listener[0]); }
        for (Listener x : arr) x.onThemeChanged();
    }

    public static void removeListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            listeners.remove(l);
            if (listeners.isEmpty() && sp != null && spListener != null) {
                sp.unregisterOnSharedPreferenceChangeListener(spListener);
                spListener = null;
                sp = null;
            }
        }
    }

    /** 当前生效的配色：手动就按手动，自动就看系统 */
    public static Colors colors(Context c) {
        Mode m = mode(c);
        if (m == Mode.LIGHT) return LIGHT;
        if (m == Mode.DARK) return DARK;
        return isSystemDark(c) ? DARK : LIGHT;
    }

    /** 系统是否处于夜间模式 */
    public static boolean isSystemDark(Context c) {
        int night = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
