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
        final android.widget.TextView jump = new android.widget.TextView(act);   // 右下角下箭头
        jump.setText("\u2193");
        jump.setTextSize(18);
        jump.setTextColor(Ui.TEXT);
        jump.setGravity(android.view.Gravity.CENTER);
        jump.setElevation(Ui.dp(act, 6));
        android.graphics.drawable.GradientDrawable jbg =
                new android.graphics.drawable.GradientDrawable();                // 暗色圆底，不抢内容
        jbg.setColor(Ui.SURF3);
        jbg.setCornerRadius(Ui.dp(act, 20));
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
                if (streaming) {
                    cancel();
                } else {
                    send();
                }
            }
        });
        bar.addView(sendBtn);

        LinearLayout card = new LinearLayout(act);   // DeepSeek 式：整块独立圆角卡片
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.bg(Ui.CARD, 22, act));     // 卡面本身不描边
        int cp = Ui.dp(act, 6);
        card.setPadding(cp, cp, cp, cp);
        card.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        modeRow.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 6), 0);   // 和输入文字左对齐
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp2.topMargin = Ui.dp(act, 2);
        card.addView(modeRow, mlp2);                 // 三颗挪到输入框下面

        LinearLayout outer = new LinearLayout(act);  // 卡片四周留白，同时当阴影的呼吸位
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 14));
        outer.setBackground(new Ui.ShadowBg(act, 22, 12, 2, 0x33000000, Ui.CARD));  // 一点点软阴影
        outer.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);            // 不开软层 shadowLayer 不生效
        outer.addView(card);
        box.addView(outer);
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
            // 正文增量才需要等回合开始：回执慢/丢会把整段流式吞掉
            if (!turnStarted || chunk.length() == 0) return;
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
        if (!"event".equals(event)) return;
        long seq = data.optLong("seq", -1);
        if (base >= 0 && seq >= 0 && seq <= base) return;
        if (!accepted) {
            accepted = true;   // 外部（PC 侧 watch/harness）发起的回合也要在这里长出来，别整回合不渲染
        }
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
                        cv.thinkEnd();                    // 只思考、没正文也要把那一行留下
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
    private final java.util.HashSet<String> toolNames = new java.util.HashSet<String>();

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
            String tn = r.optString("name", "?");
            toolNames.add(tn);
            cv.tool(tn, r.optString("arguments", ""));
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
            if (ty.length() > 0 && !noise(ty) && !toolNames.contains(ty)
                    && ty.indexOf('/') < 0) {                        // 工具名不再重复提示：卡片里已经有了
                cv.note("· " + ty, Ui.DIM);
            }
        } else if ("turn-start".equals(kind)) {
            cv.note("▷ 回合 " + r.optLong("turn", 0), Ui.DIM);
        } else if ("turn-end".equals(kind)) {
            String reason = r.optString("reason", "");
            cv.note("— 回合结束" + (reason.length() > 0 ? " · " + reason : ""), Ui.DIM);
            cv.snapToBottom();                     // 收尾再钉一次真正的底部
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
