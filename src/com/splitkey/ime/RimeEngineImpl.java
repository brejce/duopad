package com.splitkey.ime;

import android.util.Log;
import android.view.KeyEvent;

/**
 * 基于 librime 的中文输入引擎。
 *
 * 只有一条输出路：引擎产出的文本经 {@link Sink#commit} 交给调用者，
 * 由它走 Shuttle（ASCII 注入 / 非 ASCII 广播）。绝不在这里直接注入，
 * 否则会和键盘的直通路径叠成重复输入。
 */
public final class RimeEngineImpl implements RimeEngine {

    private static final String TAG = "SplitKey";
    private static final int CANDIDATE_LIMIT = 6;

    /** 上屏出口。由 KeyboardService 提供，ASCII/非 ASCII 分流交给 Shuttle。 */
    public interface Sink {
        void commit(String text);
    }

    private final Sink sink;
    private long session;
    private String[] lastCandidates = new String[0];
    private String lastComposition = "";

    private RimeEngineImpl(Sink sink, long session) {
        this.sink = sink;
        this.session = session;
    }

    /**
     * 尝试创建。任何一步失败都返回 null，调用者退回 {@link RimeEngine#NULL}。
     *
     * @param sharedDir 共享词库目录（只读）
     * @param userDir   用户目录（可写）
     */
    public static RimeEngineImpl create(String sharedDir, String userDir, Sink sink) {
        if (!RimeNative.available()) {
            Log.e(TAG, "RimeEngineImpl: native lib missing, fallback to passthrough");
            return null;
        }
        try {
            if (!RimeNative.init(sharedDir, userDir)) {
                Log.e(TAG, "RimeEngineImpl: init failed");
                return null;
            }
            long s = RimeNative.createSession();
            if (s == 0) {
                Log.e(TAG, "RimeEngineImpl: createSession failed");
                return null;
            }
            Log.e(TAG, "RimeEngineImpl: ready, session=" + s);
            return new RimeEngineImpl(sink, s);
        } catch (Throwable t) {
            Log.e(TAG, "RimeEngineImpl: create failed: " + t);
            return null;
        }
    }

    @Override
    public boolean input(char ch) {
        if (session == 0) return false;
        // 可见 ASCII 全部交给引擎：
        //   字母 -> speller 组词
        //   标点 -> punctuator（中文模式下出「，。」，而不是直通的「,.」）
        //   数字 -> selector（有候选时选词）
        // 引擎不消费时返回 false，调用方再走直通。
        if (ch < 0x21 || ch > 0x7e) return false;
        try {
            boolean consumed = RimeNative.processKey(session, ch, 0);
            drainCommit();
            refresh();
            Log.e(TAG, "rime input '" + ch + "' schema=" + RimeNative.schemaId(session)
                    + " consumed=" + consumed
                    + " ascii=" + asciiMode()
                    + " comp='" + lastComposition + "'"
                    + " cand=" + lastCandidates.length
                    + (lastCandidates.length > 0 ? " [" + lastCandidates[0] + "]" : ""));
            return consumed;
        } catch (Throwable t) {
            Log.e(TAG, "input failed: " + t);
            return false;
        }
    }

    @Override
    public boolean handleKey(int keyCode) {
        if (session == 0) return false;
        try {
            // 别用 lastComposition 判断：它是上一次 refresh 的缓存，可能滞后。
            // 直接问引擎当前是否在组词。
            String composing = nz(RimeNative.composition(session));
            boolean hasComposition = !composing.isEmpty();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DEL:
                    if (!hasComposition) return false;              // 无拼音时退格直通
                    RimeNative.processKey(session, 0xff08, 0);      // XK_BackSpace
                    drainCommit();
                    refresh();
                    return true;

                case KeyEvent.KEYCODE_SPACE:
                    if (!hasComposition) return false;              // 不打拼音时空格照常插入
                    // 不用 process_key(0x20)：空格是 0x20，Editor 的
                    // char_handler 判定是 ch > 0x20，空格走不进去；
                    // key_binder 那条路在无候选时会静默取消，结果就是
                    // 「按了没反应，点候选才上屏」。直接选首候选。
                    refresh();
                    if (lastCandidates.length == 0) {
                        RimeNative.clearComposition(session);
                        refresh();
                        return true;
                    }
                    RimeNative.selectCandidate(session, 0);
                    RimeNative.commitComposition(session);   // 选中后必须 commit 才上屏
                    drainCommit();
                    refresh();
                    return true;

                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (!hasComposition) return false;
                    RimeNative.clearComposition(session);
                    refresh();
                    return true;

                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleKey failed: " + t);
            return false;
        }
    }

    @Override
    public String[] candidates() {
        return lastCandidates;
    }

    @Override
    public String selectCandidate(int index) {
        if (session == 0) return null;
        if (index < 0 || index >= lastCandidates.length) return null;
        try {
            if (!RimeNative.selectCandidate(session, index)) return null;
            RimeNative.commitComposition(session);       // 选中后必须 commit 才上屏
            String out = drainCommit();
            refresh();
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "selectCandidate failed: " + t);
            return null;
        }
    }

    @Override
    public void reset() {
        if (session == 0) return;
        try {
            RimeNative.clearComposition(session);
            refresh();
        } catch (Throwable ignored) {}
    }

    @Override
    public String composing() {
        return lastComposition;
    }

    @Override
    public boolean asciiMode() {
        if (session == 0) return true;
        try { return RimeNative.isAsciiMode(session); }
        catch (Throwable t) { return true; }
    }

    @Override
    public boolean toggleAsciiMode() {
        if (session == 0) return true;
        try {
            boolean now = RimeNative.isAsciiMode(session);
            RimeNative.setAsciiMode(session, !now);
            // 切英文时清掉未完成的拼音，避免残留候选
            if (!now) RimeNative.clearComposition(session);
            refresh();
            return !now;
        } catch (Throwable t) {
            Log.e(TAG, "toggleAsciiMode failed: " + t);
            return true;
        }
    }

    /** 取出引擎产生的上屏文本并交给 Sink；返回该文本 */
    private String drainCommit() {
        if (session == 0) return null;
        String text = RimeNative.takeCommit(session);
        Log.e(TAG, "drainCommit text='" + text + "' sink=" + (sink != null));
        if (text != null && !text.isEmpty() && sink != null) sink.commit(text);
        return text;
    }

    /** 同步预编辑串和候选列表 */
    private void refresh() {
        if (session == 0) return;
        lastComposition = nz(RimeNative.composition(session));
        String[] cs = RimeNative.candidates(session, CANDIDATE_LIMIT);
        lastCandidates = cs != null ? cs : new String[0];
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public void destroy() {
        if (session == 0) return;
        try { RimeNative.destroySession(session); } catch (Throwable ignored) {}
        session = 0;
        lastCandidates = new String[0];
        lastComposition = "";
    }
}
