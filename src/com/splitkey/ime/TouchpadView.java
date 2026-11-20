package com.splitkey.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

/**
 * 笔记本式触摸板。手势对齐 Windows 精密触摸板 / macOS：
 *
 *   单指滑动      移动指针
 *   单指点按      左键
 *   单指双击不松  进入拖动（替代「按住触控板」——这里没有物理按压）
 *   单指长按      拖动（保留的备用入口，笔记本没这个）
 *   双指点按      右键
 *   双指上下滑    垂直滚动（带惯性）
 *   双指左右滑    横向滚动（方向锁定后不会串轴）
 *   三指点按      中键
 *   三指上下滑    上=任务视图  下=回桌面
 *   三指左右滑    左=上一个应用  右=下一个应用
 *   右侧滚轮条    垂直滚动（辅助，笔记本没有，保留）
 *
 * 多指手势结束后，留在板上的那根手指不再移动指针，直到全部抬起
 * （libinput / niri 的规则；否则抬指瞬间的平均位置突变会变成指针跳）。
 *
 * 所有点击都用震动做反馈——设备没有物理按压，这是唯一的「按下感」。
 *
 * 方向锁定：双指落下后先看主方向，超过阈值就锁死该轴，
 * 之后另一个轴的抖动不再触发（笔记本的核心手感）。
 */
public class TouchpadView extends View {

    public interface Listener {
        /** 指针相对移动 */
        void onMove(float dx, float dy);
        void onLeftClick();
        void onRightClick();
        void onMiddleClick();
        /** 开始拖动（左键按下） */
        void onDragStart();
        /** 结束拖动（左键松开） */
        void onDragEnd();
        /** 垂直滚动，正 = 向上 */
        void onScrollV(int steps);
        /** 横向滚动，正 = 向右 */
        void onScrollH(int steps);
        /** 三指滑动：0=上 1=下 2=左 3=右 */
        void onThreeFingerSwipe(int dir);
    }

    // ---- 手势参数（按笔记本手感调）----
    /** 超过这个位移才算「移动」，否则算点按 */
    private static final float TAP_MOVE_PX = 14f;
    /** 点按最长按压时间 */
    private static final long TAP_MAX_MS = 240;
    /** 双击间隔：在窗口内第二次按下 = 双击（可拖） */
    private static final long DOUBLE_TAP_MS = 320;
    /** 双指滚动：多少像素算一格滚轮（越小越灵敏） */
    private static final float WHEEL_PX = 40f;
    /** 双指落下后移动多少像素才锁定方向 */
    private static final float AXIS_LOCK_PX = 10f;
    /** 惯性衰减系数（每帧）和截止速度 */
    private static final float INERTIA_DECAY = 0.93f;
    private static final float INERTIA_MIN = 0.04f;      // px/ms
    private static final long FRAME_MS = 16;
    /** 三指滑动触发的最小位移 */
    private static final float SWIPE_MIN_PX = 90f;

    // 滚轮条几何（右侧竖条，和 ⌫ 对齐）
    private static final float SCROLL_W = 100f;
    // 实测自 d2-change.png：触摸板 165..1113，滚轮条 1123..1265，上下边距 9
    private static final float WHEEL_LEFT  = 1123f;
    private static final float WHEEL_RIGHT = 1265f;
    private static final float MARGIN = 9f;
    private static final float PAD_RIGHT = 1113f;

    private final Paint padPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    /** 触摸板左边界（由 Service 按键盘布局设定） */
    private float leftInset = 165f;

    private Listener listener;
    private Vibrator vibrator;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 单指状态 ----
    private float lastX, lastY, downX, downY;
    private long downTime;
    private boolean moved;
    private boolean singleDown;

    // ---- 双击拖动 ----
    private long lastTapUpTime;
    private boolean secondTapDown;   // 第二次按下中，还没决定是拖动还是双击
    private boolean dragging;        // 已经在拖动（左键按着）

    // ---- 双指滚动 ----
    private boolean twoDown;
    /** 本次触摸过程中出现过第二根手指（用于 UP 时区分单指/双指手势） */
    private boolean hadMulti;
    /** 多指手势后剩下的手指不认作指针，直到全部抬起 */
    private boolean suppressPointer;
    /** 本次手势产生过滚动（区分「双指点击」和「双指滑动」） */
    private boolean didScroll;
    private boolean scrolling;
    private int axisLock;            // 0=未锁定 1=垂直 2=水平
    private float twoDownX, twoDownY;
    private float scrollAccum;       // 换算成滚轮格的余量
    private long lastScrollTime;
    private float scrollVel;         // 惯性速度 px/ms

    // ---- 三指 ----
    private int threePointerCount;
    private boolean threeDown;
    private boolean threeTapped;
    private float threeDownX, threeDownY;
    /** 最近一次「三根手指都在」时的重心。
     *  UP 时只剩一根手指，不能用那时的 avg —— 单指位置和三指重心差几十像素，
     *  会把滑动距离判小，所以只能靠 MOVE 阶段记下的值。 */
    private float threeLastX, threeLastY;

    // ---- 滚轮条 ----
    private boolean inWheel;

    public TouchpadView(Context ctx) {
        super(ctx);
        padPaint.setColor(0xFFC8C8C8);
        wheelPaint.setColor(0xFFE3E3E3);
        tickPaint.setColor(0xFF5A5A5A);
        hintPaint.setColor(0xFF1A1A1A);
        setFocusable(false);
        setFocusableInTouchMode(false);
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setLeftInset(float x) {
        this.leftInset = x;
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 震动反馈：设备没有物理按压，这是唯一的「按下」反馈 */
    private void haptic() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    12, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDetachedFromWindow() {
        stopInertia();
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();

        // 窗口高度 = 左列 TAB 键底部（Service 给的），所以底边贴 h：
        // 不再减 MARGIN，否则会比 TAB 早 9px 结束。
        android.graphics.RectF padRf = new android.graphics.RectF(
                leftInset, MARGIN, PAD_RIGHT, h);
        c.drawRoundRect(padRf, 10, 10, padPaint);

        // 滚轮条
        android.graphics.RectF wheelRf = new android.graphics.RectF(
                WHEEL_LEFT, MARGIN, WHEEL_RIGHT, h);
        c.drawRoundRect(wheelRf, 10, 10, wheelPaint);
        float cx = (WHEEL_LEFT + WHEEL_RIGHT) / 2f;

        arrow.reset();
        arrow.moveTo(cx, 24);
        arrow.lineTo(cx - 13, 46);
        arrow.lineTo(cx + 13, 46);
        arrow.close();
        c.drawPath(arrow, hintPaint);

        arrow.reset();
        arrow.moveTo(cx, h - 24);
        arrow.lineTo(cx - 13, h - 46);
        arrow.lineTo(cx + 13, h - 46);
        arrow.close();
        c.drawPath(arrow, hintPaint);

        for (float y = h * 0.30f; y < h * 0.66f; y += 24) {
            c.drawRect(cx - 15, y, cx + 15, y + 3, tickPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();

        // 左边界之外（TAB/ESC 那列）：不消费，穿透给键盘
        if (x < leftInset) return false;

        boolean wheelZone = x >= WHEEL_LEFT && x <= WHEEL_RIGHT;
        if (!wheelZone && x > PAD_RIGHT) return true;   // 触摸板与滚轮之间的缝

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(x, y, e, wheelZone);
            case MotionEvent.ACTION_POINTER_DOWN:
                return onPointerDown(e);
            case MotionEvent.ACTION_MOVE:
                return onMove(e);
            case MotionEvent.ACTION_POINTER_UP:
                return onPointerUp(e);
            case MotionEvent.ACTION_UP:
                return onUp(e);
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
        }
        return super.onTouchEvent(e);
    }

    // ---------------- 按下 ----------------

    private boolean onDown(float x, float y, MotionEvent e, boolean wheelZone) {
        downX = lastX = x;
        downY = lastY = y;
        downTime = SystemClock.uptimeMillis();
        moved = false;
        scrolling = false;
        singleDown = true;
        hadMulti = false;
        didScroll = false;
        suppressPointer = false;
        inWheel = wheelZone;
        axisLock = 0;
        scrollAccum = 0;
        scrollVel = 0;
        stopInertia();          // 一碰就停惯性，和笔记本一致

        if (wheelZone) return true;

        // 双击判定：上一次点按刚结束不久 → 这次可能是「双击拖动」
        // 笔记本没有「长按拖动」，所以这里不需要任何长按计时器
        secondTapDown = (downTime - lastTapUpTime) < DOUBLE_TAP_MS;
        return true;
    }

    private boolean onPointerDown(MotionEvent e) {
        int n = e.getPointerCount();
        if (n == 2) {
            hadMulti = true;
            suppressPointer = true;
            lastTapUpTime = 0;          // 别让多指手势污染双击拖动判定
            twoDown = true;
            scrolling = true;
            axisLock = 0;
            twoDownX = avgX(e);
            twoDownY = avgY(e);
            lastX = twoDownX;
            lastY = twoDownY;
            scrollAccum = 0;
            scrollVel = 0;
            stopInertia();
        } else if (n >= 3) {
            hadMulti = true;
            suppressPointer = true;
            lastTapUpTime = 0;
            threeDown = true;
            threeTapped = false;
            threePointerCount = n;
            threeDownX = threeLastX = avgX(e);
            threeDownY = threeLastY = avgY(e);
            scrolling = false;
        }
        return true;
    }

    // ---------------- 移动 ----------------

    private boolean onMove(MotionEvent e) {
        int n = e.getPointerCount();

        if (inWheel) {
            float dy = e.getY() - lastY;
            lastY = e.getY();
            emitScrollV(dy / WHEEL_PX, true);
            return true;
        }

        if (threeDown) {
            // 只在三根都还在时更新：拾起一根后 MOVE 只剩两根，
            // 那时算的重心已经被拉向剩下那两根，不能当作三指位置
            if (n >= 3) {
                threeLastX = avgX(e);
                threeLastY = avgY(e);
            }
            return true;                // 三指只记录，不移动/不滚动
        }

        if (twoDown || scrolling) {
            float ax = avgX(e), ay = avgY(e);
            float dx = ax - lastX, dy = ay - lastY;
            lastX = ax;
            lastY = ay;

            // 方向锁定：先看哪个轴先超过阈值，锁死后另一个轴不再响应
            if (axisLock == 0) {
                float tx = ax - twoDownX, ty = ay - twoDownY;
                if (Math.abs(tx) > AXIS_LOCK_PX || Math.abs(ty) > AXIS_LOCK_PX) {
                    axisLock = Math.abs(ty) >= Math.abs(tx) ? 1 : 2;
                }
            }
            if (axisLock == 1) {
                emitScrollV(dy / WHEEL_PX, true);
            } else if (axisLock == 2) {
                emitScrollH(dx / WHEEL_PX, true);
            }
            return true;
        }

        // 多指结束后剩下的手指：不动指针，也不动 lastX/lastY
        if (suppressPointer) return true;

        // 单指
        float dx = e.getX() - lastX, dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();

        if (!moved && (Math.abs(e.getX() - downX) > TAP_MOVE_PX
                || Math.abs(e.getY() - downY) > TAP_MOVE_PX)) {
            moved = true;
                // 双击的第二下开始移动 → 进入拖动（笔记本的「双击后拖」）
            if (secondTapDown && !dragging) {
                secondTapDown = false;
                dragging = true;
                haptic();
                if (listener != null) listener.onDragStart();
            }
        }

        if (moved && !inWheel && listener != null) listener.onMove(dx, dy);
        return true;
    }

    // ---------------- 抬起 ----------------

    private boolean onPointerUp(MotionEvent e) {
        // 抬到只剩 1 根手指时，滚动结束，进入惯性
        if (e.getPointerCount() - 1 <= 1) {
            if (scrolling && axisLock != 0) startInertia();
            twoDown = false;
            scrolling = false;
            axisLock = 0;
        }
        if (e.getPointerCount() - 1 < 3) {
            // 三指还没抬起全部
        }
        return true;
    }

    private boolean onUp(MotionEvent e) {
        long dt = SystemClock.uptimeMillis() - downTime;

        // 三指
        if (threeDown) {
            float tdx = threeLastX - threeDownX;
            float tdy = threeLastY - threeDownY;
            threeDown = false;
            float adx = Math.abs(tdx), ady = Math.abs(tdy);
            if (ady > SWIPE_MIN_PX && ady > adx * 1.4f) {
                if (listener != null) listener.onThreeFingerSwipe(tdy < 0 ? 0 : 1);
            } else if (adx > SWIPE_MIN_PX && adx > ady * 1.4f) {
                if (listener != null) listener.onThreeFingerSwipe(tdx < 0 ? 2 : 3);
            } else {
                // 既不是滑动 → 三指点按 = 中键（不再用时长判定：
                // 慢速/注入环境下 UP 可能远晚于阈值，但仍是一次点按）
                haptic();
                if (listener != null) listener.onMiddleClick();
            }
            reset();
            return true;
        }

        // 双指点击（整段手势没滚动过）→ 右键
        if (hadMulti && !didScroll && !threeDown) {
            boolean tapped = !moved;
            if (tapped) {
                haptic();
                if (listener != null) listener.onRightClick();
            }
            reset();
            return true;
        }

        // 滚动结束：进惯性，不发点击
        if (scrolling || twoDown || didScroll) {
            if (axisLock != 0 && didScroll) startInertia();   // 必须在 reset 前，它要用 axisLock
            reset();
            return true;
        }


        // 拖动中松开
        if (dragging) {
            dragging = false;
            if (listener != null) listener.onDragEnd();
            lastTapUpTime = 0;
            reset();
            return true;
        }

        // 没有移动、且没被长按/双击转成拖动 → 就是点击（不再看时长：
        // 注入/慢速系统下 UP 可能远晚于 240ms，但这仍然是一次点按）
        boolean wasTap = !moved && !dragging;

        // 第二次点按（双击）：不再拖就当作第二次单击
        if (secondTapDown) {
            secondTapDown = false;
            lastTapUpTime = 0;
            if (wasTap) {
                haptic();
                if (listener != null) listener.onLeftClick();
            }
            reset();
            return true;
        }

        if (wasTap) {
            haptic();
            if (listener != null) listener.onLeftClick();
            lastTapUpTime = SystemClock.uptimeMillis();
        } else {
            lastTapUpTime = 0;
        }
        reset();
        return true;
    }

    private void reset() {
        singleDown = false;
        moved = false;
        inWheel = false;
        threeTapped = false;
        // 多指状态一起清：漏了会让下一次手势（尤其是 CANCEL 之后）被判成滚动
        twoDown = false;
        scrolling = false;
        threeDown = false;
        axisLock = 0;
        didScroll = false;
        hadMulti = false;
        suppressPointer = false;
    }

    // ---------------- 滚动 + 惯性 ----------------

    /** 累加并发出整数格；positive 表示方向（手指下滑 = 内容跟手 = 滚轮向上） */
    private void emitScrollV(float steps, boolean trackVel) {
        didScroll = true;
        if (trackVel) trackVelocity(steps);
        scrollAccum += steps;
        int whole = (int) scrollAccum;
        if (whole != 0) {
            scrollAccum -= whole;
            if (listener != null) listener.onScrollV(whole);
        }
    }

    private void emitScrollH(float steps, boolean trackVel) {
        didScroll = true;
        if (trackVel) trackVelocity(steps);
        scrollAccum += steps;
        int whole = (int) scrollAccum;
        if (whole != 0) {
            scrollAccum -= whole;
            if (listener != null) listener.onScrollH(whole);
        }
    }

    /** 用相邻两次滚动的间隔估速度，供惯性用 */
    private void trackVelocity(float steps) {
        long now = SystemClock.uptimeMillis();
        long dt = now - lastScrollTime;
        lastScrollTime = now;
        if (dt <= 0 || dt > 120) return;
        scrollVel = (steps * WHEEL_PX) / dt;      // px/ms
    }

    private int inertiaAxis;    // 1=垂直 2=水平
    private float inertiaVel;   // 格/帧

    private void startInertia() {
        if (Math.abs(scrollVel) < INERTIA_MIN) return;
        inertiaAxis = axisLock;
        inertiaVel = scrollVel / WHEEL_PX;       // 转成格/ms
        ui.removeCallbacks(inertiaRunnable);
        ui.postDelayed(inertiaRunnable, FRAME_MS);
    }

    private void stopInertia() {
        ui.removeCallbacks(inertiaRunnable);
        inertiaVel = 0;
        scrollVel = 0;
    }

    private final Runnable inertiaRunnable = new Runnable() {
        @Override public void run() {
            if (Math.abs(inertiaVel) < 0.0008f) return;
            float steps = inertiaVel * FRAME_MS;
            scrollAccum += steps;
            int whole = (int) scrollAccum;
            if (whole != 0) {
                scrollAccum -= whole;
                if (listener != null) {
                    if (inertiaAxis == 2) listener.onScrollH(whole);
                    else listener.onScrollV(whole);
                }
            }
            inertiaVel *= INERTIA_DECAY;
            ui.postDelayed(this, FRAME_MS);
        }
    };

    private float avgX(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += e.getX(i);
        return sum / e.getPointerCount();
    }

    private float avgY(MotionEvent e) {
        int n = e.getPointerCount();
        float sum = 0;
        for (int i = 0; i < n; i++) sum += e.getY(i);
        return n > 0 ? sum / n : e.getY();
    }
}
