package com.splitkey.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 键盘渲染器。布局来自 {@link KeyboardLayout}，这里只负责：
 *   算矩形 → 画 → 把点击翻译成 Listener 回调
 *
 * 修饰键粘滞（Termux/switch-term 那种）：
 *   单击 = 一次性（arms，下一个键带上后自动熄灭）
 *   再点 = 锁定（一直亮，再点关闭）
 */
public class KeyboardView extends View {

    public interface Listener {
        void onChar(String ch);
        void onKey(int keyCode);
        void onKeyWithMeta(int keyCode, int metaState);
        /** ✕：收起键盘 */
        void onExit();
    }

    // ---- 视觉参数 ----
    private static final int C_BG      = 0xFFD4D4D4;
    private static final int C_KEY     = 0xFFFFFFFF;
    private static final int C_FUNC    = 0xFFE3E3E3;
    private static final int C_DOWN    = 0xFFB8B8B8;
    private static final int C_TEXT    = 0xFF1A1A1A;
    private static final int C_SUB     = 0xFF5A5A5A;
    /** 未生效字符：浅灰，只做「这个键还能打出这个」的提示 */
    private static final int C_SUB_FAINT = 0xFFAFAFAF;
    private static final int C_TEXT_FAINT = 0xFFB0B0B0;
    private static final int C_ARMED   = 0xFF7FB3E8;   // 一次性：浅蓝
    private static final int C_LOCKED  = 0xFF0F6CBD;   // 锁定：深蓝

    private static final float GAP    = 11f;   // 横/纵键间距（实测 d2-change.png）
    private static final float PAD    = 15f;   // 屏幕四周边距
    private static final float ROW_H  = 1.13f; // 键高 / 键宽
    private static final float BOTTOM = 10f;  // 键盘底边距（与参考图一致）
    private static final float RADIUS = 8f;

    private final Paint keyPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static class RKey {
        KeySpec spec;
        RectF r = new RectF();
        int state;          // 0=off 1=armed 2=locked
    }

    private final List<RKey> keys = new ArrayList<>();
    private RKey pressed;

    private Listener listener;

    /** 长按字母键输出上方的数字 */
    private final android.os.Handler lpHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean longFired;
    private final Runnable longPress = () -> {
        if (pressed != null && pressed.spec.sub != null && listener != null) {
            longFired = true;
            Log.e("SplitKey", "long press -> " + pressed.spec.sub);
            listener.onChar(pressed.spec.sub);
            invalidate();
        }
    };

    // 修饰键状态：0=off 1=armed(一次性) 2=locked
    private int shiftState, ctrlState, altState;

    /** 双击检测：只有快速连点两次才锁定 */
    private static final long DOUBLE_CLICK_MS = 320;
    private long lastModTime;
    private String lastModWhich;

    private KeySpec[][] rows = KeyboardLayout.ALPHA;
    /** 左列最下键（TAB）的底部 y，由 layout() 写入 */
    private float leftColBottom;

    public KeyboardView(Context ctx) {
        super(ctx);
        textPaint.setColor(C_TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setColor(C_SUB);
        subPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 切回字母层（键盘隐藏时调用） */
    public void reset() {
        rows = KeyboardLayout.ALPHA;
        shiftState = ctrlState = altState = 0;
        layout(getWidth(), getHeight());
        invalidate();
    }

    public void resetModifiers() {
        shiftState = ctrlState = altState = 0;
        syncActive();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    private void layout(int w, int h) {
        keys.clear();
        if (w == 0 || h == 0) return;

        int rowCount = rows.length;

        // 单位宽基准只取「铺满行」（除底行外）的最大权重。
        // 底行是松动的（键少、可居中），不能让它反压其它行的键宽。
        float maxUnits = 0;
        int maxCols = 0;
        for (int ri = 0; ri < rowCount; ri++) {
            if (ri == rowCount - 1) continue;
            float sum = 0;
            for (KeySpec k : rows[ri]) sum += k.weight;
            maxUnits = Math.max(maxUnits, sum);
            maxCols = Math.max(maxCols, rows[ri].length);
        }
        if (maxUnits <= 0) {                    // 只有一行
            for (KeySpec k : rows[0]) maxUnits += k.weight;
            maxCols = rows[0].length;
        }
        float unitW = (w - PAD * 2 - GAP * (maxCols - 1)) / maxUnits;
        float keyH = unitW * ROW_H;

        float totalH = rowCount * keyH + (rowCount - 1) * GAP;
        if (totalH > h) {                       // 放不下就整体缩，不改比例
            keyH = (h - (rowCount - 1) * GAP) / rowCount;
            unitW = keyH / ROW_H;
            totalH = h;
        }

        textPaint.setTextSize(keyH * 0.34f);
        subPaint.setTextSize(keyH * 0.19f);

        // 键盘贴底
        float y = h - BOTTOM - totalH;

        // ---- 左列：✕ / ESC / TAB 竖直排在触摸板左侧 ----
        // TAB 贴底（与触摸板下沿对齐），ESC 紧随其上、间距 = GAP，✕ 贴顶
        float leftW = KeyboardLayout.LEFT_WIDTH * unitW;
        KeySpec[] left = KeyboardLayout.LEFT;
        leftColBottom = 0f;
        for (int i = left.length - 1; i >= 0; i--) {
            float top = (i == 0)
                    ? PAD                                            // ✕ 贴顶
                    : y - GAP - (left.length - 1 - i) * (keyH + GAP) - keyH;  // 自底向上
            RKey k = new RKey();
            k.spec = left[i];
            k.r.set(PAD, top, PAD + leftW, top + keyH);
            keys.add(k);
            leftColBottom = Math.max(leftColBottom, top + keyH);
        }

        // ---- 键盘横排 ----
        // 字母三行左对齐铺满；底行键少、天然比满宽行窄，
        // 居中放而不是贴左边（贴边会让右侧空一大块）。
        float fullW = maxUnits * unitW + GAP * (maxCols - 1);
        for (int ri = 0; ri < rowCount; ri++) {
            KeySpec[] row = rows[ri];
            float ru = 0;
            for (KeySpec k : row) ru += k.weight;
            float rowW = ru * unitW + GAP * (row.length - 1);
            boolean lastRow = (ri == rowCount - 1);
            float x = lastRow ? PAD + (fullW - rowW) / 2f : PAD;
            // 错列偏移（单位），由 KeyboardLayout.ROW_OFFSET 给出
            if (ri < KeyboardLayout.ROW_OFFSET.length) x += KeyboardLayout.ROW_OFFSET[ri] * unitW;
            for (KeySpec spec : row) {
                RKey k = new RKey();
                k.spec = spec;
                float kw = spec.weight * unitW;
                k.r.set(x, y, x + kw, y + keyH);
                keys.add(k);
                x += kw + GAP;
            }
            y += keyH + GAP;
        }

        syncActive();
    }

    private int stateOf(String which) {
        switch (which) {
            case "shift": return shiftState;
            case "ctrl":  return ctrlState;
            case "alt":   return altState;
            default:      return 0;
        }
    }

    private void setState(String which, int v) {
        switch (which) {
            case "shift": shiftState = v; break;
            case "ctrl":  ctrlState = v; break;
            case "alt":   altState = v; break;
        }
    }

    /** 大写状态：⇧ 开着（一次性或锁定） */
    private boolean upper() { return shiftState > 0; }


    private void syncActive() {
        for (RKey k : keys) {
            String a = k.spec.action;
            k.state = a.startsWith("mod:") ? stateOf(a.substring(4)) : 0;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(C_BG);

        for (RKey k : keys) {
            if (k.spec.isNone()) continue;      // 空位不画

            boolean down = (k == pressed);
            boolean isFunc = k.spec.action.startsWith("key:")
                    || k.spec.action.startsWith("mod:")
                    || k.spec.funcLook;   // 纯视觉，不影响输入
            boolean isSpace = "char: ".equals(k.spec.action);

            shadowPaint.setColor(0x22000000);
            RectF sh = new RectF(k.r);
            sh.offset(0, 2);
            c.drawRoundRect(sh, RADIUS, RADIUS, shadowPaint);

            if (k.state == 2) keyPaint.setColor(C_LOCKED);
            else if (k.state == 1) keyPaint.setColor(C_ARMED);
            else if (down) keyPaint.setColor(C_DOWN);
            else if (isFunc || isSpace) keyPaint.setColor(C_FUNC);
            else keyPaint.setColor(C_KEY);
            c.drawRoundRect(k.r, RADIUS, RADIUS, keyPaint);

            boolean white = (k.state > 0);
            textPaint.setColor(white ? 0xFFFFFFFF : C_TEXT);

            if (isSpace) {
                shadowPaint.setColor(0x44000000);
                float hw = k.r.width() * 0.12f;
                c.drawRoundRect(new RectF(k.r.centerX() - hw, k.r.centerY() - 3,
                        k.r.centerX() + hw, k.r.centerY() + 3), 3, 3, shadowPaint);
            } else if (k.spec.sub != null) {
                boolean up = upper();
                String lbl = k.spec.label;
                if (Character.isLetter(lbl.charAt(0))) lbl = lbl.toUpperCase();   // 字母键一律印大写

                if (k.spec.subShift) {
                    // 副标签 = shift 字符，随 shift 变色（真键盘：! 和 1 印在同一键）
                    subPaint.setColor(up ? (white ? 0xFFFFFFFF : C_TEXT)
                                          : (white ? 0x66FFFFFF : C_SUB_FAINT));
                    c.drawText(k.spec.sub, k.r.centerX(),
                            k.r.top + subPaint.getTextSize() * 1.6f, subPaint);

                    textPaint.setColor(up ? (white ? 0x66FFFFFF : C_TEXT_FAINT)
                                           : (white ? 0xFFFFFFFF : C_TEXT));
                    c.drawText(lbl, k.r.centerX(),
                            k.r.centerY() + textPaint.getTextSize() * 0.52f, textPaint);
                } else {
                    // 普通态：数字只是「可长按输入」的提示，字母才是主角
                    subPaint.setColor(white ? 0xCCFFFFFF : C_SUB);
                    c.drawText(k.spec.sub, k.r.centerX(),
                            k.r.top + subPaint.getTextSize() * 1.6f, subPaint);

                    textPaint.setColor(white ? 0xFFFFFFFF : C_TEXT);
                    c.drawText(lbl, k.r.centerX(),
                            k.r.centerY() + textPaint.getTextSize() * 0.52f, textPaint);
                }
            } else {
                String lbl = shiftLabel(k.spec.label);
                if (!Character.isLetter(lbl.charAt(0))) lbl = k.spec.label;
                c.drawText(lbl, k.r.centerX(), k.r.centerY() + textPaint.getTextSize() * 0.34f, textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(x, y);
                longFired = false;
                lpHandler.removeCallbacks(longPress);
                if (pressed != null && pressed.spec.sub != null) {
                    lpHandler.postDelayed(longPress, 400);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                lpHandler.removeCallbacks(longPress);
                RKey k = hit(x, y);
                if (!longFired && k != null && k == pressed) fire(k);
                pressed = null;
                longFired = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                lpHandler.removeCallbacks(longPress);
                pressed = null;
                longFired = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** 一个标准键的尺寸 */
    public float[] keySize() {
        for (RKey k : keys) {
            if (k.spec.isNone()) continue;
            return new float[]{k.r.width(), k.r.height()};
        }
        return null;
    }

    /** 左列最下键（TAB）底部 y：触摸板高度用它，与左列下沿对齐 */
    public float colBottom() { return leftColBottom; }

    /** 键盘区（不含左列）的左边界 x（触摸板用它当 leftInset） */
    public float keyboardLeft() {
        float right = 0f;                       // 左列的右边界
        for (RKey k : keys) {
            if (("key:" + KeySpec.KEY_EXIT).equals(k.spec.action)) right = Math.max(right, k.r.right);
        }
        return right > 0 ? right + GAP * 3 : PAD;
    }

    private RKey hit(float x, float y) {
        for (RKey k : keys) {
            if (k.spec.isNone()) continue;
            if (k.r.contains(x, y)) return k;
        }
        return null;
    }

    private void fire(RKey k) {
        if (listener == null) return;
        String a = k.spec.action;

        // 修饰键：
        //   第一次点        → armed（淡蓝，一次性）
        //   快速连点第二次  → locked（深蓝，锁定）
        //   慢慢点第二次    → 取消回 off
        //   locked 时再点   → off
        if (a.startsWith("mod:")) {
            String which = a.substring(4);

            long now = android.os.SystemClock.uptimeMillis();
            boolean dbl = which.equals(lastModWhich) && (now - lastModTime) < DOUBLE_CLICK_MS;
            int s = stateOf(which);

            if (s == 0) setState(which, 1);
            else if (s == 1) setState(which, dbl ? 2 : 0);
            else setState(which, 0);

            lastModTime = now;
            lastModWhich = which;

            Log.e("SplitKey", "mod " + which + " state=" + stateOf(which) + " dbl=" + dbl);
            syncActive();
            invalidate();
            return;
        }

        int meta = 0;
        Log.e("SplitKey", "fire a=" + a + " shift=" + shiftState + " ctrl=" + ctrlState + " alt=" + altState);
        if (upper()) meta |= KeyEvent.META_SHIFT_ON;
        if (ctrlState > 0)  meta |= KeyEvent.META_CTRL_ON;
        if (altState > 0)   meta |= KeyEvent.META_ALT_ON;

        if (a.startsWith("key:")) {
            int code = Integer.parseInt(a.substring(4));
            if (code == KeySpec.KEY_EXIT) {         // ✕ → 收起键盘（不杀服务）
                listener.onExit();
                consumeArmed();
                return;
            }
            if (code == KeySpec.KEY_SYMBOL) {       // &123 → 符号层
                rows = KeyboardLayout.SYMBOL;
                layout(getWidth(), getHeight());
                invalidate();
                consumeArmed();
                return;
            }
            if (code == KeySpec.KEY_ALPHA) {        // ABC → 字母层
                rows = KeyboardLayout.ALPHA;
                layout(getWidth(), getHeight());
                invalidate();
                consumeArmed();
                return;
            }
            if (meta != 0) listener.onKeyWithMeta(code, meta);
            else listener.onKey(code);
        } else if (a.startsWith("char:")) {
            String out = a.substring(5);
            if (upper() && out.length() == 1 && Character.isLetter(out.charAt(0))) {
                out = out.toUpperCase();
            }
            if (meta != 0 && out.length() == 1) {
                int kc = charToKeyCode(out.charAt(0));
                if (kc > 0) listener.onKeyWithMeta(kc, meta);
                else listener.onChar(out);
            } else {
                listener.onChar(out);
            }
        }

        consumeArmed();
    }

    /** armed（一次性）用完就熄灭；locked 保持不变 */
    private void consumeArmed() {
        boolean changed = false;
        if (shiftState == 1) { shiftState = 0; changed = true; }
        if (ctrlState == 1)  { ctrlState = 0;  changed = true; }
        if (altState == 1)   { altState = 0;   changed = true; }
        if (changed) {
            syncActive();
            invalidate();
        }
    }

    /**
     * shift 状态下键面应该显示的字符。
     * 输入行为本来就是对的（走 META_SHIFT_ON 注入），这里只是让键面跟上，
     * 不然按了 ⇧ 键面还是 ', . 1'，只有打进输入框才看得出变成了 '< > !'。
     */
    private static String shiftLabel(String s) {
        if (s == null || s.length() != 1) return s;
        char c = s.charAt(0);
        if (Character.isLetter(c)) return String.valueOf(Character.toUpperCase(c));
        switch (c) {
            case ',':  return "<";
            case '.':  return ">";
            case '/':  return "?";
            case ';':  return ":";
            case '\'': return "\"";
            case '[':  return "{";
            case ']':  return "}";
            case '\\': return "|";
            case '-':  return "_";
            case '=':  return "+";
            case '1':  return "!";
            case '2':  return "@";
            case '3':  return "#";
            case '4':  return "$";
            case '5':  return "%";
            case '6':  return "^";
            case '7':  return "&";
            case '8':  return "*";
            case '9':  return "(";
            case '0':  return ")";
            default:   return s;
        }
    }

    private static int charToKeyCode(char c) {
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
        if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        switch (c) {
            case ' ': return KeyEvent.KEYCODE_SPACE;
            case '.': return KeyEvent.KEYCODE_PERIOD;
            case ',': return KeyEvent.KEYCODE_COMMA;
            case '"': return KeyEvent.KEYCODE_APOSTROPHE;
            case '?': return KeyEvent.KEYCODE_SLASH;
            case '-': return KeyEvent.KEYCODE_MINUS;
            case '/': return KeyEvent.KEYCODE_SLASH;
            case '=': return KeyEvent.KEYCODE_EQUALS;
            default: return -1;
        }
    }
}
