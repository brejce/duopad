package com.splitkey.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 按键震动。强度档位存在 SharedPreferences，设置页可调。
 *
 * 用「时长」而不是「振幅」表达强度：很多设备（含本项目的掌机）
 * 忽略 amplitude，只认 duration。见 {@link #ms}。
 */
public final class Haptics {

    private static final String PREFS = "splitkey";
    private static final String KEY = "haptic_level";

    /**
     * 档位下标 = 存盘值。0 关闭。
     * 用时长而不是振幅：这台掌机 amplitude 固定 1.00（dumpsys 可见），
     * 只有 duration 能拉开差别。默认取「中」= 45ms，低于这个基本感觉不到。
     */
    private static final int[] MS = {0, 25, 45, 80};
    public static final String[] LABELS = {"关闭", "弱", "中", "强"};

    /** 首次运行默认档位：中 */
    private static final int DEFAULT_LEVEL = 2;

    private Haptics() {}

    public static int level(Context ctx) {
        int v = prefs(ctx).getInt(KEY, DEFAULT_LEVEL);
        return (v < 0 || v >= MS.length) ? DEFAULT_LEVEL : v;
    }

    public static void setLevel(Context ctx, int level) {
        if (level < 0 || level >= MS.length) level = DEFAULT_LEVEL;
        prefs(ctx).edit().putInt(KEY, level).apply();
    }

    /** 当前档位对应的震动时长（ms）。0 = 关闭。 */
    public static int ms(Context ctx) {
        return MS[level(ctx)];
    }

    /** 一次按键反馈。主线程调用（Vibrator 自己是异步的）。 */
    public static void tap(Context ctx) {
        int d = ms(ctx);
        if (d <= 0) return;
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        try {
            v.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {}
    }

    /** 复用 Theme 的 prefs 名，设置页和键盘看到的是同一份 */
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
