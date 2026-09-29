package com.minis.dshconsole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 设置一级页：只有侧边栏（分区列表）。手机屏小，二级内容放到另一个活动里。
 * 分区表优先问 host 的 settings.sections，拿不到就用内置表。
 */
public class SettingsActivity extends Activity {

    private static final String[] D_IDS = {"general", "models", "plugins", "presets", "devctl", "rules", "market", "cards"};
    private static final String[] D_LABELS = {"通用设置", "模型", "内置插件", "Agent 预设", "devctl", "规则设定", "插件市场", "侧边卡片"};

    private LinearLayout list;
    private Handler ui;
    private Store store;
    private Store.Dev dev;
    private boolean fromHost = false;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        ui = new Handler(Looper.getMainLooper());
        store = new Store(this);
        String name = store.def("dsh");
        dev = store.find("dsh", name);
        if (dev == null) dev = new Store.Dev();
        setTitle("设置");
        setContentView(scaffold());
        showBuiltin();
        loadSections();
    }

    protected void onResume() {
        super.onResume();
        if (!fromHost) showBuiltin();
    }

    private View scaffold() {
        LinearLayout col = Ui.col(this);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(this, Ui.PAD_H), Ui.dp(this, 8), Ui.dp(this, Ui.PAD_H), 0);

        col.addView(Ui.tv(this, "设置", Ui.H1, Ui.TEXT));
        String sub = dev.name.length() == 0 ? "未配对设备" : ("设备 · " + dev.addr());
        col.addView(Ui.tv(this, sub, Ui.FS_SMALL, Ui.MUT));
        col.addView(Ui.gap(this, 10));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        list = Ui.col(this);
        sc.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(Ui.gap(this, 6));
        col.addView(Ui.hairline(this));
        col.addView(webRow());
        return col;
    }

    /** 一行分区：标题 + 说明 + ›，点了开二级活动。 */
    private View item(final String id, final String label, final int index) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout tx = Ui.col(this);
        tx.addView(Ui.tv(this, label, 15.5f, Ui.TEXT));
        String sub = hint(id);
        if (sub.length() > 0) tx.addView(Ui.tv(this, sub, 12f, Ui.MUT));
        r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ui.tv(this, "›", 17f, Ui.MUT));
        r.setBackground(Ui.bg(Ui.SURF2, Ui.R_CARD, this));
        Ui.press(r, this, Ui.SURF3, Ui.R_CARD);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openPanel(id, label, index);
            }
        });
        LinearLayout wrap = Ui.col(this);
        wrap.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        wrap.addView(Ui.gap(this, 6));
        return wrap;
    }

    /** 一行分区下面的说明：能拿实时值就给实时值，别让用户点进去才知道自己连的是哪台。 */
    private String hint(String id) {
        if ("devctl".equals(id)) {
            String a = dev == null ? "" : dev.addr();
            String w = dev == null ? "" : store.get("web:" + dev.name, "");
            if (a.length() > 0) return w.length() > 0 ? a + " · 原版网页可用" : a;
            return "配对地址、令牌、二维码";
        }
        if ("general".equals(id)) {
            return (Ui.DARK ? "主题：暗色" : "主题：亮色") + " · 语言、启动行为";
        }
        if ("models".equals(id)) return "模型与密钥";
        if ("plugins".equals(id)) return "启用 / 停用";
        if ("presets".equals(id)) return "内置 Agent 预设";
        if ("rules".equals(id)) return "权限与规则";
        if ("market".equals(id)) return "浏览与安装";
        if ("cards".equals(id)) return "侧边栏卡片";
        return "";
    }

    private void showBuiltin() {
        fill(D_IDS, D_LABELS);
    }

    private void fill(String[] ids, String[] labels) {
        if (list == null) return;
        list.removeAllViews();
        for (int i = 0; i < ids.length; i++) list.addView(item(ids[i], labels[i], i));
        list.addView(Ui.gap(this, 10));
    }

    /** 分区表问 host（settings.sections）；host 没实现就保持内置表。 */
    private void loadSections() {
        if (dev == null || dev.host.length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                final java.util.ArrayList<String> ids = new java.util.ArrayList<String>();
                final java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
                try {
                    JSONObject r = call("settings.sections", new JSONObject());
                    JSONArray got = r == null ? null : r.optJSONArray("sections");
                    if (got != null) {
                        for (int i = 0; i < got.length(); i++) {
                            JSONObject s = got.optJSONObject(i);
                            if (s == null) continue;
                            String id = s.optString("id", "");
                            if (id.length() == 0) continue;
                            ids.add(id);
                            String lb = s.optString("label", "");
                            labels.add(lb.length() == 0 ? id : lb);
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (ids.size() == 0) return;
                fromHost = true;
                ui.post(new Runnable() {
                    public void run() {
                        fill(ids.toArray(new String[0]), labels.toArray(new String[0]));
                    }
                });
            }
        }).start();
    }

    /**
     * 进二级页：把显示名、分区 id、列表序号都带上。
     * 网页那边的分区名未必和 host 给的名字一模一样，多带一条「序号」就有兜底。
     */
    private void openPanel(String id, String label, int index) {
        try {
            android.content.Intent it = new android.content.Intent(this, SettingsPanelActivity.class);
            it.putExtra("id", id);
            it.putExtra("label", label);
            it.putExtra("index", index);
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    // —— 底部：原版网页入口（二级页直接吃 DSH 自己的网页，插件分区就不会漏） ——

    private View webRow() {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14));
        LinearLayout tx = Ui.col(this);
        tx.addView(Ui.tv(this, "原版网页", 15f, Ui.TEXT));
        String u = webUrl();
        tx.addView(Ui.tv(this, u.length() == 0 ? "自动 · host 开了网页窗口就直接看原版界面" : u, 12f, Ui.MUT));
        r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ui.tv(this, u.length() == 0 ? "设置" : "改", 13.5f, Ui.ACCENT));
        Ui.press(r, this, 0x00000000, Ui.R_CHIP);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                webDialog();
            }
        });
        return r;
    }

    private String key() {
        return "web:" + (dev == null ? "" : dev.name);
    }

    private String webUrl() {
        try {
            return store.get(key(), "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void webDialog() {
        final EditText in = Ui.input(this, "http://192.168.2.7:3081/?token=…");
        in.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(webUrl());
        new AlertDialog.Builder(this)
                .setTitle("DSH 网页地址")
                .setMessage("在跑 DSH 的机器上 `dsh web` 会把地址打在屏幕上；要手机能访问，加上 --host 0.0.0.0。")
                .setView(in)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            store.set(key(), in.getText().toString().trim());
                        } catch (Throwable ignored) {
                        }
                        setContentView(scaffold());
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            store.set(key(), "");
                        } catch (Throwable ignored) {
                        }
                        setContentView(scaffold());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 一次性连接：发一条请求就关掉（设置页不需要常连）。 */
    private JSONObject call(String method, JSONObject params) throws Exception {
        Dsh d = Dsh.open(dev, 6000, "DshConsole-settings", "android");
        try {
            return d.request(method, params, 8000, new Dsh.EvtSink() {
                public void onEvt(String evt, JSONObject data) {
                }
            });
        } finally {
            try {
                d.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
