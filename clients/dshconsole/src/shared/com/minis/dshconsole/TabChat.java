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
    private TextView plusBtn;
    /** 排队中的消息：host 的信箱才是权威，本地只多加一行「还没被 splice 认领」的乐观行。 */
    private final java.util.List<Queued> queue = new java.util.ArrayList<Queued>();
    private long lastInboxAt;
    /** 最后一条发出去的时刻 + 最后一个回包的时刻：用来量「局域网到底慢在哪」。 */
    private long turnStartedAt;
    private volatile long sentAt;
    private volatile long lastEventAt;
    private boolean inboxRefreshing;
    /** host 都没连上时的本地挂起（连上了就直接交给 host 的队列，不再拦在本机）。 */
    private final java.util.List<String> pending = new java.util.ArrayList<String>();
    /** 还没发出去的图片（输入框上方那一排）。 */
    private final java.util.List<Img> attachments = new java.util.ArrayList<Img>();
    private LinearLayout attStrip;
    private LinearLayout queueDock;
    private static final int REQ_PICK_IMAGE = 4711;

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
        installImgLoader();
    }

    /** 审批 / 提问卡上的按钮：点一下就发一条一次性 RPC 回 host。 */
    /** 调试用：画一张样例提问卡/审批卡（--ez fakeCard question|approval），验 UI 不用等真事件。 */
    private void fakeCard() {
        android.content.Intent it = act.getIntent();
        if (it == null) return;
        final String what = it.getStringExtra("fakeCard");
        if (what == null) return;
        act.ui(new Runnable() {
            public void run() {
                if ("approval".equals(what)) {
                    cv.approval("fake-1", "bash", "要跑 rm -rf /tmp/x（样例）");
                } else {
                    try {
                        JSONArray qs = new JSONArray();
                        JSONObject q = new JSONObject();
                        q.put("id", "q1");
                        q.put("header", "测试");
                        q.put("question", "这条消息你觉得该怎么回？（样例）");
                        JSONArray opts = new JSONArray();
                        for (String label : new String[]{"直接回", "先别回", "让我想想"}) {
                            JSONObject o = new JSONObject();
                            o.put("label", label);
                            opts.put(o);
                        }
                        q.put("options", opts);
                        qs.put(q);
                        cv.question("fake-q", qs);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void wireInteractiveCards() {
        cv.setApprovalCb(new ChatView.ApprovalCb() {
            public void onDecide(final String id, final boolean allow) {
                rpcQuiet("approvals.decide", id, allow ? "allow" : "deny");
            }
        });
        cv.setUserEditCb(new ChatView.UserEditCb() {
            public void onEdit(final int forkSeq, final String text) {
                editAndResend(forkSeq, text);
            }
        });
        cv.setQuestionCb(new ChatView.QuestionCb() {
            public void onAnswer(final String reqId, final String qid, final String option) {
                try {
                    JSONObject p = new JSONObject();
                    p.put("id", reqId);
                    JSONArray ans = new JSONArray();
                    JSONObject one = new JSONObject();
                    one.put("id", qid);
                    one.put("option", option);
                    ans.put(one);
                    p.put("answers", ans);
                    rpc("questions.answer", p);
                } catch (Exception e) {
                    cv.note("回答案失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    /**
     * 编辑并重发：harness 里这条路的底层是 **fork**（从某个事件 seq 分叉出一个新会话，
     * atSeq 是"含"该事件的切点），所以我们取这条消息**之前**那条记录的 seq 当切点，
     * 分叉出新会话后再把改好的文本发进去 —— 原会话一个字都不动，可回退。
     */
    private void editAndResend(final int forkSeq, String old) {
        final android.widget.EditText e = new android.widget.EditText(act);
        e.setText(old == null ? "" : old);
        e.setTextSize(14f);
        e.setTextColor(Ui.TEXT);
        e.setBackground(Ui.bg(Ui.SURF2, 10, act, Ui.STROKE, 1));
        int pp = Ui.dp(act, 12);
        e.setPadding(pp, pp, pp, pp);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(act);
        int m = Ui.dp(act, 16);
        holder.setPadding(m, Ui.dp(act, 8), m, 0);
        holder.addView(e, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(act)
                .setTitle("编辑并重发")
                .setMessage("会从这条消息之前分叉出一个新对话（原对话保留）。")
                .setView(holder)
                .setPositiveButton("重发", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String text = e.getText().toString().trim();
                        if (text.length() == 0) return;
                        act.bg(new Runnable() {
                            public void run() {
                                Dsh c = null;
                                try {
                                    c = act.openDsh(9000);
                                    JSONObject p = new JSONObject();
                                    p.put("sessionId", sessionId);
                                    if (forkSeq >= 0) p.put("seq", forkSeq);
                                    JSONObject r = c.request("sessions.fork", p, 60000, null);
                                    final String child = r.optString("sessionId", "");
                                    if (child.length() == 0) throw new Exception("fork 没返回新会话");
                                    logSend("fork → " + child);
                                    act.ui(new Runnable() {
                                        public void run() {
                                            act.openChat(child, "编辑重发");
                                            pendingSend = text;      // 会话加载完自动发出去
                                        }
                                    });
                                } catch (final Exception ex) {
                                    final String msg = String.valueOf(ex.getMessage());
                                    act.ui(new Runnable() {
                                        public void run() {
                                            cv.note(msg.toLowerCase().contains("unknown method")
                                                    ? "PC 侧插件还没有 sessions.fork，先更新插件"
                                                    : "编辑重发失败：" + msg, Ui.RED);
                                        }
                                    });
                                } finally {
                                    if (c != null) {
                                        try {
                                            c.close();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 分叉后要自动发出去的那条文本（会话加载完由 loadSession 消费）。 */
    private volatile String pendingSend;

    /** 审批用的简版 RPC（一条短连接发完就走）。 */
    private void rpcQuiet(final String method, final String id, final String decision) {
        try {
            JSONObject p = new JSONObject();
            p.put("id", id);
            p.put("decision", decision);
            rpc(method, p);
        } catch (Exception e) {
            cv.note("回话失败：" + e.getMessage(), Ui.RED);
        }
    }

    private void rpc(final String method, final JSONObject params) {
        act.bg(new Runnable() {
            public void run() {
                Dsh c = null;
                try {
                    c = act.openDsh(8000);
                    c.request(method, params, 20000, null);
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note(method + " 失败：" + e.getMessage(), Ui.RED);
                        }
                    });
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    /**
     * 输入法动画：窗口设成 ADJUST_NOTHING（不然系统直接硬压，没有过渡），
     * 位移由我们自己按 IME 那条 inset 做 220ms 平移动画 —— 整页（消息列表 + 输入卡）一起滑上来，
     * 不会出现"输入卡上去了、消息被键盘压住"的割裂感。
     */
    /**
     * 输入法上抬动画：由 MainActivity 的 inset 监听回调进来（参数是 IME 高度 px）。
     * 整页（消息列表 + 输入卡）一起平移：弹出 220ms、收起 180ms。
     */
    public void onImeInset(final int ime) {
        if (pageBox == null || android.os.Build.VERSION.SDK_INT < 30) return;
        if (ime == lastIme) return;
        lastIme = ime;
        android.util.Log.i("DshIme", "IME inset → " + ime + "px，" + (ime == 0 ? "落回 180ms" : "上抬 220ms"));
        pageBox.animate().cancel();
        pageBox.animate()
                .translationY(-ime)
                .setDuration(ime == 0 ? 180 : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private View pageBox;
    private int lastIme = -1;

    /** 装图片加载器：只有聊天页知道该用哪条连接去问 host 要字节。 */
    private void installImgLoader() {
        Img.setLoader(new Img.Loader() {
            public android.graphics.Bitmap load(Img img, String size) throws Exception {
                Dsh c = act.openDsh(9000);
                try {
                    if (img.attachmentId.length() == 0 && img.path.length() > 0) {
                        // host 上的文件：先落库换一个附件引用，再照常取字节
                        JSONObject q = new JSONObject();
                        q.put("sessionId", sessionId);
                        q.put("path", img.path);
                        JSONObject f = c.request("sessions.file", q, 60000, null);
                        img.attachmentId = f.optString("attachmentId", "");
                        img.mediaType = f.optString("mediaType", img.mediaType);
                        img.bytes = f.optInt("bytes", img.bytes);
                        img.width = f.optInt("width", img.width);
                        img.height = f.optInt("height", img.height);
                        logImg("sessions.file ok id=" + img.attachmentId + " path=" + img.path);
                    }
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("attachment", img.ref());
                    p.put("size", size);
                    JSONObject r = c.request("sessions.image", p, 60000, null);
                    logImg("sessions.image ok size=" + size + " bytes=" + r.optInt("bytes", 0));
                    return Img.decode(r.optString("base64", ""), "thumb".equals(size) ? 1024 : 2560);
                } catch (Exception e) {
                    logImg("sessions.image 失败: " + e);
                    throw e instanceof Exception ? (Exception) e : new Exception(e);
                } finally {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 图片链路的诊断日志：出问题时 `logcat -s DshImg` 一看就知道断在哪一步。 */
    private static void logImg(String msg) {
        try {
            android.util.Log.i("DshImg", msg);
        } catch (Throwable ignored) {
        }
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
        wireInteractiveCards();          // 必须等 cv 建好；放构造器/开头都是 NPE
        final android.widget.TextView jump = new android.widget.TextView(act);   // 右下角下箭头
        jump.setText("\u2193");
        jump.setTextSize(18);
        jump.setTextColor(Ui.TEXT);
        jump.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable jbg =
                Ui.bg(Ui.BG, 20, act, Ui.STROKE, 1);                             // 和消息同色，仅描一圈细边
        jump.setBackground(jbg);
        jump.setVisibility(android.view.View.GONE);                              // 贴在底部时不出现
        jump.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                cv.jumpToBottom();
            }
        });

        android.widget.FrameLayout cvWrap = new android.widget.FrameLayout(act);
        cvWrap.addView(cv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        // 顶部渐隐：消息滚到顶栏底下时淡淡化掉，和顶栏同色收口
        View fadeTop = new View(act);
        fadeTop.setBackground(new Ui.FadeBg(Ui.dp(act, 26), Ui.BG, Ui.BG & 0x00FFFFFF));
        cvWrap.addView(fadeTop, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 26),
                android.view.Gravity.TOP));
        // 底部渐隐（照 DeepSeek）：消息滑到输入卡上方时化进背景，不是硬切、也不留白带
        View fadeBot = new View(act);
        fadeBot.setBackground(new Ui.FadeBg(Ui.dp(act, 12), Ui.BG & 0x00FFFFFF, Ui.BG));
        cvWrap.addView(fadeBot, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 12),
                android.view.Gravity.BOTTOM));
        android.widget.FrameLayout.LayoutParams jlp = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(act, 40), Ui.dp(act, 40),
                android.view.Gravity.BOTTOM | android.view.Gravity.END);
        jlp.rightMargin = Ui.dp(act, 16);
        jlp.bottomMargin = Ui.dp(act, 16);
        cvWrap.addView(jump, jlp);
        cv.setFollowCb(new Runnable() {
            public void run() {
                jump.setVisibility(cv.isFollowing()
                        ? android.view.View.GONE : android.view.View.VISIBLE);
            }
        });
        box.addView(cvWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));   // 不用负 margin：会让输入卡被顶起来、下方留死空白

        View modeRow = modeStrip();                  // 模式栏搬进输入卡片内部（DeepSeek 式）

        LinearLayout bar = Ui.row(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x00000000);          // 底色交给外层圆角卡片
        int p = Ui.dp(act, 2);
        bar.setPadding(p, p, p, p);

        plusBtn = Ui.tv(act, "＋", 18f, Ui.DIM);       // 发图入口：和 ↑ 一左一右，不抢主角
        plusBtn.setGravity(Gravity.CENTER);
        plusBtn.setBackground(Ui.bg(Ui.SURF2, 19, act));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38));
        plusBtn.setLayoutParams(plp);
        plusBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickImage(); }
        });
        bar.addView(plusBtn);

        input = new EditText(act);
        input.setHint("发消息…");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(14.5f);
        input.setSingleLine(false);
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setBackgroundColor(0x00000000);        // 输入框融进卡片，不再单独一块圆角
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
                // 有内容优先"发"：跑着的时候也能把消息排队/插话送出去（以前跑着只给停止，发不了）
                if (hasDraft()) {
                    send();
                } else if (streaming) {
                    cancel();
                } else {
                    send();
                }
            }
        });
        bar.addView(sendBtn);

        // 输入变化就换按钮语义：有内容 = ↑ 发送；没内容且在跑 = ■ 停止
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) {
                refreshSendButton();
            }

            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }
        });

        LinearLayout card = new LinearLayout(act);   // DeepSeek 式：整块独立圆角卡片
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.bg(Ui.CARD, 22, act));     // 卡面本身不描边
        int cp = Ui.dp(act, 6);
        card.setPadding(cp, cp, cp, cp);

        queueDock = new LinearLayout(act);            // 队列坞：已交给 host 还在等的消息，可撤回/插话
        queueDock.setOrientation(LinearLayout.VERTICAL);
        queueDock.setVisibility(View.GONE);
        card.addView(queueDock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        attStrip = new LinearLayout(act);             // 待发图片缩略条
        attStrip.setOrientation(LinearLayout.HORIZONTAL);
        attStrip.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(act, 6);
        alp.leftMargin = Ui.dp(act, 6);
        card.addView(attStrip, alp);

        card.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        modeRow.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 6), 0);   // 和输入文字左对齐
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp2.topMargin = Ui.dp(act, 2);
        card.addView(modeRow, mlp2);                 // 三颗挪到输入框下面

        LinearLayout outer = new LinearLayout(act);  // 卡片四周留白，同时当阴影的呼吸位
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 10));  // 上/下收窄：消息贴近卡片，卡片也更靠屏幕底
        outer.setBackground(new Ui.ShadowBg(act, 22, 12, 2, 0x33000000, Ui.CARD));  // 一点点软阴影
        outer.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);            // 不开软层 shadowLayer 不生效
        outer.addView(card);
        box.addView(outer);
        pageBox = box;                       // 整页容器：IME 动画作用在它身上（MainActivity 回调进来）
        fakeCard();                          // 调试入口（只在带 extra 时生效）
        return box;
    }

    // ==================== 模式栏：模型 / 思考强度 / 工作区权限 ====================
    private JSONArray modelGroups;
    private JSONArray permCatalog;
    private String curProvider = "", curModel = "", curEffort = "", curPerm = "";
    private LinearLayout modeBar;

    interface Pick { void pick(String value); }

    /** 输入框上方一行可横滚的胶囊。 */
    private View modeStrip() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        modeBar = Ui.row(act);
        modeBar.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(act, 8);
        modeBar.setPadding(p, Ui.dp(act, 6), p, Ui.dp(act, 4));
        modeBar.setBackgroundColor(0x00000000);      // 模式栏外框底色也去掉，直接融进输入卡片
        hs.addView(modeBar);
        rebuildChips();
        // 打开会话后 sessionId 才有效，稍后再同步一次真实状态
        hs.postDelayed(new Runnable() {
            public void run() { refreshMode(); }
        }, 1500);
        return hs;
    }

    private View chip(String label, final Runnable tap) {
        return chip(label, false, tap);
    }

    /** 胶囊：图标 + 中文短名；active 时用强调色底，一眼看出这个设置被改过。 */
    private View chip(String label, boolean active, final Runnable tap) {
        TextView t = Ui.tv(act, label, 12.5f, Ui.ACCENT);          // 胶囊统一蓝字
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(Ui.dp(act, 140));
        t.setPadding(Ui.dp(act, 11), Ui.dp(act, 5), Ui.dp(act, 11), Ui.dp(act, 5));
        android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
        cg.setColor(active ? (Ui.DARK ? 0x3D0A84FF : 0xFFDCE9FF) : Ui.CHIP_BG);   // 淡蓝胶囊
        cg.setCornerRadius(Ui.dp(act, 15));
        cg.setStroke(Ui.dp(act, 1), active ? (Ui.DARK ? 0x800A84FF : 0xFFA8C8F5) : Ui.CHIP_BD);
        t.setBackground(cg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 6);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { tap.run(); }
        });
        return t;
    }

    /** deepseek-v4-flash → V4-flash，太长的 id 不整段塞进胶囊。 */
    private String shortName(String id) {
        if (id == null || id.length() == 0) return "";
        int i = id.indexOf('-');
        if (i > 0 && id.equals(id.toLowerCase())) {
            String tail = id.substring(i + 1);
            return Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
        }
        return id;
    }

    private String effCn(String v) {
        if (v == null) return "默认";
        if (v.equals("off")) return "关";
        if (v.equals("low")) return "轻";
        if (v.equals("high")) return "深";
        if (v.equals("max")) return "最深";
        return v;
    }

    private String permCn(String v) {
        if (v == null || v.length() == 0) return "默认";
        if (v.equals("read-only")) return "只读";
        if (v.equals("workspace-write")) return "可写工作区";
        if (v.equals("danger-full-access")) return "完全访问";
        if (v.equals("auto")) return "自动";
        return v;
    }

    private void rebuildChips() {
        if (modeBar == null) return;
        modeBar.removeAllViews();
        String m = curModel == null || curModel.length() == 0 ? "默认模型" : shortName(curModel);
        boolean thinkOn = curEffort != null && curEffort.length() > 0
                && !curEffort.equals("off") && !curEffort.equals("default");
        boolean locked = curPerm != null && curPerm.startsWith("read-only");
        modeBar.addView(chip("\u25C8 " + m, false, new Runnable() {
            public void run() { pickModel(); }
        }));
        modeBar.addView(chip("\u2726 思考 " + effCn(curEffort), thinkOn, new Runnable() {
            public void run() { pickEffort(); }
        }));
        modeBar.addView(chip("\u26E8 " + permCn(curPerm), locked, new Runnable() {
            public void run() { pickPerm(); }
        }));
    }

    /** 当前值：靠标题认，省得改三处调用点。 */
    private String pickCur(String title) {
        String t = title == null ? "" : title;
        if (t.contains("模型")) return curModel;
        if (t.contains("思考")) return curEffort;
        if (t.contains("权限")) return curPerm;
        return "";
    }

    /** 每个选项配一句人话，别让用户对着英文枚举猜。 */
    private String pickDesc(String title, String v) {
        if (v == null) return "";
        String t = title == null ? "" : title;
        if (t.contains("思考")) {
            if (v.equals("off")) return "不展开思考";
            if (v.equals("low")) return "想得少一点，更快";
            if (v.equals("high")) return "默认深度";
            if (v.equals("max")) return "想得最久，也最慢";
        }
        if (t.contains("权限")) {
            if (v.equals("read-only")) return "只能读，不改任何文件";
            if (v.equals("workspace-write")) return "可改当前工作目录里的文件";
            if (v.equals("danger-full-access")) return "可执行任意命令，风险自负";
            if (v.equals("auto")) return "交给它自己判断";
        }
        return "";
    }

    /** 底部浮起的选择面板：当前项打勾高亮、带说明，点完自动关。 */
    private void showPick(String title, java.util.List<String> labels, final java.util.List<String> values, final Pick cb) {
        final String cur = pickCur(title);
        LinearLayout bx = Ui.col(act);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.PANEL2);
        bg.setCornerRadius(Ui.dp(act, 18));
        bx.setBackground(bg);
        int q = Ui.dp(act, 12);
        bx.setPadding(q, q, q, q);
        TextView h = Ui.tv(act, title, 12f, Ui.DIM);
        h.setPadding(Ui.dp(act, 8), Ui.dp(act, 2), Ui.dp(act, 8), Ui.dp(act, 8));
        bx.addView(h);

        final android.app.Dialog d = new android.app.Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        for (int i = 0; i < labels.size(); i++) {
            final String val = values.get(i);
            boolean on = val != null && val.equals(cur);
            LinearLayout row = Ui.col(act);
            row.setBackground(Ui.bg(on ? 0x2E0A84FF : 0x00000000, 12, act));
            int rp = Ui.dp(act, 11);
            row.setPadding(rp, rp, rp, rp);
            row.addView(Ui.tv(act, (on ? "\u2713  " : "") + labels.get(i), 15f, on ? Ui.ACCENT : Ui.TEXT));
            String ds = pickDesc(title, val);
            if (ds.length() > 0) {
                TextView t2 = Ui.tv(act, ds, 12f, Ui.MUT);
                t2.setPadding(0, Ui.dp(act, 3), 0, 0);
                row.addView(t2);
            }
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    cb.pick(val);
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = Ui.dp(act, 4);
            bx.addView(row, rlp);
        }
        LinearLayout wrap = Ui.col(act);              // 四周留白，面板浮起来而不是贴边
        int wp2 = Ui.dp(act, 10);
        wrap.setPadding(wp2, wp2, wp2, Ui.dp(act, 12));
        wrap.addView(bx);
        d.setContentView(wrap);
        android.view.Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            android.view.WindowManager.LayoutParams wa = win.getAttributes();
            wa.dimAmount = 0.45f;
            win.setAttributes(wa);
        }
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    /** 把原始响应落到应用私有目录（root 可读），用来确认真实字段名，别再来回猜。 */
    private void dumpJson(String name, String text) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(act.getFilesDir(), name));
            fo.write(text.getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) {
        }
    }

    private void pickModel() {
        act.bg(new Runnable() {
            public void run() {
                try {
                    if (modelGroups == null) {
                        JSONObject r = act.requireDsh().request("models.catalog", new JSONObject(), 30000, null);
                        modelGroups = r.optJSONArray("groups");
                        JSONObject c = r.optJSONObject("current");
                        if (c != null) {
                            curProvider = c.optString("provider", "");
                            curModel = c.optString("model", "");
                            curEffort = c.optString("reasoningEffort", "");
                        }
                    }
                    final java.util.List<String> labels = new java.util.ArrayList<String>();
                    final java.util.List<String> values = new java.util.ArrayList<String>();
                    final String[] PK = {"provider", "vendor", "name", "id", "label", "slug"};
                    final String[] MK = {"model", "name", "id", "label", "title", "slug", "value"};
                    final String[] GK = {"models", "items", "list", "entries", "model"};
                    for (int g = 0; modelGroups != null && g < modelGroups.length(); g++) {
                        JSONObject grp = modelGroups.optJSONObject(g);
                        if (grp == null) continue;
                        String prov = grp.optString("id", "");          // provider 必须用 id
                        String provName = grp.optString("name", prov);
                        if (prov.length() == 0) {
                            for (int k = 0; k < PK.length && prov.length() == 0; k++) prov = grp.optString(PK[k], "");
                            provName = prov;
                        }
                        JSONArray ms = null;
                        for (int k = 0; k < GK.length && ms == null; k++) ms = grp.optJSONArray(GK[k]);
                        for (int m = 0; ms != null && m < ms.length(); m++) {
                            JSONObject mo = ms.optJSONObject(m);
                            String mid = "";
                            String show = "";
                            if (mo != null) {
                                mid = mo.optString("id", "");           // 要发出去的是 id
                                show = mo.optString("name", mid);       // 屏幕上显示 name
                                if (mid.length() == 0) {
                                    for (int k = 0; k < MK.length && mid.length() == 0; k++) mid = mo.optString(MK[k], "");
                                    show = mid;
                                }
                            } else {
                                mid = ms.optString(m, "");              // 条目可能直接是字符串
                                show = mid;
                            }
                            if (mid.length() == 0) continue;
                            boolean isDef = mo != null && mo.optBoolean("default", false);
                            labels.add("[" + provName + "] " + show + (isDef ? "  ★默认" : ""));
                            values.add(prov + "\n" + mid + "\n" + show);
                        }
                    }
                    if (labels.isEmpty()) {
                        setStatus("模型目录为空", Ui.AMBER);
                        cv.note("模型目录为空 · 诊断：" + (modeErr.length() == 0 ? "groups 里没有模型" : modeErr), Ui.AMBER);
                        return;
                    }
                    act.ui(new Runnable() {
                        public void run() {
                            showPick("选择模型（模式）", labels, values, new Pick() {
                                public void pick(String v) {
                                    String[] sp = v.split("\n", -1);
                                    applyModel(sp[0], sp.length > 1 ? sp[1] : "", curEffort,
                                            sp.length > 2 ? sp[2] : "");
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    setStatus("模型目录拉取失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private JSONArray efforts;
    private String curModelName = "";

    /** 流式帧指纹落盘（诊断 host 到底发没发 reasoning 增量）。 */
    /** 帧级调试日志：默认关掉，排查流式问题时置 true（写 files/frames.log，256KB 封顶）。 */
    private static final boolean DEBUG_FRAMES = false;

    private void logFrame(String kind, String text) {
        if (!DEBUG_FRAMES) return;
        try {
            java.io.File f = new java.io.File(act.getFilesDir(), "frames.log");
            if (f.length() > 262144) {
                return;
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            fo.write((kind + " | len=" + text.length() + " | "
                    + text.substring(0, Math.min(60, text.length())).replace('\n', ' ')
                    + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) {
        }
    }

    /** 思考强度：档位来自当前模型自己的 reasoning.efforts（off/low/high/max）。 */
    private void pickEffort() {
        java.util.List<String> labels = new java.util.ArrayList<String>();
        final java.util.List<String> values = new java.util.ArrayList<String>();
        for (int i = 0; efforts != null && i < efforts.length(); i++) {
            JSONObject e = efforts.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            if (id.length() == 0) continue;
            String nm = e.optString("name", id);
            String d = e.optString("description", "");
            labels.add(id + "  " + nm + (d.length() == 0 ? "" : "\n" + d)
                    + (id.equals(curEffort) ? "  ●当前" : ""));
            values.add(id);
        }
        if (labels.isEmpty()) {              // 兜底：真目录里就是这四档
            labels.add("off  不想"); values.add("off");
            labels.add("low  省电，快"); values.add("low");
            labels.add("high  想久一点"); values.add("high");
            labels.add("max  拉到顶"); values.add("max");
        }
        showPick(curModel.length() == 0 ? "思考强度（当前模型未知）" : "思考强度", labels, values, new Pick() {
            public void pick(String v) { applyModel(curProvider, curModel, v, curModelName); }
        });
    }

    /** 从目录里取出某模型支持的思考强度档位与其展示名。 */
    private void setEffortsFor(String providerId, String modelId) {
        efforts = null;
        for (int g = 0; modelGroups != null && g < modelGroups.length(); g++) {
            JSONObject grp = modelGroups.optJSONObject(g);
            if (grp == null || !providerId.equals(grp.optString("id", ""))) continue;
            JSONArray ms = grp.optJSONArray("models");
            for (int m = 0; ms != null && m < ms.length(); m++) {
                JSONObject mo = ms.optJSONObject(m);
                if (mo == null || !modelId.equals(mo.optString("id", ""))) continue;
                curModelName = mo.optString("name", modelId);
                JSONObject rr = mo.optJSONObject("reasoning");
                if (rr != null) efforts = rr.optJSONArray("efforts");
                return;
            }
        }
    }

    private void pickPerm() {
        act.bg(new Runnable() {
            public void run() {
                try {
                    if (permCatalog == null) {
                        JSONObject r = act.requireDsh().request("permissions.catalog", new JSONObject(), 30000, null);
                        permCatalog = r.optJSONArray("catalog");
                    }
                    final java.util.List<String> labels = new java.util.ArrayList<String>();
                    final java.util.List<String> values = new java.util.ArrayList<String>();
                    for (int i = 0; permCatalog != null && i < permCatalog.length(); i++) {
                        JSONObject cat = permCatalog.optJSONObject(i);
                        if (cat == null) continue;
                        String cn = cat.optString("name", "");
                        String d0 = cat.optString("description", "");
                        JSONArray ps = cat.optJSONArray("presets");
                        if (ps == null) {          // 扁平形状：这一项本身就是预设 {value,name}
                            String pn = cat.optString("name", cat.optString("value", "?"));
                            labels.add(pn + (d0.length() == 0 ? "" : "\n" + d0)
                                    + (cat.optString("value", "").equals(curPerm) ? "  ●当前" : ""));
                            values.add(presetId(cat));
                            continue;
                        }
                        for (int j = 0; ps != null && j < ps.length(); j++) {
                            JSONObject po = ps.optJSONObject(j);
                            if (po == null) continue;
                            String pn = po.optString("name", "?");
                            String d = po.optString("description", "");
                            labels.add((cn.length() == 0 ? "" : cn + " · ") + pn + (d.length() == 0 ? "" : "\n" + d));
                            values.add(presetId(po));   // 送回去的必须是标识符，不是显示名
                        }
                    }
                    if (labels.isEmpty()) {
                        // 把失败形状直接摊出来，省得再来回猜
                        if (permCatalog == null) {
                            cv.note("权限目录：响应里没有 catalog 字段（形状不同）", Ui.AMBER);
                        } else {
                            cv.note("权限目录是空数组 length=0", Ui.AMBER);
                        }
                        setStatus("权限目录为空", Ui.AMBER);
                        return;
                    }
                    act.ui(new Runnable() {
                        public void run() {
                            showPick("工作区权限", labels, values, new Pick() {
                                public void pick(String v) { applyPerm(v); }
                            });
                        }
                    });
                } catch (Exception e) {
                    setStatus("权限目录拉取失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void applyModel(final String provider, final String model, final String effort, final String show) {
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("provider", provider);
                    p.put("model", model);
                    if (effort != null && effort.length() > 0) p.put("reasoningEffort", effort);
                    JSONObject r = act.requireDsh().request("models.select", p, 30000, null);
                    JSONObject c = r.optJSONObject("default");
                    if (c == null) c = r.optJSONObject("current");
                    if (c != null) {
                        curProvider = c.optString("provider", provider);
                        curModel = c.optString("model", model);
                        curEffort = c.optString("reasoningEffort", effort == null ? "" : effort);
                    } else {
                        curProvider = provider;
                        curModel = model;
                        curEffort = effort == null ? "" : effort;
                    }
                    if (show != null && show.length() > 0) curModelName = show;
                    setEffortsFor(curProvider, curModel);
                    act.ui(new Runnable() {
                        public void run() {
                            rebuildChips();
                            cv.note("已切换 · " + (curModelName.length() > 0 ? curModelName : curModel)
                                    + (curEffort.length() == 0 ? "" : " · 思考 " + curEffort), Ui.GREEN);
                        }
                    });
                } catch (Exception e) {
                    setStatus("切换失败：" + e.getMessage(), Ui.RED);
                    cv.note("切换失败：" + e.getMessage() + "（送的 provider=" + provider + " model=" + model + "）", Ui.RED);
                }
            }
        });
    }

    private void applyPerm(final String preset) {
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("preset", preset);
                    JSONObject r = act.requireDsh().request("permissions.set", p, 30000, null);
                    curPerm = r.optString("preset", preset);
                    act.ui(new Runnable() {
                        public void run() {
                            rebuildChips();
                            cv.note("工作区权限 · " + curPerm, Ui.GREEN);
                        }
                    });
                } catch (Exception e) {
                    setStatus("权限设置失败：" + e.getMessage(), Ui.RED);
                    cv.note("权限设置失败：" + e.getMessage() + "（送的 preset=" + preset + "）", Ui.RED);
                }
            }
        });
    }

    /** 预设的「真·标识」：不同上游字段名不一样，按优先级挑第一个非空字符串。 */
    private String presetId(JSONObject po) {
        String[] keys = {"id", "value", "key", "preset", "slug", "name"};
        for (int i = 0; i < keys.length; i++) {
            String v = po.optString(keys[i], "");
            if (v.length() > 0) return v;
        }
        return "";
    }

    /** 模式栏诊断：目录拉不到时把原话摊出来，别再黑屏瞎猜。 */
    private String modeErr = "";

    /** 会话打开时同步真实状态。当前模型/权限优先取 sessions.list（这条通道最稳）。 */
    private void refreshMode() {
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject r = act.requireDsh().request("sessions.list", new JSONObject(), 30000, null);
                    JSONArray arr = r.optJSONArray("sessions");
                    if (arr == null) arr = r.optJSONArray("items");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        String id = it.optString("sessionId", it.optString("id", ""));
                        if (!sessionId.equals(id)) continue;
                        String m = it.optString("model", "");
                        if (m.length() > 0) curModel = m;
                        Object pm = it.opt("permissions");
                        if (pm != null) curPerm = DshConsole.flattenPermission(pm);
                        break;
                    }
                } catch (Exception e) {
                    modeErr = "sessions.list: " + e.getMessage();
                }
                try {
                    JSONObject r = act.requireDsh().request("models.catalog", new JSONObject(), 30000, null);
                    dumpJson("models.catalog.json", r.toString());
                    modelGroups = r.optJSONArray("groups");
                    JSONObject c = r.optJSONObject("default");
                    if (c == null) c = r.optJSONObject("current");
                    if (c == null) c = r.optJSONObject("selected");
                    if (c != null) {
                        String p = c.optString("provider", ""), m = c.optString("model", "");
                        if (p.length() > 0) curProvider = p;
                        if (m.length() > 0) curModel = m;
                        String ef = c.optString("reasoningEffort", "");
                        if (ef.length() > 0) curEffort = ef;
                    }
                    setEffortsFor(curProvider, curModel);
                    if (modelGroups == null) modeErr = "models.catalog 响应里没有 groups 字段";
                } catch (Exception e) {
                    modeErr = "models.catalog: " + e.getMessage();
                }
                try {
                    JSONObject pc = act.requireDsh().request("permissions.catalog", new JSONObject(), 30000, null);
                    dumpJson("permissions.catalog.json", pc.toString());
                    permCatalog = pc.optJSONArray("options");      // 真形状：扁平 options[]
                    if (permCatalog == null) permCatalog = pc.optJSONArray("catalog");
                    if (permCatalog == null) permCatalog = pc.optJSONArray("presets");
                    String dp = pc.optString("defaultPreset", "");
                    if (dp.length() > 0) curPerm = dp;
                    Object pcur = pc.opt("current");
                    if (pcur != null) curPerm = DshConsole.flattenPermission(pcur);
                } catch (Exception e) {
                    modeErr = "permissions.catalog: " + e.getMessage();
                }
                act.ui(new Runnable() {
                    public void run() { rebuildChips(); }
                });
            }
        });
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
        refreshSendButton();
    }

    /** 输入框里有没有东西（文字或待发图片）。 */
    private boolean hasDraft() {
        return input.getText().toString().trim().length() > 0 || !attachments.isEmpty();
    }

    /**
     * 主按钮语义（跟 DeepSeek / harness 一致）：
     *   有内容           → ↑ 发送（跑着也能发出去，进队列）
     *   没内容 + 正在跑  → ■ 停止
     *   没内容 + 空闲    → ↑ 发送
     */
    private void refreshSendButton() {
        act.ui(new Runnable() {
            public void run() {
                boolean has = hasDraft();
                if (has || !streaming) {
                    sendBtn.setText("\u2191");
                    sendBtn.setBackground(Ui.bg(Ui.ACCENT, 21, act));
                } else {
                    sendBtn.setText("\u25A0");
                    sendBtn.setBackground(Ui.bg(Ui.RED, 21, act));
                }
                sendBtn.setEnabled(true);
            }
        });
    }

    // ---------------- 回合状态：只从 host 读，不靠客户端猜 ----------------
    // 「可发送 / 停止发送」＝ host 那个会话的 running 字段（sessions.state，老 host 退回 sessions.list）。
    // 本地只负责渲染增量；不再用"我发过消息/收到过 turn-end"来推断按钮语义。

    private volatile boolean hostRunning = false;
    private volatile boolean hostStateKnown = false;
    private volatile boolean stateBusy = false;
    private Thread stateThread;
    private volatile String statusBase = "连接中…";

    /** 状态栏＝「连接 + host 报的回合状态」，永远由 host 说了算。 */
    private void setStateStatus() {
        act.ui(new Runnable() {
            public void run() {
                setStatus(statusBase + " · " + (hostRunning ? "停止发送" : "可发送"),
                          hostRunning ? Ui.ACCENT : Ui.GREEN);
            }
        });
    }

    private void applyHostState(final boolean running, final String why) {
        hostStateKnown = true;
        hostRunning = running;
        streaming = running;                 // 老的 streaming 判断点全部改吃 host 状态
        streamingUi(running);
        if (!running) dropStale();            // 空闲了还挂着"排队中"就是假的，收掉
        if (!running) {
            act.ui(new Runnable() {           // host 说空闲 → 还开着的过程组一律收尾（幂等）
                public void run() {
                    cv.thinkEnd();
                }
            });
        }
        setStateStatus();                    // 每次都拉回 host 口径；瞬时提示活不过一个轮询周期
        logFrame("host-state", (running ? "running" : "idle") + " · " + why);
    }

    /** 读一次 host 的真实运行状态。任何异常都只记日志，绝不用本地猜测兜底。 */
    private void refreshHostState(final String why) {
        final String id = sessionId;
        if (id == null || id.length() == 0 || stateBusy) return;
        stateBusy = true;
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = conn;
                    if (c == null) return;
                    JSONObject p = new JSONObject();
                    p.put("sessionId", id);
                    boolean running;
                    JSONObject r = null;
                    try {
                        r = c.request("sessions.state", p, 12000, null);
                    } catch (Exception older) {
                        r = null;                                    // 老 host 没这个方法
                    }
                    if (r != null && r.optJSONObject("state") != null) {
                        running = r.optJSONObject("state").optBoolean("running", false);
                    } else {
                        JSONObject list = c.request("sessions.list", new JSONObject(), 20000, null);
                        JSONArray items = list.optJSONArray("items");
                        JSONObject found = null;
                        for (int i = 0; items != null && i < items.length(); i++) {
                            JSONObject one = items.optJSONObject(i);
                            if (one != null && id.equals(one.optString("sessionId", ""))) found = one;
                        }
                        if (found == null) return;                   // 列表里还没出现，下一轮再读
                        running = found.optBoolean("running", false);
                    }
                    if (!id.equals(sessionId)) return;               // 期间切了会话，丢掉过期结果
                    applyHostState(running, why);
                } catch (Exception e) {
                    logFrame("host-state-err", why + " · " + e.getMessage());
                } finally {
                    stateBusy = false;
                }
            }
        });
    }

    /** 状态轮询：host 才是唯一真相，事件漏了、别人在 PC 上发起的回合，靠它兜住。 */
    private void startStateWatch() {
        stopStateWatch();
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!stopPump) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (stopPump || conn == null) continue;
                    refreshHostState("poll");
                }
            }
        });
        t.setDaemon(true);
        stateThread = t;
        t.start();
    }

    private void stopStateWatch() {
        Thread t = stateThread;
        stateThread = null;
        if (t != null) t.interrupt();
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
        post(sb.toString(), null, "queue", null);
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
                    refreshHostState("connect");          // 按钮语义先按 host 的真实状态摆好
                    startStateWatch();
                    refreshInbox();                       // host 那边还排着的消息，坞里也得有
                    probeCapabilities();                  // 老插件要提前说一声，别等功能报错了才发现
                    final String pending = pendingSend;
                    pendingSend = null;
                    if (pending != null && pending.length() > 0) {
                        act.ui(new Runnable() {
                            public void run() {
                                input.setText(pending);
                                send();                      // 走正常发送（进队列），复用整条链路
                            }
                        });
                    }
                    statusBase = "已连接 · " + TabSessions.shortId(sessionId);
                    setStateStatus();
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
        hostRunning = false;
        hostStateKnown = false;
        deltaCount = 0;
        promptId = -1;
    }

    private void stopWatch() {
        stopPump = true;
        stopStateWatch();
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
                    final String pending = pendingSend;
                    pendingSend = null;
                    if (pending != null && pending.length() > 0) {
                        act.ui(new Runnable() {
                            public void run() {
                                input.setText(pending);
                                send();                      // 走正常发送（进队列），复用整条链路
                            }
                        });
                    }
                    statusBase = "已连接 · " + TabSessions.shortId(sessionId);
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
                        }
                    });
                    refreshHostState("reconnect");           // 重连后以 host 现场为准
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
                                // 重连属于"状态"，不该往消息流里塞 —— 顶栏已经写着"连接中断 · 重连中…"
                                statusBase = "连接中断 · 重连中…";
                                setStatus(statusBase, Ui.AMBER);
                                // 按钮保持最后一次从 host 读到的状态：断线期间猜不出真相，就不猜
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
                act.ui(new Runnable() {
                    public void run() {
                        setStatus(turnStarted ? "排队中（当前回合运行）" : "已发送", Ui.GREEN);
                    }
                });
                refreshHostState("prompt-ack");        // 按钮/状态文本随后归位到 host 的真实状态
            } else {
                final JSONObject err = frame.optJSONObject("error");
                final String msg = err == null ? frame.toString() : err.optString("message", err.toString());
                act.ui(new Runnable() {
                    public void run() {
                        cv.note("发送被拒：" + msg, Ui.RED);
                    }
                });
                refreshHostState("prompt-reject");
            }
        } else if (frame.has("error")) {
            final JSONObject err = frame.optJSONObject("error");
            final String msg = err == null ? "" : err.optString("message", err.toString());
            act.ui(new Runnable() {
                public void run() {
                    if (benignRace(msg)) {
                        // 回合一结束，排队里的"插话/撤回"就没意义了 —— 这是竞态，不是故障
                        cv.note("这条回合已经结束了，插话/撤回不用了", Ui.DIM);
                        dropStale();
                    } else {
                        cv.note("错误：" + msg, Ui.RED);
                    }
                }
            });
        }
    }

    private void onEvent(String event, JSONObject data) {
        if (data == null) data = new JSONObject();
        long now = System.currentTimeMillis();
        lastEventAt = now;
        if (sentAt > 0 && !"snapshot".equals(event)) {     // 首包延迟：局域网里应该是几十毫秒
            logSend("首包 " + event + " +" + (now - sentAt) + "ms");
            sentAt = 0;
        }
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
        if ("approval".equals(event) || "question".equals(event)) {
            android.util.Log.i("DshCard", "收到 " + event + " 事件: " + data);
        }
        if ("approval".equals(event)) {
            final String id = data.optString("id", "");
            final String tool = data.optString("toolName", data.optString("name", ""));
            final String reason = data.optString("reason", data.optString("text", ""));
            act.ui(new Runnable() {
                public void run() {
                    cv.approval(id, tool, reason);
                }
            });
            return;
        }
        if ("question".equals(event)) {
            final String id = data.optString("id", "");
            final JSONArray qs = data.optJSONArray("questions");
            act.ui(new Runnable() {
                public void run() {
                    cv.question(id, qs);
                }
            });
            return;
        }
        if ("delta".equals(event)) {
            final String chunk = data.optString("text", "");
            logFrame(data.optBoolean("reasoning", false) ? "reasoning" : "text", chunk);
            if (data.optBoolean("reasoning", false)) {   // 思考增量：先于一切门，收到就长出来
                final boolean firstThink = thinkBuf.length() == 0;
                thinkBuf.append(chunk);
                turnStarted = true;                      // 外部发起的回合也据此进入流式态
                act.ui(new Runnable() {
                    public void run() {
                        cv.thinkAppend(chunk);
                        logFrame("R-render", chunk);
                        if (firstThink) setStatus("思考中…", Ui.AMBER);
                    }
                });
                return;
            }
            // 正文增量才需要等回合开始：回执慢/丢会把整段流式吞掉（host 说在跑也算数）
            if ((!turnStarted && !hostRunning) || chunk.length() == 0) return;
            logFrame("T-pass", chunk);
            deltaCount++;
            if (deltaCount == 1) {
                act.ui(new Runnable() {
                    public void run() {
                        cv.thinkEnd();                   // 正文开口 → 思考收成一行
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
        if ("tool-delta".equals(event)) {
            // 工具行先出现，参数边长边补 —— harness 的 preparing 态
            final String cid = data.optString("callId", "");
            if (cid.length() == 0) return;
            final String tname = data.optString("name", "");
            final String chunk = data.optString("text", "");
            logFrame("tool-delta", chunk);
            act.ui(new Runnable() {
                public void run() {
                    cv.toolDelta(cid, tname, chunk);
                }
            });
            return;
        }
        if (!"event".equals(event)) return;
        long seq = data.optLong("seq", -1);
        if (base >= 0 && seq >= 0 && seq <= base) return;
        if (!accepted) {
            accepted = true;   // 外部（PC 侧 watch/harness）发起的回合也要在这里长出来，别整回合不渲染
        }
        String kind = data.optString("kind", "");
        if ("turn-start".equals(kind)) {
            act.ui(new Runnable() {
                public void run() {
                    cv.beginTurn();
                }
            });
        }
        if ("turn-start".equals(kind)) {
            turnStarted = true;
            turnStartedAt = System.currentTimeMillis();
            return;
        }
        if ("turn-end".equals(kind)) {
            // 收尾与 turnStarted 无关：app 中途重启/别人开的回合，我们没见过 turn-start，
            // 但过程组还挂在那儿 —— 不收就会出现"思考早结束了，头上还写思考中"。
            act.ui(new Runnable() {
                public void run() {
                    cv.thinkEnd();
                }
            });
            if (turnStarted) {
                turnStarted = false;
                final JSONObject rs = data.optJSONObject("reason");
                final String rk = rs != null ? rs.optString("kind", "") : data.optString("reason", "");
                final String rcode = rs == null ? "" : rs.optString("code", "");
                final String rmsg = rs == null ? "" : rs.optString("message", "");
                final String rcause = rs == null ? "" : rs.optString("cause", "");
                act.ui(new Runnable() {
                    public void run() {
                        cv.thinkEnd();                    // 只思考、没正文也要把那一行留下
                        cv.botEnd();
                        long secs = Math.max(1, (System.currentTimeMillis() - turnStartedAt + 999) / 1000);
                        cv.turnFooter("\u5DF2\u5B8C\u6210 \u00B7 \u7528\u65F6 " + secs + " \u79D2",
                                cv.turnTraces(), cv.turnTraces().size());
                        // 这轮怎么收的，得说清楚：额度用尽/上下文超限以前是「什么都没发生」
                        if ("error".equals(rk)) {
                            cv.fail(rmsg, rcode);
                        } else if ("aborted".equals(rk)) {
                            cv.note("\u2298 \u5DF2\u6253\u65AD" + (rcause.length() > 0 ? " \u00B7 " + rcause : ""), Ui.DIM);
                        } else if ("max-tokens".equals(rk)) {
                            cv.note("\u26A0 \u8FBE\u5230\u8F93\u51FA\u4E0A\u9650\uFF08max-tokens\uFF09", Ui.AMBER);
                        } else if ("blocked".equals(rk)) {
                            cv.note("\u26A0 \u672C\u8F6E\u88AB\u62E6\u622A\uFF08blocked\uFF09", Ui.DIM);
                        } else if (rk.length() > 0 && !"completed".equals(rk)) {
                            cv.note("\u2014 \u56DE\u5408\u7ED3\u675F \u00B7 " + rk, Ui.DIM);
                        }
                    }
                });
                refreshHostState("turn-end");             // 队列里还有就仍是"停止发送"，不自己判空
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
            if (isMine(ut)) {
                // host 把这条记进会话了（这才叫真落地）→ 坞里那行可以撤了
                final Queued done = queuedByText(ut);
                if (done != null) {
                    act.ui(new Runnable() {
                        public void run() {
                            queue.remove(done);
                            renderQueue();
                        }
                    });
                }
                return;                              // 自己发的本地已渲染过，别重复
            }
            final ArrayList<Img> uim = Img.list(data.optJSONArray("images"));
            act.ui(new Runnable() {
                public void run() {
                    if (looksInjected(ut)) {
                        cv.inject(DshConsole.clamp(ut, 60000));
                    } else if (ut.length() > 0) {
                        cv.user(DshConsole.clamp(ut, 4000));   // 电脑端发的（含带图）也要显示
                    }
                    if (!uim.isEmpty()) {
                        logImg("live user images n=" + uim.size());
                        cv.images(uim, false);
                    }
                }
            });
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
    private final java.util.HashSet<String> toolNames = new java.util.HashSet<String>();

    /**
     * 会话事件的渲染。以前这里只对"不含 / 的事件名"打个灰点，于是 harness 那些
     * 命名空间事件（llm/retry、compaction/*、command/*、approval/*…）**全被静默丢掉** ✗
     * 现在按 harness 的会话事件表一类一类说清楚。
     */
    private void renderEvent(String ty, JSONObject r) {
        if (ty == null || ty.length() == 0) return;
        if (toolNames.contains(ty)) return;                       // 工具名已经在卡片里了
        String extra = evSum(r);
        if (ty.startsWith("llm/retry")) {                          // 模型重试：harness 会插一行
            cv.note("\u27F3 模型重试" + (extra.length() > 0 ? " · " + extra : ""), Ui.AMBER);
            return;
        }
        if (ty.startsWith("compaction/start")) {
            cv.note("\u25A4 正在压缩上下文…", Ui.DIM);
            return;
        }
        if (ty.startsWith("compaction/summary")) {
            cv.note("\u25A4 上下文已压缩（写入摘要）" + (extra.length() > 0 ? " · " + extra : ""), Ui.DIM);
            return;
        }
        if (ty.startsWith("compaction/end")) {
            cv.note("\u25A4 压缩结束", Ui.DIM);
            return;
        }
        if (ty.startsWith("compaction/prune")) {
            cv.note("\u25A4 清理历史", Ui.DIM);
            return;
        }
        if (ty.startsWith("command/run")) {
            cv.note("\u276F 命令 " + extra, Ui.DIM);
            return;
        }
        if (ty.startsWith("command/done")) {
            cv.note("\u2713 命令完成", Ui.DIM);
            return;
        }
        if (ty.startsWith("approval/asked")) {                     // 需要用户拍板（交互还差按钮）
            cv.note("\u26A0 需要你确认" + (extra.length() > 0 ? " · " + extra : ""), Ui.AMBER);
            return;
        }
        if (ty.startsWith("approval/decided")) {
            cv.note("\u00B7 审批已处理" + (extra.length() > 0 ? " · " + extra : ""), Ui.DIM);
            return;
        }
        if (ty.startsWith("request/context") || ty.startsWith("system/message")
                || ty.startsWith("developer/message")) {           // 注入的上下文：折叠，不占屏
            if (extra.length() > 0) cv.inject(DshConsole.clamp(extra, 4000));
            return;
        }
        if (ty.startsWith("deliverables/presented")) {
            JSONArray items = r.optJSONArray("items");
            cv.deliverables(r.optString("name", ""), items);
            return;
        }
        if (noise(ty)) return;
        if (ty.indexOf('/') < 0) cv.note("\u00B7 " + ty, Ui.DIM);
    }

    /** 事件里能给用户看的一点点信息（有 text/reason/attempt/name 就取）。 */
    private static String evSum(JSONObject r) {
        String[] keys = {"text", "reason", "message", "name", "command", "query", "attempt", "model"};
        for (int i = 0; i < keys.length; i++) {
            String v = r.optString(keys[i], "");
            if (v.length() > 0) return DshConsole.clamp(v.replace('\n', ' '), 80);
        }
        return "";
    }

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
        if (low.startsWith("cwd:") || low.startsWith("runtime")
                || low.indexOf("\ncwd:") >= 0 || low.indexOf("<runtime") >= 0) {
            return true;
        }
        // DSH 往会话里塞的运行期上下文有一批固定话术（截图里那种整屏大段的就是它）
        return low.indexOf("runtime context") >= 0
                || low.indexOf("file policy") >= 0
                || low.indexOf("approval requests are auto-granted") >= 0
                || low.indexOf("approval policy") >= 0
                || low.indexOf("sandbox") >= 0 && low.indexOf("dsH file".toLowerCase()) >= 0
                || low.startsWith("current dsh");
    }

    /**
     * 模型偶尔把自家协议的残留吐在**消息最开头**（实测原文：`m00049</ap> 小代码酱。`）。
     * 只剥掉开头这一小段「m+数字</标签>」或「<标签>」，正文一个字都不动；
     * 其余位置的尖括号片段交给 ChatView.tagAt() 按等宽次级色显示。
     */
    private static String stripLeadingArtifact(String s) {
        if (s == null) return "";
        String t = s.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^m?\\d{2,}\\s*</[A-Za-z][A-Za-z0-9]{0,12}>\\s*").matcher(t);
        if (m.find()) {
            logRaw("stripped", t.substring(0, Math.min(t.length(), m.end() + 20)));
            return t.substring(m.end());
        }
        return s;
    }

    /** 文本里出现尖括号时把原文打进 logcat：定位"怪标记"到底是什么（tag=DshRaw）。 */
    private static void logRaw(String where, String text) {
        if (text == null || text.indexOf('<') < 0) return;
        try {
            android.util.Log.i("DshRaw", where + ": " + DshConsole.clamp(text.replace((char) 10, (char) 32), 400));
        } catch (Throwable ignored) {
        }
    }

    /** 上一条记录的 seq：给"编辑并重发"算分叉点用。 */
    private int lastRecordSeq = -1;

    private void renderRecord(JSONObject r) {
        String kind = r.optString("kind", r.optString("type", "?"));
        int seq = r.optInt("seq", -1);
        if ("user".equals(kind)) {
            String txt = r.optString("text", "");
            logRaw("user", txt);
            ArrayList<Img> imgs = Img.list(r.optJSONArray("images"));
            if (!imgs.isEmpty()) logImg("user images n=" + imgs.size());
            // 协议层不区分「我自己发的」和「host 注入的运行期上下文」，两者都是 user 记录：
            // 发出去的原文见过 → 用户气泡；否则按注入上下文折叠展示。
            boolean steer = r.optBoolean("steering", false);
            if (!isMine(txt) && looksInjected(txt)) cv.inject(DshConsole.clamp(txt, 60000));
            else if (txt.length() > 0) {
                // forkSeq 取"上一条记录"的 seq：分叉是**含**切点的，取前一条正好把这条排除出去
                cv.user(DshConsole.clamp(txt, 4000), steer, lastRecordSeq);
            }
            if (!imgs.isEmpty()) cv.images(imgs, true);      // 历史里的图：先画占位，真要显示再去 host 取
        } else if ("inbox".equals(kind)) {
            applyQueueSplice(r.optJSONArray("inserted")); // 队列变了：重新跟 host 对一次账
        } else if ("assistant".equals(kind)) {
            String at = r.optString("text", "");
            logRaw("assistant", at);
            at = stripLeadingArtifact(at);
            if (at.length() > 0) cv.bot(DshConsole.clamp(at, 8000));
            ArrayList<Img> aim = Img.list(r.optJSONArray("images"));
            if (!aim.isEmpty()) {
                logImg("assistant images n=" + aim.size() + " id=" + aim.get(0).attachmentId);
                cv.images(aim, false);                      // agent 自己产出的图（截图 / read_image）
            }
        } else if ("tool-call".equals(kind)) {
            String tn = r.optString("name", "?");
            toolNames.add(tn);
            // callId 是 host 新加的：有了它，结果才能落回自己那一行（而不是塞给最后一行）
            cv.tool(r.optString("callId", ""), tn, r.optString("arguments", ""));
        } else if ("tool-result".equals(kind)) {
            JSONObject e = r.optJSONObject("error");
            String cid = r.optString("callId", "");
            if (e != null) {
                cv.toolResult(cid, "[" + e.optString("name", "error") + "] "
                        + e.optString("reason", e.optString("code", "")), true, false);
            } else {
                cv.toolResult(cid, r.optString("text", ""), false, false);
            }
            ArrayList<Img> tim = Img.list(r.optJSONArray("images"));
            if (!tim.isEmpty()) {
                logImg("tool images n=" + tim.size() + " id=" + tim.get(0).attachmentId);
                cv.images(tim, false);                      // 工具产出的图（read_image / 截图）挂在它下面
            }
        } else if ("event".equals(kind)) {
            renderEvent(r.optString("type", ""), r);
        } else if ("turn-start".equals(kind)) {
            // 「▷ 回合 N」这类内部计数不再往对话里写（要排查看 logcat 就行）
        } else if ("turn-end".equals(kind)) {
            // 「— 回合结束 …」整行都不再往对话里写：
            // 过程组表头/页脚已经表达了收尾；真出问题另有失败卡、打断提示、上限提示。
            // （以前是按 reason 判断，可 reason 在协议里是**对象**——optString 拿到的是一串
            //  {"kind":"completed"}，跟 "completed" 比永远不等，所以怎么都藏不掉。）
            cv.snapToBottom();                     // 收尾再钉一次真正的底部
        }
        if (seq >= 0) lastRecordSeq = seq;         // 给下一条用户消息算分叉点
    }

    // ---------------- 发送 / 打断 / 历史 ----------------

    /** 默认发送 = 挂机等：交给 host 进队列，当前回合跑完自动跑（跟 harness 一样，不拦在本地）。 */
    private void send() {
        submit("queue");
    }

    /** mode: queue=排队挂机等；steer=立刻插进当前回合（就是旁边那个 ⏎ 键）。 */
    private void send(final String modeArg) {
        submit(modeArg);
    }

    /** 统一发送入口：文字 + 待发图片一起走；发出去就在输入卡上挂一条「排队中」。 */
    private void submit(final String modeArg) {
        final String text = input.getText().toString().trim();
        final ArrayList<Img> imgs = new ArrayList<Img>(attachments);
        if (text.length() == 0 && imgs.isEmpty()) return;
        if (sessionId.length() == 0) {
            act.toast("先在侧边栏新建或选择对话");
            act.openDrawer();
            return;
        }
        input.setText("");
        clearAttachments();
        if (text.length() > 0) markMine(text);               // 记账：这条是我发的，历史里别当注入
        // 带上分叉切点：发之前"最新记录的 seq"就是这条消息之前那条 —— 新发的消息也能编辑重发
        if (text.length() > 0) {
            cv.user(DshConsole.clamp(text, 4000), "steer".equals(modeArg), lastRecordSeq);
        }
        if (!imgs.isEmpty()) cv.images(imgs, true);          // 本机图片先本地亮出来，别等 host 回程
        final Queued row = new Queued();
        row.text = text;
        row.imgs = imgs;
        row.rpcId = java.util.UUID.randomUUID().toString();  // 自己铸 id：发出去之前就知道自己是谁
        queue.add(row);
        renderQueue();
        post(text, imgs, modeArg, row);
    }

    /** 真正把一条消息推给 host（mode: queue=等回合结束 / steer=立刻插进去）。 */
    private void post(final String text, final ArrayList<Img> imgs, final String modeArg, final Queued row) {
        deltaCount = 0;
        accepted = false;
        turnStarted = true;
        final Dsh c = conn;
        if (c == null) {
            setStatus("未连接 · 正在重连…", Ui.AMBER);       // 同上：状态进顶栏，别进对话
            loadSession(sessionId, sessionTitle);
            return;
        }
        setStatus("steer".equals(modeArg) ? "插话中…" : "已交给 host 排队…", Ui.AMBER);
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("mode", modeArg);
                    p.put("text", text);
                    p.put("requestId", row == null ? "" : row.rpcId);
                    JSONArray arr = new JSONArray();
                    if (imgs != null) {
                        for (int i = 0; i < imgs.size(); i++) arr.put(imgs.get(i).sendPayload());
                    }
                    p.put("images", arr);
                    sentAt = System.currentTimeMillis();
                    lastEventAt = sentAt;
                    promptId = c.begin("sessions.prompt", p);
                    logSend("prompt 已发出 mode=" + modeArg + " textLen=" + text.length()
                            + " imgs=" + (imgs == null ? 0 : imgs.size())
                            + " rid=" + (row == null ? "-" : row.rpcId));
                    act.ui(new Runnable() {
                        public void run() {
                            if (row != null) {
                                row.sending = false;    // 已经交出去了，接下来以 host 的信箱为准
                                renderQueue();
                            }
                        }
                    });
                    try {
                        Thread.sleep(600);           // 等 host 把这条记进队列，再对一次账
                    } catch (InterruptedException ignored) {
                    }
                    refreshInbox();
                    try {
                        Thread.sleep(3400);          // 局域网里 4 秒还没任何回包 = 不正常
                    } catch (InterruptedException ignored) {
                    }
                    if (lastEventAt <= sentAt) {     // 一个包都没回来，才去查 tail / 重发
                        logSend("4 秒无回包 → 进确认流程");
                        verifyPrompt(text, p, row);
                    }
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            if (row != null) {
                                queue.remove(row);   // 根本没递出去，别在坞里骗人
                                renderQueue();
                            }
                            cv.note("发送失败：" + e.getMessage(), Ui.RED);
                        }
                    });
                    refreshHostState("send-fail");
                }
            }
        });
    }

    /**
     * 发出后确认：`sessions.tail` 里能不能看到这条 user 记录。
     * 「发出去了但迟迟没反应」多半是这条根本没落地（socket 半死 / 被 host 拒了），
     * 这里发现没落地就换一条连接重发一次，并且把话说给用户听。
     */
    private void verifyPrompt(final String text, final JSONObject params, final Queued row) {
        if (sessionId.length() == 0) return;
        act.bg(new Runnable() {
            public void run() {
                Dsh one = null;
                Boolean found = null;
                try {
                    one = act.openDsh(9000);
                    JSONObject q = new JSONObject();
                    q.put("sessionId", sessionId);
                    q.put("limit", 12);
                    JSONObject r = one.request("sessions.tail", q, 20000, null);
                    JSONArray recs = r.optJSONArray("records");
                    found = Boolean.FALSE;
                    if (recs != null) {
                        for (int i = 0; i < recs.length(); i++) {
                            JSONObject rec = recs.optJSONObject(i);
                            if (rec == null || !"user".equals(rec.optString("kind", ""))) continue;
                            String t = rec.optString("text", "").trim();
                            if (text.length() > 0 ? text.equals(t) : true) {
                                found = Boolean.TRUE;
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    logSend("verify 查不了（" + t + "），跳过");
                    return;
                } finally {
                    if (one != null) {
                        try {
                            one.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                final boolean ok = found != null && found.booleanValue();
                logSend("verify " + (ok ? "ok" : "未落地"));
                if (ok) {
                    if (conn == null || !conn.alive()) {          // 消息进了会话，但我们的流可能已经死了
                        // 静默重挂：这只是"我们这条流"的健康问题，不该在对话里弹一行吓人
                        logSend("连接不健康 → 静默重挂监听");
                        act.ui(new Runnable() {
                            public void run() {
                                setStatus("正在重连…", Ui.AMBER);
                                loadSession(sessionId, sessionTitle);
                            }
                        });
                    }
                    return;
                }
                act.ui(new Runnable() {
                    public void run() {
                        setStatus("host 没收到这条 · 换条连接重发…", Ui.AMBER);   // 状态进顶栏
                    }
                });
                resend(params, row);
            }
        });
    }

    /** 兜底重发：走一条全新的短连接，直接把同一条 prompt 再递一次。 */
    private void resend(final JSONObject params, final Queued row) {
        act.bg(new Runnable() {
            public void run() {
                Dsh one = null;
                try {
                    one = act.openDsh(9000);
                    JSONObject r = one.request("sessions.prompt", params, 60000, null);
                    boolean accepted = r.optBoolean("accepted", true);
                    logSend("重发 " + (accepted ? "成功" : "被拒") + " " + r);
                    final boolean ok = accepted;
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note(ok ? "已重发 · 等 host 跑" : "重发被 host 拒绝", ok ? Ui.DIM : Ui.RED);
                        }
                    });
                } catch (final Exception e) {
                    logSend("重发失败: " + e);
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note("重发失败：" + e.getMessage(), Ui.RED);
                        }
                    });
                } finally {
                    if (one != null) {
                        try {
                            one.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    /** 发送链路的诊断日志：`logcat -s DshSend` 看这条。 */
    private static void logSend(String msg) {
        try {
            android.util.Log.i("DshSend", msg);
        } catch (Throwable ignored) {
        }
    }

    /** host 那几种"来晚了"的报错：回合已经结束/这条已经跑掉，属于竞态，别当故障报。 */
    private static boolean benignRace(String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        return m.contains("no longer accepts steering")
                || m.contains("steer-unavailable")
                || m.contains("queue-item-not-found")
                || m.contains("sessions.queue");
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
                    try {
                        Thread.sleep(1200);           // 打断是异步的：等 host 真停下来再问状态
                    } catch (InterruptedException ignored) {
                    }
                    refreshHostState("cancel");
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

    // ==================== 图片：选图 / 待发缩略条 ====================

    /** ＋ 号走系统选择器（不需要任何存储权限）。 */
    private void pickImage() {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            act.startActivityForResult(android.content.Intent.createChooser(it, "选图片"), REQ_PICK_IMAGE);
        } catch (Throwable t) {
            act.toast("打不开相册：" + t.getMessage());
        }
    }

    /** 选择器回来的结果（由 MainActivity.onActivityResult 转发）。 */
    public void onPickResult(android.content.Intent data) {
        if (data == null) return;
        final List<android.net.Uri> uris = new ArrayList<android.net.Uri>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                android.net.Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        act.toast("正在压图…");
        act.bg(new Runnable() {
            public void run() {
                int ok = 0;
                String bad = null;
                for (int i = 0; i < uris.size(); i++) {
                    try {
                        final Img img = Img.fromUri(act, uris.get(i));
                        act.ui(new Runnable() {
                            public void run() {
                                attachments.add(img);
                                renderAttachments();
                            }
                        });
                        ok++;
                    } catch (Exception e) {
                        bad = e.getMessage();
                    }
                }
                final int n = ok;
                final String err = bad;
                act.ui(new Runnable() {
                    public void run() {
                        if (n == 0) act.toast(err == null ? "这几张图都没法用" : err);
                        else if (err != null) act.toast("加了 " + n + " 张，有张不行：" + err);
                    }
                });
            }
        });
    }

    private void clearAttachments() {
        attachments.clear();
        renderAttachments();
        refreshSendButton();
    }

    /** 待发图片：一排圆角缩略图，右上角 ✕ 撤掉。 */
    private void renderAttachments() {
        refreshSendButton();
        if (attStrip == null) return;
        attStrip.removeAllViews();
        if (attachments.isEmpty()) {
            attStrip.setVisibility(View.GONE);
            return;
        }
        attStrip.setVisibility(View.VISIBLE);
        for (int i = 0; i < attachments.size(); i++) {
            final Img img = attachments.get(i);
            final android.widget.FrameLayout cell = new android.widget.FrameLayout(act);
            android.widget.ImageView iv = new android.widget.ImageView(act);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.bg(Ui.SURF3, 12, act));
            iv.setImageBitmap(img.bmp);
            cell.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    Ui.dp(act, 62), Ui.dp(act, 62)));
            TextView del = Ui.tv(act, "✕", 10.5f, 0xFFFFFFFF);
            del.setGravity(Gravity.CENTER);
            del.setBackground(Ui.bg(0xE0222222, 9, act));
            android.widget.FrameLayout.LayoutParams dlp =
                    new android.widget.FrameLayout.LayoutParams(Ui.dp(act, 18), Ui.dp(act, 18));
            dlp.gravity = Gravity.TOP | Gravity.END;
            cell.addView(del, dlp);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    attachments.remove(img);
                    renderAttachments();
                }
            });
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Img.view(act, img);
                }
            });
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(Ui.dp(act, 62), Ui.dp(act, 62));
            lp.rightMargin = Ui.dp(act, 8);
            attStrip.addView(cell, lp);
        }
    }

    // ==================== 队列坞：已交给 host 还没跑的消息 ====================

    /** 排队中的消息挂在输入卡上方：能撤回、能插话（harness 的 queued 行就是干这个的）。 */
    private void renderQueue() {
        if (queueDock == null) return;
        queueDock.removeAllViews();
        if (queue.isEmpty()) {
            queueDock.setVisibility(View.GONE);
            return;
        }
        queueDock.setVisibility(View.VISIBLE);
        for (int i = 0; i < queue.size(); i++) {
            final Queued row = queue.get(i);
            LinearLayout line = Ui.row(act);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setBackground(Ui.bg(Ui.SURF2, 14, act));
            int hp = Ui.dp(act, 8);
            line.setPadding(hp, Ui.dp(act, 6), hp, Ui.dp(act, 6));
            TextView mark = Ui.tv(act, row.sending ? "…" : "⏳", 12.5f, Ui.AMBER);
            line.addView(mark);
            String head = safe(row.text);
            String show = head.length() == 0 ? "(图片 " + row.imgs.size() + " 张)" : DshConsole.clamp(head, 60);
            if (!row.imgs.isEmpty() && head.length() > 0) show = "🖼 " + show;
            TextView tv = Ui.tv(act, show, 12.5f, Ui.DIM);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = Ui.dp(act, 6);
            line.addView(tv, tlp);
            if (row.sending) {
                line.addView(chip("未送出", Ui.DIM, null));
            } else if (row.itemId.length() == 0) {
                line.addView(chip("已送出 · 等 host 回执", Ui.DIM, null));
            } else {
                line.addView(chip("撤回", Ui.RED, new View.OnClickListener() {
                    public void onClick(View v) {
                        queueAction(row, "remove");
                    }
                }));
                line.addView(chip("⏎ 插话", Ui.ACCENT, new View.OnClickListener() {
                    public void onClick(View v) {
                        queueAction(row, "steer");
                    }
                }));
            }
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.bottomMargin = Ui.dp(act, 5);
            queueDock.addView(line, llp);
        }
    }

    private TextView chip(String label, int color, View.OnClickListener click) {
        TextView t = Ui.tv(act, label, 11.5f, color);
        t.setPadding(Ui.dp(act, 9), Ui.dp(act, 4), Ui.dp(act, 9), Ui.dp(act, 4));
        t.setBackground(Ui.bg(Ui.CARD, 12, act));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(act, 6);
        t.setLayoutParams(lp);
        if (click != null) t.setOnClickListener(click);
        return t;
    }

    private String safe(String s) {
        return s == null ? "" : s.replace('\n', ' ');
    }

    /** 按 rpcId 或 host 的 itemId 认人。 */
    private Queued queuedById(String key) {
        if (key == null || key.length() == 0) return null;
        for (int i = 0; i < queue.size(); i++) {
            Queued q = queue.get(i);
            if (key.equals(q.rpcId) || key.equals(q.itemId)) return q;
        }
        return null;
    }

    /** 撤回 / 插话：就地问 host 改队列，别自己猜结果。 */
    private void queueAction(final Queued row, final String kind) {
        final Dsh c = conn;
        if (c == null) {
            act.toast("还没连上 host");
            return;
        }
        if (row.itemId.length() == 0) {
            act.toast("host 还没确认这条，稍等一下");
            return;
        }
        if ("steer".equals(kind) && !hostRunning) {
            // 回合都没在跑，插进哪儿去 —— 直接收掉这行，别发那条注定被拒的请求
            queue.remove(row);
            renderQueue();
            act.toast("回合已经结束，不用插话了");
            return;
        }
        act.toast("steer".equals(kind) ? "插话中…" : "撤回中…");
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    p.put("itemId", row.itemId);
                    JSONObject act1 = new JSONObject();
                    act1.put("kind", kind);
                    p.put("action", act1);
                    c.begin("sessions.queue", p);
                    act.ui(new Runnable() {
                        public void run() {
                            if ("steer".equals(kind)) {
                                queue.remove(row);
                                renderQueue();
                                setStatus("已插话 · 立刻提交", Ui.ACCENT);
                            } else {
                                setStatus("已请求撤回…", Ui.DIM);
                            }
                        }
                    });
                    try {
                        Thread.sleep(900);
                    } catch (InterruptedException ignored) {
                    }
                    refreshInbox();                       // 到底成没成，以 host 的信箱为准
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            cv.note("队列操作失败：" + e.getMessage(), Ui.RED);
                        }
                    });
                }
            }
        });
    }

    /** host 的 inbox 事件：别自己猜 splice 语义，直接重新对一次账（轻量、且权威）。 */
    private void applyQueueSplice(JSONArray inserted) {
        long now = System.currentTimeMillis();
        if (now - lastInboxAt < 500) return;              // 节流：一次变动会连着来好几条
        lastInboxAt = now;
        refreshInbox();
    }

    /**
     * 探一下 PC 侧插件是不是新版：`sessions.inbox` 是那一批一起加的。
     * 旧版直接回 unknown method —— 那就把"哪些功能会失效 + 怎么更新"一次说清楚，
     * 别让用户在图片/删除/审批上一个个撞墙。
     */
    private void probeCapabilities() {
        if (sessionId.length() == 0) return;
        act.bg(new Runnable() {
            public void run() {
                Dsh c = null;
                boolean old = false;
                try {
                    c = act.openDsh(8000);
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sessionId);
                    c.request("sessions.inbox", p, 15000, null);
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage()).toLowerCase();
                    old = m.contains("unknown method") || m.contains("unsupported");
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (!old) return;
                act.ui(new Runnable() {
                    public void run() {
                        cv.note("PC 侧插件是旧版：图片、排队/插话、审批卡、删除对话 这些会失效。"
                                + "在 PC 上 git pull 后重启插件就好（仓库 host-patch/PROMPT-images-inbox.md 有清单）",
                                Ui.AMBER);
                    }
                });
            }
        });
    }

    /** 跟 host 对账信箱：它在的我留着（并记下 itemId），它没有的就是已经跑起来了。 */
    private void refreshInbox() {
        if (sessionId.length() == 0) return;
        final String sid = sessionId;
        new Thread(new Runnable() {
            public void run() {
                Dsh c = null;
                JSONObject snap = null;
                boolean ok = false;
                try {
                    c = act.openDsh(9000);
                    JSONObject p = new JSONObject();
                    p.put("sessionId", sid);
                    snap = c.request("sessions.inbox", p, 20000, null);
                    ok = true;
                } catch (Throwable ignored) {
                    // 老版 host 没这个方法：问不到就当它管不了队列
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                final JSONObject s = snap;
                final boolean good = ok;
                act.ui(new Runnable() {
                    public void run() {
                        if (!sid.equals(sessionId)) return;       // 已经换会话了，这份快照作废
                        if (good) adoptInbox(s);
                        else dropStale();
                    }
                });
            }
        }).start();
    }

    private void collectInbox(JSONArray arr, ArrayList<JSONObject> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
    }

    private void adoptInbox(JSONObject snap) {
        ArrayList<JSONObject> items = new ArrayList<JSONObject>();
        collectInbox(snap == null ? null : snap.optJSONArray("nextTurn"), items);
        collectInbox(snap == null ? null : snap.optJSONArray("nextStep"), items);
        java.util.HashSet<String> alive = new java.util.HashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject o = items.get(i);
            String itemId = o.optString("id", "");
            String rid = o.optString("rpcId", "");
            String txt = o.optString("text", "");
            if (itemId.length() > 0) alive.add(itemId);
            if (rid.length() > 0) alive.add(rid);
            Queued row = rid.length() > 0 ? queuedById(rid) : null;
            if (row == null && itemId.length() > 0) row = queuedById(itemId);
            if (row == null) row = queuedByText(txt);          // 电脑上发的、本机没记过的
            if (row == null) {
                row = new Queued();
                row.rpcId = rid;
                row.text = txt;
                row.imgs = Img.list(o.optJSONArray("images"));
                queue.add(row);
            }
            row.itemId = itemId;
            row.sending = false;
        }
        for (int i = queue.size() - 1; i >= 0; i--) {
            Queued row = queue.get(i);
            if (row.sending) continue;                        // 还在路上，先别动
            if (alive.contains(row.itemId) || alive.contains(row.rpcId)) continue;
            queue.remove(i);                                  // host 信箱里没有 → 它已经跑起来了
        }
        renderQueue();
    }

    /** 问不到信箱（老版 host）时别硬撑着显示「排队中」，过几秒就当它跑起来了。 */
    private void dropStale() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int i = queue.size() - 1; i >= 0; i--) {
            Queued row = queue.get(i);
            if (!row.sending && now - row.at > 300000) {   // 5 分钟还没等到 host 回程，才当它丢了
                queue.remove(i);
                changed = true;
            }
        }
        if (changed) renderQueue();
    }

    private Queued queuedByText(String text) {
        if (text == null || text.length() == 0) return null;
        for (int i = 0; i < queue.size(); i++) {
            Queued q = queue.get(i);
            if (q.text != null && q.text.trim().equals(text.trim())) return q;
        }
        return null;
    }

    /** 一条排队消息：本机在等 host 跑的，就在这儿记着。 */
    static final class Queued {
        String rpcId = "";
        String itemId = "";               // host 记录 id：撤回 / 插话都拿它办事
        String text = "";
        List<Img> imgs = new ArrayList<Img>();
        boolean sending = true;
        long at = System.currentTimeMillis();
    }
}
