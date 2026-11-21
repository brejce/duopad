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
        /** 原样上屏，不经过 Rime（不把 ASCII 标点转成全角） */
        void onCharRaw(String ch);
        void onKey(int keyCode);
        void onKeyWithMeta(int keyCode, int metaState);
        /** ✕：收起键盘 */
        void onExit();
        /** Ctrl/Alt + 空格：切中英（在本地处理，绝不注入系统热键） */
        void onToggleIme();
        /** ⚙：打开设置页 */
        void onSettings();
    }

    // ---- 视觉参数 ----
    /** 当前生效配色。applyTheme() 里从 Theme 拉，不再写死。 */
    private Theme.Colors tc = Theme.LIGHT;
    private int C_BG, C_KEY, C_FUNC, C_DOWN, C_TEXT, C_SUB,
                C_SUB_FAINT, C_TEXT_FAINT, C_ARMED, C_LOCKED;
    /** 未生效字符：浅灰，只做「这个键还能打出这个」的提示 */



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
        if (pressed == null || pressed.spec.sub == null || listener == null) return;
        longFired = true;
        // 副标签 = 长按直出。和主标签同一套中英文映射，但不进 Rime：
        // 「<」在 punctuator 里是候选列表 [《, 〈, «, ‹]，进引擎就变成选词了。
        String sub = punctLabel(pressed.spec.sub);
        Log.e("SplitKey", "long press -> " + sub);
        listener.onCharRaw(sub);
        invalidate();
    };

    /**
     * 长按连发：⌫ / 空格 / 方向键这类「按住就想一直动」的键。
     * 延时与长按数字一致（400ms），之后每 55ms 一次。
     * 只连发这一类键；字母、标点按住不重复（真键盘也不重复）。
     */
    private static final long REPEAT_DELAY = 400;
    private static final long REPEAT_INTERVAL = 55;
    private RKey repeatKey;
    private final Runnable repeatRun = new Runnable() {
        @Override public void run() {
            if (repeatKey == null || listener == null) return;
            longFired = true;               // 抬起时不再补一次
            emit(repeatKey);
            lpHandler.postDelayed(this, REPEAT_INTERVAL);
        }
    };

    /** 这个键按住要不要连发 */
    private static boolean repeatable(KeySpec spec) {
        if ("char: ".equals(spec.action)) return true;       // 空格
        if (!spec.action.startsWith("key:")) return false;
        int code;
        try {
            code = Integer.parseInt(spec.action.substring(4));
        } catch (NumberFormatException e) {
            return false;
        }
        return code == KeyEvent.KEYCODE_DEL
                || code == KeyEvent.KEYCODE_FORWARD_DEL
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** 连发专用：只出输入，不动修饰键状态、不切层 */
    private void emit(RKey k) {
        String a = k.spec.action;
        if (a.startsWith("key:")) {
            int code = Integer.parseInt(a.substring(4));
            if (code > 0) listener.onKey(code);
        } else if (a.startsWith("char:")) {
            listener.onChar(a.substring(5));
        }
    }

    // 修饰键状态：0=off 1=armed(一次性) 2=locked
    private int shiftState, ctrlState, altState;
    /** true = 英文直通。空格键上显示当前状态。 */
    private boolean asciiMode = true;

    /** 双击检测：只有快速连点两次才锁定 */
    private static final long DOUBLE_CLICK_MS = 320;
    private long lastModTime;
    private String lastModWhich;

    private KeySpec[][] rows = KeyboardLayout.ALPHA;
    /** 左列最下键（TAB）的底部 y，由 layout() 写入 */
    private float leftColBottom;

    public KeyboardView(Context ctx) {
        super(ctx);
        applyTheme(Theme.colors(ctx));
        textPaint.setColor(C_TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setColor(C_SUB);
        subPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 中英状态变化，空格键文案跟着变。 */
    public void setAsciiMode(boolean ascii) {
        if (asciiMode == ascii) return;
        asciiMode = ascii;
        invalidate();
    }

    /** 换主题：重新取色并重绘。Settings 改完模式后调它。 */
    public void applyTheme(Theme.Colors c) {
        tc = c;
        C_BG = c.bg; C_KEY = c.key; C_FUNC = c.func; C_DOWN = c.down;
        C_TEXT = c.text; C_SUB = c.sub; C_SUB_FAINT = c.faint;
        C_TEXT_FAINT = c.textFaint; C_ARMED = c.armed; C_LOCKED = c.locked;
        textPaint.setColor(C_TEXT);
        subPaint.setColor(C_SUB);
        invalidate();
    }

    public void applyTheme(Context ctx) { applyTheme(Theme.colors(ctx)); }

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

        // ---- 左列：✕ / ESC / ⚙ / TAB 竖直排在触摸板左侧 ----
        // 这四个键按自己的高度走，不跟右侧 keyH：✕ 顶部和 TAB 底部固定，
        // 中间垂直四等分。右侧键盘行、触摸板、滚轮的坐标一概不受影响。
        float leftW = KeyboardLayout.LEFT_WIDTH * unitW;
        KeySpec[] left = KeyboardLayout.LEFT;
        float leftTop = PAD;            // ✕ 贴顶，与原版一致
        // 原版 TAB 底部 = y - GAP - keyH + keyH = y - GAP（不是 y）。
        // 这里必须沿用，差一个 GAP 就会整体下移。
        float leftBottom = y - GAP;
        float leftKeyH = (leftBottom - leftTop - GAP * (left.length - 1)) / left.length;
        leftColBottom = 0f;
        for (int i = 0; i < left.length; i++) {
            float top = leftTop + i * (leftKeyH + GAP);
            RKey k = new RKey();
            k.spec = left[i];
            k.r.set(PAD, top, PAD + leftW, top + leftKeyH);
            keys.add(k);
            leftColBottom = Math.max(leftColBottom, top + leftKeyH);
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
                // 中英状态：空格左侧印出来，不用猜现在打的是哪种
                subPaint.setColor(white ? 0xAAFFFFFF : C_SUB);
                subPaint.setTextAlign(Paint.Align.LEFT);
                // EN / 中， / 中, —— 一眼看出下一个标点是全角还是半角
                String mode = asciiMode ? "EN" : (punctAscii() ? "中," : "中，");
                c.drawText(mode, k.r.left + 18,
                        k.r.centerY() + subPaint.getTextSize() * 0.36f, subPaint);
                subPaint.setTextAlign(Paint.Align.CENTER);
            } else if (k.spec.sub != null) {
                boolean up = upper();
                String lbl = k.spec.label;
                if (Character.isLetter(lbl.charAt(0))) lbl = lbl.toUpperCase();   // 字母键一律印大写
                else lbl = punctLabel(lbl);

                if (k.spec.subShift) {
                    // 副标签 = shift 字符，随 shift 变色（真键盘：! 和 1 印在同一键）
                    subPaint.setColor(up ? (white ? 0xFFFFFFFF : C_TEXT)
                                          : (white ? 0x66FFFFFF : C_SUB_FAINT));
                    c.drawText(punctLabel(k.spec.sub), k.r.centerX(),
                            k.r.top + subPaint.getTextSize() * 1.6f, subPaint);

                    textPaint.setColor(up ? (white ? 0x66FFFFFF : C_TEXT_FAINT)
                                           : (white ? 0xFFFFFFFF : C_TEXT));
                    c.drawText(lbl, k.r.centerX(),
                            k.r.centerY() + textPaint.getTextSize() * 0.52f, textPaint);
                } else {
                    // 普通态：数字只是「可长按输入」的提示，字母才是主角
                    subPaint.setColor(white ? 0xCCFFFFFF : C_SUB);
                    c.drawText(punctLabel(k.spec.sub), k.r.centerX(),
                            k.r.top + subPaint.getTextSize() * 1.6f, subPaint);

                    textPaint.setColor(white ? 0xFFFFFFFF : C_TEXT);
                    c.drawText(lbl, k.r.centerX(),
                            k.r.centerY() + textPaint.getTextSize() * 0.52f, textPaint);
                }
            } else {
                String lbl = shiftLabel(k.spec.label);
                if (!Character.isLetter(lbl.charAt(0))) lbl = punctLabel(k.spec.label);
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
                // 按下就震，不等抬起 —— 抬手还要等 50-150ms，那样反馈是滞后的
                if (pressed != null) Haptics.tap(getContext());
                longFired = false;
                lpHandler.removeCallbacks(longPress);
                if (pressed != null && pressed.spec.sub != null) {
                    lpHandler.postDelayed(longPress, 400);
                }
                repeatKey = (pressed != null && repeatable(pressed.spec)) ? pressed : null;
                if (repeatKey != null) lpHandler.postDelayed(repeatRun, REPEAT_DELAY);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                lpHandler.removeCallbacks(longPress);
                lpHandler.removeCallbacks(repeatRun);
                repeatKey = null;
                RKey k = hit(x, y);
                if (!longFired && k != null && k == pressed) fire(k);
                pressed = null;
                longFired = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                lpHandler.removeCallbacks(longPress);
                lpHandler.removeCallbacks(repeatRun);
                repeatKey = null;
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

        // 修饰键 + 空格 = 切中英。必须在本地吃掉：
        // Ctrl+Space 是 Android 的系统输入法切换热键，一旦注入就会被
        // PhoneWindowManager 拦下并弹出别的 IME。我们根本不要它出门。
        if ("char: ".equals(a) && (ctrlState > 0 || altState > 0)) {
            resetModifiers();
            if (listener != null) listener.onToggleIme();
            return;
        }
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
            if (code == KeySpec.KEY_SETTINGS) {     // ⚙ → 设置页
                listener.onSettings();
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
            // 标点在「该出半角」时绕开 Rime 直出：
            //   Ctrl      → 强制半角（英文模式下也走这里，省一次引擎空转）
            //   Shift     → 取反：中文模式出半角
            // 全角的情况照旧交给 Rime 的 punctuator 映射。
            if (out.length() == 1 && !Character.isLetterOrDigit(out.charAt(0))
                    && out.charAt(0) != ' ' && punctAscii()) {
                listener.onCharRaw(out);
                consumeArmed();
                return;
            }
            // 反向：英文模式按 Shift → 标点要全角。Rime 此时是 ASCII 模式
            // 不管标点，所以在这里自己映射成全角再直出。
            if (out.length() == 1 && !Character.isLetterOrDigit(out.charAt(0))
                    && out.charAt(0) != ' ' && asciiMode && shiftState > 0) {
                listener.onCharRaw(punctLabel(out));
                consumeArmed();
                return;
            }
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
    /**
     * 下次按标点键会不会出半角 ASCII。
     *
     * 三种状态，各自取反：
     *   中文模式        → 全角
     *   英文模式        → 半角
     *   Shift（临时）   → 把当前状态反过来：中文出半角，英文出全角
     * Ctrl（临时）    → 强制半角，不问当前模式
     */
    private boolean punctAscii() {
        if (ctrlState > 0) return true;
        if (shiftState > 0) return !asciiMode;
        return asciiMode;
    }

    /**
     * 键面标点：中文画全角、英文/Shift/Ctrl 画半角 —— 和实际输出一致，
     * 不让人猜现在打的是哪种。主标签和副标签共用这一套。
     */
    private String punctLabel(String s) {
        if (s == null || s.length() != 1 || punctAscii()) return s;
        switch (s.charAt(0)) {
            case ',': return "，";
            case '.': return "。";
            case '/': return "、";
            case '<': return "《";
            case '>': return "》";
            case '?': return "？";
            case ';': return "；";
            case ':': return "：";
            case '!': return "！";
            case '(': return "（";
            case ')': return "）";
            case '[': return "【";
            case ']': return "】";
            case '-': return "－";
            case '=': return "＝";
            default:  return s;
        }
    }

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
