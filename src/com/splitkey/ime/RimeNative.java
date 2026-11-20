package com.splitkey.ime;

import android.util.Log;

/**
 * librime 的 JNI 桥。只做「调用转发 + 字符串搬运」，不含任何输入法逻辑。
 *
 * 原生库 libsplitkey_rime.so = 薄 JNI（jni/splitkey_rime.cpp）+ 静态链接的 librime。
 * 原生库不存在时 {@link #available()} 为 false，整个输入法退回直通模式，
 * 不会因为缺库崩溃。
 */
final class RimeNative {

    private static final String TAG = "SplitKey";
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("splitkey_rime");
            loaded = true;
            Log.e(TAG, "librime loaded");
        } catch (Throwable t) {
            Log.e(TAG, "librime not available: " + t);
        }
    }

    private RimeNative() {}

    static boolean available() { return loaded; }

    /**
     * 初始化引擎。
     * @param sharedDir 共享词库目录（只读，principal 的 *.yaml / build/）
     * @param userDir   用户目录（可写，存用户词频、custom.yaml）
     */
    static native boolean init(String sharedDir, String userDir);

    /** 建会话，返回 session id；0 表示失败 */
    static native long createSession();

    static native void destroySession(long session);

    /** 送入键。返回 true = 引擎消化了（不直通） */
    static native boolean processKey(long session, int keyCode, int mask);

    /** 取出并清空待上屏文本 */
    static native String takeCommit(long session);

    /** 当前拼音串（预编辑） */
    static native String composition(long session);

    /** 候选词，limit <= 0 表示默认（通常 5） */
    static native String[] candidates(long session, int limit);

    /** 选第 index 个候选。返回 true = 成功（commit 要用 takeCommit 取） */
    static native boolean selectCandidate(long session, int index);

    /** 当前高亮的候选下标（左右键移动），无候选返回 -1 */
    static native int highlighted(long session);

    /**
     * 把当前组合提交上屏（select_candidate 只选中、不 commit，
     * 必须再调这个才能拿到文字）。
     */
    static native boolean commitComposition(long session);

    /** 清空组合状态 */
    static native void clearComposition(long session);

    /** 中英状态：true = 英文直通，false = 中文。 */
    static native void setAsciiMode(long session, boolean ascii);

    static native boolean isAsciiMode(long session);

    /** 当前方案 id（如 luna_pinyin）。空串 = 没有选中方案。 */
    static native String schemaId(long session);
}
