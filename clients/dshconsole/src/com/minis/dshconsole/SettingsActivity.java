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
    /** 当前生效的设备：本地模式=回环那台，远端模式=选中的那台。一级页所有读取都走它。 */
    private Store.Dev active;
    private boolean fromHost = false;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        ui = new Handler(Looper.getMainLooper());
        store = new Store(this);
        String name = store.def("dsh");
        dev = store.find("dsh", name);
        if (dev == null) dev = new Store.Dev();
        active = MainActivity.activeDevOf(store, name);
        if (active == null) active = dev;
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
        subTitle = Ui.tv(this, deviceLine(), Ui.FS_SMALL, Ui.MUT);
        col.addView(subTitle);
        col.addView(Ui.gap(this, 10));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        list = Ui.col(this);
        list.addView(modeCard());          // 运行模式：协议头指向远端还是本机
        list.addView(Ui.gap(this, 10));
        list.addView(Ui.tv(this, "DSH 原版设置（二级页）", 12f, Ui.MUT));
        list.addView(Ui.gap(this, 6));
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

    // ==================== 运行模式：远端（局域网 PC）/ 本地（这台手机上的 harness） ====================

    private LinearLayout modeCard;
    /** 顶部「设备 · xx / 本地 harness · xx」那行。 */
    private TextView subTitle;

    /** 两枚胶囊 + 一行说明：远端 走局域网那台；本地 走本机回环，协议一模一样。 */
    private View modeCard() {
        modeCard = Ui.col(this);
        rebuildModeCard();
        return modeCard;
    }

    private void rebuildModeCard() {
        if (modeCard == null) return;
        modeCard.removeAllViews();
        boolean local = MainActivity.MODE_LOCAL.equals(store.get("runMode", MainActivity.MODE_REMOTE));

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(this, "运行模式", 15.5f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(chip("远端", !local, new Runnable() {
            public void run() {
                store.set("runMode", MainActivity.MODE_REMOTE);
                onModeChanged();
            }
        }));
        head.addView(chip("本地", local, new Runnable() {
            public void run() {
                store.set("runMode", MainActivity.MODE_LOCAL);
                onModeChanged();
                checkLocalOrGuide();
            }
        }));
        modeCard.addView(head);

        String host = store.get("local:host", "127.0.0.1");
        String port = store.get("local:port", "7788");
        String tok = store.get("local:token", "");
        String line = local
                ? "本地 · " + host + ":" + port
                        + (tok.length() > 0 ? " · 已配令牌" : " · 免令牌（同一台机器，回环免鉴权）")
                : "远端 · " + (dev == null || dev.addr().length() == 0 ? "未配对" : dev.addr());
        modeCard.addView(Ui.tv(this, line, 12f, local ? Ui.AMBER : Ui.MUT));
        if (local) {
            LinearLayout links = Ui.row(this);
            TextView cfg = Ui.tv(this, "本机地址 / 令牌…", 12f, Ui.ACCENT);
            cfg.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 14), 0);
            cfg.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    editLocal();
                }
            });
            links.addView(cfg);
            TextView guide = Ui.tv(this, "本地环境引导…", 12f, Ui.ACCENT);
            guide.setPadding(0, Ui.dp(this, 6), 0, 0);
            guide.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showLocalGuide(null);
                }
            });
            links.addView(guide);
            modeCard.addView(links);
        }
    }

    /** 模式一换：重新解析生效设备、刷新顶部那行、重新问分区表（本地/远端可能列的不一样）。 */
    private void onModeChanged() {
        active = MainActivity.activeDevOf(store, store.def("dsh"));
        if (active == null) active = dev;
        rebuildModeCard();
        if (subTitle != null) subTitle.setText(deviceLine());
        loadSections();
    }

    /** 顶部那行小字：本地模式写「本地 harness · 127.0.0.1:7788」，远端写选中那台。 */
    private String deviceLine() {
        if (active == null || active.addr().length() == 0) return "未配对设备";
        return (MainActivity.isLocal(store) ? "本地 harness · " : "设备 · ") + active.addr();
    }

    private TextView chip(String text, boolean on, final Runnable cb) {
        final TextView t = Ui.tv(this, text, 12.5f, on ? 0xFF0E1116 : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        t.setBackground(on ? Ui.bg(Ui.ACCENT, 14, this) : Ui.bg(Ui.SURF2, 14, this, Ui.STROKE, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (cb != null) cb.run();
            }
        });
        return t;
    }

    /** 切到本地后探一下：连不上就把安装引导摆出来，别让用户对着"连不上"发呆。 */
    private void checkLocalOrGuide() {
        final Store.Dev d = MainActivity.localDevOf(store);
        new Thread(new Runnable() {
            public void run() {
                final String why = probeLocal(d);
                if (why == null) return;                 // 已经通着，什么都不用说
                ui.post(new Runnable() {
                    public void run() {
                        showLocalGuide(why);
                    }
                });
            }
        }).start();
    }

    /** 探一下本地端口；通了返回 null，不通返回人话原因。 */
    private String probeLocal(Store.Dev d) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(d.host, d.port), 1200);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (m.contains("refused")) return d.addr() + " 上没有东西在监听";
            if (m.contains("timeout") || m.contains("timed out")) return d.addr() + " 没响应（超时）";
            return "连不上 " + d.addr();
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 本地 harness 的安装引导。
     * app 自己没法在 Android 上跑 Node（harness 的原生模块只有 glibc 构建），
     * 所以这里把"在这台手机上准备一个 Linux 环境"的每一步摆出来，边装边检测。
     */
    private void showLocalGuide(String why) {
        final Store.Dev d = MainActivity.localDevOf(store);
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 8), p, 0);

        ScrollView sc = new ScrollView(this);
        LinearLayout inner = Ui.col(this);
        sc.addView(inner);

        inner.addView(Ui.tv(this, why == null ? ("目标 " + d.addr()) : ("状态 · " + why),
                12.5f, why == null ? Ui.MUT : Ui.AMBER));
        inner.addView(Ui.gap(this, 6));
        inner.addView(Ui.tv(this, "本地模式 = 这台手机自己当 host。App 侧已经就绪（免令牌、协议不变），"
                + "还差一个能跑 harness 的 Linux 环境：", 12.5f, Ui.DIM));
        inner.addView(Ui.gap(this, 10));
        inner.addView(step("1", "装 Termux（F-Droid 版）并准备 Ubuntu", "pkg update && pkg install -y proot-distro",
                "proot-distro install ubuntu"));
        inner.addView(step("2", "进 Ubuntu，装 Node 和 git", "proot-distro login ubuntu",
                "apt update && apt install -y nodejs npm git"));
        inner.addView(step("3", "拉仓库并启动本地 harness", "git clone https://github.com/menghuanshiguang/devctl-dsh",
                "sh devctl-dsh/clients/dshconsole/local/start-local.sh devctl-dsh"));
        inner.addView(step("4", "给本地 harness 配一个模型密钥（它是一台独立的 DSH，要用自己的）", null, null));
        inner.addView(Ui.gap(this, 8));
        inner.addView(Ui.tv(this, "跑起来后脚本会打印端口（默认 7788，只绑回环、免令牌）。"
                + "点下面「检测」我就去探一次，通了自动回本地模式。", 12f, Ui.MUT));

        box.addView(sc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        new android.app.AlertDialog.Builder(this)
                .setTitle("本地环境引导")
                .setView(box)
                .setPositiveButton("检测连接", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                final String bad = probeLocal(d);
                                ui.post(new Runnable() {
                                    public void run() {
                                        if (bad == null) {
                                            Toast.makeText(SettingsActivity.this,
                                                    "本地 harness 通了 · " + d.addr(), Toast.LENGTH_LONG).show();
                                            onModeChanged();
                                        } else {
                                            Toast.makeText(SettingsActivity.this, "还没通：" + bad,
                                                    Toast.LENGTH_LONG).show();
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNeutralButton("切回远端", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dlg, int w) {
                        store.set("runMode", MainActivity.MODE_REMOTE);
                        onModeChanged();
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 一行步骤：序号 + 说明 + 可复制的命令。 */
    private View step(String no, String text, String cmd1, String cmd2) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        TextView n = Ui.tv(this, no, 12f, Ui.MUT);
        n.setPadding(0, 0, Ui.dp(this, 8), 0);
        row.addView(n);
        LinearLayout col = Ui.col(this);
        col.addView(Ui.tv(this, text, 12.5f, Ui.TEXT));
        if (cmd1 != null) col.addView(cmdRow(cmd1));
        if (cmd2 != null) col.addView(cmdRow(cmd2));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View cmdRow(final String cmd) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        TextView t = Ui.tv(this, cmd, 11.5f, Ui.DIM);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = Ui.tv(this, "复制", 11.5f, Ui.ACCENT);
        copy.setPadding(Ui.dp(this, 8), 0, 0, 0);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Object svc = getSystemService("clipboard");
                    if (svc instanceof android.content.ClipboardManager) {
                        ((android.content.ClipboardManager) svc).setPrimaryClip(
                                android.content.ClipData.newPlainText("cmd", cmd));
                    }
                    Toast.makeText(SettingsActivity.this, "命令已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
        r.addView(copy);
        return r;
    }

    /** 本地模式的三件套：地址、端口、令牌（本地 harness 起在手机上时用它）。 */
    private void editLocal() {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final android.widget.EditText h = field(store.get("local:host", "127.0.0.1"), "地址");
        final android.widget.EditText pt = field(store.get("local:port", "7788"), "端口");
        final android.widget.EditText tk = field(store.get("local:token", ""), "令牌（本地模式一般不用填）");
        box.addView(h);
        box.addView(pt);
        box.addView(tk);
        box.addView(Ui.tv(this, "本地 harness 用 local/cordis.local.patch.yml 启动时只绑回环、免令牌；"
                + "要接局域网那台才需要填 $DSH_HOME/devctl-dsh.json 里的令牌", 11.5f, Ui.MUT));
        new android.app.AlertDialog.Builder(this)
                .setTitle("本地 harness")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        store.set("local:host", h.getText().toString().trim());
                        store.set("local:port", pt.getText().toString().trim());
                        store.set("local:token", tk.getText().toString().trim());
                        rebuildModeCard();
                        Toast.makeText(SettingsActivity.this, "已保存 · 回主界面会自动重连",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.widget.EditText field(String value, String hint) {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(14f);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUT);
        e.setBackground(Ui.bg(Ui.SURF2, 10, this, Ui.STROKE, 1));
        int p = Ui.dp(this, 10);
        e.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        e.setLayoutParams(lp);
        return e;
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
        rebuildModeCard();                 // 只重画模式卡，分区行从它下面（第 4 个孩子）开始重排
        while (list.getChildCount() > 4) list.removeViewAt(4);
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
