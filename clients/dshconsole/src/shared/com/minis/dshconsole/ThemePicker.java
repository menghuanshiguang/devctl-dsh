package com.minis.dshconsole;

/**
 * 外观选择弹层（照参考图做）：圆角卡片 + 居中标题 + 单选圆点三行 + 底部「确认」。
 *
 * 用自定义布局 + 透明窗口背景，而不是系统 AlertDialog 的 setItems ——
 * 后者是一列纯文字（没有圆点、没有居中标题、也没有底部分隔确认行）。
 */
final class ThemePicker {

        private static final String[] KEYS = {"system", "light", "dark"};
    private static final String[] LABELS = {"系统", "浅色", "深色"};

    private ThemePicker() {
    }

    static void show(final android.app.Activity act) {
        final Store store = new Store(act);
        final String cur = store.get("theme", "system");
        final String[] chosen = {cur.indexOf("light") == 0 ? "light" : cur.indexOf("dark") == 0 ? "dark" : "system"};

        android.widget.LinearLayout card = Ui.col(act);
        card.setBackground(Ui.bg(Ui.SURF2, 20, act));
        card.setClipToOutline(true);

        // 标题（居中）
        android.widget.TextView title = Ui.tv(act, "外观", 18f, Ui.TEXT);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, Ui.dp(act, 18), 0, Ui.dp(act, 16));
        card.addView(title, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(hairline(act));

        final android.widget.TextView[] dots = new android.widget.TextView[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            final int idx = i;
            android.widget.LinearLayout row = Ui.row(act);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(act, 58));
            row.setPadding(Ui.dp(act, 26), 0, Ui.dp(act, 20), 0);

            final android.widget.TextView dot = Ui.tv(act, "", 17f, Ui.TEXT);
            dot.setGravity(android.view.Gravity.CENTER);
            dot.setPadding(0, 0, Ui.dp(act, 18), 0);
            dots[i] = dot;
            row.addView(dot);
            row.addView(Ui.tv(act, LABELS[i], 17f, Ui.TEXT),
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            Ui.press(row, act, Ui.SURF3, 0);
            row.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    chosen[0] = KEYS[idx];
                    paintDots(dots, chosen[0]);
                }
            });
            card.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        paintDots(dots, chosen[0]);
        card.addView(hairline(act));

        // 底部「确认」：照参考图，确认行比上面几行略矮、字是强调色
        android.widget.TextView ok = Ui.tv(act, "确认", 17f, Ui.ACCENT);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setPadding(0, Ui.dp(act, 15), 0, Ui.dp(act, 15));
        Ui.press(ok, act, Ui.SURF3, 0);
        card.addView(ok, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout holder = new android.widget.FrameLayout(act);
        int m = Ui.dp(act, 30);
        holder.setPadding(m, 0, m, 0);
        holder.addView(card, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        // 不用 AlertDialog：它的"面板"会把自定义视图按屏幕撑开（就是那张大灰卡）。
        // 换成裸 Dialog + setContentView，窗口尺寸自己说了算。
        final android.app.Dialog dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(holder);
        dlg.setCanceledOnTouchOutside(true);
        ok.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                store.set("theme", chosen[0]);
                Ui.themeDirty = true;                 // 主界面回来时自己重建
                dlg.dismiss();
                try {
                    act.recreate();                   // 设置页当场重建（颜色都是构造时定的）
                    act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                } catch (Throwable ignored) {
                }
            }
        });
        // 关键：窗口尺寸必须在 show() **之前**设好。放在 onShow 里会先按默认尺寸露一帧，
        // 再跳到正确尺寸 —— 用户看到的就是"弹出来抽一下变位"。
        final int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.86f);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            dlg.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface d) {
                android.view.Window w = dlg.getWindow();
                if (w == null) return;
                // 有些 ROM 会在 show 时把 layout 重置一遍，这里再设一次（同值，不会跳）
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                w.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < card.getChildCount(); i++) {
                    android.view.View ch = card.getChildAt(i);
                    sb.append(i).append(':').append(ch.getClass().getSimpleName())
                      .append('=').append(ch.getHeight()).append("  ");
                }
                android.util.Log.i("DshTheme", "外观弹层 width=" + width
                        + " 卡片高=" + card.getHeight() + " holder高=" + holder.getHeight()
                        + " 各子高: " + sb);
            }
        });
        dlg.show();
    }

    /** 单选圆点：选中 = 实心圆 + 白点；未选中 = 空心细圈。 */
    private static void paintDots(android.widget.TextView[] dots, String chosen) {
        for (int i = 0; i < dots.length; i++) {
            boolean on = KEYS[i].equals(chosen);
            dots[i].setText(on ? "\u25C9" : "\u25CB");
            dots[i].setTextColor(on ? Ui.TEXT : Ui.MUT);
        }
    }

    /** 细线：一定给死高度（MATCH_PARENT 宽 + 1dp 高），否则竖向布局会把它拉满。 */
    private static android.view.View hairline(android.content.Context c) {
        android.view.View v = new android.view.View(c);
        v.setBackgroundColor(Ui.STROKE2);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, Ui.dp(c, 0.5f))));
        return v;
    }
}
