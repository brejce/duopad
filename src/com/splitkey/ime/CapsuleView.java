package com.splitkey.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 上屏候选胶囊，两种状态：
 *
 * 展开（有输入焦点）：深色圆角条，显示候选词，点击选词
 * 折叠（无焦点）：半透明小圆球，可拖动，贴边后隐藏一半
 *
 * 现在显示假的英文候选；接 Rime 只需换 setCandidates 的数据源。
 */
public class CapsuleView extends View {

    public interface Listener {
        void onPick(String word);
        /** 点一下小球（切换键盘显示） */
        void onTap();
        /** 按下开始拖动，参数是屏幕坐标 */
        void onDragStart(float sx, float sy);
        /** 拖动中，参数是屏幕坐标 */
        void onDrag(float sx, float sy);
        /** 松手，参数是屏幕坐标 */
        void onDragEnd(float sx, float sy);
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<String> words = new ArrayList<>();
    private String composing = "";

    /** 当前高亮的候选（左右键移动）。-1 = 无 */
    private int highlight = -1;
    private final Paint hl = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 候选前的序号（1..9），有候选时才画 */
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;

    private final RectF box = new RectF();
    private final List<RectF> wordRects = new ArrayList<>();

    private static final float PAD_H = 26f;
    private static final float PAD_V = 14f;
    private static final float GAP = 22f;
    private static final float BALL = 72f;

    /** true = 小圆球（无焦点） */
    private boolean collapsed = true;

    /** 拖动中：画得稍大、更实 */
    private boolean dragMode;

    public void setDragging(boolean d) {
        if (dragMode == d) return;
        dragMode = d;
        invalidate();
    }

    // 拖动
    private float lastX, lastY;
    private boolean dragging;
    private float downRawX, downRawY;
    private boolean moved;

    public CapsuleView(Context ctx) {
        super(ctx);
        fg.setColor(0xFFEDEDED);
        fg.setTextSize(34f);
        fg.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(28f);
        hint.setTextAlign(Paint.Align.CENTER);
        hl.setColor(0x40FFFFFF);
        num.setTextSize(20f);
        num.setTextAlign(Paint.Align.LEFT);
        setFocusable(false);
    }

    public void setListener(Listener l) { listener = l; }

    public void setCollapsed(boolean c) {
        if (collapsed == c) return;
        collapsed = c;
        requestLayout();
        invalidate();
    }

    public boolean isCollapsed() { return collapsed; }

    public void setHighlight(int idx) {
        if (highlight == idx) return;
        highlight = idx;
        invalidate();
    }

    public void setCandidates(String composing, List<String> cands) {
        this.composing = composing == null ? "" : composing;
        words.clear();
        if (cands != null) words.addAll(cands);
        requestLayout();
        invalidate();
    }

    /** 候选占的宽度：正文 + 序号（1..9 才画） */
    private float wordWidth(int i) {
        float w = fg.measureText(words.get(i));
        if (i < 9) w += num.measureText("1") + 6f;
        return w;
    }

    public String composing() { return composing; }
    public List<String> words() { return words; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (collapsed) {
            setMeasuredDimension((int) BALL, (int) BALL);
            return;
        }

        int width = 0;
        for (int i = 0; i < words.size(); i++) {
            width += (int) (wordWidth(i) + PAD_H * 2 + GAP);
        }
        if (!composing.isEmpty()) width += (int) (hint.measureText(composing) + PAD_H * 2);
        if (words.isEmpty()) width = (int) (PAD_H * 2 + 40);
        width += (int) PAD_H;
        width = Math.max(width, (int) 120);

        int height = (int) (fg.getTextSize() + PAD_V * 2);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();

        if (collapsed) {
            // 纯透明占位：不画任何东西。窗口本身必须挂着（见 KeyboardService.addCapsule），
            // 但折叠态不可见、不接触摸、不焦点，完全不参与交互。
            return;
        }

        bg.setColor(0xE6222228);
        box.set(0, 0, w, h);
        c.drawRoundRect(box, h / 2f, h / 2f, bg);

        float x = PAD_H;

        if (!composing.isEmpty()) {
            hint.setColor(0xFF8A8A96);
            c.drawText(composing, x + hint.measureText(composing) / 2f,
                    h / 2f + hint.getTextSize() / 3f, hint);
            x += hint.measureText(composing) + PAD_H + GAP;
        }

        wordRects.clear();

        if (words.isEmpty()) {
            hint.setColor(0xFF6A6A76);
            c.drawText("•", w / 2f, h / 2f + hint.getTextSize() / 3f, hint);
            return;
        }

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            float tw = wordWidth(i);
            RectF r = new RectF(x, 0, x + tw + PAD_H * 2, h);
            wordRects.add(r);

            // 高亮底：左右键移到这里
            if (i == highlight) {
                RectF hr = new RectF(r);
                hr.inset(4, 6);
                c.drawRoundRect(hr, (hr.height()) / 2f, (hr.height()) / 2f, hl);
            }

            float tx = x + PAD_H;
            // 序号：1..9，超出不画（数字键只到 9）
            if (i < 9) {
                num.setColor(i == highlight ? 0xFFFFFFFF : 0xFF6A6A76);
                c.drawText(String.valueOf(i + 1), tx, h / 2f + num.getTextSize() / 3f, num);
                tx += num.measureText("1") + 6f;
            }

            fg.setColor(0xFFEDEDED);
            c.drawText(word, tx + fg.measureText(word) / 2f,
                    h / 2f + fg.getTextSize() / 3f, fg);
            x += tw + PAD_H * 2 + GAP;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        float rx = e.getRawX(), ry = e.getRawY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = rx;
                downRawY = ry;
                moved = false;
                dragging = collapsed;
                if (collapsed && listener != null) listener.onDragStart(rx, ry);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(rx - downRawX) > 12 || Math.abs(ry - downRawY) > 12) moved = true;
                if (collapsed && moved && listener != null) listener.onDrag(rx, ry);
                return true;

            case MotionEvent.ACTION_UP:
                if (!collapsed) {
                    for (int i = 0; i < wordRects.size() && i < words.size(); i++) {
                        if (wordRects.get(i).contains(x, y)) {
                            if (listener != null) listener.onPick(words.get(i));
                            return true;
                        }
                    }
                } else if (listener != null) {
                    if (moved) listener.onDragEnd(rx, ry);
                    else listener.onTap();          // 没怎么动 = 点击
                }
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
