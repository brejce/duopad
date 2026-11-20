package com.splitkey.ime;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置页。
 *
 * 现在只做「主题」一项（其余设置等有了再加）。
 * 入口按钮还没接：外部用
 *     startActivity(new Intent(ctx, SettingsActivity.class));
 * 就能打开。
 */
public class SettingsActivity extends PageActivity {

    @Override
    protected void build() {
        addTitle("设置");

        addSection("外观");
        buildThemeRow();
    }

    /**
     * 主题：三选一。点一下就地生效并存盘，不用「保存」按钮。
     * 自动 = 跟随系统 uiMode；白天/黑夜 = 固定。
     */
    private void buildThemeRow() {
        LinearLayout row = addRow("主题", null);

        Theme.Mode cur = Theme.mode(this);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);

        String[] labels = {"跟随系统", "白天", "黑夜"};
        Theme.Mode[] modes = {Theme.Mode.AUTO, Theme.Mode.LIGHT, Theme.Mode.DARK};

        for (int i = 0; i < modes.length; i++) {
            final Theme.Mode m = modes[i];
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(14f);
            t.setGravity(android.view.Gravity.CENTER);
            int px = dp(12), py = dp(8);
            t.setPadding(px, py, px, py);

            boolean on = (cur == m);
            t.setTextColor(on ? 0xFFFFFFFF : tc.text);
            t.setBackground(rounded(on ? tc.locked : tc.func, 8));
            t.setOnClickListener(v -> {
                Theme.setMode(this, m);
                // 重建本页（选中态要变），键盘那边由 Service 自己刷新
                recreate();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            seg.addView(t, lp);
        }

        row.addView(seg);
    }
}
