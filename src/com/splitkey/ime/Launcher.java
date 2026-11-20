package com.splitkey.ime;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

/** 桌面图标：启动键盘服务后立即退出，不留界面 */
public class Launcher extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else {
            // 重装 APK 会把绑定丢掉，启动时重新 set 一次把 HiddenIme 拉起来
            ImeBinder.force(this);

            Intent i = new Intent(this, KeyboardService.class);
            i.putExtra("pin", true);      // 手动启动 = 强制显示键盘
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
        finish();
    }
}
