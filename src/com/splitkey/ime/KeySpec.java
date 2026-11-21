package com.splitkey.ime;

/**
 * 一个键的声明。纯数据，不含绘制和行为。
 *
 * action 格式：
 *   char:q      普通字符
 *   key:67      Android keyCode
 *   mod:shift   修饰键（粘滞：单击一次性，再点锁定，再点关闭）
 *   none        空位（占一列但不画、不可点，用来对齐列）
 */
public final class KeySpec {

    /** 非 keyCode 的特殊动作，layout 里用负数区分 */
    public static final int KEY_EXIT   = -1;    // ✕ 关键盘
    public static final int KEY_SYMBOL = -2;    // &123
    public static final int KEY_ALPHA  = -3;    // ABC
    public static final int KEY_SETTINGS = -4;  // ⚙ 打开设置页

    public final String label;
    public final String sub;
    public final float weight;
    public final String action;
    /** 纯视觉：画成功能键浅灰底。不影响 action，输入路径完全不变。 */
    public final boolean funcLook;
    /**
     * 副标签是不是「shift 出来的字符」。
     * true  → 像真键盘那样随 shift 变色（1 和 ! 印在同一键上）
     * false → 副标签是别的用途（字母键上方的 1..0 是长按快捷输入），与 shift 无关
     */
    public final boolean subShift;

    public KeySpec(String label, String sub, float weight, String action) {
        this(label, sub, weight, action, false, false);
    }

    public KeySpec(String label, String sub, float weight, String action,
                   boolean funcLook, boolean subShift) {
        this.label = label;
        this.sub = sub;
        this.weight = weight;
        this.action = action;
        this.funcLook = funcLook;
        this.subShift = subShift;
    }

    public static KeySpec ch(String c) {
        return new KeySpec(c, null, 1f, "char:" + c);
    }

    public static KeySpec ch(String c, String sub) {
        return new KeySpec(c, sub, 1f, "char:" + c);
    }

    public static KeySpec key(String label, int keyCode) {
        return new KeySpec(label, null, 1f, "key:" + keyCode);
    }

    public static KeySpec mod(String label, String which) {
        return new KeySpec(label, null, 1f, "mod:" + which);
    }

    /** 空位：占一列但不画、不可点 */
    public static KeySpec none() {
        return new KeySpec(null, null, 1f, "none");
    }

    public KeySpec w(float weight) {
        return new KeySpec(label, sub, weight, action, funcLook, subShift);
    }

    /** 只用浅灰功能键底色，action 原样保留（输入路径不变） */
    public KeySpec func() {
        return new KeySpec(label, sub, weight, action, true, subShift);
    }

    /** 副标签是 shift 字符（随 shift 变色），用于符号层数字键 */
    public KeySpec shiftSub() {
        return new KeySpec(label, sub, weight, action, funcLook, true);
    }

    public boolean isNone() {
        return "none".equals(action);
    }
}
