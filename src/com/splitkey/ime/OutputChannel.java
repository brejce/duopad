package com.splitkey.ime;

import java.util.List;

/**
 * 键盘输出通道。
 * 键盘 UI 只认这一个接口，不关心字符最终怎么进上屏。
 */
public interface OutputChannel {

    /** 发送任意文本。ASCII 走按键注入，非 ASCII 走 IME 提交。 */
    void sendText(String text);

    /** 发送功能键（DEL / ENTER / TAB / 方向键等） */
    void sendKey(int keyCode);

    /** 组合键（Ctrl+C 等） */
    void sendKeyWithMeta(int keyCode, int metaState);

    /** 更新上屏候选胶囊。传空列表 = 隐藏。 */
    void setCandidates(List<String> candidates);
}
