package com.splitkey.ime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 下屏键盘 + 上屏胶囊，都以系统悬浮窗运行。
 *
 * 焦点驱动（HiddenIme 发 ACTION_FOCUS）：
 *   上屏有可编辑输入框 → 键盘显示
 *   失去焦点           → 键盘隐藏
 *   点小球             → 手动锁定/解锁，没焦点也能用键盘
 *
 * 胶囊：有真候选才展开；没候选（纯英文输入）就缩成贴边小球。
 *
 * 为什么不用 Activity：Activity 加 FLAG_NOT_FOCUSABLE 会被判为
 * "no focused window" → 输入派发超时 → ANR。悬浮窗不会。
 */
public class KeyboardService extends Service
        implements KeyboardView.Listener, CapsuleView.Listener {

    private static final String TAG = "SplitKey";
    private static final String CH = "splitkey";
    private static final int NOTIF_ID = 1;
    private static final float BALL = 72f;

    private KeyboardView kb;
    private TouchpadView touchpad;
    private android.widget.FrameLayout.LayoutParams tlp;
    /** true = 触摸板铺满下屏，键盘收起 */
    private boolean touchpadFull;
    private android.widget.FrameLayout kbRoot;
    private float curX = 960, curY = 540;
    private WindowManager wm;
    private CapsuleView capsule;
    private WindowManager topWm;
    private Context topCtx;
    private Shuttle shuttle;
    /** 后台线程装配、主线程使用 */
    private volatile RimeEngine rime = RimeEngine.NULL;

    private boolean focused = false;
    /** 手动锁定键盘（点小球切换） */
    private boolean kbPinned = false;

    private WindowManager.LayoutParams capsuleLp;
    private float cursorX = -1, cursorY = -1;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingFalse;

    /** 上屏焦点变化，带 400ms 去抖（窗口切换会先 finish 再 start） */
    private final BroadcastReceiver focusRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent it) {
            boolean on = it.getBooleanExtra("on", false);
            Log.e(TAG, "focus -> " + on);
            if (on) {
                if (pendingFalse != null) {
                    handler.removeCallbacks(pendingFalse);
                    pendingFalse = null;
                }
                applyFocus(true);
            } else {
                if (pendingFalse != null) handler.removeCallbacks(pendingFalse);
                pendingFalse = () -> { pendingFalse = null; applyFocus(false); };
                handler.postDelayed(pendingFalse, 400);
            }
        }
    };

    /** 设置页改了主题 -> 键盘重取配色（同进程 prefs 回调，不用广播） */
    private final Theme.Listener themeListener = () -> {
        if (kb == null) return;
        kb.applyTheme(this);
        if (touchpad != null) touchpad.applyTheme(this);
        Log.e(TAG, "theme changed -> reapplied");
    };

    /**
     * 系统日/夜切换。悬浮窗不在 Activity 的生命周期里，收不到
     * onConfigurationChanged，只能挂 ComponentCallbacks 自己收。
     * AUTO 模式下全靠它。
     */
    private final android.content.ComponentCallbacks2 uiModeCb =
            new android.content.ComponentCallbacks2() {
        @Override public void onConfigurationChanged(android.content.res.Configuration c) {
            if (Theme.mode(KeyboardService.this) != Theme.Mode.AUTO) return;
            Log.e(TAG, "uiMode night=" + Theme.isSystemDark(KeyboardService.this));
            Theme.notifySystemModeChanged();
        }
        @Override public void onLowMemory() {}
        @Override public void onTrimMemory(int level) {}
    };

    private final BroadcastReceiver cursorRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent it) {
            cursorX = it.getFloatExtra("x", -1);
            cursorY = it.getFloatExtra("y", -1);
            if (capsule != null && !capsule.isCollapsed()) snapCapsule(true);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "SplitKey",
                NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTIF_ID, new Notification.Builder(this, CH)
                .setContentTitle("SplitKey")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .build());

        shuttle = new Shuttle(this);
        initRime();

        DisplayManager dm = getSystemService(DisplayManager.class);

        // 上屏的候选胶囊和光标视图都在这里创建。
        // 之前这两个调用丢了，导致 updateCapsule() 因 capsule==null 直接返回，
        // 表现就是"中文没有候选框"。
        addCapsule(dm);

        Display bottom = null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) { bottom = d; break; }
        }
        Context kbCtx = bottom != null ? createDisplayContext(bottom) : this;
        wm = (WindowManager) kbCtx.getSystemService(WINDOW_SERVICE);
        kb = new KeyboardView(kbCtx);
        kb.setListener(this);

        touchpad = new TouchpadView(kbCtx);
        touchpad.setListener(new TouchpadView.Listener() {
            @Override public void onMove(float dx, float dy) { moveCursor(dx, dy); }
            @Override public void onLeftClick() { sendMouse("C"); }
            @Override public void onRightClick() { sendMouse("R"); }
            @Override public void onMiddleClick() { sendMouse("B"); }
            @Override public void onDragStart() { sendMouse("D"); }
            @Override public void onDragEnd() { sendMouse("U"); }
            @Override public void onScrollV(int steps) { sendMouse("W:" + steps); }
            @Override public void onScrollH(int steps) { sendMouse("H:" + steps); }
            @Override public void onZoomToggle() { toggleTouchpadFull(); }
            @Override public void onThreeFingerSwipe(int dir) {
                // dir: 0=上 1=下 2=左 3=右（笔记本三指手势映射）
                if (dir == 0) shuttle.sendKey(187);              // 上：任务视图
                else if (dir == 1) shuttle.sendKey(3);           // 下：回桌面
                else if (dir == 2) {                             // 左：上一个应用
                    shuttle.sendKeyWithMeta(61, android.view.KeyEvent.META_ALT_ON);
                } else {                                         // 右：下一个应用
                    shuttle.sendKeyWithMeta(61, android.view.KeyEvent.META_ALT_ON
                            | android.view.KeyEvent.META_SHIFT_ON);
                }
            }
        });

        kbRoot = new android.widget.FrameLayout(kbCtx);
        kbRoot.addView(kb, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        tlp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 0);
        tlp.gravity = Gravity.TOP;
        kbRoot.addView(touchpad, tlp);
        kbRoot.post(() -> {
            int h = kbRoot.getHeight();
            // 触摸板下边界对齐 TAB 键底部（= 左列最下键），与键盘首行留 GAP
            if (!touchpadFull) tlp.height = (int) kb.colBottom();
            touchpad.requestLayout();
            Log.e(TAG, "touchpad h=" + tlp.height);
        });

        Log.e(TAG, "keyboard prepared, display=" + (bottom != null ? bottom.getDisplayId() : 0));

        // 上屏「键」小球已去掉：没用了，还占一块可点区域
        applyFocus(false);

        registerReceiver(focusRx, new IntentFilter(HiddenIme.ACTION_FOCUS),
                Context.RECEIVER_EXPORTED);
        registerReceiver(cursorRx, new IntentFilter(HiddenIme.ACTION_CURSOR),
                Context.RECEIVER_EXPORTED);
        Theme.addListener(this, themeListener);
        getApplicationContext().registerComponentCallbacks(uiModeCb);

        Intent q = new Intent(HiddenIme.ACTION_QUERY);
        q.setPackage("com.splitkey.ime");
        sendBroadcast(q);
    }

    private static final float PAD = 14f;   // = KeyboardView.PAD

    /**
     * 装 librime。词库从 APK assets 首次拷到私有目录（librime 只能读真实文件路径）。
     * 任何一步失败都退回直通模式，不影响键盘其他功能。
     */
    private void initRime() {
        // librime 首次初始化要编译词库，会阻塞数秒；放到后台线程，
        // 否则主线程 ANR。完成后再切回主线程更新界面状态。
        new Thread(() -> {
            RimeEngine impl = createRime();
            if (impl == null) return;
            handler.post(() -> {
                rime = impl;
                if (kb != null) kb.setAsciiMode(rime.asciiMode());
                updateCapsule();
                Log.e(TAG, "rime: engine attached");
            });
        }, "rime-init").start();
    }

    /** 后台线程：装引擎。失败返回 null。 */
    private RimeEngine createRime() {
        try {
            java.io.File shared = new java.io.File(getFilesDir(), "rime/shared");
            java.io.File user = new java.io.File(getFilesDir(), "rime/user");
            if (!user.exists() && !user.mkdirs()) {
                Log.e(TAG, "rime: cannot create " + user);
                return null;
            }
            installAssets("rime", shared);

            return RimeEngineImpl.create(
                    shared.getAbsolutePath(), user.getAbsolutePath(),
                    text -> shuttle.sendText(text));
        } catch (Throwable t) {
            Log.e(TAG, "rime init failed: " + t);
            return null;
        }
    }

    /** 把 assets/<assetPath> 整棵树拷到 dst，已存在且大小一致就跳过 */
    private void installAssets(String assetPath, java.io.File dst) {
        android.content.res.AssetManager am = getAssets();
        try {
            String[] top = am.list(assetPath);
            if (top == null || top.length == 0) {
                Log.e(TAG, "rime: assets/" + assetPath + " missing");
                return;
            }
            copyTree(am, assetPath, dst);
        } catch (Throwable t) {
            Log.e(TAG, "rime: installAssets failed: " + t);
        }
    }

    private void copyTree(android.content.res.AssetManager am, String path, java.io.File dst)
            throws java.io.IOException {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {          // 是文件
            if (dst.exists() && dst.length() > 0) return;
            dst.getParentFile().mkdirs();
            try (java.io.InputStream in = am.open(path);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Throwable t) {
                Log.e(TAG, "rime: copy " + path + " failed: " + t);
            }
            return;
        }
        if (!dst.exists() && !dst.mkdirs()) return;
        for (String k : kids) copyTree(am, path + "/" + k, new java.io.File(dst, k));
    }

    /** 上屏尺寸（Service 自己的 resources 可能是下屏的） */
    private android.util.DisplayMetrics metrics() {
        return topCtx != null ? topCtx.getResources().getDisplayMetrics()
                              : getResources().getDisplayMetrics();
    }

    private java.io.Writer mouseW;

    /** 发命令给 uimouse 虚拟鼠标（系统光标走这条路） */
    private void sendMouse(String cmd) {
        try {
            if (mouseW == null) {
                mouseW = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(
                                new java.io.FileOutputStream("/data/local/tmp/sk_mouse")));
            }
            mouseW.write(cmd + "\n");
            mouseW.flush();
            if (!cmd.startsWith("M:")) Log.e(TAG, "mouse: " + cmd);   // M 太频繁，只记非移动
        } catch (Throwable t) {
            Log.e(TAG, "sendMouse fail: " + t);
            mouseW = null;       // 写失败下次重开
        }
    }

    /**
     * 让系统真正绑定 HiddenIme。
     *
     * 我们的键盘是悬浮窗，从不请求 IME 窗口，系统就不会创建 InputMethodService，
     * 结果 getCurrentInputConnection() 永远为 null -> 中文 commitText 无处可去
     * （日志特征：commitRemote direct=false）。
     *
     * HiddenIme 自己把窗口压成 0 高度，所以「显示」它不会挡住任何东西，
     * 但这一步是必须的：只有被 showSoftInput 唤起，服务才会 onBind。
     */
    private void ensureImeStarted() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) return;
            boolean shown = imm.showSoftInput(kbRoot, 0);
            Log.e(TAG, "ensureImeStarted showSoftInput=" + shown
                    + " hiddenIme=" + HiddenIme.isAlive());
        } catch (Throwable t) {
            Log.e(TAG, "ensureImeStarted failed: " + t);
        }
    }

    private void applyFocus(boolean on) {
        focused = on;
        // 上屏一来焦点就确认 IME 绑定还在：重装/重启后绑定会丢，
        // 丢了中文就 commit 不出去。有实例时 ensure() 是空操作，不会 fork。
        if (on) ImeBinder.ensure(this);
        // 只开不关：焦点来了就显示，焦点走了不隐藏（否则触摸板一起消失，就没法再操作）。
        // 隐藏只由 ✕ 触发。上屏没焦点时往哪里输入是系统的事，不是键盘该操心的。
        if (on) toggleKeyboard(true);
        else rime.reset();          // 丢焦点就把残留拼音丢掉，不要在桌面上飘候选
        updateCapsule();
    }

    private void toggleKeyboard(boolean show) {
        try {
            // 以真实挂载状态为准：窗口可能被系统藏掉/摘掉，布尔量会和现实脱节
            boolean attached = kbRoot.getParent() != null;   // 只信父容器，窗口被系统藏掉时这个值仍准确
            if (!show && attached) {
                wm.removeView(kbRoot);
                kb.reset();          // 复位到字母层 + 清修饰键
                Log.e(TAG, "keyboard hidden");
            } else if (show && !attached) {
                wm.addView(kbRoot, kbParams());
                Log.e(TAG, "keyboard shown");
                // 键盘一露头就要确认上屏 IME 在跑，否则中文提交无处可去
                ensureImeStarted();
            }
        } catch (Throwable t) {
            Log.e(TAG, "keyboard toggle failed: " + t);
        }
    }

    /**
     * 触摸板全屏 / 还原。
     *
     * 不拆窗口：键盘和触摸板是同一个 kbRoot，这里只把 KeyboardView 藏起来、
     * 把触摸板高度从 colBottom 拉到 MATCH_PARENT。窗口本身一直挂着，
     * 所以焦点、IME 绑定、手势状态全都不受影响。
     */
    private void toggleTouchpadFull() {
        if (kbRoot == null || kb == null || touchpad == null || tlp == null) return;
        touchpadFull = !touchpadFull;
        Log.e(TAG, "touchpad full=" + touchpadFull);

        kb.setVisibility(touchpadFull ? android.view.View.GONE
                                      : android.view.View.VISIBLE);
        tlp.height = touchpadFull
                ? android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                : (int) kb.colBottom();
        touchpad.setExpanded(touchpadFull);
        try { touchpad.setLayoutParams(tlp); } catch (Throwable ignored) {}
        touchpad.requestLayout();
    }

    private WindowManager.LayoutParams kbParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void addCapsule(DisplayManager dm) {
        Display top = dm.getDisplay(Display.DEFAULT_DISPLAY);
        topCtx = top != null ? createDisplayContext(top) : this;
        topWm = (WindowManager) topCtx.getSystemService(WINDOW_SERVICE);

        capsule = new CapsuleView(topCtx);
        capsule.setListener(this);

        capsuleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // 起始是折叠球，不接触摸
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        capsuleLp.gravity = Gravity.TOP | Gravity.START;

        int screenW = metrics().widthPixels;
        capsuleLp.x = screenW - (int) BALL;
        capsuleLp.y = 300;

        try {
            topWm.addView(capsule, capsuleLp);
            Log.e(TAG, "capsule added");
        } catch (Throwable t) {
            Log.e(TAG, "capsule failed: " + t);
        }
    }

    /** 移动残差：整数化会丢掉不足 1px 的位移，必须留到下次累加，否则慢速移动会「粘住」 */
    private float moveAccX, moveAccY;

    private void moveCursor(float dx, float dy) {
        // 只发 uinput，系统自己维护光标位置
        float speed = 1.6f;
        moveAccX += dx * speed;
        moveAccY += dy * speed;
        int mx = (int) moveAccX, my = (int) moveAccY;
        if (mx == 0 && my == 0) return;
        moveAccX -= mx;
        moveAccY -= my;
        sendMouse("M:" + mx + "," + my);
    }

    /** 有真候选才展开，否则缩成小球 */
    private void updateCapsule() {
        if (capsule == null) return;
        String[] cs = rime.candidates();
        List<String> list = new ArrayList<>();
        if (cs != null) for (String c : cs) list.add(c);

        // 没焦点就别显示：桌面上、别的 app 里都不该冒候选。
        // 而且失去焦点时引擎里可能还留着上次的组合，不挡就会飘在桌面上。
        boolean has = focused && !list.isEmpty();
        capsule.setCandidates(rime.composing(), list);
        capsule.setHighlight(rime.highlighted());
        capsule.setCollapsed(!has);
        setCapsuleTouchable(has);

        if (has) capsule.post(() -> snapCapsule(true));
        else snapCapsule(false);
    }

    /**
     * 折叠态（无候选）的球是纯透明占位：不接触摸、不焦点，
     * 否则它会吃掉点击上屏输入框的事件，输入框拿不到焦点，键盘就起不来。
     * 展开态要能点候选词，所以必须可触摸。
     */
    private void setCapsuleTouchable(boolean touchable) {
        if (capsuleLp == null || topWm == null) return;
        boolean notTouchable =
                (capsuleLp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
        if (notTouchable == !touchable) return;      // 状态已对
        if (touchable) capsuleLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else capsuleLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { topWm.updateViewLayout(capsule, capsuleLp); } catch (Throwable ignored) {}
    }

    private void snapCapsule(boolean expanded) {
        if (capsuleLp == null || topWm == null) return;
        int screenW = metrics().widthPixels;
        int screenH = metrics().heightPixels;

        if (expanded) {
            int w = capsule.getWidth();
            if (cursorY > 0) {
                // 从光标底部开始向右展开
                capsuleLp.x = (int) Math.max(4, cursorX);
                capsuleLp.y = (int) (cursorY + 4);
                if (capsuleLp.x + w > screenW - 4) capsuleLp.x = Math.max(4, screenW - w - 4);
                if (capsuleLp.y + capsule.getHeight() > screenH - 4) {
                    capsuleLp.y = (int) Math.max(4, cursorY - capsule.getHeight() - 4);
                }
            } else {
                capsuleLp.x = Math.max(0, (screenW - w) / 2);
                capsuleLp.y = (int) (screenH * 0.62f);
            }
        } else {
            int half = (int) (BALL / 2);
            if (capsuleLp.x + half < screenW / 2) capsuleLp.x = -half;
            else capsuleLp.x = screenW - half;
            capsuleLp.y = Math.max(0, Math.min(capsuleLp.y, screenH - (int) BALL));
        }
        try { topWm.updateViewLayout(capsule, capsuleLp); } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        // 从桌面图标启动 = 手动开键盘。每次都重新置位，不能因为“已经 pin 过”就跳过
        if (it != null && it.getBooleanExtra("pin", false)) {
            kbPinned = true;
            toggleKeyboard(true);
            Log.e(TAG, "pinned by launcher, shown=" + kbRoot.isAttachedToWindow());
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent it) { return null; }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(focusRx); } catch (Exception ignored) {}
        try { unregisterReceiver(cursorRx); } catch (Exception ignored) {}
        Theme.removeListener(themeListener);
        try { getApplicationContext().unregisterComponentCallbacks(uiModeCb); }
        catch (Throwable ignored) {}
        // 不能用 isAttachedToWindow 判断：stopSelf 后它会先变 false，窗口就漏在屏幕上
        try { if (kbRoot != null) wm.removeView(kbRoot); } catch (Throwable ignored) {}
        try { if (topWm != null && capsule != null) topWm.removeView(capsule); } catch (Throwable ignored) {}
        if (rime instanceof RimeEngineImpl) ((RimeEngineImpl) rime).destroy();
        super.onDestroy();
    }

    // ---------- CapsuleView.Listener ----------

    @Override
    public void onPick(String word) {
        Log.e(TAG, "pick " + word);
        // 按点击的候选文字反查下标：胶囊只回传文字，不回传 index
        int idx = indexOfCandidate(word);
        String out = idx >= 0 ? rime.selectCandidate(idx) : null;
        if (out == null) shuttle.sendText(word);       // 引擎没接时手动直通
        updateCapsule();
    }

    private int indexOfCandidate(String word) {
        String[] cs = rime.candidates();
        for (int i = 0; i < cs.length; i++) if (cs[i].equals(word)) return i;
        return -1;
    }

    @Override
    public void onTap() {
        // 点小球时 onDragStart 已经把窗口扩到全屏了，先缩回小球
        restoreBall();
        kbPinned = !kbPinned;
        Log.e(TAG, "tap, kbPinned=" + kbPinned);
        toggleKeyboard(kbPinned || focused);
    }

    /** 把全屏的拖动窗口缩回贴边小球 */
    private void restoreBall() {
        if (capsuleLp == null) return;
        int sw = metrics().widthPixels, sh = metrics().heightPixels;
        int half = (int) (BALL / 2);

        int fx = (int) capsule.getTranslationX();
        int fy = (int) capsule.getTranslationY();

        capsule.setDragging(false);
        capsule.setTranslationX(0);
        capsule.setTranslationY(0);

        if (fx + half < sw / 2) fx = -half;
        else fx = sw - half;
        fy = Math.max(0, Math.min(fy, sh - (int) BALL));

        capsuleLp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        capsuleLp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        capsuleLp.x = fx;
        capsuleLp.y = fy;
        try { topWm.updateViewLayout(capsule, capsuleLp); } catch (Throwable ignored) {}
    }

    @Override
    public void onDragStart(float sx, float sy) {
        if (capsuleLp == null) return;
        int sw = metrics().widthPixels, sh = metrics().heightPixels;
        capsuleLp.width = sw;
        capsuleLp.height = sh;
        capsuleLp.x = 0;
        capsuleLp.y = 0;
        try { topWm.updateViewLayout(capsule, capsuleLp); } catch (Throwable ignored) {}
        capsule.setTranslationX(sx - BALL / 2f);
        capsule.setTranslationY(sy - BALL / 2f);
        capsule.setDragging(true);
    }

    @Override
    public void onDrag(float sx, float sy) {
        capsule.setTranslationX(sx - BALL / 2f);
        capsule.setTranslationY(sy - BALL / 2f);
    }

    @Override
    public void onDragEnd(float sx, float sy) {
        if (capsuleLp == null) return;
        int sw = metrics().widthPixels, sh = metrics().heightPixels;
        int half = (int) (BALL / 2);

        int fx = (int) (sx - half);
        int fy = (int) (sy - half);
        if (fx + half < sw / 2) fx = -half;
        else fx = sw - half;
        fy = Math.max(0, Math.min(fy, sh - (int) BALL));

        capsule.setDragging(false);
        capsule.setTranslationX(0);
        capsule.setTranslationY(0);

        capsuleLp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        capsuleLp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        capsuleLp.x = fx;
        capsuleLp.y = fy;
        try { topWm.updateViewLayout(capsule, capsuleLp); } catch (Throwable ignored) {}
        Log.e(TAG, "drag end -> " + fx + "," + fy);
    }

    // ---------- KeyboardView.Listener ----------

    @Override
    public void onChar(String ch) {
        Log.e(TAG, "onChar '" + ch + "'");
        boolean consumed = false;
        if (ch.length() == 1) {
            char c = ch.charAt(0);
            // 空格：有拼音时必须当作「选首候选」，不能直接打空格。
            // 它声明成 char: 走的是 onChar，绕过了 onKey 里的 handleKey。
            if (c == ' ' && rime.handleKey(android.view.KeyEvent.KEYCODE_SPACE)) {
                updateCapsule();
                return;
            }
            consumed = rime.input(c);
        }
        if (!consumed) shuttle.sendText(ch);
        updateCapsule();
    }

    /** 原样上屏：不碰 Rime。用于长按副标签、Shift/Ctrl + 标点。 */
    @Override
    public void onCharRaw(String ch) {
        Log.e(TAG, "onCharRaw '" + ch + "'");
        if (ch != null && !ch.isEmpty()) shuttle.sendText(ch);
    }

    @Override
    public void onSettings() {
        Intent it = new Intent(this, SettingsActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(it);
        } catch (Throwable t) {
            Log.e(TAG, "open settings failed: " + t);
        }
    }

    @Override
    public void onToggleIme() {
        boolean ascii = rime.toggleAsciiMode();
        Log.e(TAG, "toggle ime -> ascii=" + ascii);
        kb.setAsciiMode(ascii);
        updateCapsule();
    }

    @Override
    public void onExit() {
        // ✕：只收起键盘，服务不死，下次点输入框立刻回来
        Log.e(TAG, "key ✕ -> hide keyboard");
        kbPinned = false;
        toggleKeyboard(false);
    }

    @Override
    public void onKey(int keyCode) {
        Log.e(TAG, "onKey " + keyCode);
        if (rime.handleKey(keyCode)) { updateCapsule(); return; }
        shuttle.sendKey(keyCode);
    }

    @Override
    public void onKeyWithMeta(int keyCode, int metaState) {
        Log.e(TAG, "onKeyWithMeta " + keyCode + " meta=" + metaState);
        shuttle.sendKeyWithMeta(keyCode, metaState);
    }
}
