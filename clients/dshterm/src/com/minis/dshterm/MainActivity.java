package com.minis.dshterm;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * DSH 终端：UI 全在 assets/term.html（WebView），原生只负责"把命令丢进 proot 跑、把输出送回去"。
 *
 * 调试入口（我可以从命令行自测，不用点屏幕）：
 *   am start -n com.minis.dshterm/.MainActivity --es cmd "uname -a"
 *   am start -n com.minis.dshterm/.MainActivity --ez install true
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0B0B0D);
        getWindow().setNavigationBarColor(0xFF0B0B0D);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0B0D);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "dsh");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        web.loadUrl("file:///android_asset/term.html");

        // 命令行自测：带了 cmd 就自动跑；带了 install 就自动装环境
        if (b == null && getIntent() != null) {
            String b64 = getIntent().getStringExtra("cmdb64");
            String decoded = null;
            if (b64 != null && b64.length() > 0) {
                try {
                    decoded = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
                } catch (Throwable ignored) {
                }
            }
            final String cmd = decoded != null ? decoded : getIntent().getStringExtra("cmd");
            final boolean install = getIntent().getBooleanExtra("install", false);
            if (cmd != null || install) {
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (install) doInstall();
                        else doRun(cmd);
                    }
                }, 1200);
            }
        }
    }

    /** 整行 → 只进 logcat（命令行侧自测用，别在终端里重复一遍）。 */
    private void pushLine(final String text) {
        android.util.Log.i("DshTerm", text);
    }

    /** 把一段原始输出送回网页（含 \r：终端靠它做同行刷新）。 */
    private void push(final String text) {
        android.util.Log.i("DshTerm", text);        // 全部输出镜像到 logcat，命令行侧能自测
        ui.post(new Runnable() {
            public void run() {
                if (web == null) return;
                String js = "window.term&&term.write(" + org.json.JSONObject.quote(text) + ")";
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private Runner.Out sink() {
        return new Runner.Out() {
            public void line(String text) {          // 我们自己的提示（下载进度、阶段…）
                pushLine(text);
                push(text + "\n");                   // 终端里也要看得见
            }

            public void chunk(String text) {
                push(text);
            }
        };
    }

    private void doInstall() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Runner.install(MainActivity.this, sink());
                } catch (final Exception e) {
                    push("安装失败：" + e);
                }
                push("__STATE__");
            }
        }).start();
    }

    private void doHarness() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Runner.installHarness(MainActivity.this, sink());
                } catch (final Exception e) {
                    push("装 harness 失败：" + e);
                }
                push("__STATE__");
            }
        }).start();
    }

    private void doRun(final String cmd) {
        if (cmd == null || cmd.trim().length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Runner.exec(MainActivity.this, cmd.trim(), sink());
                } catch (final Exception e) {
                    push("执行失败：" + e);
                }
                push("__STATE__");
            }
        }).start();
    }

    /** 网页侧拿到的原生能力就这么几个。 */
    public class Bridge {
        @JavascriptInterface
        public void install() {
            doInstall();
        }

        @JavascriptInterface
        public void harness() {
            doHarness();
        }

        @JavascriptInterface
        public void run(final String cmd) {
            doRun(cmd);
        }

        @JavascriptInterface
        public void stop() {
            Runner.stop();
            push("已停止");
            push("__STATE__");
        }

        @JavascriptInterface
        public String state() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("installed", Runner.installed(MainActivity.this));
                o.put("harness", Runner.harnessInstalled(MainActivity.this));
                o.put("busy", Runner.busy());
                o.put("proot", Runner.proot(MainActivity.this).exists());
                o.put("root", Runner.home(MainActivity.this).getAbsolutePath());
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }
    }
}
