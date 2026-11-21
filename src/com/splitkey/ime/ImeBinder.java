package com.splitkey.ime;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * 让上屏的 HiddenIme 被系统真正绑定。
 *
 * 为什么需要这个：
 *   HiddenIme 是 InputMethodService，只有系统「选中并绑定」它时才会被创建。
 *   我们的键盘画在下屏悬浮窗里，从不请求 IME 窗口，所以系统不会主动绑它 ——
 *   结果 getCurrentInputConnection() 拿不到连接，中文 commitText 无处可去
 *   （日志特征：commitRemote direct=false，文字消失）。
 *
 * 实测：进程被杀后，只要重新 set 一次默认输入法，系统就会立刻绑定
 * （HiddenIme created + mBoundToMethod=true）。不用先切走再切回。
 */
public final class ImeBinder {

    private static final String TAG = "SplitKey";
    private static final String SELF = "com.splitkey.ime/.HiddenIme";

    private ImeBinder() {}

    /**
     * 需要时重新绑定。
     *
     * 判断依据必须是「当前默认输入法是不是我」，不能只看 HiddenIme 实例还在不在：
     * Gboard 抢走默认输入法时，我们的实例仍然活着（instance != null），
     * 早先按 isAlive() 判断就会直接跳过 —— 结果 IME 一直是 Gboard，
     * commitText 落进 Gboard，表现的就像键盘被抢了，得手动关掉 App 才恢复。
     *
     * 读 settings 是本地 SharedPreferences 读，开销很小，不 fork 进程。
     */
    public static void ensure(Context ctx) {
        if (isDefault(ctx)) return;
        // force() 要 fork su 进程（100~300ms），别在窗口切换的密集焦点里连发
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastForce < FORCE_COOLDOWN_MS) return;
        lastForce = now;
        Log.e(TAG, "ImeBinder: not default any more -> rebind");
        force(ctx);
    }

    private static long lastForce = 0;
    private static final long FORCE_COOLDOWN_MS = 1500;

    /** 当前默认输入法是不是我们 */
    public static boolean isDefault(Context ctx) {
        try {
            String cur = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            return SELF.equals(cur);
        } catch (Throwable t) {
            Log.e(TAG, "ImeBinder.isDefault failed: " + t);
            return false;
        }
    }

    /**
     * 无条件重新 set 一次。
     * 即使当前已经是自己，`ime set` 也会让 InputMethodManager 重新走一遍选中流程，
     * 从而把服务绑起来 —— 这是实测有效的唯一方式。
     * 需要 root（本项目的运行环境一直有）。
     */
    public static void force(Context ctx) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "ime set " + SELF});
            // 等它真正跑完，别让调用方抢在前面
            p.waitFor();
            Log.e(TAG, "ImeBinder: set " + SELF + " rc=" + p.exitValue()
                    + " alive=" + HiddenIme.isAlive());
        } catch (Throwable t) {
            Log.e(TAG, "ImeBinder failed: " + t);
        }
    }
}
