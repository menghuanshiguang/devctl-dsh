package com.minis.dshconsole;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 实时读取 DSH 设置的独立页面：侧边栏＝一级，点进二级；内容用 WebView 渲染、可点。 */
public class SettingsActivity extends Activity {

    private static final String[] D_IDS = {"general", "models", "plugins", "presets", "devctl", "rules", "market", "cards"};
    private static final String[] D_LABELS = {"\u901a\u7528\u8bbe\u7f6e", "\u6a21\u578b", "\u5185\u7f6e\u63d2\u4ef6", "Agent \u9884\u8bbe",
            "devctl", "\u89c4\u5219\u8bbe\u5b9a", "\u63d2\u4ef6\u5e02\u573a", "\u4fa7\u8fb9\u5361\u7247"};

    private WebView web;
    private Handler ui;
    private Store store;
    private Store.Dev dev;
    private String cur = "devctl";
    private boolean revealToken = false;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Handler(Looper.getMainLooper());
        store = new Store(this);
        String name = store.def("dsh");
        dev = store.find("dsh", name);
        if (dev == null) {
            dev = new Store.Dev();
        }
        setTitle("DSH \u8bbe\u7f6e");
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "dshNative");
        web.loadDataWithBaseURL("dsh://settings/", SHELL, "text/html", "utf-8", null);
        setContentView(web);
        ui.postDelayed(new Runnable() {
            public void run() {
                boot();
            }
        }, 320);
    }

    /** JS 回调入口（跑在 WebView 的线程上，别直接碰 UI）。 */
    public class Bridge {
        @JavascriptInterface
        public void section(String id) {
            request(id);
        }

        @JavascriptInterface
        public void act(final String id, final String action, final String arg) {
            doAct(id, action, arg);
        }
    }

    /** 拉分区列表：优先问 host（settings.sections），拿不到就用内置表。 */
    private void boot() {
        new Thread(new Runnable() {
            public void run() {
                JSONArray list = new JSONArray();
                try {
                    JSONObject r = call("settings.sections", new JSONObject());
                    JSONArray got = r == null ? null : r.optJSONArray("sections");
                    if (got != null) {
                        for (int i = 0; i < got.length(); i++) {
                            JSONObject s = got.optJSONObject(i);
                            if (s != null && s.optString("id", "").length() > 0) list.put(s);
                        }
                    }
                } catch (Throwable ignored) {
                    // host 还没实现，用内置
                }
                try {
                    if (list.length() == 0) {
                        for (int i = 0; i < D_IDS.length; i++) {
                            JSONObject s = new JSONObject();
                            s.put("id", D_IDS[i]);
                            s.put("label", D_LABELS[i]);
                            list.put(s);
                        }
                    }
                } catch (Throwable ignored) {
                }
                final JSONArray f = list;
                String q = "\"devctl\"";
                try {
                    q = JSONObject.quote(cur);
                } catch (Throwable ignored) {
                }
                final String fq = q;
                ui.post(new Runnable() {
                    public void run() {
                        js("renderSide(" + f.toString() + "," + fq + ")");
                        request(cur);
                    }
                });
            }
        }).start();
    }

    /** 读一个分区的二级页面。 */
    private void request(final String id) {
        cur = id;
        new Thread(new Runnable() {
            public void run() {
                JSONObject panel = null;
                String err = null;
                try {
                    panel = panelOf(id);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final JSONObject fp = panel;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fp != null) {
                            js("show(" + fp.toString() + ")");
                            return;
                        }
                        String s = null;
                        try {
                            s = card("\u8bfb\u53d6\u5931\u8d25", fe).toString();
                        } catch (Throwable ignored) {
                        }
                        js("show(" + (s == null ? "{}" : s) + ")");
                    }
                });
            }
        }).start();
    }

    private JSONObject panelOf(String id) throws Exception {
        if (dev != null && dev.host.length() > 0) {
            try {
                JSONObject params = new JSONObject();
                params.put("id", id);
                JSONObject r = call("settings.panel", params);
                JSONObject p = r == null ? null : r.optJSONObject("panel");
                if (p != null) return p;
            } catch (Throwable ignored) {
                // host 侧还没实现 settings.panel
            }
        }
        return localPanel(id);
    }

    /** 本地兜底：devctl 分区用 app 自己的配对记录 + 一次握手探测。 */
    private JSONObject localPanel(String id) throws Exception {
        if ("devctl".equals(id)) return devctlPanel();
        JSONObject o = new JSONObject();
        o.put("title", labelOf(id));
        JSONArray bs = new JSONArray();
        JSONObject c = new JSONObject();
        c.put("kind", "card");
        c.put("text", "\u8fd9\u4e00\u5206\u533a\u7531 DSH \u4e3b\u7a0b\u5e8f\u63d0\u4f9b\u3002");
        c.put("note", "\u9700\u8981 host \u4fa7\u534f\u8bae\u6269\u5c55\uff08settings.panel\uff09\u540e\u624d\u80fd\u5b9e\u65f6\u8bfb\u53d6\u3002");
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
                    if (host.length() > 0 && plat.length() > 0) host = host + " \u00b7 " + plat;
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
        o.put("title", "devctl \u8fdc\u7a0b\u63a7\u5236");
        o.put("subtitle", "\u4ece\u624b\u673a\u6216\u5176\u4ed6\u8bbe\u5907\u7528 dshctl \u9a71\u52a8\u8fd9\u53f0 DSH");
        JSONArray blocks = new JSONArray();

        JSONObject c1 = new JSONObject();
        c1.put("kind", "card");
        JSONArray rows = new JSONArray();
        rows.put(row("\u8bbe\u5907", dev == null ? "" : dev.name, null, null));
        rows.put(row("\u5730\u5740", dev == null ? "" : dev.addr(), null,
                new JSONArray().put(btn("\u590d\u5236", "copy", dev == null ? "" : dev.addr()))));
        if (host.length() > 0) rows.put(row("Host", host, null, null));
        if (ver.length() > 0) rows.put(row("\u7248\u672c", ver, null, null));
        String token = dev == null ? "" : dev.token;
        JSONArray tbtns = new JSONArray();
        tbtns.put(btn(revealToken ? "\u9690\u85cf" : "\u663e\u793a", revealToken ? "hide-token" : "reveal-token", ""));
        tbtns.put(btn("\u590d\u5236", "copy", token));
        rows.put(row("\u8bbf\u95ee\u4ee4\u724c", revealToken ? token : mask(token), null, tbtns));
        c1.put("rows", rows);
        blocks.put(c1);

        JSONObject c2 = new JSONObject();
        c2.put("kind", "card");
        c2.put("title", "\u914d\u5bf9\u547d\u4ee4");
        c2.put("text", "\u5728\u53e6\u4e00\u53f0\u8bbe\u5907\u4e0a\u88c5\u597d dshctl \u540e\u8fd0\u884c\uff1a");
        String nm = dev == null || dev.name.length() == 0 ? "home" : dev.name;
        String cmd = "dshctl add " + nm + " " + (dev == null ? "" : dev.addr()) + " --token " + token;
        JSONArray r2 = new JSONArray();
        r2.put(row("\u547d\u4ee4", cmd, null, new JSONArray().put(btn("\u590d\u5236", "copy", cmd))));
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

    private JSONObject btn(String label, String action, String arg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("label", label);
        o.put("action", action);
        o.put("arg", arg == null ? "" : arg);
        return o;
    }

    private String mask(String t) {
        if (t == null || t.length() == 0) return "\u2014";
        if (t.length() <= 8) return "\u2022\u2022\u2022\u2022";
        return t.substring(0, 4) + "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + t.substring(t.length() - 4);
    }

    private String labelOf(String id) {
        for (int i = 0; i < D_IDS.length; i++) {
            if (D_IDS[i].equals(id)) return D_LABELS[i];
        }
        return id == null ? "\u8bbe\u7f6e" : id;
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

    private void doAct(final String id, final String action, final String arg) {
        if ("reveal-token".equals(action) || "hide-token".equals(action)) {
            revealToken = "reveal-token".equals(action);
            request(id);
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
                    params.put("id", id);
                    params.put("action", action);
                    params.put("arg", arg == null ? "" : arg);
                    JSONObject r = call("settings.action", params);
                    if (r != null) {
                        msg = r.optString("message", "");
                        reload = r.optBoolean("reload", false);
                    }
                } catch (Throwable t) {
                    msg = "host \u6682\u4e0d\u652f\u6301\u8be5\u64cd\u4f5c";
                }
                final String fm = msg;
                final boolean fr = reload;
                ui.post(new Runnable() {
                    public void run() {
                        if (fm != null && fm.length() > 0) {
                            Toast.makeText(SettingsActivity.this, fm, Toast.LENGTH_SHORT).show();
                        }
                        if (fr) request(id);
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
                    Toast.makeText(SettingsActivity.this, "\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, "\u590d\u5236\u5931\u8d25", Toast.LENGTH_SHORT).show();
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
        o.put("title", title == null ? "\u8bbe\u7f6e" : title);
        JSONArray bs = new JSONArray();
        JSONObject c = new JSONObject();
        c.put("kind", "card");
        if (note != null && note.length() > 0) c.put("note", note);
        bs.put(c);
        o.put("blocks", bs);
        return o;
    }

    private static final String SHELL =
            "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
            + "html,body{margin:0;height:100%;background:#fff;color:#1c1c1e;font:15px/1.5 -apple-system,Roboto,'Noto Sans SC',sans-serif}"
            + "#wrap{display:flex;height:100%}"
            + "#side{width:31%;max-width:166px;min-width:110px;background:#f7f7f8;border-right:1px solid #e7e7ea;overflow:auto;padding:10px 6px}"
            + ".item{padding:11px 10px;border-radius:10px;font-size:13.5px;margin-bottom:2px}"
            + ".item.on{background:#e8e8ec;font-weight:600}"
            + "#main{flex:1;overflow:auto;padding:14px 12px 40px}"
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
            + "</style></head><body><div id='wrap'><div id='side'></div><div id='main'></div></div><script>"
            + "function $(i){return document.getElementById(i)}"
            + "function esc(s){return String(s==null?'':s).replace(/[&<>]/g,function(c){return c=='&'?'&amp;':(c=='<'?'&lt;':'&gt;')})}"
            + "function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}"
            + "function renderSide(list,cur){window.cur=cur;var b=$('side');b.innerHTML='';list.forEach(function(s){var d=el('div','item'+(s.id===cur?' on':''),s.label);d.onclick=function(){if(window.cur===s.id)return;window.cur=s.id;renderSide(list,s.id);show({title:s.label,blocks:[{note:'\u8bfb\u53d6\u4e2d\u2026'}]});dshNative.section(s.id)};b.appendChild(d)})}"
            + "function show(p){var m=$('main');m.innerHTML='';m.appendChild(el('h1',null,p.title||'\u8bbe\u7f6e'));if(p.subtitle)m.appendChild(el('div','sub',p.subtitle));(p.blocks||[]).forEach(function(k){m.appendChild(card(k))})}"
            + "function card(k){var c=el('div','card');if(k.title)c.appendChild(el('div','ct',k.title));if(k.text)c.appendChild(el('div','sub',k.text));(k.rows||[]).forEach(function(r){c.appendChild(line(r))});if(k.svg){var w=el('div','svg');w.innerHTML=k.svg;c.appendChild(w)}if(k.note)c.appendChild(el('div','note',k.note));return c}"
            + "function line(r){var d=el('div','row');d.appendChild(el('div','k',r.k));var v=el('div','v');v.appendChild(el('span',null,r.v));if(r.badge)v.appendChild(el('span','badge','\u25cf '+(r.badge||'')));d.appendChild(v);(r.btns||[]).forEach(function(x){var t=el('button',null,x.label);t.onclick=function(){dshNative.act(window.cur||'devctl',x.action,x.arg||'')};d.appendChild(t)});return d}"
            + "</script></body></html>";
}
