package com.splitkey.ime;

import android.os.Bundle;
import android.view.ViewGroup;
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

        addSection("触感");
        buildHapticRow();

        addSection("防烧屏");
        buildBrightnessSlider();
        buildDimAlphaSlider();
        buildIdleDimRow();
        buildIdleHideRow();

        addSection("关于");
        buildLicensesRow();
    }

    /** 震动强度：四档，点一下即生效（键盘按下和触摸板手势共用这个值） */
    private void buildHapticRow() {
        LinearLayout row = addRow("震动强度", null);

        int cur = Haptics.level(this);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < Haptics.LABELS.length; i++) {
            final int lv = i;
            TextView t = new TextView(this);
            t.setText(Haptics.LABELS[i]);
            t.setTextSize(14f);
            t.setGravity(android.view.Gravity.CENTER);
            int px = dp(12), py = dp(8);
            t.setPadding(px, py, px, py);

            boolean on = (cur == lv);
            t.setTextColor(on ? 0xFFFFFFFF : tc.text);
            t.setBackground(rounded(on ? tc.locked : tc.func, 8));
            t.setOnClickListener(v -> {
                Haptics.setLevel(this, lv);
                Haptics.tap(this);          // 立刻试一下强度
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

    /** 闲置多久开始变暗 */
    private void buildIdleDimRow() {
        buildSegmented("闲置变暗", Idle.DIM_LABELS, Idle.dimIndex(this),
                i -> Idle.setDimIndex(this, i));
    }

    /** 闲置多久收起键盘 */
    private void buildIdleHideRow() {
        buildSegmented("闲置收起", Idle.HIDE_LABELS, Idle.hideIndex(this),
                i -> Idle.setHideIndex(this, i));
    }

    /**
     * 整体亮度：拖动时键盘实时跟着变（预览），松手才存盘。
     * 存盘后会触发 Idle 的 prefs 回调，键盘按新值重绘一次，预览就此结束。
     */
    private void buildBrightnessSlider() {
        buildSlider("整体亮度", Idle.brightness(this),
                // 拖动中：直接把透明度当预览值下发
                pct -> KeyboardService.preview(pct / 100f),
                // 松手：存盘 + 结束预览
                pct -> {
                    Idle.setBrightness(this, pct);
                    KeyboardService.preview(-1f);
                });
    }

    /**
     * 变暗亮度：拖动时让键盘按「整体亮度 × 变暗值」实时变暗给你看；
     * 松手恢复到整体亮度，并把变暗值存盘。
     */
    private void buildDimAlphaSlider() {
        buildSlider("变暗亮度", Idle.dimPct(this),
                pct -> KeyboardService.preview(
                        Idle.brightness(this) / 100f * pct / 100f),
                pct -> {
                    Idle.setDimPct(this, pct);
                    KeyboardService.preview(-1f);   // 回到整体亮度
                });
    }

    /**
     * 无极滑块 + 实时百分比读数。
     *
     * @param onDrag  拖动中回调（做预览，不存盘）
     * @param onDrop  松手回调（存盘 + 结束预览）
     */
    private void buildSlider(String label, int cur,
                             java.util.function.IntConsumer onDrag,
                             java.util.function.IntConsumer onDrop) {
        LinearLayout row = addRow(label, null);

        TextView val = new TextView(this);
        val.setText(cur + "%");
        val.setTextSize(15f);
        val.setTextColor(tc.sub);

        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(Idle.MAX_PCT - Idle.MIN_PCT);
        sb.setProgress(cur - Idle.MIN_PCT);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                int pct = Idle.MIN_PCT + p;
                val.setText(pct + "%");
                // 只在用户拖动时预览，避免程序化 setProgress 触发
                if (fromUser) onDrag.accept(pct);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                onDrop.accept(Idle.MIN_PCT + s.getProgress());
            }
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        col.addView(sb, slp);
        col.addView(val, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        row.addView(col, lp);
    }

    /** 通用分段选择器：一排等宽小按钮，选中项高亮，点了即存盘并重建 */
    private void buildSegmented(String label, String[] labels, int cur,
                               java.util.function.IntConsumer onPick) {
        LinearLayout row = addRow(label, null);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(14f);
            t.setGravity(android.view.Gravity.CENTER);
            int px = dp(12), py = dp(8);
            t.setPadding(px, py, px, py);

            boolean on = (cur == i);
            t.setTextColor(on ? 0xFFFFFFFF : tc.text);
            t.setBackground(rounded(on ? tc.locked : tc.func, 8));
            t.setOnClickListener(v -> { onPick.accept(idx); recreate(); });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            seg.addView(t, lp);
        }

        row.addView(seg);
    }

    /** 开源许可：点进去看 licenses/ 下的第三方许可原文 */
    private void buildLicensesRow() {
        LinearLayout row = addRow("开源许可", "›");
        row.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LicensesActivity.class)));
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
