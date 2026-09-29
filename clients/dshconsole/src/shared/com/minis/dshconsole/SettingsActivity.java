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
    /** 顶部那行「设备 · xx」小字。 */
    private TextView subTitle;
    private Handler ui;
    private Store store;
    private Store.Dev dev;
    /** 当前生效的设备：本地模式=回环那台，远端模式=选中的那台。一级页所有读取都走它。 */
    private Store.Dev active;
    private boolean fromHost = false;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);   // 跟主页面一样不用系统标题栏
        Ui.applyTheme(this);
        ui = new Handler(Looper.getMainLooper());
        store = new Store(this);
        String name = store.def("dsh");
        dev = store.find("dsh", name);
        if (dev == null) dev = new Store.Dev();
        active = Cores.get().device(store, name);
        if (active == null) active = dev;
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

        col.addView(topBar());                       // 顶栏跟主页面同一套：18dp 内边距 + 15.5f 标题 + 发丝线

        LinearLayout body = Ui.col(this);
        int ph = Ui.dp(this, Ui.PAD_H);
        body.setPadding(ph, Ui.dp(this, 10), ph, Ui.dp(this, 26));   // 底部留出系统导航栏，别再被切
        col.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        list = Ui.col(this);
        list.addView(appearanceRow());           // 外观（跟随系统 / 深色 / 浅色）
        list.addView(Ui.gap(this, 10));
        if (Cores.get().local()) {                // 本地版才有的卡片，远端版这行不显示
            list.addView(localCard());
            list.addView(Ui.gap(this, 10));
        }
        TextView sec = Ui.tv(this, "DSH 原版设置", 11.5f, Ui.DIM);   // 跟侧栏的 section 表头同一档
        list.addView(sec);
        list.addView(Ui.gap(this, 8));
        sc.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(Ui.gap(this, 10));
        body.addView(webRow());                  // 原版网页也做成一张卡，形状跟上面统一
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

    /** 顶栏：跟 MainActivity 同款（左边返回键、中间标题 + 一行小字、下面发丝线）。 */
    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));

        TextView back = Ui.tv(this, "\u2039", 26f, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, Ui.dp(this, 12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        LinearLayout mid = Ui.col(this);
        mid.addView(Ui.tv(this, "设置", 15.5f, Ui.TEXT));
        subTitle = Ui.tv(this, deviceLine(), 11f, Ui.DIM);
        mid.addView(subTitle);
        bar.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout wrap = Ui.col(this);
        wrap.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View hair = new View(this);
        hair.setBackgroundColor(Ui.STROKE2);
        wrap.addView(hair, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));
        return wrap;
    }

    /** 顶部那行小字：远端版写「设备 · 192.168.x.x:7788」，本地版写「本地 harness · 127.0.0.1:7788」。 */
    private String deviceLine() {
        if (active == null || active.addr().length() == 0) return "未配对设备";
        return (Cores.get().local() ? "本地 harness · " : "设备 · ") + active.addr();
    }

    /** 外观：跟随系统 / 深色 / 浅色。改完立刻重建自己（颜色都是构造时定的）。 */
    private View appearanceRow() {
        final String cur = themePref();
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        r.setBackground(Ui.bg(Ui.SURF2, Ui.R_CARD, this));
        LinearLayout tx = Ui.col(this);
        tx.addView(Ui.tv(this, "外观", 15.5f, Ui.TEXT));
        tx.addView(Ui.tv(this, themeLabel(cur), 12f, Ui.MUT));
        r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ui.tv(this, "\u203A", 17f, Ui.MUT));
        Ui.press(r, this, Ui.SURF3, Ui.R_CARD);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String[] keys = {"system", "dark", "light"};
                String[] labels = {"跟随系统", "深色", "浅色"};
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("外观")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                new Store(SettingsActivity.this).set("theme", keys[w]);
                                Ui.themeDirty = true;               // 主界面回来时自己重建
                                recreate();                          // 设置页当场重建
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        return r;
    }

    private String themePref() {
        try {
            return new Store(this).get("theme", "system");
        } catch (Throwable t) {
            return "system";
        }
    }

    private String themeLabel(String pref) {
        if ("dark".equals(pref)) return "深色";
        if ("light".equals(pref)) return "浅色";
        return "跟随系统";
    }

    /**
     * 本地版专有的「本地环境」卡：远端版不显示（两套 app 的 UI 共用，只在这里分叉）。
     * 目前落地到 proot 自检；rootfs / Node / harness 的安装与启动接着往这儿加。
     */
    private View localCard() {
        LinearLayout card = Ui.col(this);
        card.addView(Ui.tv(this, "本地环境", 15.5f, Ui.TEXT));
        final LocalEnv env = Cores.get().runtime();
        card.addView(Ui.tv(this, env == null ? "" : env.state(this), 12f, Ui.MUT));
        LinearLayout row = Ui.row(this);
        TextView check = Ui.tv(this, "环境自检", 12.5f, Ui.ACCENT);
        check.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 14), 0);
        check.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final TextView out = Ui.tv(SettingsActivity.this, "", 11.5f, Ui.DIM);
                out.setTypeface(android.graphics.Typeface.MONOSPACE);
                Toast.makeText(SettingsActivity.this, env == null ? "" : env.selfCheck(SettingsActivity.this),
                        Toast.LENGTH_LONG).show();
            }
        });
        row.addView(check);
        card.addView(row);
        return card;
    }


    /** 一行分区下面的说明：能拿实时值就给实时值，别让用户点进去才知道自己连的是哪台。 */
    private String hint(String id) {
        if ("devctl".equals(id)) {
            String a = active == null ? "" : active.addr();
            String w = active == null ? "" : store.get("web:" + active.name, "");
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
        int keep = Cores.get().local() ? 4 : 2;    // 保留头部（本地版多一张卡）
        while (list.getChildCount() > keep) list.removeViewAt(keep);
        for (int i = 0; i < ids.length; i++) list.addView(item(ids[i], labels[i], i));
        list.addView(Ui.gap(this, 10));
    }

    /** 分区表问 host（settings.sections）；host 没实现就保持内置表。 */
    private void loadSections() {
        if (active == null || active.host.length() == 0) return;
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
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        r.setBackground(Ui.bg(Ui.SURF2, Ui.R_CARD, this));
        LinearLayout tx = Ui.col(this);
        tx.addView(Ui.tv(this, "原版网页", 15.5f, Ui.TEXT));
        String u = webUrl();
        TextView sub = Ui.tv(this, u.length() == 0 ? "自动 · 开了网页窗口就直接看原版界面" : u, 12f, Ui.MUT);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);      // 那串 token 别再折三行
        tx.addView(sub);
        r.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView edit = Ui.tv(this, u.length() == 0 ? "设置" : "改", 12.5f, Ui.ACCENT);
        edit.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        edit.setBackground(Ui.bg(Ui.SURF3, 12, this));
        r.addView(edit);
        Ui.press(r, this, Ui.SURF3, Ui.R_CARD);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                webDialog();
            }
        });
        return r;
    }

    private String key() {
        return "web:" + (active == null ? "" : active.name);
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
        Dsh d = Dsh.open(active, 6000, "DshConsole-settings", "android");
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
