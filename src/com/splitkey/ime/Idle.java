package com.splitkey.ime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 亮度与闲置保护。
 *
 * 两件事：
 *   1) 键盘整体亮度（无极，10..100%）
 *   2) 闲置保护：N 秒没输入先变暗、再收起
 *
 * 为什么关心：下屏是 OLED，且系统对这个屏的烧屏保护是关的
 * （`burn_in_protection=0`，该屏 `brightnessDim=-1` 完全不走系统变暗）。
 * 白天主题键面纯白（sRGB 均值 255），长时间挂着就是满亮度烘烤同一批像素。
 * 降低整体亮度是长期措施，闲置变暗/收起是短期措施。
 */
public final class Idle {

    private static final String PREFS = "splitkey";
    private static final String K_BRIGHT = "kb_brightness";
    private static final String K_DIM_ALPHA = "idle_dim_alpha";
    private static final String K_DIM = "idle_dim";
    private static final String K_HIDE = "idle_hide";

    /** 变暗延时档位（ms），0 = 不启用 */
    private static final int[] DIM_MS = {0, 10_000, 30_000, 60_000};
    public static final String[] DIM_LABELS = {"关闭", "10 秒", "30 秒", "1 分"};

    /** 收起延时档位（ms），0 = 不启用 */
    private static final int[] HIDE_MS = {0, 30_000, 60_000, 300_000};
    public static final String[] HIDE_LABELS = {"关闭", "30 秒", "1 分", "5 分"};

    /** 无极亮度值域（百分比） */
    public static final int MIN_PCT = 10, MAX_PCT = 100;

    private Idle() {}

    /** 键盘整体亮度，百分比。默认 100 —— 不改的话行为与从前一致。 */
    public static int brightness(Context c) {
        return clampPct(prefs(c).getInt(K_BRIGHT, 100));
    }
    public static void setBrightness(Context c, int pct) {
        prefs(c).edit().putInt(K_BRIGHT, clampPct(pct)).apply();
    }

    /** 闲置变暗后的亮度，百分比（相对整体亮度再乘一次）。默认 50。 */
    public static int dimPct(Context c) {
        return clampPct(prefs(c).getInt(K_DIM_ALPHA, 50));
    }
    public static void setDimPct(Context c, int pct) {
        prefs(c).edit().putInt(K_DIM_ALPHA, clampPct(pct)).apply();
    }

    public static int dimIndex(Context c) {
        return clamp(prefs(c).getInt(K_DIM, 0), DIM_MS.length);
    }
    public static void setDimIndex(Context c, int i) {
        prefs(c).edit().putInt(K_DIM, clamp(i, DIM_MS.length)).apply();
    }
    public static long dimMs(Context c) { return DIM_MS[dimIndex(c)]; }

    public static int hideIndex(Context c) {
        return clamp(prefs(c).getInt(K_HIDE, 0), HIDE_MS.length);
    }
    public static void setHideIndex(Context c, int i) {
        prefs(c).edit().putInt(K_HIDE, clamp(i, HIDE_MS.length)).apply();
    }
    public static long hideMs(Context c) { return HIDE_MS[hideIndex(c)]; }

    // ---------- 实时生效 ----------
    // 键盘是常驻悬浮窗、不在任何 Activity 生命周期里，改完设置要靠同进程
    // prefs 回调把它叫醒，不能等下次重开。

    public interface Listener { void onBrightnessChanged(); }

    private static final java.util.List<Listener> listeners = new java.util.ArrayList<>();
    private static SharedPreferences.OnSharedPreferenceChangeListener spListener;

    public static void addListener(Context c, Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                spListener = (p, key) -> {
                    if (!K_BRIGHT.equals(key) && !K_DIM_ALPHA.equals(key)) return;
                    Listener[] arr;
                    synchronized (listeners) { arr = listeners.toArray(new Listener[0]); }
                    for (Listener x : arr) x.onBrightnessChanged();
                };
                prefs(c).registerOnSharedPreferenceChangeListener(spListener);
            }
            listeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            listeners.remove(l);
            if (listeners.isEmpty() && spListener != null) {
                // 拿不到原 Context 也行：注销是幂等的
                listeners.clear();
                spListener = null;
            }
        }
    }

    private static int clampPct(int v) {
        return v < MIN_PCT ? MIN_PCT : (v > MAX_PCT ? MAX_PCT : v);
    }
    private static int clamp(int i, int n) { return (i < 0 || i >= n) ? 0 : i; }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
