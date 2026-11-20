package com.splitkey;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/** 一次性验证：注入 SOURCE_MOUSE 事件，看系统是否画出光标 */
public class MouseTest {
    public static void main(String[] args) {
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Method getInst = imCls.getDeclaredMethod("getInstance");
            getInst.setAccessible(true);
            Object im = getInst.invoke(null);
            Method inject = imCls.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            Method setBtn = MotionEvent.class.getDeclaredMethod("setButtonState", int.class);
            setBtn.setAccessible(true);

            float x = 960, y = 540;
            for (int i = 0; i < 30; i++) {
                x += 12;
                y += 8;
                long now = SystemClock.uptimeMillis();
                MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
                ev.setSource(InputDevice.SOURCE_MOUSE);
                inject.invoke(im, ev, 0);
                ev.recycle();
                Thread.sleep(30);
            }
            System.out.println("done x=" + x + " y=" + y);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
