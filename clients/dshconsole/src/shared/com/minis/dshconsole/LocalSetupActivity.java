package com.minis.dshconsole;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 本地版的启用引导：第一次进 app 就把 harness 装好、起好，用户点一下「进入」就能用。
 * 远端版永远不会走到这个界面（{@code Cores.get().hasRuntime()} 为 false）。
 */
public class LocalSetupActivity extends Activity {

    private TextView title;
    private TextView sub;
    private TextView log;
    private ScrollView logBox;
    private TextView action;
    private LinearLayout bar;
    private boolean started;
    private boolean harnessStarting;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        setTitle("启用本地版");
        setContentView(scaffold());
        refresh();
    }

    private View scaffold() {
        LinearLayout col = Ui.col(this);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(this, Ui.PAD_H), Ui.dp(this, 18), Ui.dp(this, Ui.PAD_H), Ui.dp(this, 14));

        title = Ui.tv(this, "把 harness 装进这台手机", Ui.H1, Ui.TEXT);
        col.addView(title);
        sub = Ui.tv(this, "装完就不用 PC 了：协议、界面和远端版一模一样，只是 host 跑在你自己手机里。",
                Ui.FS_SMALL, Ui.MUT);
        col.addView(sub);
        col.addView(Ui.gap(this, 12));

        LinearLayout facts = Ui.col(this);
        facts.setBackground(Ui.bg(Ui.SURF2, Ui.R_CARD, this));
        int p = Ui.dp(this, 12);
        facts.setPadding(p, p, p, p);
        facts.addView(line("要下载", "Ubuntu 根文件系统 30MB + Node 53MB + 插件 2MB"));
        facts.addView(line("装在哪", "app 私有目录，卸载即清；不碰系统、不要 root"));
        facts.addView(line("装完得到", "一台 127.0.0.1:7788 的本地 DSH（只绑回环、免令牌）"));
        col.addView(facts);
        col.addView(Ui.gap(this, 12));

        bar = Ui.col(this);
        action = Ui.tv(this, "一键启用", 15f, 0xFF0E1116);
        action.setGravity(Gravity.CENTER);
        action.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        action.setBackground(Ui.bg(Ui.ACCENT, 14, this));
        action.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startInstall();
            }
        });
        bar.addView(action);
        col.addView(bar);

        log = Ui.tv(this, "", 11f, Ui.DIM);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        logBox = new ScrollView(this);
        logBox.setVerticalScrollBarEnabled(false);
        int lp = Ui.dp(this, 10);
        logBox.setPadding(lp, lp, lp, lp);
        logBox.setBackground(Ui.bg(Ui.SURF, Ui.R_CARD, this));
        logBox.addView(log);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = Ui.dp(this, 12);
        col.addView(logBox, llp);
        return col;
    }

    private View line(String k, String v) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        TextView kk = Ui.tv(this, k, 12.5f, Ui.MUT);
        kk.setMinWidth(Ui.dp(this, 62));
        r.addView(kk);
        r.addView(Ui.tv(this, v, 12.5f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private LocalEnv env() {
        return Cores.get().runtime();
    }

    /** 追加一行日志；谁都能调（内部统一回主线程，别在后台线程碰 TextView）。 */
    private void say(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                log.append(text + "\n");
                logBox.post(new Runnable() {
                    public void run() {
                        logBox.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    /** 按当前状态摆界面：没装 → 一键启用；装好没跑 → 启动；在跑 → 进入。 */
    private void refresh() {
        say("· 状态：ready=" + env().ready(this) + " running=" + env().running(this));
        boolean ready = env().ready(this);
        boolean running = env().running(this);
        say("· " + env().state(this));
        if (running) {
            title.setText("本地 harness 已在跑");
            sub.setText("直接进主界面就能用：协议头指向 127.0.0.1:7788，免令牌。");
            action.setText("进入");
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });
        } else if (ready) {
            title.setText("环境已装好");
            sub.setText("正在自动启动 harness…（起来后直接连 127.0.0.1:7788）");
            action.setText("启动 harness");
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startHarness();
                }
            });
            // 装好但没在跑 → 自动启动，不用用户点（要求就是"进来就能用"）
            action.postDelayed(new Runnable() {
                public void run() {
                    if (!isFinishing()) startHarness();
                }
            }, 300);
        } else {
            action.setText("一键启用");
            action.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startInstall();
                }
            });
            // 第一次进来直接开始装，用户不用点（装完自动启动、自动进主界面）
            action.postDelayed(new Runnable() {
                public void run() {
                    if (!isFinishing() && !LocalSetupActivity.this.started) {
                        say("· 首次启动，自动开始安装");
                        startInstall();          // 注意：不能写 run()，那会递归到自己
                    }
                }
            }, 800);
        }
    }

    private void startInstall() {
        if (started) return;
        started = true;
        action.setEnabled(false);
        action.setText("安装中…");
        say("== 开始安装 ==");
        env().install(this, new LocalEnv.Progress() {
            public void onStep(final String message, final int pct) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        action.setText(message + "  " + pct + "%");
                    }
                });
                // 只在整数百分比变化时落一行日志，别刷屏
                if (pct >= 0 && pct % 5 == 0) say("· " + message);
            }

            public void onDone(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        say(ok ? "== 安装完成 ==" : "== 安装失败：" + message + " ==");
                        action.setEnabled(true);
                        started = false;              // 复位：否则「一键启用」再也点不动
                        if (ok) startHarness();
                        else refresh();
                    }
                });
            }
        });
    }

    private void startHarness() {
        if (harnessStarting) return;
        harnessStarting = true;
        action.setEnabled(false);
        action.setText("启动中…");
        say("== 启动 harness ==");
        env().start(this, new LocalEnv.Progress() {
            public void onStep(final String message, final int pct) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        action.setText(message);
                    }
                });
            }

            public void onDone(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        say("· " + message);
                        action.setEnabled(true);
                        harnessStarting = false;          // 复位，别让按钮一次之后点不动
                        if (ok) {
                            // 给它两秒起监听，再进主界面
                            action.postDelayed(new Runnable() {
                                public void run() {
                                    refresh();
                                }
                            }, 2500);
                        } else {
                            refresh();
                        }
                    }
                });
            }
        });
    }
}
