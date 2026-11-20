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
        // 只处理字母和分隔符；数字标点直通，避免抢走键盘的常规输入
        if (!(ch >= 'a' && ch <= 'z') && ch != '\'') return false;
        try {
            boolean consumed = RimeNative.processKey(session, ch, 0);
            drainCommit();
            refresh();
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
            switch (keyCode) {
                case KeyEvent.KEYCODE_DEL:
                    if (lastComposition.isEmpty()) return false;   // 无拼音时空格删直通
                    RimeNative.processKey(session, 0xff08, 0);     // XK_BackSpace
                    drainCommit();
                    refresh();
                    return true;

                case KeyEvent.KEYCODE_SPACE:
                    if (lastComposition.isEmpty()) return false;
                    RimeNative.processKey(session, 0x20, 0);       // XK_space = 选首候选
                    drainCommit();
                    refresh();
                    return true;

                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (lastComposition.isEmpty()) return false;
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

    /** 取出引擎产生的上屏文本并交给 Sink；返回该文本 */
    private String drainCommit() {
        if (session == 0) return null;
        String text = RimeNative.takeCommit(session);
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
