package com.splitkey.ime;

import android.os.Bundle;

/**
 * 许可 / 关于页。
 *
 * 读 assets/licenses/ 下的原文（build.sh 用 aapt2 -A 把 licenses/ 目录打进去），
 * 一份一份展开。BSD 系许可要求二进制分发时随附这些声明，这一页就是履行它。
 */
public class LicensesActivity extends PageActivity {

    private static final String DIR = "licenses";

    @Override
    protected void build() {
        addTitle("开源许可");

        addBody("SplitKey 的 Rime 输入引擎静态链接了以下组件。"
                + "全部为宽松许可，不要求 SplitKey 自身采用相同许可。");

        String[] files = listLicenses();
        if (files.length == 0) {
            addBody("（未找到 licenses/ 资源，构建时是否漏了 aapt2 -A licenses ？）");
            return;
        }

        for (String name : files) {
            addSection(prettyName(name));
            addBody(read(name));
        }
    }

    /** 只要许可原文；README.md 是索引，单独在页面顶部说明，不逐个展开 */
    private String[] listLicenses() {
        try {
            String[] all = getAssets().list(DIR);
            if (all == null) return new String[0];
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String f : all) {
                if (f.equalsIgnoreCase("README.md")) continue;
                out.add(f);
            }
            java.util.Collections.sort(out);
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private String read(String name) {
        try (java.io.InputStream in = getAssets().open(DIR + "/" + name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    /** marisa-trie-BSD-2-Clause-OR-LGPL-2.1.md → 去掉扩展名，好读一点 */
    private static String prettyName(String f) {
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }
}
