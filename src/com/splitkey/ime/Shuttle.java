package com.splitkey.ime;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 输出分发器。
 *
 * ASCII    -> 后台线程写常驻 root shell: input text 'x'
 * 非 ASCII -> 广播给隐藏 IME，由它 commitText
 *
 * 所有注入都在后台线程，主线程只入队 —— 否则会 ANR。
 */
public class Shuttle implements OutputChannel {

    private static final String TAG = "SplitKey";
    public static final String ACTION_CMD = "com.splitkey.ime.CMD";
    public static final String OP_COMMIT = "commit";
    public static final String OP_CANDIDATES = "candidates";
    private static final String FIFO = "/data/local/tmp/sk_in";

    private final Context ctx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public Shuttle(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public void sendText(String text) {
        if (text == null || text.isEmpty()) return;
        // 空格单独走 keyevent：daemon 的 shell read 会 trim 行首尾空白，
        // "TXT: " 会被吃成空串，导致空格按不出来
        if (" ".equals(text)) {
            injectKeyCode(android.view.KeyEvent.KEYCODE_SPACE);
            return;
        }
        if (isAscii(text)) injectText(text);
        else commitRemote(text);
    }

    @Override
    public void sendKey(int keyCode) {
        injectKeyCode(keyCode);
    }

    @Override
    public void sendKeyWithMeta(int keyCode, int metaState) {
        injectCombo(keyCode, metaState);
    }

    /** 在指定屏幕坐标点击（触摸板用） */
    public void tap(int x, int y) {
        worker.execute(() -> run("TAP:" + x + "," + y));
    }

    /** 长按（触摸板双指点击 → 右键菜单） */
    public void longPress(int x, int y) {
        worker.execute(() -> run("LONG:" + x + "," + y));
    }

    /** 滚轮（触摸板双指用） */
    /** 触摸滑动（触发系统手势） */
    public void sendSwipe(int x1, int y1, int x2, int y2, int ms) {
        worker.execute(() -> run("SWIPE:" + x1 + "," + y1 + "," + x2 + "," + y2 + "," + ms));
    }

    public void roll(int steps) {
        worker.execute(() -> run("ROLL:" + steps));
    }

    @Override
    public void setCandidates(List<String> candidates) {
        JSONArray arr = new JSONArray();
        if (candidates != null) for (String c : candidates) arr.put(c);
        Intent it = new Intent(ACTION_CMD);
        it.setPackage("com.splitkey.ime");
        it.putExtra("op", OP_CANDIDATES);
        it.putExtra("text", arr.toString());
        ctx.sendBroadcast(it);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    private void commitRemote(String text) {
        // 同进程直连，省掉一次广播往返（这是中文上屏延迟的来源）。
        boolean direct = HiddenIme.commitInProcess(text);
        Log.e(TAG, "commitRemote text='" + text + "' direct=" + direct);
        if (direct) return;

        Intent it = new Intent(ACTION_CMD);
        it.setPackage("com.splitkey.ime");
        it.putExtra("op", OP_COMMIT);
        it.putExtra("text", text);
        ctx.sendBroadcast(it);
    }

    /** 后台线程：input text */
    private void injectText(String text) {
        worker.execute(() -> run("TXT:" + text));
    }

    /** 后台线程：input keyevent */
    private void injectKeyCode(int keyCode) {
        worker.execute(() -> run("KEY:" + keyCode));
    }

    /**
     * 后台线程：input keycombination。
     * daemon 侧展开成 keycombination 的 meta 键码序列：
     * CTRL_LEFT=113, ALT_LEFT=57, SHIFT_LEFT=59
     */
    private void injectCombo(int keyCode, int metaState) {
        StringBuilder sb = new StringBuilder("COMBO:");
        if ((metaState & android.view.KeyEvent.META_CTRL_ON) != 0) sb.append("113,");
        if ((metaState & android.view.KeyEvent.META_ALT_ON) != 0) sb.append("57,");
        if ((metaState & android.view.KeyEvent.META_SHIFT_ON) != 0) sb.append("59,");
        sb.append(keyCode);
        worker.execute(() -> run(sb.toString()));
    }

    /**
     * 后台线程：把命令写进 FIFO，由 root daemon 执行 input。
     * 用 FIFO 而不是 su —— Magisk 31.1 的 su notify 会崩。
     */
    private void run(String cmd) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(FIFO);
            fos.write((cmd + "\n").getBytes());
            fos.flush();
            Log.e(TAG, "fifo: " + cmd);
        } catch (Exception e) {
            Log.e(TAG, "fifo fail: " + e);
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }
}
