package com.splitkey.ime;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 两个页面的公共骨架：主题化背景、标题、滚动内容区。
 *
 * 只做排版，不含业务。SettingsActivity / LicensesActivity 继承它。
 */
abstract class PageActivity extends Activity {

    protected Theme.Colors tc;
    private LinearLayout root;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tc = Theme.colors(this);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(tc.bg);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 顶部留出状态栏高度，否则标题会和状态栏文字叠在一起
        root.setPadding(dp(20), dp(24) + statusBarHeight(), dp(20), dp(24));
        sc.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        setContentView(sc);
        build();
    }

    /** 子类在这里往 addTitle / addSection / addRow 里塞内容 */
    protected abstract void build();

    protected void addTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(26f);
        t.setTextColor(tc.text);
        t.setPadding(0, 0, 0, dp(18));
        root.addView(t);
    }

    /** 分组小标题 */
    protected void addSection(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(tc.sub);
        t.setPadding(dp(4), dp(16), 0, dp(6));
        root.addView(t);
    }

    /**
     * 一行卡片。返回其中的右侧容器，调用者可往里加控件。
     * 没有按钮时就是一条展示行。
     */
    protected LinearLayout addRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(tc.key, 10));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(17f);
        l.setTextColor(tc.text);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            TextView v = new TextView(this);
            v.setText(value);
            v.setTextSize(15f);
            v.setTextColor(tc.sub);
            row.addView(v);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        root.addView(row, lp);
        return row;
    }

    /** 正文段落（许可页用） */
    protected void addBody(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(tc.sub);
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        root.addView(t, lp);
    }

    /** 状态栏高度（没有则返回 0） */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    protected Context ctx() { return this; }

    protected GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    protected int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置改完夜间模式回来时，自动跟随时要重新取色
        Theme.Colors now = Theme.colors(this);
        if (now != tc) recreate();
    }
}
