package com.minis.dshconsole;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 设置二级页：一个分区的正文。
 * 优先直接加载 DSH 原版网页（任何插件注册的分区都原样出现，不用逐个适配）；
 * 网页地址没配或加载失败，就回落成 app 自己的结构化渲染。
 */
public class SettingsPanelActivity extends Activity {

    private String id = "devctl";
    private String label = "设置";
    private WebView web;
    private Handler ui;
    private Store store;
    private Store.Dev dev;
    private FrameLayout root;
    private boolean revealToken = false;
    private boolean webMode = false;
    private boolean sideHidden = true;
    private String webUrl = "";
    /** 原版网页拿不到时的原因说明，进结构化视图时当第一张卡显示。 */
    private String webNotice = null;
    /** 一级页列表里的序号：注入脚本文字对不上时按它兜底点第几个分区。 */
    private int index = -1;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        ui = new Handler(Looper.getMainLooper());
        store = new Store(this);
        String nm = store.def("dsh");
        dev = store.find("dsh", nm);
        if (dev == null) dev = new Store.Dev();
        if (getIntent() != null) {
            String s = getIntent().getStringExtra("id");
            if (s != null && s.length() > 0) id = s;
            String l = getIntent().getStringExtra("label");
            if (l != null && l.length() > 0) label = l;
            index = getIntent().getIntExtra("index", -1);
        }
        try {
            webUrl = store.get("web:" + dev.name, "");
        } catch (Throwable ignored) {
        }
        setTitle(label);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        start();
    }

    private void newWeb() {
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 关键：让 WebView 用真正的手机宽度布局（否则 DSH 的桌面版排版会被挤成竖条），
        // 并锁掉系统字体缩放。
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 注入脚本的调试点（console.log('[ds]...')）直接进 logcat：adb logcat -s DsWeb
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("DsWeb", m.message() + " @" + m.lineNumber());
                return true;
            }
        });
    }

    private void mount(View extra) {
        root.removeAllViews();
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (extra != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.bottomMargin = Ui.dp(this, 18);
            lp.rightMargin = Ui.dp(this, 12);
            root.addView(extra, lp);
        }
    }

    private void start() {
        if (webUrl.length() > 0) {
            useWeb();
            return;
        }
        useStructured();
        askWebWindow();
    }

    /** 问 host 一句「网页窗口开了没、在几号端口」——端口由 host 的 hello 带回来，不猜。 */
    private void askWebWindow() {
        if (dev == null || dev.host.length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                String base = null;
                String why = "";
                Dsh live = null;
                try {
                    live = Dsh.open(dev, 5000, "DshConsole-settings", "android");
                    JSONObject w = live.hello == null ? null : live.hello.optJSONObject("web");
                    if (w != null) {
                        int p = w.optInt("port", 0);
                        String url = w.optString("url", "");
                        if (w.optBoolean("ready", false) && url.length() > 0) base = url;
                        else if (w.optBoolean("ready", false) && p > 0) base = "http://" + dev.host + ":" + p + "/?token=" + dev.token;
                        else why = webReason(w.optString("reason", "")) + webTrace(w);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (live != null) {
                        try {
                            live.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                final String found = base;
                final String reason = why;
                if (found == null && reason.length() > 0) {
                    // host 明确说了「没开」，那就别再猜端口了。
                    ui.post(new Runnable() {
                        public void run() {
                            webNotice = reason;
                            Toast.makeText(SettingsPanelActivity.this, "DSH 原版网页：" + reason, Toast.LENGTH_LONG).show();
                            if (!webMode) request(id);
                        }
                    });
                    return;
                }
                if (found != null) {
                    ui.post(new Runnable() {
                        public void run() {
                            webUrl = found.indexOf("token=") >= 0 ? found : found + "/?token=" + dev.token;
                            if (!webMode || web == null) useWeb();
                        }
                    });
                    return;
                }
                probeWindow();          // 老版 host：hello 里没有 web 字段，退回探测
            }
        }).start();
    }

    private String webReason(String reason) {
        if ("host-web-not-found".equals(reason)) return "host 上没找到 DSH 网页服务，先在 host 上把 DSH 的网页窗口开起来";
        if ("discovery-failed".equals(reason)) return "没扫到 DSH 网页服务，检查 host 上的 DSH 网页窗口";
        if ("searching".equals(reason)) return "host 还在找自己的网页服务，等一下再进";
        return "网页窗口没开（" + reason + "）";
    }

    /** host 顺手带回来的诊断：目标地址 + 扫到过哪些端口。 */
    private String webTrace(JSONObject w) {
        String out = "";
        String t = w.optString("target", "");
        if (t.length() > 0) out += " · 目标 " + t;
        org.json.JSONArray seen = w.optJSONArray("seen");
        if (seen != null && seen.length() > 0) {
            out += " · 有应答的端口 ";
            for (int i = 0; i < seen.length(); i++) {
                JSONObject e = seen.optJSONObject(i);
                if (e == null) continue;
                out += (i > 0 ? "," : "") + e.optInt("port", 0) + "(" + e.optInt("status", 0) + ")";
            }
        } else {
            out += " · 扫过的端口一个都没应答";
        }
        String u = w.optString("url", "");
        if (u.length() > 0) out += " · 可直接打开 " + u;
        return out;
    }

    /** 问 host 一句「网页窗口开了没」，开了就切原版页面（失败就留在结构化视图）。 */
    private void probeWindow() {
        if (dev == null || dev.host.length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                final String health = windowBase() + "/__devctl-web/health?token=" + dev.token;
                boolean ok = false;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(health).openConnection();
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(2000);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    JSONObject j = new JSONObject(bo.toString("UTF-8"));
                    ok = j.optBoolean("ok", false);
                } catch (Throwable ignored) {
                }
                if (!ok) return;
                ui.post(new Runnable() {
                    public void run() {
                        webUrl = windowBase() + "/?token=" + dev.token;
                        if (!webMode || web == null) useWeb();
                    }
                });
            }
        }).start();
    }

    /** 网页窗口的根地址：host 地址换端口 7790（web.port 的默认值）。 */
    private String windowBase() {
        String addr = dev == null ? "" : dev.addr();
        int i = addr.lastIndexOf(':');
        String host = i > 0 ? addr.substring(0, i) : addr;
        return "http://" + host + ":7790";
    }

    // —— 模式一：直接吃 DSH 原版网页 ——

    private void useWeb() {
        webMode = true;
        newWeb();
        web.setWebViewClient(new WebViewClient() {
            public void onPageFinished(WebView v, String url) {
                inject();
            }

            public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && url.equals(webUrl)) fallback("网页打不开，" + desc + " · 已切结构化");
            }
        });
        mount(menuBtn());
        web.loadUrl(webUrl);
    }

    /** 网页加载完：进设置 → 点目标分区 → 撑成全屏页面。 */
    private void inject() {
        web.evaluateJavascript(DsWeb.hideWidgets(), null);
        DsWeb.apply(web, label, id, index);
        confirmSection();
    }

    /**
     * 几秒后问一句「现在停在哪个分区」：对不上就在界面上说清楚，
     * 别让用户对着「通用设置」发呆还以为自己点错了。
     */
    private void confirmSection() {
        if (web == null || (label.length() == 0 && id.length() == 0)) return;
        ui.postDelayed(new Runnable() {
            public void run() {
                if (web == null) return;
                DsWeb.probe(web, new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String value) {
                        final String cur = unquote(value);
                        if (cur.length() == 0) return;
                        if (norm(cur).equals(norm(label)) || norm(cur).equals(norm(id))) return;
                        ui.post(new Runnable() {
                            public void run() {
                                Toast.makeText(SettingsPanelActivity.this,
                                        "没跳到「" + label + "」，在顶部胶囊里点一下（现在：" + cur + "）",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        }, 7600);              // 等注入脚本那段 7 秒纠偏窗口结束，再看它到底停在哪
    }

    private static String unquote(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        return s;
    }

    private static String norm(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '(' || c == ')' || c == '（' || c == '）' || c == '·' || c == '-' || c == '_') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private String hideJs() {
        return DsWeb.hideWidgets();
    }

    /** 右上角小圆钮：显示 / 隐藏原版网页自带的侧边栏。 */
    private View menuBtn() {
        TextView b = new TextView(this);
        b.setText(sideHidden ? "▣" : "☰");
        b.setTextSize(15f);
        b.setTextColor(Ui.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.bg(Ui.BG, 20, this, Ui.STROKE, 1));
        Ui.press(b, this, Ui.SURF3, 20);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sideHidden = !sideHidden;
                ((TextView) v).setText(sideHidden ? "▣" : "☰");
                if (web == null) return;
                if (sideHidden) {
                    DsWeb.apply(web, label, id, index);
                    Toast.makeText(SettingsPanelActivity.this, "全屏设置页", Toast.LENGTH_SHORT).show();
                } else {
                    DsWeb.restore(web);
                    Toast.makeText(SettingsPanelActivity.this, "DSH 原版弹层", Toast.LENGTH_SHORT).show();
                }
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Toast.makeText(SettingsPanelActivity.this, "改用 app 界面", Toast.LENGTH_SHORT).show();
                useStructured();
                return true;
            }
        });
        return b;
    }

    private void fallback(String msg) {
        if (!webMode) return;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        useStructured();
    }

    // —— 模式二：app 自己渲染（host 给结构化 JSON） ——

    private void useStructured() {
        webMode = false;
        newWeb();
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "dshNative");
        web.loadDataWithBaseURL("dsh://settings/", SHELL, "text/html", "utf-8", null);
        mount(null);
        ui.postDelayed(new Runnable() {
            public void run() {
                request(id);
            }
        }, 300);
    }

    /** JS 回调入口（跑在 WebView 线程上，别直接碰 UI）。 */
    public class Bridge {
        @JavascriptInterface
        public void act(final String id2, final String action, final String arg) {
            doAct(id2, action, arg);
        }
    }

    /** 读一个分区的结构化数据。 */
    private void request(final String sid) {
        id = sid;
        new Thread(new Runnable() {
            public void run() {
                JSONObject panel = null;
                String err = null;
                try {
                    panel = panelOf(sid);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final JSONObject fp = panel;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fp != null) {
                            if (webNotice != null) {
                                try {
                                    org.json.JSONArray blocks = fp.optJSONArray("blocks");
                                    if (blocks == null) {
                                        blocks = new org.json.JSONArray();
                                        fp.put("blocks", blocks);
                                    }
                                    JSONObject tip = new JSONObject();
                                    tip.put("title", "原版网页不可用");
                                    tip.put("text", webNotice);
                                    org.json.JSONArray out = new org.json.JSONArray();
                                    out.put(tip);
                                    for (int i = 0; i < blocks.length(); i++) out.put(blocks.opt(i));
                                    fp.put("blocks", out);
                                } catch (Throwable ignored) {
                                }
                            }
                            js("show(" + fp.toString() + ")");
                            return;
                        }
                        String s = null;
                        try {
                            s = card("读取失败", fe).toString();
                        } catch (Throwable ignored) {
                        }
                        js("show(" + (s == null ? "{}" : s) + ")");
                    }
                });
            }
        }).start();
    }

    private JSONObject panelOf(String sid) throws Exception {
        if (dev != null && dev.host.length() > 0) {
            try {
                JSONObject params = new JSONObject();
                params.put("id", sid);
                JSONObject r = call("settings.panel", params);
                JSONObject p = r == null ? null : r.optJSONObject("panel");
                if (p != null) return p;
            } catch (Throwable ignored) {
                // host 侧还没实现 settings.panel
            }
        }
        return localPanel(sid);
    }

    /** 本地兜底：devctl 分区用 app 自己的配对记录 + 一次握手探测。 */
    private JSONObject localPanel(String sid) throws Exception {
        if ("devctl".equals(sid)) return devctlPanel();
        JSONObject o = new JSONObject();
        o.put("title", sid);
        JSONArray bs = new JSONArray();
        JSONObject c = new JSONObject();
        c.put("kind", "card");
        c.put("text", "这一分区由 DSH 主程序提供。");
        c.put("note", "host 那侧开了网页窗口就会自动换成 DSH 自己的页面；也可以在上一页手填网页地址。");
        bs.put(c);
        o.put("blocks", bs);
        return o;
    }

    /** devctl 分区：app 侧就能实时算出来的那部分（配对地址、令牌、配对命令、Host 版本）。 */
    private JSONObject devctlPanel() throws Exception {
        String ver = "";
        String host = "";
        Dsh live = null;
        try {
            if (dev != null && dev.host.length() > 0) {
                live = Dsh.open(dev, 5000, "DshConsole-settings", "android");
                JSONObject h = live.hello == null ? null : live.hello.optJSONObject("host");
                if (h != null) {
                    ver = h.optString("version", "");
                    host = h.optString("name", "");
                    String plat = h.optString("platform", "");
                    if (host.length() > 0 && plat.length() > 0) host = host + " · " + plat;
                }
                if (ver.length() == 0 && live.hello != null) ver = live.hello.optString("version", "");
            }
        } catch (Throwable ignored) {
        } finally {
            if (live != null) {
                try {
                    live.close();
                } catch (Throwable ignored) {
                }
            }
        }

        JSONObject o = new JSONObject();
        o.put("title", "devctl 远程控制");
        o.put("subtitle", "从手机或其他设备用 dshctl 驱动这台 DSH");
        JSONArray blocks = new JSONArray();

        JSONObject c1 = new JSONObject();
        c1.put("kind", "card");
        JSONArray rows = new JSONArray();
        rows.put(row("设备", dev == null ? "" : dev.name, null, null));
        rows.put(row("地址", dev == null ? "" : dev.addr(), null,
                new JSONArray().put(btn("复制", "copy", dev == null ? "" : dev.addr()))));
        if (host.length() > 0) rows.put(row("Host", host, null, null));
        if (ver.length() > 0) rows.put(row("版本", ver, null, null));
        String token = dev == null ? "" : dev.token;
        JSONArray tbtns = new JSONArray();
        tbtns.put(btn(revealToken ? "隐藏" : "显示", revealToken ? "hide-token" : "reveal-token", ""));
        tbtns.put(btn("复制", "copy", token));
        rows.put(row("访问令牌", revealToken ? token : mask(token), null, tbtns));
        c1.put("rows", rows);
        blocks.put(c1);

        JSONObject c2 = new JSONObject();
        c2.put("kind", "card");
        c2.put("title", "配对命令");
        c2.put("text", "在另一台设备上装好 dshctl 后运行：");
        String nm = dev == null || dev.name.length() == 0 ? "home" : dev.name;
        String cmd = "dshctl add " + nm + " " + (dev == null ? "" : dev.addr()) + " --token " + token;
        JSONArray r2 = new JSONArray();
        r2.put(row("命令", cmd, null, new JSONArray().put(btn("复制", "copy", cmd))));
        c2.put("rows", r2);
        blocks.put(c2);

        o.put("blocks", blocks);
        return o;
    }

    private JSONObject row(String k, String v, String badge, JSONArray btns) throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", k);
        o.put("v", v == null ? "" : v);
        if (badge != null) o.put("badge", badge);
        if (btns != null) o.put("btns", btns);
        return o;
    }

    private JSONObject btn(String label2, String action, String arg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("label", label2);
        o.put("action", action);
        o.put("arg", arg == null ? "" : arg);
        return o;
    }

    private String mask(String t) {
        if (t == null || t.length() == 0) return "—";
        if (t.length() <= 8) return "••••";
        return t.substring(0, 4) + "••••••••" + t.substring(t.length() - 4);
    }

    private void doAct(final String sid, final String action, final String arg) {
        if ("reveal-token".equals(action) || "hide-token".equals(action)) {
            revealToken = "reveal-token".equals(action);
            request(sid);
            return;
        }
        if ("copy".equals(action)) {
            copy(arg);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String msg = null;
                boolean reload = false;
                try {
                    JSONObject params = new JSONObject();
                    params.put("id", sid);
                    params.put("action", action);
                    params.put("arg", arg == null ? "" : arg);
                    JSONObject r = call("settings.action", params);
                    if (r != null) {
                        msg = r.optString("message", "");
                        reload = r.optBoolean("reload", false);
                    }
                } catch (Throwable t) {
                    msg = "host 暂不支持该操作";
                }
                final String fm = msg;
                final boolean fr = reload;
                ui.post(new Runnable() {
                    public void run() {
                        if (fm != null && fm.length() > 0) Toast.makeText(SettingsPanelActivity.this, fm, Toast.LENGTH_SHORT).show();
                        if (fr) request(sid);
                    }
                });
            }
        }).start();
    }

    private void copy(final String text) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("dsh", text == null ? "" : text));
                    Toast.makeText(SettingsPanelActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(SettingsPanelActivity.this, "复制失败", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (web != null) web.evaluateJavascript(code, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private JSONObject card(String title, String note) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", title == null ? "设置" : title);
        JSONArray bs = new JSONArray();
        JSONObject c = new JSONObject();
        c.put("kind", "card");
        if (note != null && note.length() > 0) c.put("note", note);
        bs.put(c);
        o.put("blocks", bs);
        return o;
    }

    /** 一次性连接：发一条请求就关掉。 */
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

    /** 页面内小工具：__dshSide(收起原版侧边栏) / __dshPick(点中目标分区)。 */
    private static final String HELPER =
            "(function(){"
            + "window.__dshHidden=[];"
            + "window.__dshSide=function(on){try{"
            + "if(!on){(window.__dshHidden||[]).forEach(function(e){try{e.style.display=''}catch(x){}});"
            + "window.__dshHidden=[];return 1;}"
            + "var vw=window.innerWidth,vh=window.innerHeight,c=[],i,e,r,hid=[],sel;"
            + "sel=document.querySelectorAll('nav,aside,[class*=sidebar],[class*=sider],[class*=side-nav]');"
            + "for(i=0;i<sel.length;i++)c.push(sel[i]);"
            + "var k=document.body?document.body.children:[];"
            + "for(i=0;i<k.length;i++)c.push(k[i]);"
            + "for(i=0;i<c.length;i++){e=c[i];if(!e.getBoundingClientRect)continue;r=e.getBoundingClientRect();"
            + "if(r.width>0&&r.width<=vw*0.62&&r.height>=vh*0.5&&r.height>r.width&&r.left<=vw*0.12){"
            + "e.style.display='none';hid.push(e);}}"
            + "window.__dshHidden=hid;return hid.length;"
            + "}catch(err){return -1}};"
            + "window.__dshPick=function(want){try{if(window.__dshPicked)return 1;"
            + "var a=document.querySelectorAll('a,button,li,div,span,p'),w=String(want).replace(/\\s+/g,''),i,e,t;"
            + "for(i=0;i<a.length;i++){e=a[i];if(e.children.length>0)continue;"
            + "t=(e.textContent||'').replace(/\\s+/g,'');"
            + "if(t===w){var c=e.closest?e.closest('a,button,li,[role=tab],[role=menuitem]'):null;"
            + "(c||e).click();window.__dshPicked=1;return 1;}}return 0}catch(err){return 0}};"
            + "return 1})()";

    /** 结构化回落的页面骨架：没有侧边栏，只渲染分区正文。 */
    private static final String SHELL =
            "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
            + "html,body{margin:0;height:100%;background:#fff;color:#1c1c1e;font:15px/1.5 -apple-system,Roboto,'Noto Sans SC',sans-serif}"
            + "#main{height:100%;overflow:auto;padding:14px 14px 48px}"
            + "h1{font-size:18px;margin:2px 0 4px}"
            + ".sub{color:#6b6b70;font-size:12.5px;margin-bottom:12px;line-height:1.5}"
            + ".card{background:#fbfbfc;border:1px solid #e7e7ea;border-radius:12px;padding:12px;margin-bottom:12px}"
            + ".ct{font-size:13.5px;font-weight:600;margin-bottom:6px}"
            + ".row{display:flex;flex-wrap:wrap;align-items:center;gap:6px 8px;margin:8px 0;font-size:13.5px}"
            + ".k{color:#6b6b70;width:58px;flex:none;font-size:12.5px}"
            + ".v{flex:1 1 116px;min-width:116px;word-break:break-all;overflow-wrap:anywhere}"
            + ".note{color:#9a9aa0;font-size:12px;margin-top:8px;line-height:1.5}"
            + "button{font-size:12.5px;padding:5px 11px;border-radius:9px;border:1px solid #e7e7ea;background:#fff;color:#1c1c1e;flex:none}"
            + ".badge{color:#12a150;font-size:12px;margin-left:6px}"
            + ".svg{width:170px;height:170px;margin:8px auto 0}.svg svg{width:100%;height:100%}"
            + "</style></head><body><div id='main'></div><script>"
            + "function $(i){return document.getElementById(i)}"
            + "function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}"
            + "function show(p){var m=$('main');m.innerHTML='';m.appendChild(el('h1',null,p.title||'设置'));"
            + "if(p.subtitle)m.appendChild(el('div','sub',p.subtitle));"
            + "(p.blocks||[]).forEach(function(k){m.appendChild(card(k))})}"
            + "function card(k){var c=el('div','card');if(k.title)c.appendChild(el('div','ct',k.title));"
            + "if(k.text)c.appendChild(el('div','sub',k.text));"
            + "(k.rows||[]).forEach(function(r){c.appendChild(line(r))});"
            + "if(k.svg){var w=el('div','svg');w.innerHTML=k.svg;c.appendChild(w)}"
            + "if(k.note)c.appendChild(el('div','note',k.note));return c}"
            + "function line(r){var d=el('div','row');d.appendChild(el('div','k',r.k));var v=el('div','v');"
            + "v.appendChild(el('span',null,r.v));if(r.badge)v.appendChild(el('span','badge','● '+(r.badge||'')));"
            + "d.appendChild(v);(r.btns||[]).forEach(function(x){var t=el('button',null,x.label);"
            + "t.onclick=function(){dshNative.act(window.cur||'devctl',x.action,x.arg||'')};d.appendChild(t)});return d}"
            + "</script></body></html>";
}
