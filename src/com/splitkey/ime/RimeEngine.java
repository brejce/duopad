package com.splitkey.ime;

import java.util.Arrays;

/**
 * Rime 引擎接口。
 *
 * 当前是空实现（NULL），只处理直通输入。
 * 后续把 librime JNI 塞进 RimeEngineImpl 即可 —— 键盘和 Shuttle 无需改动。
 */
public interface RimeEngine {

    /** 送入一个键，返回 true 表示引擎消化了它（不走直通） */
    boolean input(char ch);

    /** 送入功能键（退格、回车、空格选词） */
    boolean handleKey(int keyCode);

    /** 当前候选词，空数组表示无候选 */
    String[] candidates();

    /** 选择第 index 个候选，返回要上屏的文本；无效返回 null */
    String selectCandidate(int index);

    /** 清空组合状态 */
    void reset();

    /** 当前组合中的拼音串（用于下屏预编辑显示） */
    String composing();

    /** 空实现：所有按键直通，无候选 */
    RimeEngine NULL = new RimeEngine() {
        @Override public boolean input(char ch) { return false; }
        @Override public boolean handleKey(int keyCode) { return false; }
        @Override public String[] candidates() { return new String[0]; }
        @Override public String selectCandidate(int index) { return null; }
        @Override public void reset() {}
        @Override public String composing() { return ""; }
    };
}
