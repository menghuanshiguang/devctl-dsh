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
    /** 「DSH 设置」那张分组卡片（分区行往这里填）。 */
    private LinearLayout secCard;
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
        col.addView(topBar());

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout body = Ui.col(this);
        int ph = Ui.dp(this, 8);
        body.setPadding(ph, Ui.dp(this, 2), ph, Ui.dp(this, 28));
        sc.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = body;

        // 应用
        LinearLayout appCard = group(body, "应用");
        appCard.addView(appearanceItem());
        divider(appCard);
        appCard.addView(webRow());

        // DSH 设置（分区表由 host 给，或内置表）
        secCard = group(body, "DSH 设置");

        // 关于
        LinearLayout aboutCard = group(body, "关于");
        aboutCard.addView(infoRow("设备", deviceLine()));
        divider(aboutCard);
        aboutCard.addView(infoRow("运行模式", Cores.get().local() ? "本地 harness（app 内）" : "远端（局域网那台）"));

        if (Cores.get().local()) {                     // 本地版才有的那张卡
            body.addView(localCard());
        }
        return col;
    }

    /** 只读信息行（无 chevron、点不动）。 */
    private View infoRow(String key, String value) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 50));
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        r.addView(Ui.tv(this, key, 16f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = Ui.tv(this, value == null ? "" : value, 15f, Ui.MUT);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(v);
        return r;
    }

    /**
     * 一行（照参考图）：[图标 24dp] gap [标题 16f] ... [右侧值 15f 灰] [chevron]。
     * 不再"每行一张卡+副标题"，而是紧凑一行，调用方负责放进分组卡片里。
     */
    private View item(final String id, final String label, final int index) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 54));
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        TextView icon = Ui.tv(this, glyphOf(id), 15f, Ui.DIM);
        icon.setGravity(Gravity.CENTER);
        r.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.tv(this, label, 16f, Ui.TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 12);
        r.addView(title, tlp);

        String value = hint(id);
        if (value.length() > 0) {
            TextView v = Ui.tv(this, DshConsole.clamp(value, 18), 15f, Ui.MUT);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.rightMargin = Ui.dp(this, 4);
            r.addView(v, vlp);
        }
        r.addView(Ui.tv(this, "\u203A", 17f, Ui.MUT));
        Ui.press(r, this, Ui.SURF3, 0);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openPanel(id, label, index);
            }
        });
        return r;
    }

    /** 每行左边那个小图标（没有图标资源，就用单色几何字符，深色下不会跳出彩色 emoji）。 */
    private static String glyphOf(String id) {
        if ("general".equals(id)) return "\u2699";
        if ("models".equals(id)) return "\u25C7";
        if ("plugins".equals(id)) return "\u29C9";
        if ("presets".equals(id)) return "\u2726";
        if ("devctl".equals(id)) return "\u2318";
        if ("rules".equals(id)) return "\u2696";
        if ("market".equals(id)) return "\u229E";
        if ("cards".equals(id)) return "\u25A6";
        return "\u25CB";
    }

    /** 顶栏（照参考图）：左边一个圆形返回键，标题居中，无发丝线。 */
    private View topBar() {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        int vp = Ui.dp(this, 6);
        bar.setPadding(Ui.dp(this, 12), vp, Ui.dp(this, 12), vp);

        TextView back = Ui.tv(this, "\u2039", 22f, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.bg(Ui.SURF2, 20, this));
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 40));
        blp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, blp);

        TextView title = Ui.tv(this, "设置", 18f, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams tlp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        bar.addView(title, tlp);
        return bar;
    }

    /** 分组卡片：一个圆角容器装若干行，行之间是"从文字处开始"的发丝线（照参考图）。 */
    private LinearLayout group(LinearLayout parent, String header) {
        LinearLayout wrap = Ui.col(this);
        if (header != null && header.length() > 0) {
            TextView h = Ui.tv(this, header, 12.5f, Ui.MUT);
            h.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), 0, Ui.dp(this, 7));
            wrap.addView(h);
        }
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.bg(Ui.SURF2, 14, this));
        card.setClipToOutline(true);
        wrap.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        parent.addView(wrap);
        return card;
    }

    /** 组内分隔线：左边从文字起始处缩进（图标那一段留白），跟参考图一致。 */
    private void divider(LinearLayout card) {
        if (card.getChildCount() == 0) return;
        View line = new View(this);
        line.setBackgroundColor(Ui.STROKE2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f)));
        lp.leftMargin = Ui.dp(this, 52);
        card.addView(line, lp);
    }

    /** 顶部那行小字：远端版写「设备 · 192.168.x.x:7788」，本地版写「本地 harness · 127.0.0.1:7788」。 */
    private String deviceLine() {
        if (active == null || active.addr().length() == 0) return "未配对设备";
        return (Cores.get().local() ? "本地 harness · " : "设备 · ") + active.addr();
    }

    /** 外观：跟随系统 / 深色 / 浅色。改完立刻重建自己（颜色都是构造时定的）。 */
    /** 外观行（照参考图）：图标 + 标题 + 右侧当前值 + chevron，点开三选一。 */
    private View appearanceItem() {
        final String cur = themePref();
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 54));
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        TextView icon = Ui.tv(this, "\u263D", 15f, Ui.DIM);
        icon.setGravity(Gravity.CENTER);
        r.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 12);
        r.addView(Ui.tv(this, "外观", 16f, Ui.TEXT), tlp);

        TextView v = Ui.tv(this, themeLabel(cur), 15f, Ui.MUT);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.rightMargin = Ui.dp(this, 4);
        r.addView(v, vlp);
        r.addView(Ui.tv(this, "\u203A", 17f, Ui.MUT));

        Ui.press(r, this, Ui.SURF3, 0);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v2) {
                final String[] keys = {"system", "dark", "light"};
                String[] labels = {"跟随系统", "深色", "浅色"};
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("外观")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                new Store(SettingsActivity.this).set("theme", keys[w]);
                                Ui.themeDirty = true;
                                recreate();
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
        if (secCard == null) return;
        secCard.removeAllViews();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) divider(secCard);
            secCard.addView(item(ids[i], labels[i], i));
        }
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

    /** 原版网页：同一行样式，右侧显示"自动/已设置"。 */
    private View webRow() {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 54));
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        TextView icon = Ui.tv(this, "\u25A6", 15f, Ui.DIM);
        icon.setGravity(Gravity.CENTER);
        r.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 12);
        r.addView(Ui.tv(this, "原版网页", 16f, Ui.TEXT), tlp);

        String u = webUrl();
        r.addView(Ui.tv(this, u.length() == 0 ? "自动" : "已设置", 15f, Ui.MUT),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(Ui.tv(this, "\u203A", 17f, Ui.MUT));
        Ui.press(r, this, Ui.SURF3, 0);
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
