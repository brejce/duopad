package com.splitkey.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * 隐藏 IME：
 * - 输入视图是 0 高度的 View（不显示键盘，但系统会正常绑定 → 能拿到焦点事件和 InputConnection）
 * - 通过广播接收下屏键盘的指令（Shuttle.ACTION_CMD）
 * - 通过广播向上屏/下屏窗口通知焦点变化（ACTION_FOCUS）
 */
public class HiddenIme extends InputMethodService {

    private static final String TAG = "SplitKey";
    public static final String ACTION_FOCUS = "com.splitkey.ime.FOCUS";
    public static final String ACTION_QUERY = "com.splitkey.ime.QUERY_FOCUS";
    public static final String ACTION_CURSOR = "com.splitkey.ime.CURSOR";

    /** 当前是否有可编辑焦点，供 KeyboardService 启动时查询 */
    private boolean focused = false;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent it) {
            if (ACTION_QUERY.equals(it.getAction())) {
                Log.e(TAG, "query focus -> " + focused);
                sendFocus(focused);
                return;
            }
            String op = it.getStringExtra("op");
            String text = it.getStringExtra("text");
            if (op == null) return;
            Log.e(TAG, "rx op=" + op + " text=" + text);

            if (Shuttle.OP_COMMIT.equals(op)) {
                if (text != null) commit(text);
            } else if (Shuttle.OP_CANDIDATES.equals(op)) {
                Log.e(TAG, "candidates=" + text);
            }
        }
    };

    /**
     * 0 高度输入视图。
     * 返回 null 会让系统认为 IME 没有输入视图，因而 onStartInput/onFinishInput
     * 不触发、getCurrentInputConnection() 为 null。给一个 0x0 的 View 就正常了。
     */
    @Override
    public View onCreateInputView() {
        Log.e(TAG, "onCreateInputView -> 0x0 view");
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        return v;
    }

    /**
     * 让 IME 窗口占 0 高度。
     * 不做的话系统会在屏幕底部留一条 IME 导航条（黑条 + 收起箭头 + 切换输入法地球），
     * 既占地方又挡触摸。
     */
    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        int h = getResources().getDisplayMetrics().heightPixels;
        outInsets.contentTopInsets = h;
        outInsets.visibleTopInsets = h;
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
        outInsets.touchableRegion.setEmpty();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** 强行把 IME 窗口压成 0 高，去掉底部导航条（收起箭头 + 切换输入法地球） */
    @Override
    public void onWindowShown() {
        super.onWindowShown();
        shrinkWindow();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        // 不调 super，避免系统按默认逻辑展开键盘窗口
        shrinkWindow();
        // 系统每次请求显示键盘都走这里。必要：✕ 只收起键盘、不杀服务，
        // 点回同一个输入框时 onStartInput 不会重发，只能靠这个把键盘拉回来。
        if (info != null && info.inputType != 0) {
            focused = true;
            sendFocus(true);
        }
    }

    private void shrinkWindow() {
        try {
            android.view.Window w = getWindow() != null ? getWindow().getWindow() : null;
            if (w != null) {
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = 0;
                lp.height = 0;
                w.setAttributes(lp);
                w.setLayout(0, 0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "shrinkWindow failed: " + t);
        }
    }

    /** 请求光标位置上报，用于把胶囊摆到插入符下方 */
    private void requestCursor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { Log.e(TAG, "requestCursor: no ic"); return; }
        try {
            boolean ok = ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR);
            Log.e(TAG, "requestCursor -> " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "requestCursorUpdates failed: " + t);
        }
    }

    @Override
    public void onUpdateCursorAnchorInfo(android.view.inputmethod.CursorAnchorInfo info) {
        if (info == null) { Log.e(TAG, "cursor: null"); return; }
        float x = info.getInsertionMarkerHorizontal();
        float bottom = info.getInsertionMarkerBottom();
        if (Float.isNaN(x) || Float.isNaN(bottom)) { Log.e(TAG, "cursor NaN"); return; }

        // CursorAnchorInfo 是编辑器视图的局部坐标，必须用 getMatrix() 转成屏幕坐标。
        // 不加这一步，不同 App 的窗口位置不同，胶囊就会跑偏（设置和浏览器对不上）。
        float[] pt = {x, bottom};
        try {
            android.graphics.Matrix m = info.getMatrix();
            if (m != null) m.mapPoints(pt);
            else Log.e(TAG, "cursor matrix null");
        } catch (Throwable t) {
            Log.e(TAG, "cursor matrix failed: " + t);
        }
        Log.e(TAG, "cursor local=(" + x + "," + bottom + ") screen=(" + pt[0] + "," + pt[1] + ")");

        Intent it = new Intent(ACTION_CURSOR);
        it.setPackage("com.splitkey.ime");
        it.putExtra("x", pt[0]);
        it.putExtra("y", pt[1]);
        sendBroadcast(it);
    }

    /**
     * 进程内直连：KeyboardService 和 HiddenIme 在同一个进程里，
     * 中间隔一层广播要走 system_server 队列，实测有可感知延迟。
     * 这里挂一个静态实例，Shuttle 直接调用，不走广播。
     */
    private static volatile HiddenIme instance;

    static boolean isAlive() { return instance != null; }

    /** 进程内提交。没有活动实例时返回 false，调用方自行退回广播。 */
    static boolean commitInProcess(String text) {
        HiddenIme ime = instance;
        if (ime == null) return false;
        ime.commit(text);
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        IntentFilter f = new IntentFilter(Shuttle.ACTION_CMD);
        f.addAction(ACTION_QUERY);
        registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        Log.e(TAG, "HiddenIme created");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try { unregisterReceiver(rx); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        String pkg = attribute != null ? attribute.packageName : "?";
        Log.e(TAG, "onStartInput pkg=" + pkg + " type=" + (attribute != null ? attribute.inputType : -1));
        // 只有真正可编辑的输入框才算焦点（过滤掉无输入类型的）
        boolean editable = attribute != null && attribute.inputType != 0;
        focused = editable;
        sendFocus(editable);
        if (editable) requestCursor();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        Log.e(TAG, "onFinishInput");
        focused = false;
        sendFocus(false);
    }

    /**
     * 系统每次请求显示键盘都会走这里。
     * 必要：✕ 只是收起键盘，点回同一个输入框时 onStartInput 不会重发，
     * 只能靠这个把键盘拉回来。
     */
    private void sendFocus(boolean on) {
        Intent it = new Intent(ACTION_FOCUS);
        it.setPackage("com.splitkey.ime");
        it.putExtra("on", on);
        sendBroadcast(it);

        // 有焦点了就把下屏键盘拉起来：KeyboardService 可能被 ✕ 停掉过，
        // 没有它这个广播就没人接，键盘永远出不来。
        if (on) ensureKeyboard();
    }

    /** 拉起下屏键盘。已在跑就什么都不做，不会重复显示。 */
    private void ensureKeyboard() {
        try {
            Intent it = new Intent(this, KeyboardService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(it);
            else startService(it);
        } catch (Throwable t) {
            Log.e(TAG, "ensureKeyboard failed: " + t);
        }
    }

    /** 提交文本到当前焦点输入框 */
    private void commit(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.e(TAG, "commit: no InputConnection");
            return;
        }
        ic.commitText(text, 1);
        Log.e(TAG, "committed: " + text);
    }
}
