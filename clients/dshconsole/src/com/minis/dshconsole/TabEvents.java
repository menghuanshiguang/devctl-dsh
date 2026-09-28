package com.minis.dshconsole;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/** 实时事件流页：监听某个会话的 evt（watch / tail）。 */
public class TabEvents extends Tab {

    private EditText sessionInput;
    private LogView out;
    private Dsh conn;
    private volatile boolean watching = false;
    private Thread pump;

    public TabEvents(MainActivity a) {
        super(a);
    }

    protected View build() {
        LinearLayout col = Ui.col(act);
        col.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10));

        col.addView(Ui.section(act, "事件流 · sessions.watch", null));
        sessionInput = Ui.input(act, "sessionId（留空＝聊天页当前会话）");
        col.addView(sessionInput);

        LinearLayout r = Ui.row(act);
        r.addView(Ui.btn(act, "开始监听", new View.OnClickListener() {
            public void onClick(View v) {
                start();
            }
        }));
        r.addView(Ui.btn(act, "停止", new View.OnClickListener() {
            public void onClick(View v) {
                stop();
            }
        }));
        r.addView(Ui.btn(act, "拉取最近", new View.OnClickListener() {
            public void onClick(View v) {
                tail(30);
            }
        }));
        r.addView(Ui.btn(act, "清屏", new View.OnClickListener() {
            public void onClick(View v) {
                out.clear();
            }
        }));
        col.addView(r);

        out = new LogView(act);
        col.addView(out, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private String sid() {
        String s = sessionInput.getText().toString().trim();
        if (s.length() == 0) {
            TabChat chat = act.chatTab();
            if (chat != null && chat.currentSessionId().length() > 0) return chat.currentSessionId();
            s = act.store.lastSession(act.dshName);
        }
        return s;
    }

    private void start() {
        final String id = sid();
        if (id.length() == 0) {
            act.toast("没有可监听的会话");
            return;
        }
        if (watching) {
            act.toast("正在监听");
            return;
        }
        watching = true;
        final long base = System.currentTimeMillis();
        act.bg(new Runnable() {
            public void run() {
                try {
                    final Dsh c = act.openDsh(12000);
                    conn = c;
                    JSONObject p = new JSONObject();
                    p.put("sessionId", id);
                    c.begin("sessions.watch", p);
                    out.line("— 开始监听 " + TabSessions.shortId(id) + " —", Ui.GREEN);
                    c.pumpFrames(new Dsh.FrameSink() {
                        public void onFrame(JSONObject f) {
                            if (f.has("evt")) {
                                String evt = f.optString("evt");
                                JSONObject d = f.optJSONObject("data");
                                if ("delta".equals(evt)) {
                                    out.append(d == null ? "" : d.optString("text", ""), Ui.TEXT);
                                } else if ("watch-end".equals(evt)) {
                                    out.line("\n— watch 结束 —", Ui.AMBER);
                                } else if ("snapshot".equals(evt)) {
                                    out.line("— 快照 cursor=" + (d == null ? "?" : d.optLong("cursor", 0))
                                            + " —", Ui.DIM);
                                } else if ("event".equals(evt) && d != null) {
                                    out.line("\n[" + d.optString("kind") + "] seq=" + d.optLong("seq")
                                            + " " + DshConsole.clamp(d.toString(), 400), Ui.PANEL2 == 0 ? Ui.TEXT : Ui.TEXT);
                                } else {
                                    out.line("\n«" + evt + "» " + (d == null ? "" : d.toString()), Ui.DIM);
                                }
                            } else if (f.has("id")) {
                                if (!f.optBoolean("ok", true)) {
                                    out.line("✗ " + f.optJSONObject("error"), Ui.RED);
                                }
                            }
                        }
                    }, 3600000, new Dsh.Stop() {
                        public boolean stop() {
                            return !watching;
                        }
                    });
                    out.line("\n— 监听已结束（" + ((System.currentTimeMillis() - base) / 1000) + "s）—", Ui.AMBER);
                    watching = false;
                } catch (final Exception e) {
                    watching = false;
                    out.line("监听失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void stop() {
        watching = false;
        final Dsh c = conn;
        conn = null;
        act.bg(new Runnable() {
            public void run() {
                if (c != null) c.close();
            }
        });
        out.line("— 已停止监听 —", Ui.AMBER);
    }

    private void tail(final int limit) {
        final String id = sid();
        if (id.length() == 0) {
            act.toast("没有可拉取的会话");
            return;
        }
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject p = new JSONObject();
                    p.put("sessionId", id);
                    p.put("limit", limit);
                    JSONObject r = c.request("sessions.tail", p, 30000, null);
                    JSONArray recs = r.optJSONArray("records");
                    out.line("— 最近 " + limit + " 条（" + TabSessions.shortId(id) + "）—", Ui.DIM);
                    if (recs == null) return;
                    for (int i = 0; i < recs.length(); i++) {
                        JSONObject o = recs.optJSONObject(i);
                        if (o == null) continue;
                        String kind = o.optString("kind", "?");
                        if ("user".equals(kind)) out.line("你 ▸ " + o.optString("text", ""), Ui.ACCENT);
                        else if ("assistant".equals(kind)) out.line("AI ▸ " + o.optString("text", ""), Ui.TEXT);
                        else if ("tool-call".equals(kind)) out.line("[call] " + o.optString("name", "")
                                + " " + o.optString("arguments", ""), Ui.AMBER);
                        else if ("tool-result".equals(kind)) out.line("[ret] "
                                + DshConsole.clamp(o.optString("text", ""), 800), Ui.DIM);
                        else out.line("— " + kind + " —", Ui.DIM);
                    }
                } catch (final Exception e) {
                    out.line("拉取失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    public void onHide() {
        if (watching) stop();
    }
}
