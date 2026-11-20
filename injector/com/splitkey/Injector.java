package com.splitkey;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * 鼠标事件注入器（root 常驻，app_process 运行）。
 *
 * 从 FIFO 读命令：
 *   M:x,y   鼠标移动到绝对坐标（悬停）
 *   D       左键按下
 *   U       左键松开
 *   T:x,y   点击（按下+松开）
 *   S:dy    滚轮（正=向上）
 *
 * 所有 @hide API 都走反射（android.jar 里只声明了公开部分）。
 */
public class Injector {

    static final String FIFO = "/data/local/tmp/sk_mouse";

    static Object im;
    static Method mInject, mSetButtonState, mSetAxisValue;
    static float curX = 960, curY = 540;

    public static void main(String[] args) {
        try {
            // InputManager.getInstance()（@hide）
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imCls.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            im = getInstance.invoke(null);

            mInject = imCls.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            mInject.setAccessible(true);

            // MotionEvent.setButtonState / setAxisValue（@hide）
            mSetButtonState = MotionEvent.class.getDeclaredMethod("setButtonState", int.class);
            mSetButtonState.setAccessible(true);
            mSetAxisValue = MotionEvent.class.getDeclaredMethod("setAxisValue", int.class, float.class);
            mSetAxisValue.setAccessible(true);

            System.out.println("injector ready");
            System.out.flush();

            File f = new File(FIFO);
            while (true) {
                if (!f.exists()) { Thread.sleep(500); continue; }
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f)));
                String line;
                while ((line = r.readLine()) != null) {
                    try { handle(line.trim()); }
                    catch (Throwable t) { t.printStackTrace(); }
                }
                Thread.sleep(200);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void handle(String line) throws Exception {
        if (line.isEmpty()) return;
        String[] p = line.split(":", 2);
        String cmd = p[0];

        switch (cmd) {
            case "M": {
                String[] xy = p[1].split(",");
                curX = Float.parseFloat(xy[0]);
                curY = Float.parseFloat(xy[1]);
                send(MotionEvent.ACTION_HOVER_MOVE, 0);
                break;
            }
            case "D":
                send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY);
                break;
            case "U":
                send(MotionEvent.ACTION_UP, 0);
                break;
            case "T": {
                if (p.length > 1 && p[1].contains(",")) {
                    String[] xy = p[1].split(",");
                    curX = Float.parseFloat(xy[0]);
                    curY = Float.parseFloat(xy[1]);
                    send(MotionEvent.ACTION_HOVER_MOVE, 0);
                }
                send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY);
                Thread.sleep(40);
                send(MotionEvent.ACTION_UP, 0);
                break;
            }
            case "S": {
                scroll(Float.parseFloat(p[1]));
                break;
            }
            default:
                break;
        }
    }

    static void send(int action, int buttonState) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, action, curX, curY, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        if (action == MotionEvent.ACTION_DOWN) {
            mSetButtonState.invoke(ev, buttonState);
        }
        mInject.invoke(im, ev, 0);
        ev.recycle();
    }

    static void scroll(float dy) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, curX, curY, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        mSetAxisValue.invoke(ev, MotionEvent.AXIS_VSCROLL, dy);
        mInject.invoke(im, ev, 0);
        ev.recycle();
    }
}
