package com.minis.dshconsole;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 会话聊天页：sessions.tail 拉历史 + sessions.watch 流式增量，ChatGPT 式气泡渲染。 */
public class TabChat extends Tab {
    private ChatView cv;
    private EditText input;
    private TextView status;
    private TextView sendBtn;
    private final java.util.List<String> pending = new java.util.ArrayList<String>();

    private String sessionId = "";
    private String sessionTitle = "";
    private Dsh conn;
    private Thread pumpThread;
    private volatile boolean stopPump = false;

    private volatile boolean streaming = false;
    private volatile boolean accepted = false;
    private volatile boolean turnStarted = false;
    private int deltaCount = 0;
    /** 思考增量缓存：host 打补丁后会以 delta{reasoning:true} 发下来，攒着等正文开始时先落成思考块。 */
    private final StringBuilder thinkBuf = new StringBuilder();
    private long base = -1;
    private int promptId = -1;

    public TabChat(MainActivity a) {
        super(a);
    }

    protected View build() {
        LinearLayout box = Ui.col(act);

        LinearLayout st = Ui.row(act);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(Ui.dp(act, 12), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 2));
        status = Ui.tv(act, "空闲 · 未选择会话", 11f, Ui.DIM);
        status.setSingleLine(true);
        st.addView(status, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(st);

        cv = new ChatView(act);
        box.addView(cv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = Ui.row(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.PANEL);
        int p = Ui.dp(act, 8);
        bar.setPadding(p, p, p, p);

        input = new EditText(act);
        input.setHint("发消息…");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(14.5f);
        input.setSingleLine(false);
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setBackground(Ui.bg(Ui.PANEL2, 20, act));
        int ip = Ui.dp(act, 12);
        input.setPadding(ip, Ui.dp(act, 9), ip, Ui.dp(act, 9));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(input, ilp);

        sendBtn = Ui.tv(act, "↑", 19f, 0xFFFFFFFF);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackground(Ui.bg(Ui.ACCENT, 21, act));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(act, 42), Ui.dp(act, 42));
        slp.leftMargin = Ui.dp(act, 8);
        sendBtn.setLayoutParams(slp);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (streaming) {
                    cancel();
                } else {
                    send();
                }
            }
        });
        bar.addView(sendBtn);
        box.addView(bar);
        return box;
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
    }

    private void setSendIcon(boolean stop) {
        act.ui(new Runnable() {
            public void run() {
                // 由调用处保证在主线程
            }
        });
    }

    private void streamingUi(final boolean on) {
        act.ui(new Runnable() {
            public void run() {
                sendBtn.setText(on ? "■" : "↑");
                sendBtn.setBackground(Ui.bg(on ? Ui.RED : Ui.ACCENT, 21, act));
            }
        });
    }

    /** 回合结束：把挂起的消息放行 —— 此时发出去，就等于"等到了"。 */
    private void flushPending() {
        if (pending.isEmpty() || sessionId.length() == 0) {
            return;
        }
        final int n = pending.size();
        StringBuilder sb = new StringBuilder();
        for (String t : pending) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(t);
            markMine(t);
        }
        pending.clear();
        act.ui(new Runnable() {
            public void run() {
                cv.markPendingSentAll();
                cv.note("⏳ 回合结束 · 挂起的 " + n + " 条已放行", Ui.DIM);
            }
        });
        setStatus("放行挂起的 " + n + " 条…", Ui.AMBER);
        post(sb.toString(), "queue");
    }

    // ---------------- 会话生命周期 ----------------

    public String currentSessionId() {
        return sessionId;
    }

    public String currentTitle() {
        return sessionTitle;
    }

    public void loadSession(final String id, final String title) {
        view();
        if (id == null || id.length() == 0) return;
        sessionId = id;
        sessionTitle = title == null ? "" : title;
        act.store.setLastSession(act.dshName, id);
        cv.clear();
        stopWatch();
        resetState();
        streamingUi(false);
        setStatus("连接中…", Ui.AMBER);
        act.bg(new Runnable() {
            public void run() {
                try {
                    final Dsh c = act.openDsh(12000);
                    conn = c;
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("limit", 200);
                    JSONObject r = c.request("sessions.tail", p, 30000, null);
                    if (r.optBoolean("hasMore", false)) {
                        act.ui(new Runnable() {
                            public void run() {
                                cv.note("↑ 更早的记录未加载（host 的 tail 只认 limit，没有游标翻页）", Ui.DIM);
                            }
                        });
                    }
                    renderRecords(r.optJSONArray("records"));
                    c.begin("sessions.watch", watchParams());
                    startPump();
                    act.ui(new Runnable() {
                        public void run() {
                            setStatus("已连接 · " + TabSessions.shortId(sessionId), Ui.GREEN);
                        }
                    });
                } catch (final Exception e) {
                    conn = null;
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note("连接失败：" + e.getMessage(), Ui.RED);
                            setStatus("连接失败", Ui.RED);
                        }
                    });
                }
            }
        });
    }

    private JSONObject watchParams() {
        JSONObject p = new JSONObject();
        try {
            p.put("sessionId", sessionId);
        } catch (Exception ignored) {
        }
        return p;
    }

    private void resetState() {
        base = -1;
        accepted = false;
        turnStarted = false;
        streaming = false;
        deltaCount = 0;
        promptId = -1;
    }

    private void stopWatch() {
        stopPump = true;
        final Dsh c = conn;
        conn = null;
        act.bg(new Runnable() {
            public void run() {
                if (c != null) c.close();
            }
        });
        pumpThread = null;
    }

    private int watchTries = 0;

    /**
     * 长连接被掐之后：补一根 + 重新 watch，让事件流自己接上。
     * 以前只改个状态文字，会话从此瞎掉 —— 这就是"流断后再也发不出去"的根。
     */
    private void retryWatch() {
        if (stopPump) {
            return;
        }
        watchTries++;
        if (watchTries > 60) {                       // 连一小时都恢复不了才认输
            act.ui(new Runnable() {
                public void run() {
                    setStatus("事件流断开 · 侧栏「设置 → 重新连接」", Ui.RED);
                }
            });
            return;
        }
        try {
            Thread.sleep(Math.min(8000, (long) watchTries * 800));   // 退避，最长 8s
        } catch (InterruptedException ignored) {
            return;
        }
        if (stopPump) {
            return;
        }
        final Dsh c = conn;
        if (c == null) {
            retryWatch();
            return;
        }
        try {
            c.reconnect();
            c.begin("sessions.watch", watchParams());
            startPump();                             // 新连接上重新挂监听
            watchTries = 0;
            act.ui(new Runnable() {
                public void run() {
                    setStatus("连接已恢复", Ui.GREEN);
                }
            });
            resyncHistory();                         // 关键：补齐中断期间漏掉的内容
        } catch (Exception e) {
            retryWatch();                            // 一直试，别让用户手动救
        }
    }

    /**
     * 断线恢复后补历史：事件流只推增量，断开那段不重新读 tail 就永远丢了
     * （用户看到的"回答答一半没了"就是这么来的）。重建一遍，界面以 tail 为准。
     */
    private void resyncHistory() {
        if (sessionId.length() == 0 || stopPump) {
            return;
        }
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = conn;
                    if (c == null) {
                        return;
                    }
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("limit", 200);
                    JSONObject r = c.request("sessions.tail", p, 30000, null);
                    final JSONArray recs = r.optJSONArray("records");
                    act.ui(new Runnable() {
                        public void run() {
                            cv.clear();
                            renderRecords(recs);
                            streaming = false;
                            streamingUi(false);
                        }
                    });
                } catch (Exception ignored) {
                    // 补不上就算了，下次重连还会再试
                }
            }
        });
    }

    private void startPump() {
        stopPump = false;
        Thread t = new Thread(new Runnable() {
            public void run() {
                final Dsh c = conn;
                try {
                    c.pumpFrames(new Dsh.FrameSink() {
                        public void onFrame(JSONObject frame) {
                            onServerFrame(frame);
                        }
                    }, 3600000, new Dsh.Stop() {
                        public boolean stop() {
                            return stopPump;
                        }
                    });
                } catch (final Exception e) {
                    if (!stopPump) {
                        final String why = String.valueOf(e.getMessage());
                        final int attempt = watchTries + 1;
                        act.ui(new Runnable() {
                            public void run() {
                                cv.note("↻ 连接中断，正在自动重连（漏掉的内容会自动补齐）", Ui.AMBER);
                                setStatus("连接中断 · 重连中…", Ui.AMBER);
                                streamingUi(false);          // 绝不把输入框锁死
                            }
                        });
                        retryWatch();                        // 换连接 + 重新 watch
                    }
                }
            }
        });
        t.setDaemon(true);
        pumpThread = t;
        t.start();
    }

    private void onServerFrame(JSONObject frame) {
        if (frame.has("evt")) {
            onEvent(frame.optString("evt"), frame.optJSONObject("data"));
            return;
        }
        if (promptId > 0 && frame.optInt("id", -1) == promptId) {
            if (frame.optBoolean("ok", false)) {
                accepted = true;
                final boolean running = turnStarted;
                act.ui(new Runnable() {
                    public void run() {
                        streamingUi(true);
                        setStatus(running ? "排队中（当前回合运行）" : "已发送", Ui.GREEN);
                    }
                });
            } else {
                final JSONObject err = frame.optJSONObject("error");
                final String msg = err == null ? frame.toString() : err.optString("message", err.toString());
                act.ui(new Runnable() {
                    public void run() {
                        cv.note("发送被拒：" + msg, Ui.RED);
                        streamingUi(false);
                    }
                });
            }
        } else if (frame.has("error")) {
            final JSONObject err = frame.optJSONObject("error");
            final String msg = err == null ? "" : err.optString("message", err.toString());
            act.ui(new Runnable() {
                public void run() {
                    cv.note("错误：" + msg, Ui.RED);
                }
            });
        }
    }

    private void onEvent(String event, JSONObject data) {
        if (data == null) data = new JSONObject();
        if ("snapshot".equals(event)) {
            base = data.optLong("cursor", -1);
            return;
        }
        if ("watch-start".equals(event)) {
            return;
        }
        if ("watch-end".equals(event)) {
            stopPump = true;
            final String why = data.optString("reason", "");
            act.ui(new Runnable() {
                public void run() {
                    setStatus("监听结束 " + why, Ui.DIM);
                }
            });
            return;
        }
        if ("delta".equals(event)) {
            final String chunk = data.optString("text", "");
            // 不再等 prompt 的 ack：回执慢/丢会把整段流式吞掉，turnStarted 就够判断
            if (!turnStarted || chunk.length() == 0) return;
            if (data.optBoolean("reasoning", false)) {   // host 放行 reasoning-delta 后走这里
                thinkBuf.append(chunk);
                return;
            }
            deltaCount++;
            if (deltaCount == 1) {
                act.ui(new Runnable() {
                    public void run() {
                        if (thinkBuf.length() > 0) {     // 思考先落块，正文跟在它下面
                            cv.thinking(thinkBuf.toString());
                            thinkBuf.setLength(0);
                        }
                        cv.botStart();
                        setStatus("生成中…", Ui.ACCENT);
                    }
                });
            }
            act.ui(new Runnable() {
                public void run() {
                    cv.append(chunk);
                }
            });
            return;
        }
        if (!"event".equals(event)) return;
        long seq = data.optLong("seq", -1);
        if (base >= 0 && seq >= 0 && seq <= base) return;
        if (!accepted) return;
        String kind = data.optString("kind", "");
        if ("turn-start".equals(kind)) {
            turnStarted = true;
            return;
        }
        if ("turn-end".equals(kind)) {
            if (turnStarted) {
                turnStarted = false;
                streaming = false;
                final String reason = data.optString("reason", "");
                act.ui(new Runnable() {
                    public void run() {
                        if (thinkBuf.length() > 0) {      // 只思考、没正文的情况也要留下痕迹
                            cv.thinking(thinkBuf.toString());
                            thinkBuf.setLength(0);
                        }
                        cv.botEnd();
                        streamingUi(false);
                        setStatus("回合结束 " + reason, Ui.DIM);
                    }
                });
                flushPending();
            }
            return;
        }
        if ("assistant".equals(kind) && deltaCount > 0) {
            deltaCount = 0;
            return;
        }
        if ("user".equals(kind)) {
            final String ut = data.optString("text", "");
            if (isMine(ut)) return;                  // 自己发的本地已渲染过，别重复
            if (looksInjected(ut)) {
                act.ui(new Runnable() {
                    public void run() {
                        cv.inject(DshConsole.clamp(ut, 60000));
                    }
                });
            }
            return;
        }
        final JSONObject rec = data;
        act.ui(new Runnable() {
            public void run() {
                renderRecord(rec);
            }
        });
    }

    /**
     * 长对话优化：分帧灌进去。原来一次性把 200 条记录全建成 View，
     * 主线程要走上千次构造 + 一次巨量测量，长会话打开就卡死。
     */
    /** 发送模式的中文标签（取值仍是协议原值 queue/steer，别改坏协议）。 */
    private static String modeLabel(String v) {
        if ("steer".equals(v)) {
            return "插话 ‹steer›";
        }
        return "排队 ‹queue›";
    }

    /** 运行期噪音（step/session-log/queue…）：这些不是对话，别往屏幕上摆。 */
    private static boolean noise(String ty) {
        if (ty == null) {
            return true;
        }
        String s = ty.toLowerCase();
        return s.startsWith("step")
                || s.startsWith("session-log")
                || s.startsWith("delivery")
                || s.startsWith("queue")
                || s.startsWith("ping")
                || s.startsWith("heartbeat")
                || s.startsWith("token")
                || s.startsWith("usage")
                || s.startsWith("agent")
                || s.startsWith("inbox")
                || s.startsWith("splice")
                || s.startsWith("system")
                || s.startsWith("log");
    }

    private void renderRecords(final JSONArray records) {
        if (records == null || records.length() == 0) {
            return;
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            act.ui(new Runnable() {
                public void run() {
                    renderRecords(records);
                }
            });
            return;
        }
        cv.beginBulk();
        renderChunk(records, 0, records.length());
    }

    /** 每帧 30 条，剩下的下一帧继续 —— 界面先出来，再慢慢长满。 */
    private void renderChunk(final JSONArray records, final int from, final int total) {
        final int to = Math.min(total, from + 30);
        renderRange(records, from, to);
        if (to < total) {
            act.ui(new Runnable() {
                public void run() {
                    renderChunk(records, to, total);
                }
            });
            return;
        }
        cv.endBulk();
    }

    private void renderRange(JSONArray records, int fromIdx, int toIdx) {
        if (records == null || records.length() == 0) {
            act.ui(new Runnable() {
                public void run() {
                    cv.note("新对话 — 说点什么开始吧", Ui.DIM);
                }
            });
            return;
        }
        for (int i = fromIdx; i < toIdx; i++) {
            final JSONObject r = records.optJSONObject(i);
            if (r != null) {
                act.ui(new Runnable() {
                    public void run() {
                        renderRecord(r);
                    }
                });
            }
        }
    }

    /** 我发出去过的原文（用来把 host 注入的 user 记录区分出来）。 */
    private static final java.util.HashSet<String> MINE = new java.util.HashSet<String>();

    private static boolean isMine(String t) {
        return t != null && MINE.contains(t.trim());
    }

    private static void markMine(String t) {
        if (t == null || t.trim().length() == 0) return;
        MINE.add(t.trim());
        if (MINE.size() > 400) {
            java.util.Iterator<String> it = MINE.iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
    }

    /** 注入上下文的特征：带标签块 / 键值头 / 超长。 */
    private static boolean looksInjected(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() == 0) return false;
        if (s.length() > 3000) return true;
        char c = s.charAt(0);
        if (c == '<' || c == '[' || c == '{') return true;
        String low = s.toLowerCase();
        return low.startsWith("cwd:") || low.startsWith("runtime")
                || low.indexOf("\ncwd:") >= 0 || low.indexOf("<runtime") >= 0;
    }

    private void renderRecord(JSONObject r) {
        String kind = r.optString("kind", r.optString("type", "?"));
        if ("user".equals(kind)) {
            String txt = r.optString("text", "");
            // 协议层不区分「我自己发的」和「host 注入的运行期上下文」，两者都是 user 记录：
            // 发出去的原文见过 → 用户气泡；否则按注入上下文折叠展示。
            if (!isMine(txt) && looksInjected(txt)) cv.inject(DshConsole.clamp(txt, 60000));
            else cv.user(DshConsole.clamp(txt, 4000));
        } else if ("assistant".equals(kind)) {
            cv.bot(DshConsole.clamp(r.optString("text", ""), 8000));
        } else if ("tool-call".equals(kind)) {
            cv.tool(r.optString("name", "?"), r.optString("arguments", ""));
        } else if ("tool-result".equals(kind)) {
            JSONObject e = r.optJSONObject("error");
            if (e != null) {
                cv.toolResult("[" + e.optString("name", "error") + "] "
                        + e.optString("reason", e.optString("code", "")), true);
            } else {
                cv.toolResult(r.optString("text", ""), false);
            }
        } else if ("event".equals(kind)) {
            String ty = r.optString("type", "");
            if (ty.length() > 0 && !noise(ty)) cv.note("· " + ty, Ui.DIM);
        } else if ("turn-start".equals(kind)) {
            cv.note("▷ 回合 " + r.optLong("turn", 0), Ui.DIM);
        } else if ("turn-end".equals(kind)) {
            String reason = r.optString("reason", "");
            cv.note("— 回合结束" + (reason.length() > 0 ? " · " + reason : ""), Ui.DIM);
        }
    }

    // ---------------- 发送 / 打断 / 历史 ----------------

    /** 默认发送 = 挂机等：进队列，等当前回合跑完再执行（harness 的默认行为）。 */
    private void send() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        if (sessionId.length() == 0) {
            act.toast("先在侧边栏新建或选择对话");
            act.openDrawer();
            return;
        }
        if (streaming) {
            // 本地挂起：不推给 host —— 协议没有"撤回排队消息"的方法，先发出去就再也提前不了
            input.setText("");
            pending.add(text);
            cv.pendingUser(DshConsole.clamp(text, 4000), new View.OnClickListener() {
                public void onClick(View v) {
                    if (!pending.remove(text)) {
                        return;
                    }
                    markMine(text);
                    post(text, "steer");
                    cv.note("⏎ 插话 · 立即提交", Ui.DIM);
                }
            });
            setStatus("已挂起 " + pending.size() + " 条 · 等回合结束", Ui.AMBER);
            return;
        }
        send("queue");
    }

    /** mode: queue=排队挂机等；steer=立刻插进当前回合（就是旁边那个 ⏎ 键）。 */
    private void send(final String modeArg) {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        if (sessionId.length() == 0) {
            act.toast("先在侧边栏新建或选择对话");
            act.openDrawer();
            return;
        }
        input.setText("");
        markMine(text);                        // 记账：这条是我发的，历史里别当注入
        cv.user(DshConsole.clamp(text, 4000));
        post(text, modeArg);
    }

    /** 真正把一条消息推给 host（mode: queue=等回合结束 / steer=立刻插进去）。 */
    private void post(final String text, final String modeArg) {
        deltaCount = 0;
        accepted = false;
        turnStarted = true;
        final Dsh c = conn;
        if (c == null) {
            cv.note("未连接，正在重连…", Ui.AMBER);
            loadSession(sessionId, sessionTitle);
            return;
        }
        final String mode = modeArg;
        streaming = true;      // ← 以前只设了 turnStarted，streaming 永远是 false：■ 按下去走 send() 排队，停不下来
        streamingUi(true);
        setStatus("发送中…", Ui.AMBER);
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("mode", mode);
                    p.put("text", text);
                    p.put("images", new JSONArray());
                    promptId = c.begin("sessions.prompt", p);
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note("发送失败：" + e.getMessage(), Ui.RED);
                            streaming = false;
                            streamingUi(false);
                        }
                    });
                }
            }
        });
    }

    /** 打断当前回合（也供侧边栏菜单调用）。 */
    public void cancel() {
        if (sessionId.length() == 0) {
            act.toast("先选择会话");
            return;
        }
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    c.request("sessions.cancel", p, 30000, null);
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note("已请求打断当前回合", Ui.AMBER);
                        }
                    });
                } catch (final Exception e) {
                    act.toast("打断失败：" + e.getMessage());
                }
            }
        });
    }

    public void menuHistory() {
        pullHistory(40);
    }

    public void menuClear() {
        cv.clear();
    }

    private void pullHistory(final int limit) {
        if (sessionId.length() == 0) {
            act.toast("先选择会话");
            return;
        }
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("limit", limit);
                    JSONObject r = c.request("sessions.tail", p, 30000, null);
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note("— 最近 " + limit + " 条 —", Ui.DIM);
                        }
                    });
                    renderRecords(r.optJSONArray("records"));
                } catch (final Exception e) {
                    act.toast("拉取失败：" + e.getMessage());
                }
            }
        });
    }

    /** 会话选择器（菜单「换会话」）。 */
    public void menuPick() {
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    final JSONObject r = c.request("sessions.list", new JSONObject(), 30000, null);
                    final JSONArray arr = r.optJSONArray("items");
                    final List<String> ids = new ArrayList<String>();
                    final List<String> labels = new ArrayList<String>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null || "subagent".equals(o.optString("origin"))) continue;
                            ids.add(o.optString("sessionId"));
                            String t = o.optString("title", "");
                            labels.add((t.length() > 0 ? t : "(未命名)") + "   "
                                    + TabSessions.age(o.optLong("updatedAt", 0)));
                        }
                    }
                    final String[] items = labels.toArray(new String[0]);
                    act.ui(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(act).setTitle("选择会话")
                                    .setItems(items, new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            if (w < 0 || w >= ids.size()) return;
                                            int k = items[w].lastIndexOf("   ");
                                            act.openChat(ids.get(w), k > 0 ? items[w].substring(0, k) : items[w]);
                                        }
                                    }).show();
                        }
                    });
                } catch (final Exception e) {
                    act.toast("加载失败：" + e.getMessage());
                }
            }
        });
    }
}
