package com.splitkey.ime;

import android.view.KeyEvent;

/**
 * 键盘布局：纯数据。改布局只动这个文件，不碰渲染和功能。
 *
 * 两种节点：
 *   LEFT   左侧竖列（✕ / ESC / TAB），浮在键盘区左边
 *   各行    右侧四条横排，每行铺满 11.4 + 左列 1.4 = 12.8 单位
 *
 * @see com.splitkey.ime.KeyboardView#layout
 */
public final class KeyboardLayout {

    private KeyboardLayout() {}

    private static KeySpec[] row(KeySpec... ks) { return ks; }

    /** 左列宽度（单位）：与 ⌫ / ⏎ 同宽 */
    public static final float LEFT_WIDTH = 1.4f;

    /**
     * 每行相对左边距的额外偏移（单位）。
     * 只用于形成错列，值由目标位置反推，别改算法。
     */
    public static final float[] ROW_OFFSET = {
            0f,     // q 行：贴 PAD
            0.5f,   // a 行：右移半单位 → a 键左边缘对齐 q 键中心
            0f,     // z 行
            0f,     // 底行
    };

    /** 左侧竖列。✕ 由 Service 按 action="exit" 处理。 */
    public static final KeySpec[] LEFT = row(
            KeySpec.key("✕", KeySpec.KEY_EXIT),
            KeySpec.key("ESC", KeyEvent.KEYCODE_ESCAPE),
            KeySpec.key("TAB", KeyEvent.KEYCODE_TAB)
    );

    /** 字母层 */
    public static final KeySpec[][] ALPHA = {

            // 字母行 1（上方带数字副标签）
            row(
                    KeySpec.ch("q", "1"), KeySpec.ch("w", "2"), KeySpec.ch("e", "3"),
                    KeySpec.ch("r", "4"), KeySpec.ch("t", "5"), KeySpec.ch("y", "6"),
                    KeySpec.ch("u", "7"), KeySpec.ch("i", "8"), KeySpec.ch("o", "9"),
                    KeySpec.ch("p", "0"),
                    KeySpec.key("⌫", KeyEvent.KEYCODE_DEL).w(1.4f)
            ),

            // 字母行 2：a..l + ⏎（⇧ 在下行故这里不留修饰键位）
            row(
                    KeySpec.ch("a"), KeySpec.ch("s"), KeySpec.ch("d"),
                    KeySpec.ch("f"), KeySpec.ch("g"), KeySpec.ch("h"),
                    KeySpec.ch("j"), KeySpec.ch("k"), KeySpec.ch("l"),
                    KeySpec.key("⏎", KeyEvent.KEYCODE_ENTER).w(2.0f)
            ),

            // 字母行 3：⇧ 在最左，? 在最右
            row(
                    KeySpec.mod("⇧", "shift").w(1.4f),
                    KeySpec.ch("z"), KeySpec.ch("x"), KeySpec.ch("c"),
                    KeySpec.ch("v"), KeySpec.ch("b"), KeySpec.ch("n"),
                    KeySpec.ch("m"),
                    // 双字符键：主=未 shift，副=shift（键面同时印出来，靠深浅提示生效者）
                    KeySpec.ch(",", "<").func().shiftSub(),
                    KeySpec.ch(".", ">").func().shiftSub(),
                    KeySpec.ch("/", "?").func()
            ),

            // 底行：Ctrl（与 ⇧ 同宽）Alt，空格居中，方向键贴右
            row(
                    KeySpec.mod("Ctrl", "ctrl").w(1.55f),
                    KeySpec.mod("Alt", "alt"),
                    KeySpec.key("&123", KeySpec.KEY_SYMBOL).w(1.4f),
                    KeySpec.ch(" ").w(5.8f),
                    KeySpec.key("←", KeyEvent.KEYCODE_DPAD_LEFT).w(1.1f),
                    KeySpec.key("→", KeyEvent.KEYCODE_DPAD_RIGHT).w(1.1f)
            ),
    };

    /** 数字 / 符号层 */
    public static final KeySpec[][] SYMBOL = {

            row(
                    KeySpec.ch("1", "!").shiftSub(), KeySpec.ch("2", "@").shiftSub(), KeySpec.ch("3", "#").shiftSub(),
                    KeySpec.ch("4", "$").shiftSub(), KeySpec.ch("5", "%").shiftSub(), KeySpec.ch("6", "^").shiftSub(),
                    KeySpec.ch("7", "&").shiftSub(), KeySpec.ch("8", "*").shiftSub(), KeySpec.ch("9", "(").shiftSub(),
                    KeySpec.ch("0", ")").shiftSub(),
                    KeySpec.key("⌫", KeyEvent.KEYCODE_DEL).w(1.4f)
            ),

            // 与字母层 a..l + ⏎ 同构：9 键 + ⏎(2.0)，右边缘对齐 ⌫
            // 真键盘右侧那一列。不放 ( ) @ # % * —— 第一行 shift 副标签已经有了
            // （3/#、5/%、8/*、9/(、0/)、2/@），重复占位是浪费。
            row(
                    KeySpec.ch("-", "_").shiftSub(), KeySpec.ch("=", "+").shiftSub(),
                    KeySpec.ch("[", "{").shiftSub(), KeySpec.ch("]", "}").shiftSub(),
                    KeySpec.ch("\\", "|").shiftSub(),
                    KeySpec.ch(";", ":").shiftSub(),
                    KeySpec.ch(",", "<").shiftSub(), KeySpec.ch(".", ">").shiftSub(),
                    KeySpec.ch("/", "?").shiftSub(),
                    KeySpec.key("⏎", KeyEvent.KEYCODE_ENTER).w(2.0f)
            ),

            // 与字母层 ⇧ + 7 键 + 3 键 同构
            row(
                    KeySpec.mod("⇧", "shift").w(1.4f),
                    KeySpec.ch("'", "\"").shiftSub(), KeySpec.ch("~", "`").shiftSub(),
                    KeySpec.ch("¥"), KeySpec.ch("…"), KeySpec.ch("—"),
                    KeySpec.ch("•"), KeySpec.ch("°"), KeySpec.ch("×"),
                    KeySpec.ch("÷")
            ),

            row(
                    KeySpec.mod("Ctrl", "ctrl").w(1.55f),
                    KeySpec.mod("Alt", "alt"),
                    KeySpec.key("ABC", KeySpec.KEY_ALPHA).w(1.4f),
                    KeySpec.ch(" ").w(5.8f),
                    KeySpec.key("←", KeyEvent.KEYCODE_DPAD_LEFT).w(1.1f),
                    KeySpec.key("→", KeyEvent.KEYCODE_DPAD_RIGHT).w(1.1f)
            ),
    };
}
