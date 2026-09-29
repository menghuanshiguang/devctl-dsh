package com.minis.dshconsole;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 对话流渲染。
 * 观感目标：harness 式 —— 等宽字体、发丝描边卡片、内容整宽、卡片默认折叠、回合用细线分隔。
 * 稳定性要点：所有卡片强制 MATCH_PARENT（宽窄不再随内容跑）；流式逐字追加只在"贴近底部"时
 * 跟随滚动并做 100ms 节流（否则每个字都强制滚一次 = 抖动源）。
 */
public class ChatView extends ScrollView {

    private static final String CURSOR = "\u258D";   // ▍ 流式光标
    private static final String OPEN = " \u25BE";    // ▾ 已展开
    private static final String SHUT = " \u25B8";    // ▸ 已折叠

    private final Context ctx;
    private LinearLayout col;
    private TextView cur;        // 流式中的那一段文本
    private String curRaw = "";
    private long lastScroll;
    private boolean hasContent;

    public ChatView(Context c) {
        super(c);
        this.ctx = c;
        setBackgroundColor(Ui.BG);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        setFillViewport(true);
        setPadding(Ui.dp(c, Ui.PAD_H), Ui.dp(c, Ui.S2), Ui.dp(c, Ui.PAD_H), Ui.dp(c, 2));   // 底部几乎不留：最后一条直接贴着渐隐/输入卡
        col = Ui.col(c);
        addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        empty();
    }

    /** 我发出去的话：右对齐气泡，宽度封顶 84%。 */
    public void user(String text) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S3);
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.bubble(Ui.MINE, ctx, true));
        int p = Ui.dp(ctx, 12);
        b.setPadding(p, Ui.dp(ctx, 9), p, Ui.dp(ctx, 9));
        TextView t = plainBody(text == null ? "" : text, Ui.TEXT);
        t.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84f));
        b.addView(t);
        endRow(b);
        col.addView(copyBar(new String[]{text == null ? "" : text}), fullLp());
        scroll(true);
    }

    /** 消息右下角的小复制按钮。src 是可变引用，流式期间内容会持续增长。 */
    private View copyBar(final String[] src) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.END);
        final TextView t = new TextView(ctx);
        t.setText("\u29C9 \u590D\u5236");
        t.setTextSize(Ui.FS_SMALL - 1);
        t.setTextColor(Ui.MUT);
        t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String s = src[0] == null ? "" : src[0];
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh", s));
                    android.widget.Toast.makeText(ctx, "\u5DF2\u590D\u5236 " + s.length() + " \u5B57",
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    android.widget.Toast.makeText(ctx, "\u590D\u5236\u5931\u8D25\uFF1A" + e.getMessage(),
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 助手整段（历史里的非流式消息）：整宽平铺，不套气泡底。 */
    public void bot(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;                                  // dsh 每个 step 都会发一条空助手记录，别让它占高度
        }
        dropEmpty();
        hasContent = true;
        spacer(Ui.S4);
        col.addView(richBody(text == null ? "" : text, Ui.TEXT), fullLp());
        col.addView(copyBar(new String[]{text}), fullLp());
        scroll(true);
    }

    private String[] copySrc;

    /** 开始流式：先放一个空文本，之后 append 往里塞。 */
    public void botStart() {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S4);
        curRaw = "";
        cur = plainBody("", Ui.TEXT);
        col.addView(cur, fullLp());
        copySrc = new String[]{""};
        col.addView(copyBar(copySrc), fullLp());   // 复制按钮跟着正文一起长
        scroll(true);
    }

    public void append(String chunk) {
        if (chunk == null || chunk.length() == 0) return;
        if (cur == null) botStart();
        curRaw += chunk;
        if (copySrc != null) copySrc[0] = curRaw;
        cur.setText(md(curRaw + CURSOR));
        markLinks(cur, curRaw);
        scroll(false);
    }

    public void botEnd() {
        if (cur == null) return;
        final String raw = curRaw;
        cur.setText(md(raw));
        markLinks(cur, raw);
        // 流式期间是逐字改同一个 TextView，塞不进真表格；收尾时按最终文本重建一次，
        // 让 markdown 表格升级成真 View 表格（否则会一直留着等宽文本表格 + 字面 **）
        if (hasTable(raw)) {
            int idx = col.indexOfChild(cur);
            ViewGroup.LayoutParams lp = cur.getLayoutParams();
            col.removeView(cur);
            View v = richBody(raw, Ui.TEXT);
            if (idx >= 0) col.addView(v, idx, lp);
            else col.addView(v, lp);
        }
        cur = null;
        curRaw = "";
        scroll(true);
    }

    /** 有没有 markdown 表格：某行带竖线、下一行是 | - : 空格 组成的分隔行。 */
    private boolean hasTable(String s) {
        if (s == null || s.indexOf('|') < 0) return false;
        String[] lines = s.split("\n", -1);
        for (int i = 0; i + 1 < lines.length; i++) {
            String a = lines[i].trim();
            String b = lines[i + 1].trim();
            if (a.indexOf('|') < 0 || b.indexOf('|') < 0) continue;
            boolean sep = b.length() > 0;
            for (int k = 0; k < b.length(); k++) {
                char ch = b.charAt(k);
                if (ch != '|' && ch != '-' && ch != ':' && ch != ' ' && ch != '\t') { sep = false; break; }
            }
            if (sep && b.indexOf('-') >= 0) return true;
        }
        return false;
    }

    /** 思考内容：折叠的暗色块，跟工具卡一个交互（点标题展开）。 */
    public void thinking(String text) {
        if (text == null || text.trim().isEmpty()) return;
        fold("\u2726", "思考", text.trim(), Ui.DIM, Ui.SURF2);
    }

    // ── 思考块：流式期间边思考边长，正文一开口自动收起成一行 ──
    private LinearLayout thinkBox;
    private TextView thinkHead;
    private TextView thinkBody;
    private String thinkRaw = "";
    private TextView thinkPrev;               // 流式期间只露最新一行
    private long thinkHeadAt;                 // 标题文字节流：流式期间别每帧 setText，否则点击会被取消

    private boolean following = true;         // 用户是否贴在底部；一旦翻上去就不再抢位置
    private long ignoreScrollUntil;           // 程序化滚动后的短暂静默期，免得把自己的滚动误判成用户操作
    private Runnable followCb;                // 跟随状态变化回调（给右下角下箭头用）

    /** 由外部（TabChat）接管右下角下箭头的显示/隐藏。 */
    public void setFollowCb(Runnable r) {
        followCb = r;
    }

    public boolean isFollowing() {
        return following;
    }

    private void setFollowing(boolean f) {
        if (following == f) return;
        following = f;
        if (followCb != null) followCb.run();
    }

    private boolean atBottom() {
        int gap = (col.getHeight() - getHeight()) - getScrollY();
        return gap <= Ui.dp(ctx, 32);
    }

    /** 回到最新（点右下角下箭头走这里）。 */
    public void jumpToBottom() {
        setFollowing(true);
        scroll(true);
    }

    /** 标题文字节流：250ms 内的重复更新直接吞掉。 */
    private boolean headTick() {
        long now = System.currentTimeMillis();
        if (now - thinkHeadAt < 250) return false;
        thinkHeadAt = now;
        return true;
    }

    /** 视口高度变了（键盘弹起/收起）→ 本来贴着底部就继续贴着最新的消息。 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldh > 0 && h != oldh && following) {
            snapToBottom();                    // 键盘抬起时把消息一起顶上去，别被盖住
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (System.currentTimeMillis() < ignoreScrollUntil) return;   // 自己滚的不算
        setFollowing(atBottom());
    }

    /** ✦ 思考 · N 字 ▾   phase=1 表示还在思考。 */
    private String thinkHeadText(int count, int phase, boolean open) {
        return (phase == 1 ? "\u2726 \u6B63\u5728\u601D\u8003\u2026" : "\u2726 \u601D\u8003")
                + (count > 0 ? " \u00B7 " + count + " \u5B57" : "")
                + (open ? " \u25BE" : " \u25B8");
    }

    private void legacyThinkStart() {
        if (thinkBox != null) return;
        dropEmpty();
        hasContent = true;
        spacer(Ui.S2);
        thinkRaw = "";
        thinkBox = Ui.col(ctx);
        thinkBox.setBackground(Ui.bg(Ui.SURF2, Ui.R_CARD, ctx));
        int pad = Ui.dp(ctx, Ui.PAD_CARD);
        thinkBox.setPadding(pad, Ui.dp(ctx, 10), pad, Ui.dp(ctx, 10));

        final TextView head = new TextView(ctx);
        head.setTextSize(Ui.FS_SMALL);
        head.setTextColor(Ui.DIM);
        final TextView prev = new TextView(ctx);            // 流式期间只露最新一行
        prev.setTextSize(Ui.FS_SMALL);
        prev.setTextColor(Ui.MUT);
        prev.setSingleLine(true);
        prev.setEllipsize(android.text.TextUtils.TruncateAt.START);
        final TextView body = new TextView(ctx);            // 全文：默认折叠
        body.setTextSize(Ui.FS_SMALL - 1);
        body.setTextColor(Ui.DIM);
        body.setLineSpacing(0, 1.15f);
        body.setVisibility(View.GONE);
        final int[] st = new int[]{0, 1, 0};          // 字数 / 是否还在思考 / 用户是否手动展开过
        head.setTag(st);
        head.setText(thinkHeadText(0, 1, false));
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean open = body.getVisibility() == View.VISIBLE;
                body.setVisibility(open ? View.GONE : View.VISIBLE);
                st[1] = 0;
                st[2] = 1;
                head.setText(thinkHeadText(st[0], 0, !open));
                if (prev.getVisibility() == View.VISIBLE) prev.setVisibility(open ? View.VISIBLE : View.GONE);
                if (!open) setFollowing(false);     // 展开后自己看，别让流式把屏幕拽回底部
                else scroll(false);
            }
        });
        thinkBox.addView(head, fullLp());
        thinkBox.addView(prev, fullLp());
        thinkBox.addView(body, fullLp());
        thinkHead = head;
        thinkPrev = prev;
        thinkBody = body;
        col.addView(thinkBox, fullLp());
        scroll(true);
    }

    /** 思考正文的最后一行（太长截尾），用于单行预览。 */
    private String thinkTailLine() {
        String s = thinkRaw.replace("\r", "");
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == ' ')) end--;
        if (end == 0) return "";
        int st = s.lastIndexOf('\n', end - 1) + 1;
        boolean cut = false;
        if (end - st > 60) { st = end - 60; cut = true; }
        return (cut ? "\u2026" : "") + s.substring(st, end).trim();
    }

    /** 思考增量：默认折叠，只在标题下刷新最新一行；展开时只喂尾部一段，标题才够得着。 */
    private void legacyThinkAppend(String chunk) {
        if (thinkBox == null) thinkStart();
        thinkRaw += chunk;
        if (thinkBody != null) thinkBody.setText(thinkShow());
        boolean open = thinkBody != null && thinkBody.getVisibility() == View.VISIBLE;
        if (thinkPrev != null) {
            thinkPrev.setText(thinkTailLine());
            thinkPrev.setVisibility(open ? View.GONE : View.VISIBLE);
        }
        if (thinkHead != null && thinkHead.getTag() instanceof int[]) {
            int[] st = (int[]) thinkHead.getTag();
            st[0] = thinkRaw.length();
            if (headTick()) thinkHead.setText(thinkHeadText(st[0], 1, open));   // 节流，否则点击会被 setText 取消
        }
        scroll(false);
    }

    /** 展开时喂给正文的文本：流式期间只给尾部一段，标题不会被顶出屏幕。 */
    private CharSequence thinkShow() {
        final int cap = 1200;
        if (thinkRaw.length() <= cap) return thinkRaw;
        return "\u2026" + thinkRaw.substring(thinkRaw.length() - cap);
    }

    /** 思考结束：收起，只留一行「✦ 思考 · N 字 ▸」。 */
    private void legacyThinkEnd() {
        if (thinkBox == null) return;
        if (thinkHead != null && thinkHead.getTag() instanceof int[]) {
            int[] st = (int[]) thinkHead.getTag();
            st[0] = thinkRaw.length();
            st[1] = 0;
            boolean open = thinkBody != null && thinkBody.getVisibility() == View.VISIBLE;
            thinkHead.setText(thinkHeadText(st[0], 0, open));
            if (thinkBody != null) thinkBody.setText(thinkRaw);      // 结束后换全文，回看不受限
            if (thinkPrev != null) thinkPrev.setVisibility(View.GONE);
            if (thinkBody != null && st[2] == 0) thinkBody.setVisibility(View.GONE);
        }
        thinkBox = null;
        thinkHead = null;
        thinkPrev = null;
        thinkBody = null;
    }

    /** 提示词注入（host 以 user 记录下发的运行期上下文）。 */
    public void inject(String text) {
        fold("\u2301", "注入上下文", text, Ui.VIOLET, Ui.TINT_INJ);
    }

    // ── 工具组：连续的工具调用/结果并成一张卡，点标题才看里面每一条 ──
    private LinearLayout toolGroup;
    private LinearLayout toolGroupItems;
    private TextView toolGroupHead;
    private int toolGroupN;
    private int toolGroupChars;
    private int groupAt;
    private boolean toolGroupOpen;

    /** 拿当前工具组容器；组后面若已插进别的内容（或界面被清空）就自动收口重开。 */
    private LinearLayout groupHost() {
        if (toolGroup != null && (col == null || col.getChildCount() - 1 != groupAt)) {
            endToolGroup();                        // 组已经不是最后一块 → 说明中间断过
        }
        if (toolGroup == null) {
            toolGroupOpen = false;
            toolGroupN = 0;
            toolGroupChars = 0;
            toolGroup = Ui.col(ctx);
            toolGroup.setBackground(Ui.bg(Ui.TINT_TOOL, Ui.R_CARD, ctx));
            int pad = Ui.dp(ctx, Ui.PAD_CARD);
            toolGroup.setPadding(pad, Ui.dp(ctx, 9), pad, Ui.dp(ctx, 9));
            toolGroupHead = new TextView(ctx);
            toolGroupHead.setTextSize(Ui.FS_SMALL);
            toolGroupHead.setTypeface(Typeface.MONOSPACE);
            toolGroupHead.setSingleLine(true);
            toolGroupHead.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 展开/收起用「挂上 / 摘下」，不用 setVisibility：隐藏的子项仍可能被布局算进高度，
            // 摘掉的视图物理上不可能占位（组卡上下出现大片空白的根治办法）。
            toolGroupItems = Ui.col(ctx);
            final LinearLayout myGroup = toolGroup;
            final LinearLayout myItems = toolGroupItems;
            final TextView myHead = toolGroupHead;
            final int[] st = new int[]{0, 0, toolGroupItems.getParent() == null ? 0 : 1};  // 条目数/字数/展开态
            myGroup.setTag(st);
            toolGroupHead.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean wasOpen = myItems.getParent() != null;
                    if (wasOpen) {
                        myGroup.removeView(myItems);
                    } else {
                        myGroup.addView(myItems, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                    }
                    st[2] = wasOpen ? 0 : 1;
                    if (myGroup == toolGroup) {
                        toolGroupOpen = st[2] == 1;   // 只有"当前组"才同步共享字段
                    }
                    myHead.setText(groupHeadText(st[0], st[1], st[2] == 1));
                    toggleInPlace(myGroup);          // 原地展开/收起，别把人甩到列表最底下
                }
            });
            Ui.press(toolGroupHead, ctx, 0x00000000, Ui.R_CHIP);
            toolGroup.addView(toolGroupHead, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.topMargin = Ui.dp(ctx, 8);            // 只留 8dp：别再叠 fullLp 的上 10 下 4
            col.addView(toolGroup, glp);
            groupAt = col.getChildCount() - 1;
        }
        return toolGroup;
    }

    /** 组标题文案：⚙ 工具调用 ×N · 共 M 字 ▸（按传入计数生成，多组不串味）。 */
    private android.text.SpannableStringBuilder groupHeadText(int n, int chars, boolean open) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append("\u2699 ").append(n < 2 ? "工具调用" : "工具调用 \u00D7" + n);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.AMBER), 0, sb.length(), 0);
        int b = sb.length();
        sb.append("  ").append(chars + " \u5B57").append(open ? OPEN : SHUT);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUT), b, sb.length(), 0);
        return sb;
    }

    /** 刷新当前组标题，并把计数写回该组 tag（收起/展开时要用它）。 */
    private void updateGroupHead() {
        if (toolGroupHead == null) {
            return;
        }
        toolGroupHead.setText(groupHeadText(toolGroupN, toolGroupChars, toolGroupOpen));
        if (toolGroup != null && toolGroup.getTag() instanceof int[]) {
            int[] s = (int[]) toolGroup.getTag();
            s[0] = toolGroupN;
            s[1] = toolGroupChars;
            s[2] = toolGroupOpen ? 1 : 0;
        }
    }

    /**
     * 原地展开/收起：记下这一刻这块在视口里的位置，重排后把滚动位置拉回去，
     * 免得展开一下就被甩到消息列表最底下（点击展开最烦这个）。
     */
    private void toggleInPlace(final View anchor) {
        final int base = getScrollY() - topInCol(anchor);
        anchor.post(new Runnable() {
            public void run() {
                scrollTo(0, Math.max(0, topInCol(anchor) + base));
            }
        });
    }

    /** anchor 相对列表内容（col）顶部的偏移：要一路爬 parent，组内条目嵌了两层，只看 getTop() 会算歪。 */
    private int topInCol(View v) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != col) {
            y += cur.getTop();
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return y;
    }

    /** 收口当前工具组。 */
    private void endToolGroup() {
        toolGroup = null;
        toolGroupHead = null;
    }

    /** 组内单条：剥掉卡片底与内距，只留一行标题 + 上方发丝线（嵌套卡片会看着脏）。 */
    private void addIntoGroup(LinearLayout card) {
        if (toolGroupItems.getChildCount() > 0) {
            View hr = new View(ctx);
            hr.setBackgroundColor(Ui.STROKE);
            toolGroupItems.addView(hr, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 6);
        toolGroupItems.addView(card, lp);
        card.setBackground(null);
        card.setPadding(0, 0, 0, 0);
    }

    /** 工具调用：折叠成一行，点标题才看完整参数。 */
    private void legacyTool(String name, String args) {
        fold("\u2699", name == null ? "?" : name, args, Ui.AMBER, Ui.TINT_TOOL, true);
    }

    /** 工具结果 / 报错。 */
    private void legacyToolResult(String text, boolean err) {
        fold(err ? "\u2717" : "\u21B3", err ? "工具报错" : "工具结果", text,
                err ? Ui.RED : Ui.MUT, err ? Ui.TINT_ERR : Ui.TINT_TOOL, true);
    }

    /** 非工具内容：先收掉当前工具组，再单独出一条折叠卡。 */
    private void fold(final String glyph, final String name, final String full,
                      final int accent, final int tint) {
        endToolGroup();
        fold(glyph, name, full, accent, tint, false);
    }

    /**
     * 折叠卡片：默认只剩一行「图标 名称 · N 字 ▸」，点标题才展开正文。
     * 正文放进横向滚动容器，宽表格不会被挤成一团。groupable = 挂进当前工具组。
     */
    private void fold(final String glyph, final String name, final String full,
                      final int accent, final int tint, boolean groupable) {
        dropEmpty();
        hasContent = true;
        final LinearLayout host = groupable ? groupHost() : null;
        if (!groupable) {
            spacer(Ui.S2);
        }
        final String body = full == null ? "" : full.trim();
        final int len = body.length();

        final LinearLayout card = Ui.col(ctx);
        card.setBackground(Ui.bg(tint, Ui.R_CARD, ctx));
        int pad = Ui.dp(ctx, Ui.PAD_CARD);
        card.setPadding(pad, Ui.dp(ctx, 9), pad, Ui.dp(ctx, 9));

        final TextView head = new TextView(ctx);
        head.setText(headText(glyph, name, len, false, accent));
        head.setTextSize(Ui.FS_SMALL);
        head.setTypeface(Typeface.MONOSPACE);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);

        // 正文懒建：长对话里几百张卡片，不该先把几百个隐藏容器也建出来
        final LinearLayout[] boxRef = {null};
        final boolean[] st = {false};
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                st[0] = !st[0];
                if (st[0] && boxRef[0] == null) {
                    boxRef[0] = bodyBox(body);
                    card.addView(boxRef[0], new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                if (boxRef[0] != null) {
                    boxRef[0].setVisibility(st[0] ? View.VISIBLE : View.GONE);
                }
                head.setText(headText(glyph, name, len, st[0], accent));
                toggleInPlace(card);               // 原地展开/收起（原来这句是 scroll(true)，一点就跳到底 ✗）
            }
        });
        Ui.press(head, ctx, 0x00000000, Ui.R_CHIP);

        card.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (groupable) {
            toolGroupN++;
            toolGroupChars += len;
            addIntoGroup(card);
            updateGroupHead();
        } else {
            col.addView(card, fullLp());
        }
        scroll(true);
    }

    /** 标题行：名称用强调色、字数与开合箭头用弱色（一个 TextView 里两段上色）。 */
    private CharSequence headText(String glyph, String name, int len, boolean open, int accent) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(glyph).append(" ").append(name);
        sb.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, sb.length(), 0);
        int b = sb.length();
        sb.append("  ").append(len == 0 ? "空" : String.valueOf(len) + " 字").append(open ? OPEN : SHUT);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUT), b, sb.length(), 0);
        return sb;
    }

    /** 回合边界用发丝分隔线；彩色提示走居中细字。 */
    public void note(String text, int color) {
        note(text, color, true);
    }

    /** \u9759\u9ed8\u63d0\u793a\uff1a\u53ea\u5728\u672c\u6765\u5c31\u8d34\u5e95\u65f6\u624d\u8ddf\u7740\u6eda\uff0c\u7edd\u4e0d\u628a\u6b63\u5728\u7ffb\u5386\u53f2\u7684\u4eba\u62fd\u5230\u5e95\u90e8\uff08\u91cd\u8fde\u63d0\u793a\u7528\uff09 */
    public void noteQuiet(String text, int color) {
        note(text, color, false);
    }

    private void note(String text, int color, boolean jump) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S1);
        String s = text == null ? "" : text;
        if (color == Ui.DIM) {
            LinearLayout sv = Ui.sep(ctx, s);
            sv.setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3));   // 分隔行上下留白收窄，别在底部空一大块
            col.addView(sv, fullLp());
        } else {
            TextView t = Ui.tv(ctx, s, Ui.FS_SMALL, color);
            t.setGravity(Gravity.CENTER);
            col.addView(t, fullLp());
        }
        scroll(jump);
    }

    public void clear() {
        col.removeAllViews();
        cur = null;
        curRaw = "";
        hasContent = false;
        empty();
    }

    private void empty() {
        LinearLayout b = Ui.col(ctx);
        b.setTag("empty");
        b.setPadding(Ui.dp(ctx, Ui.PAD_H), Ui.dp(ctx, 56), Ui.dp(ctx, Ui.PAD_H), 0);
        TextView t = Ui.tv(ctx, "DshConsole", 22f, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        b.addView(t);
        TextView s = Ui.tv(ctx, "从左边抽屉选设备 / 工作区 / 会话", Ui.FS_SMALL, Ui.DIM);
        s.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        s.setLineSpacing(Ui.dp(ctx, 4), 1f);
        b.addView(s);
        TextView s2 = Ui.tv(ctx, "发一句话就能开跑", Ui.FS_SMALL, Ui.MUT);
        s2.setPadding(0, Ui.dp(ctx, 4), 0, 0);
        b.addView(s2);
        col.addView(b, fullLp());
    }

    private LinearLayout.LayoutParams fullLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 10);          // Minis：消息间距 上 10 / 下 4，别让两条挤成一坨
        lp.bottomMargin = Ui.dp(ctx, 4);
        return lp;
    }

    private void spacer(int dp) {
        View v = new View(ctx);
        col.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(ctx, dp)));
    }

    private final java.util.List<View> pendBubbles = new java.util.ArrayList<View>();
    private final java.util.List<View> pendEnters = new java.util.ArrayList<View>();

    /** 挂起待发：右对齐气泡 + 左侧一个 ⏎；它是"还没发出去"的消息，按 ⏎ 才立刻提交。 */
    public void pendingUser(String text, final View.OnClickListener onSubmit) {
        LinearLayout row = Ui.row(ctx);
        row.setGravity(android.view.Gravity.END);
        final TextView bubble = Ui.tv(ctx, text, Ui.FS_BODY, Ui.TEXT);
        bubble.setBackground(Ui.bg(Ui.SURF3, Ui.R_BUBBLE, ctx));
        int p = Ui.dp(ctx, 11);
        bubble.setPadding(p, p, p, p);
        final TextView enter = Ui.tv(ctx, "⏎", 16f, 0xFF0E1116);
        enter.setGravity(android.view.Gravity.CENTER);
        enter.setBackground(Ui.bg(Ui.AMBER, 20, ctx));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(Ui.dp(ctx, 34), Ui.dp(ctx, 34));
        elp.rightMargin = Ui.dp(ctx, 8);
        enter.setLayoutParams(elp);
        enter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bubble.setBackground(Ui.bg(Ui.MINE, Ui.R_BUBBLE, ctx));
                enter.setVisibility(View.GONE);
                pendEnters.remove(enter);
                pendBubbles.remove(bubble);
                if (onSubmit != null) {
                    onSubmit.onClick(v);
                }
            }
        });
        pendBubbles.add(bubble);
        pendEnters.add(enter);
        row.addView(enter);
        row.addView(bubble);
        col.addView(row, fullLp());
        scroll(true);
    }

    /** 挂起的都放行了：气泡转成正常发出态（MINE 实心）。 */
    public void markPendingSentAll() {
        for (View b : pendBubbles) {
            b.setBackground(Ui.bg(Ui.MINE, Ui.R_BUBBLE, ctx));
        }
        for (View e : pendEnters) {
            e.setVisibility(View.GONE);
        }
        pendBubbles.clear();
        pendEnters.clear();
    }

    private static final int CELL_MAX = 16;

    /**
     * 解析表格块：从 start 起若确是 GFM 表格就返回行数组（end[0] = 块结束下标），否则 null。
     * 流式渲染（等宽文本）与真表格 View 共用这一份扫描逻辑。
     */
    private java.util.ArrayList<java.util.ArrayList<String>> parseTable(String s, int start, int[] end) {
        java.util.ArrayList<java.util.ArrayList<String>> rows =
                new java.util.ArrayList<java.util.ArrayList<String>>();
        int seps = 0;
        int i = start;
        while (i < s.length()) {
            int nl = s.indexOf('\n', i);
            int stop = (nl < 0) ? s.length() : nl;
            String t = s.substring(i, stop).trim();
            if (t.length() < 2 || t.charAt(0) != '|') {
                break;
            }
            String body = t.substring(1, (t.charAt(t.length() - 1) == '|') ? t.length() - 1 : t.length());
            java.util.ArrayList<String> cells = new java.util.ArrayList<String>();
            boolean sep = true;
            int last = 0;
            for (int j = 0; j <= body.length(); j++) {
                if (j == body.length() || body.charAt(j) == '|') {
                    String cell = body.substring(last, j).trim();
                    last = j + 1;
                    if (j < body.length()) {
                        cells.add(cell);
                    } else if (cell.length() > 0) {
                        cells.add(cell);
                    }
                    for (int k = 0; k < cell.length(); k++) {
                        char ch = cell.charAt(k);
                        if (ch != '-' && ch != ':' && ch != ' ') {
                            sep = false;
                            break;
                        }
                    }
                }
            }
            if (sep && cells.size() > 0) {
                seps++;
            } else {
                rows.add(cells);
            }
            i = (nl < 0) ? s.length() : nl + 1;
        }
        if (rows.size() < 2 || seps == 0) {
            return null;
        }
        end[0] = i;
        return rows;
    }

    /** GFM 表格块 → 等宽对齐文本（流式期间用；整条渲染完成后 richBody 会换成真表格）。 */
    private int table(android.text.SpannableStringBuilder out, String s, int start) {
        int[] end = new int[1];
        java.util.ArrayList<java.util.ArrayList<String>> rows = parseTable(s, start, end);
        if (rows == null) {
            return start;
        }
        int i = end[0];                             // 原样返回块结束下标
        int n = 0;
        for (java.util.ArrayList<String> r : rows) {
            if (r.size() > n) {
                n = r.size();
            }
        }
        int[] w = new int[n];
        for (java.util.ArrayList<String> r : rows) {
            for (int c = 0; c < n; c++) {
                int v = cellWidth(c < r.size() ? r.get(c) : "");
                if (v > w[c]) {
                    w[c] = v;
                }
            }
        }
        for (int c = 0; c < n; c++) {
            if (w[c] > CELL_MAX) {
                w[c] = CELL_MAX;
            }
        }
        int total = 3 * (n - 1);                    // 列间「 │ 」占 3 格
        for (int c = 0; c < n; c++) {
            total += w[c];
        }
        while (total > 36) {                        // 一屏装不下就削最宽那列，保证不折行
            int m = 0;
            for (int c = 1; c < n; c++) {
                if (w[c] > w[m]) {
                    m = c;
                }
            }
            if (w[m] <= 4) {
                break;
            }
            w[m]--;
            total--;
        }
        int st = out.length();
        for (int r = 0; r < rows.size(); r++) {
            java.util.ArrayList<String> cells = rows.get(r);
            for (int c = 0; c < n; c++) {
                if (c > 0) {
                    out.append(" │ ");
                }
                out.append(fit(c < cells.size() ? cells.get(c) : "", w[c]));
            }
            out.append("\n");
            if (r == 0) {                                  // parseTable 已保证存在分隔行
                int rs = out.length();
                for (int c = 0; c < n; c++) {
                    if (c > 0) {
                        out.append("─┼─");
                    }
                    for (int k = 0; k < w[c]; k++) {
                        out.append("─");
                    }
                }
                out.setSpan(new android.text.style.ForegroundColorSpan(Ui.STROKE),
                        rs, out.length(), 0);
                out.append("\n");
            }
        }
        out.setSpan(new android.text.style.TypefaceSpan("monospace"), st, out.length(), 0);
        out.setSpan(new android.text.style.RelativeSizeSpan(0.88f), st, out.length(), 0);
        return i;
    }

    /** 显示宽度：CJK/全角算 2 格，其余 1 格。 */
    private static int cellWidth(String c) {
        int w = 0;
        for (int i = 0; i < c.length(); i++) {
            w += isWide(c.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    /** 截断到 n 格宽并补空格对齐。 */
    private static String fit(String c, int n) {
        int w = cellWidth(c);
        if (w > n) {
            StringBuilder b = new StringBuilder();
            int acc = 0;
            for (int i = 0; i < c.length(); i++) {
                int cw = isWide(c.charAt(i)) ? 2 : 1;
                if (acc + cw > n - 1) {
                    break;
                }
                acc += cw;
                b.append(c.charAt(i));
            }
            b.append('…');
            w = acc + 1;
            return b.toString() + spaces(n - w);
        }
        return c + spaces(n - w);
    }

    private static String spaces(int k) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < k; i++) {
            b.append(' ');
        }
        return b.toString();
    }

    private static boolean isWide(char ch) {
        return (ch >= 0x1100 && ch <= 0x115F) || (ch >= 0x2E80 && ch <= 0xA4CF)
                || (ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0xF900 && ch <= 0xFAFF)
                || (ch >= 0xFE30 && ch <= 0xFE6F) || (ch >= 0xFF00 && ch <= 0xFF60)
                || (ch >= 0xFFE0 && ch <= 0xFFE6);
    }

    /** 正文文本：统一字级与行距，并把最小 markdown 渲掉（别把星号摊给用户看）。 */
    /**
     * Minis 式真表格（等价于 ChatMiscViews.BorderedMarkdownTable）：
     * 外框 1dp + 6dp 圆角、表头行铺次级底、列间 1dp 竖线、单元格 14sp / padding 10×8、
     * 每格 weight(1f) 等高，整块可横向滚动（窄屏不折行）。
     */
    /** 显示宽度：CJK 算 2 格（用来定列宽权重）。 */
    private static int disp(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            n += (s.charAt(i) > 0x2E80) ? 2 : 1;
        }
        return n;
    }

    private View tableView(java.util.ArrayList<java.util.ArrayList<String>> rows) {
        int n = Math.max(1, rows.get(0).size());        // 列数以表头为准（否则尾行多余格会冒出幽灵列）
        int[] w = new int[n];
        for (int r = 0; r < rows.size(); r++) {
            java.util.ArrayList<String> cells = rows.get(r);
            for (int c = 0; c < n && c < cells.size(); c++) {
                int dw = disp(cells.get(c));
                if (dw > w[c]) {
                    w[c] = dw;
                }
            }
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.SURF);
        g.setCornerRadius(Ui.dp(ctx, 6));
        g.setStroke(Ui.dp(ctx, 1), Ui.STROKE);
        box.setBackground(g);
        box.setClipToOutline(true);                    // 圆角裁切，行底色不会顶出圆角外
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                View hr = new View(ctx);
                hr.setBackgroundColor(Ui.STROKE);
                box.addView(hr, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        Ui.dp(ctx, 1)));
            }
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            if (r == 0) {
                line.setBackgroundColor(Ui.SURF2);     // 表头行铺次级底（Minis tableHeaderBg）
            }
            java.util.ArrayList<String> cells = rows.get(r);
            for (int c = 0; c < n; c++) {
                if (c > 0) {
                    View v = new View(ctx);
                    v.setBackgroundColor(Ui.STROKE);
                    line.addView(v, new LinearLayout.LayoutParams(Ui.dp(ctx, 1),
                            LinearLayout.LayoutParams.MATCH_PARENT));
                }
                TextView tv = new TextView(ctx);
                tv.setText(md(c < cells.size() ? cells.get(c) : ""));
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.FS_SMALL);
                tv.setTextColor(r == 0 ? Ui.TEXT : Ui.DIM);
                if (r == 0) {
                    tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                }
                tv.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
                tv.setMinWidth(0);
                tv.setTextIsSelectable(true);          // 表格里的字也能长按选中复制
                float wt = Math.max(3f, Math.min(18f, w[c]));   // 按内容宽定权重：均分会把「值」列挤到换行
                line.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, wt));
            }
            box.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(box, new android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    /** 正文：普通段落与真表格混排；没有表格就退回纯文本路径。 */
    private View richBody(String s, int color) {
        if (s == null) {
            s = "";
        }
        if (s.indexOf('|') < 0) {
            return plainBody(s, color);
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        StringBuilder buf = new StringBuilder();
        int[] end = new int[1];
        int i = 0;
        boolean any = false;
        while (i < s.length()) {
            java.util.ArrayList<java.util.ArrayList<String>> rows = parseTable(s, i, end);
            if (rows != null) {
                if (buf.length() > 0) {
                    box.addView(plainBody(buf.toString(), color));
                    buf.setLength(0);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(ctx, 6);
                lp.bottomMargin = Ui.dp(ctx, 6);
                box.addView(tableView(rows), lp);
                any = true;
                i = end[0];
                continue;
            }
            int nl = s.indexOf('\n', i);
            if (nl < 0) {
                buf.append(s, i, s.length());
                break;
            }
            buf.append(s, i, nl + 1);
            i = nl + 1;
        }
        if (buf.length() > 0) {
            box.addView(plainBody(buf.toString(), color));
        }
        return any ? box : plainBody(s, color);
    }

    /** 标题六档字号（Minis MarkdownText.kt HeadingBlock）。 */
    private static float headingSp(int h) {
        if (h <= 1) {
            return Ui.H1;
        }
        if (h == 2) {
            return Ui.H2;
        }
        if (h == 3) {
            return Ui.H3;
        }
        if (h == 4) {
            return Ui.H4;
        }
        if (h == 5) {
            return Ui.H5;
        }
        return Ui.H6;
    }

    private TextView plainBody(String s, int color) {
        TextView t = Ui.tv(ctx, s == null ? "" : s, Ui.FS_BODY, color);
        t.setText(md(s));
        t.setLineSpacing(0, 1.25f);                    // Minis 正文 16sp / 行高≈19-20sp（1.25 倍）
        t.setTextIsSelectable(true);
        markLinks(t, s);                               // 有链接才上 MovementMethod（会顶掉长按选词）
        return t;
    }

    /** markdown 渲染：**粗体** / `等宽` / ~~删除~~ / # 标题 / - 列表 / > 引用 / --- 分隔线。 */
    private CharSequence md(String s) {
        if (s == null) {
            return "";
        }
        if (!hasMd(s)) {
            return s;
        }
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        int i = 0;
        while (i < s.length()) {
            int nl = s.indexOf('\n', i);
            int stop = nl < 0 ? s.length() : nl;
            String line = s.substring(i, stop);
            String t = line.trim();
            int lead = 0;
            while (lead < line.length() && (line.charAt(lead) == ' ' || line.charAt(lead) == '\t')) {
                lead++;
            }
            String pad = line.substring(0, lead);
            if (t.startsWith("|")) {
                int next = table(out, s, i);
                if (next != i) {
                    i = next;
                    out.append("\n");
                    continue;
                }
            }
            if (t.length() >= 3 && (t.equals("---") || t.equals("***") || t.equals("___"))) {
                out.append(pad).append("──────────────");
            } else if (t.startsWith("#")) {
                int h = t.indexOf(' ');
                if (h > 0 && h <= 6) {                       // Minis：标题六档，逐级递减
                    int st = out.length();
                    out.append(t.substring(h + 1));
                    out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, out.length(), 0);
                    out.setSpan(new android.text.style.AbsoluteSizeSpan(
                            Math.round(headingSp(h)), true), st, out.length(), 0);
                } else {
                    inline(out, line);
                }
            } else if (t.startsWith(">")) {
                out.append(pad).append("▏ ");
                inline(out, t.startsWith("> ") ? t.substring(2) : t.substring(1));
            } else if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                out.append(pad).append("·  ");
                inline(out, t.substring(2));
            } else {
                inline(out, line);
            }
            if (nl < 0) {
                break;
            }
            out.append("\n");
            i = nl + 1;
        }
        return out;
    }

    /** 有没有值得渲染的语法（没有就原样返回，省一轮扫描）。 */
    private static boolean hasMd(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('`') >= 0 || s.indexOf('~') >= 0
                || s.indexOf('#') >= 0 || s.indexOf("> ") >= 0
                || s.indexOf("http") >= 0 || s.indexOf("](") >= 0
                || s.startsWith("|") || s.indexOf("\n|") >= 0;
    }

    /** 行内：**粗体** / `等宽` / ~~删除~~，其余原样。 */
    private void inline(android.text.SpannableStringBuilder out, String s) {
        int i = 0;
        while (i < s.length()) {
            int b = s.indexOf("**", i);
            int c = s.indexOf('`', i);
            int k = s.indexOf("~~", i);
            int l = linkAt(s, i);
            int u = urlAt(s, i);
            int best = -1;
            if (b >= 0) {
                best = b;
            }
            if (c >= 0 && (best < 0 || c < best)) {
                best = c;
            }
            if (k >= 0 && (best < 0 || k < best)) {
                best = k;
            }
            if (l >= 0 && (best < 0 || l < best)) {
                best = l;
            }
            if (u >= 0 && (best < 0 || u < best)) {
                best = u;
            }
            if (best < 0) {
                out.append(s.substring(i));
                return;
            }
            if (best == l) {                                  // [文字](链接)
                int mid = s.indexOf("](", l);
                int end = s.indexOf(')', mid + 2);
                out.append(s.substring(i, l));
                int ls = out.length();
                out.append(s.substring(l + 1, mid));
                link(out, ls, out.length(), s.substring(mid + 2, end));
                i = end + 1;
                continue;
            }
            if (best == u) {                                  // 裸链接
                int e = u;
                while (e < s.length() && !isUrlStop(s.charAt(e))) {
                    e++;
                }
                while (e > u && ".,;:!?)]}\u3002\uff0c\uff1b\uff1a\uff01\uff1f\uff09\"'".indexOf(s.charAt(e - 1)) >= 0) {
                    e--;
                }
                out.append(s.substring(i, u));
                int us = out.length();
                out.append(s.substring(u, e));
                link(out, us, out.length(), s.substring(u, e));
                i = e;
                continue;
            }
            String mark = s.startsWith("**", best) ? "**" : (s.startsWith("~~", best) ? "~~" : "`");
            int e = s.indexOf(mark, best + mark.length());
            if (e < 0) {
                out.append(s.substring(i));
                return;
            }
            out.append(s.substring(i, best));
            int st = out.length();
            out.append(s.substring(best + mark.length(), e));
            if ("**".equals(mark)) {
                out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, out.length(), 0);
            } else if ("~~".equals(mark)) {
                out.setSpan(new android.text.style.StrikethroughSpan(), st, out.length(), 0);
            } else {
                out.setSpan(new android.text.style.TypefaceSpan("monospace"), st, out.length(), 0);
                out.setSpan(new android.text.style.ForegroundColorSpan(Ui.AMBER), st, out.length(), 0);
            }
            i = e + mark.length();
        }
    }

    /** 正文里有没有可点的链接（没链接就保持原来的长按选词）。 */
    static boolean hasLink(String s) {
        return s != null && (s.indexOf("http://") >= 0 || s.indexOf("https://") >= 0
                || s.indexOf("](") >= 0);
    }

    /** 找 [文字](url) 的起点；没有返回 -1。 */
    private static int linkAt(String s, int from) {
        int p = s.indexOf('[', from);
        while (p >= 0) {
            int mid = s.indexOf("](", p);
            if (mid > 0 && s.indexOf(')', mid + 2) > 0) {
                return p;
            }
            p = s.indexOf('[', p + 1);
        }
        return -1;
    }

    /** 找裸链接（http:// / https://）的起点；前面紧挨着字母数字的（如 xhttp://）不算。 */
    private static int urlAt(String s, int from) {
        int p = s.indexOf("http", from);
        while (p >= 0) {
            boolean ok = s.startsWith("http://", p) || s.startsWith("https://", p);
            if (ok && p > 0) {
                char pv = s.charAt(p - 1);
                ok = !(Character.isLetterOrDigit(pv) || pv == '_' || pv == '.' || pv == '/');
            }
            if (ok) {
                return p;
            }
            p = s.indexOf("http", p + 4);
        }
        return -1;
    }

    private static boolean isUrlStop(char ch) {
        return ch == ' ' || ch == '\t' || ch == '<' || ch == '>' || ch == '"' || ch == '\u3000';
    }

    /** 给 [st,en) 打上可点链接。 */
    private void link(android.text.SpannableStringBuilder out, int st, int en, final String url) {
        if (en <= st || url.length() == 0) {
            return;
        }
        out.setSpan(new android.text.style.ClickableSpan() {
            @Override
            public void onClick(View v) {
                openLink(url);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(Ui.ACCENT);
                ds.setUnderlineText(false);
            }
        }, st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 点链接：交系统浏览器；实在没有就退化成复制到剪贴板。 */
    private void openLink(String url) {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Exception e) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url));
                note("链接已复制（没找到能打开的浏览器）", Ui.AMBER, true);
            } catch (Exception e2) {
            }
        }
    }

    /** 有链接的正文才上 LinkMovementMethod（它会顶掉长按选词，所以按需上）。 */
    private void markLinks(TextView tv, String s) {
        if (hasLink(s) && tv.getMovementMethod() == null) {
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            tv.setHighlightColor(0);
        }
    }

    private void dropEmpty() {
        View e = col.findViewWithTag("empty");
        if (e != null) col.removeView(e);
    }

    private boolean quiet;                 // 批量灌历史期间：不做逐条滚动

    /** 批量灌历史：期间不逐条滚动（几十次动画会把主线程堵死），收尾一次跳到底。 */
    public void beginBulk() {
        quiet = true;
    }

    public void endBulk() {
        quiet = false;
        scroll(true);
    }

    /** 折叠卡片展开后的正文：横向滚动 + 等宽；超长正文截断，别把布局和内存撑爆。 */
    private LinearLayout bodyBox(String body) {
        LinearLayout box = Ui.col(ctx);
        String t = body;
        if (t.length() > 20000) {
            t = t.substring(0, 20000) + "\n…（已截断，共 " + body.length() + " 字）";
        }
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(Ui.mono(ctx, t, Ui.FS_MONO, Ui.DIM),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setPadding(0, Ui.dp(ctx, 7), 0, 0);
        box.addView(hs, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** 自动跟随：只在贴近底部时跟随，且 100ms 节流 —— 逐字追加不再抖。 */
    private boolean scrollQueued;
    private boolean scrollSmooth;

    private void scroll(boolean force) {
        if (quiet) {
            return;                        // 批量期间不滚，避免几十次动画排队
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!force) {
            if (now - lastScroll < 100) {
                queueScroll(false);        // 被节流掉的也要补一次收尾滚动，否则永远差一截
                return;
            }
            if (!nearBottom() && !nearBottomLoose()) {
                return;
            }
        }
        lastScroll = now;
        queueScroll(force);
    }

    /** 稍后再滚：新内容刚加进来时布局还没结算，立刻滚会滚到旧高度上（"不自动下滑"的真因）。 */
    private void queueScroll(final boolean smooth) {
        if (smooth) {
            scrollSmooth = true;
            setFollowing(true);                      // 强制滚动 = 回到最新，重新开始跟随
        } else if (!following) {
            return;                                  // 用户已经翻上去看别处了，绝不抢位置
        }
        if (scrollQueued) {
            return;
        }
        scrollQueued = true;
        postDelayed(new Runnable() {
            public void run() {
                boolean sm = scrollSmooth;
                scrollQueued = false;
                scrollSmooth = false;
                int y = Math.max(0, col.getHeight() + getPaddingTop() + getPaddingBottom() - getHeight());
                ignoreScrollUntil = System.currentTimeMillis() + 150;   // 自己滚的，别当成用户操作
                if (sm) {
                    smoothScrollTo(0, y);
                } else {
                    scrollTo(0, y);
                }
            }
        }, 40);
    }

    /**
     * 无条件滚到真正的底部：布局结算后再补两枪。
     * 回合结束/收尾用——不走节流、不看 following，避免"永远差一截"。
     */
    public void snapToBottom() {
        following = true;
        postDelayed(new Runnable() {
            public void run() {
                ignoreScrollUntil = System.currentTimeMillis() + 150;
                scrollTo(0, bottomY());
            }
        }, 60);
        postDelayed(new Runnable() {
            public void run() {
                int y = bottomY();
                if (getScrollY() < y - 2) {          // 长文本/图片结算晚，差一点就再补
                    ignoreScrollUntil = System.currentTimeMillis() + 150;
                    scrollTo(0, y);
                }
            }
        }, 320);
        postDelayed(new Runnable() {
            public void run() {
                int y = bottomY();
                if (getScrollY() < y - 2) {          // 复制行/图片结算更晚，再补一枪
                    ignoreScrollUntil = System.currentTimeMillis() + 150;
                    scrollTo(0, y);
                }
            }
        }, 900);
    }

    /** 内容真实底部：列高 + 上下内边距 − 视口高（少算 padding 就会差一截）。 */
    private int bottomY() {
        return Math.max(0, col.getHeight() + getPaddingTop() + getPaddingBottom() - getHeight());
    }

    /** 宽松版"贴近底部"：一屏之内都算跟随，否则内容一长滚动就悄悄停了。 */
    private boolean nearBottomLoose() {
        int gap = (col.getHeight() - getHeight()) - getScrollY();
        return gap <= Math.max(Ui.dp(ctx, 200), getHeight());
    }

    private boolean nearBottom() {
        return getScrollY() + getHeight() >= col.getHeight() - Ui.dp(ctx, 160);
    }

    /** 右对齐一行（我方气泡）。 */
    private void endRow(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.END;
        col.addView(v, lp);
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550 \u8f68\u8ff9\uff1a\u601d\u8003\u6bb5\u843d + \u5de5\u5177\u884c\u5408\u6210\u4e00\u6761\u7ad6\u8f68\uff08\u9762\u677f\u5e38\u9a7b / \u5217\u8868\u5185\u5d4c\u4e24\u79cd\u5bbf\u4e3b\uff09 \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    private Trace trace;
    private int traceAnchor = -1;
    private TextView lastToolDet;                 // \u6700\u540e\u4e00\u6761\u5de5\u5177\u884c\u7684\u8be6\u60c5\u6846\uff08\u7ed3\u679c\u56de\u6765\u65f6\u5f80\u91cc\u585e\uff09
    private String lastToolArgs = "";
    private View traceCard;                       // \u9762\u677f\u5916\u58f3\uff08TabChat \u7ed9\uff09
    private LinearLayout traceHeadHost, traceBodyHost;
    private View traceScrollView;

    /** TabChat \u628a\u300c\u6d88\u606f\u533a\u4e0a\u65b9\u7684\u601d\u8003\u9762\u677f\u300d\u63a5\u8fdb\u6765\uff1a\u6807\u9898\u5bbf\u4e3b + \u6b63\u6587\u5bbf\u4e3b + \u6b63\u6587\u6eda\u52a8\u6761\u3002 */
    public void setTraceHost(View card, LinearLayout headHost, LinearLayout bodyHost, View scroll) {
        traceCard = card;
        trace = null;                 // 换了宿主：旧轨迹的视图归旧宿主，别再把新内容塞进去
        traceAnchor = -1;
        traceHeadHost = headHost;
        traceBodyHost = bodyHost;
        traceScrollView = scroll;
    }

    /** \u8f68\u8ff9\u8fd8\u5728\u539f\u4f4d\u5417\uff1f\u5217\u8868\u6a21\u5f0f\u4e0b\u4e2d\u95f4\u63d2\u4e86\u6b63\u6587/\u65b0\u6d88\u606f\u5c31\u6536\u53e3\uff0c\u4e0b\u4e00\u6bb5\u601d\u8003\u53e6\u5f00\u4e00\u6761\u3002 */
    private Trace traceLive() {
        if (traceHeadHost != null) return trace;      // \u9762\u677f\u6a21\u5f0f\u4e0d\u53c2\u4e0e\u5217\u8868\u9519\u4f4d\u5224\u65ad
        if (trace != null && (col == null || col.getChildCount() - 1 != traceAnchor)) traceEnd();
        return trace;
    }

    /** \u771f\u5f00\u4e00\u6761\u8f68\u8ff9\uff1a\u6709\u9762\u677f\u5c31\u6302\u8fdb\u9762\u677f\uff08\u66ff\u6362\u4e0a\u4e00\u6761\uff09\uff0c\u6ca1\u6709\u5c31\u843d\u5728\u6d88\u606f\u6d41\u91cc\u3002 */
    private void openTrace() {
        if (traceHeadHost != null) {
            trace = new Trace();
            trace.bodyWrap = traceScrollView;
            trace.scroll = traceScrollView;
            if (traceCard != null) traceCard.setVisibility(View.VISIBLE);
            if (traceScrollView != null) traceScrollView.setVisibility(View.VISIBLE);
            trace.box.removeAllViews();          // 先摘下来：head/body 还挂在 box 上，直接 add 会报 has a parent
            traceHeadHost.removeAllViews();
            traceBodyHost.removeAllViews();
            traceHeadHost.addView(trace.head, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            traceBodyHost.addView(trace.body, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return;
        }
        dropEmpty();
        hasContent = true;
        spacer(Ui.S2);
        trace = new Trace();
        col.addView(trace.box, fullLp());
        traceAnchor = col.getChildCount() - 1;
    }

    /** \u5f00\u4e00\u6761\u8f68\u8ff9\uff08\u540c\u4e00\u8f6e\u91cc\u91cd\u590d\u8c03\u7528\u4f1a\u63a5\u5230\u5f53\u524d\u8fd9\u6761\u4e0a\uff09\u3002 */
    public void thinkStart() {
        if (traceHeadHost != null) {
            if (trace != null && !trace.done) return;
            openTrace();
            return;
        }
        if (traceLive() != null) return;
        openTrace();
    }

    /** \u601d\u8003\u589e\u91cf\uff1a\u63a5\u5728\u5f53\u524d\u6bb5\u843d\u540e\u9762\uff0c\u4e0a\u4e00\u6761\u662f\u5de5\u5177\u884c\u5c31\u53e6\u8d77\u4e00\u6bb5\u3002 */
    public void thinkAppend(String chunk) {
        if (chunk == null || chunk.length() == 0) return;
        thinkStart();
        trace.para(chunk);
        scroll(false);
    }

    /** \u601d\u8003\u7ed3\u675f\uff1a\u6807\u9898\u5b9a\u7a3f\u6210\u300c\u5df2\u601d\u8003\uff08\u7528\u65f6 N \u79d2\uff09\u300d\uff0c\u6b63\u6587\u6536\u8d77\uff08\u70b9\u6807\u9898\u8fd8\u80fd\u518d\u5c55\u5f00\uff09\u3002 */
    public void thinkEnd() {
        if (trace == null) return;
        trace.finish();
        if (traceHeadHost == null) { trace = null; traceAnchor = -1; }
        scroll(false);
    }

    private void traceEnd() {
        if (trace == null) return;
        trace.finish();
        trace = null;
        traceAnchor = -1;
    }

    /** \u5de5\u5177\u8c03\u7528\uff1a\u5728\u8f68\u8ff9\u91cc\u843d\u4e00\u884c\u300c\u56fe\u6807 + \u4e2d\u6587\u6458\u8981\u300d\uff0c\u70b9\u8fd9\u884c\u770b\u53c2\u6570\u3002 */
    public void tool(String name, String args) {
        thinkStart();
        trace.toolRow(name, args);
        scroll(false);
    }

    /** \u5de5\u5177\u7ed3\u679c\uff1a\u585e\u8fdb\u4e0a\u4e00\u884c\u5de5\u5177\u884c\u7684\u8be6\u60c5\u91cc\uff1b\u6ca1\u6709\u5bf9\u5e94\u884c\u5c31\u81ea\u5df1\u843d\u4e00\u884c\u3002 */
    public void toolResult(String text, boolean err) {
        if (traceLive() == null) thinkStart();
        trace.result(text, err);
        scroll(false);
    }

    /** \u628a\u4e00\u6bb5\u53c2\u6570\u538b\u6210\u4e00\u884c\u77ed\u6458\u8981\uff1a\u8f6c\u4e49\u6362\u884c\u3001\u5236\u8868\u3001\u591a\u4f59\u7a7a\u767d\u5168\u6536\u6389\uff0c\u8d85\u957f\u622a\u65ad\u3002 */
    private String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ")
                .replace("\\\"", "\"").replace("\\\\", "\\");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > max) t = t.substring(0, max) + "\u2026";
        return t;
    }

    private String toolGlyph(String name, String args) {
        String s = (name == null ? "" : name).toLowerCase() + " " + clean(args, 80).toLowerCase();
        if (s.indexOf("search") >= 0) return "\ud83d\udd0d";
        if (s.indexOf("fetch") >= 0 || s.indexOf("browse") >= 0 || s.indexOf("http") >= 0) return "\ud83c\udf10";
        if (s.indexOf("read") >= 0 || s.indexOf("cat ") >= 0) return "\ud83d\udcc4";
        if (s.indexOf("write") >= 0 || s.indexOf("edit") >= 0 || s.indexOf("patch") >= 0) return "\u270f\ufe0f";
        if (s.indexOf("bash") >= 0 || s.indexOf("shell") >= 0 || s.indexOf("exec") >= 0
                || s.indexOf("cd ") >= 0 || s.indexOf("python") >= 0 || s.indexOf("git ") >= 0
                || s.indexOf("npm ") >= 0 || s.indexOf("powershell") >= 0) return "\ud83d\udda5\ufe0f";
        if (s.indexOf("ls ") >= 0 || s.indexOf("list") >= 0 || s.indexOf("glob") >= 0
                || s.indexOf("dir ") >= 0) return "\ud83d\udcc1";
        if (s.indexOf("grep") >= 0) return "\ud83d\udd0e";
        return "\u2699";
    }

    private String toolLabel(String name, String args) {
        String s = name == null ? "" : name.toLowerCase();
        String v;
        if (s.indexOf("search") >= 0) v = "\u641c\u7d22\u7f51\u9875";
        else if (s.indexOf("fetch") >= 0 || s.indexOf("browse") >= 0 || s.indexOf("visit") >= 0
                || s.indexOf("http") >= 0 || s.indexOf("open") >= 0) v = "\u6d4f\u89c8\u7f51\u9875";
        else if (s.indexOf("read") >= 0 || s.indexOf("cat") >= 0) v = "\u8bfb\u53d6\u6587\u4ef6";
        else if (s.indexOf("write") >= 0 || s.indexOf("edit") >= 0 || s.indexOf("patch") >= 0) v = "\u5199\u5165\u6587\u4ef6";
        else if (s.indexOf("bash") >= 0 || s.indexOf("shell") >= 0 || s.indexOf("exec") >= 0) v = "\u6267\u884c\u547d\u4ee4";
        else if (s.indexOf("list") >= 0 || s.indexOf("ls") >= 0 || s.indexOf("glob") >= 0
                || s.indexOf("find") >= 0) v = "\u67e5\u770b\u76ee\u5f55";
        else if (s.indexOf("grep") >= 0) v = "\u68c0\u7d22\u5185\u5bb9";
        else v = cmdLike(args) ? "\u6267\u884c\u547d\u4ee4" : "\u8c03\u7528\u5de5\u5177";
        String extra = pickArg(args, "query");
        if (extra.length() == 0) extra = pickArg(args, "command");
        if (extra.length() == 0) extra = pickArg(args, "path");
        if (extra.length() == 0) extra = pickArg(args, "url");
        if (extra.length() == 0) extra = args == null ? "" : args;
        extra = clean(extra, 30);
        return extra.length() == 0 ? v : v + " \u00b7 " + extra;
    }

    private boolean cmdLike(String args) {
        String c = clean(args, 60).toLowerCase();
        if (c.length() == 0) return false;
        return c.indexOf("cd ") == 0 || c.indexOf("python") == 0 || c.indexOf("npm ") == 0
                || c.indexOf("git ") == 0 || c.indexOf("ls ") == 0 || c.indexOf("dir ") == 0
                || c.indexOf("$") == 0 || c.indexOf("@\\\"") == 0 || c.indexOf("echo ") == 0;
    }

    /** \u4ece\u5de5\u5177\u53c2\u6570 JSON \u91cc\u62a0\u4e00\u4e2a\u5b57\u7b26\u4e32\u5b57\u6bb5\uff08query/path/command/url \u4e4b\u7c7b\uff09\u3002 */
    private String pickArg(String args, String key) {
        if (args == null || args.length() == 0) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]{0,200})\"").matcher(args);
        return m.find() ? m.group(1) : "";
    }

    /** \u4e00\u6761\u8f68\u8ff9\uff1a\u53ef\u6298\u53e0\u6807\u9898 + \u7ad6\u8f68\u6b63\u6587\uff08\u601d\u8003\u6bb5\u843d / \u5de5\u5177\u884c\u6df7\u6392\uff09\u3002 */
    private class Trace {
        final LinearLayout box = Ui.col(ctx);
        final LinearLayout body = Ui.col(ctx);
        final TextView head = new TextView(ctx);
        final StringBuilder paraBuf = new StringBuilder();
        final long at = System.currentTimeMillis();
        int tools, paras;
        boolean done, open = true, lastTool;
        TextView paraTv;
        View bodyWrap, scroll;

        Trace() {
            head.setTextSize(Ui.FS_SMALL);
            head.setTextColor(Ui.MUT);
            head.setTypeface(Typeface.MONOSPACE);
            head.setSingleLine(true);
            head.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6));
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            body.setBackground(new Ui.Rail(Ui.STROKE, Ui.dp(ctx, 9), Ui.dp(ctx, 2),
                    Ui.dp(ctx, 10), Ui.dp(ctx, 12)));
            head.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    open = !open;
                    body.setVisibility(open ? View.VISIBLE : View.GONE);
                    if (bodyWrap != null) bodyWrap.setVisibility(open ? View.VISIBLE : View.GONE);
                    if (open && bodyWrap != null) bodyWrap.scrollTo(0, 0);   // 展开时从头上看起
                    refresh();
                    toggleInPlace(head);
                }
            });
            Ui.press(head, ctx, Ui.SURF2, Ui.R_CHIP);      // 表头常驻：思考完也留着，随时可点着折叠
            box.addView(head, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            Ui.MaxScroll frame = new Ui.MaxScroll(ctx);           // 大框：表头是框顶盖，流式思考只在框内滚
            frame.maxH = Ui.dp(ctx, 280);
            frame.addView(body, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
            box.addView(frame, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            bodyWrap = frame;
            scroll = frame;
            refresh();
        }

        void refresh() {
            String s;
            if (!done) s = paras > 0 ? "\u601d\u8003\u4e2d\u2026" : "\u8c03\u7528\u5de5\u5177\u4e2d\u2026";
            else if (paras > 0) s = "\u5df2\u601d\u8003\uff08\u7528\u65f6 " + (int) Math.max(1, (System.currentTimeMillis() - at + 999) / 1000) + " \u79d2\uff09";
            else s = "\u5de5\u5177\u8c03\u7528 \u00d7" + tools;
            head.setText(s + (open ? "  \u25be" : "  \u25b8"));
        }

        void follow() {
            if (scroll == null) return;
            final android.widget.ScrollView sv = (android.widget.ScrollView) scroll;
            sv.post(new Runnable() { public void run() { sv.fullScroll(View.FOCUS_DOWN); } });
        }

        /** \u6b63\u6587\u884c\uff1a\u5de6 20dp \u653e\u5706\u70b9/\u56fe\u6807\uff08\u7ad6\u8f68\u4ece\u6b63\u4e2d\u7a7f\u8fc7\uff09\uff0c\u53f3\u8fb9\u662f\u6587\u5b57\u3002 */
        TextView line(String mark, int markColor, int textColor) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView mk = new TextView(ctx);
            mk.setText(mark);
            mk.setTextSize(Ui.FS_SMALL);
            mk.setTextColor(markColor);
            mk.setTypeface(Typeface.MONOSPACE);
            mk.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            row.addView(mk, new LinearLayout.LayoutParams(Ui.dp(ctx, 20),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView tx = new TextView(ctx);
            tx.setTextSize(Ui.FS_SMALL);
            tx.setTextColor(textColor);
            tx.setLineSpacing(Ui.dp(ctx, 4), 1f);
            row.addView(tx, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, 6);
            body.addView(row, lp);
            return tx;
        }

        void para(String chunk) {
            if (paraTv == null || lastTool) {
                paraTv = line("\u2022", Ui.MUT, Ui.DIM);
                paraBuf.setLength(0);
                paras++;
                lastTool = false;
                refresh();                  // \u53ea\u5728\u8d77\u65b0\u6bb5\u65f6\u5237\u6807\u9898\uff1a\u6d41\u5f0f\u671f\u95f4\u522b\u6bcf\u5e27 setText
            }
            paraBuf.append(chunk);
            paraTv.setText(paraBuf.length() > 2600
                    ? "\u2026" + paraBuf.substring(paraBuf.length() - 2600) : paraBuf);
            follow();
        }

        void toolRow(String name, String args) {
            tools++;
            lastTool = true;
            paraTv = null;
            refresh();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView mk = new TextView(ctx);
            mk.setText(toolGlyph(name, args));
            mk.setTextSize(Ui.FS_SMALL);
            mk.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            row.addView(mk, new LinearLayout.LayoutParams(Ui.dp(ctx, 20),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView tx = new TextView(ctx);
            tx.setText(toolLabel(name, args));
            tx.setTextSize(Ui.FS_SMALL);
            tx.setTextColor(Ui.TEXT);
            tx.setLineSpacing(Ui.dp(ctx, 4), 1f);
            row.addView(tx, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final LinearLayout item = Ui.col(ctx);
            item.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            final TextView det = new TextView(ctx);
            det.setTextSize(Ui.FS_SMALL);
            det.setTextColor(Ui.DIM);
            det.setTypeface(Typeface.MONOSPACE);
            det.setBackground(Ui.bg(Ui.SURF2, 10, ctx));
            det.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
            det.setVisibility(View.GONE);
            String raw = args == null ? "" : args.trim();
            det.setText((name == null || name.length() == 0 ? "" : name + "\n")
                    + (raw.length() == 0 ? "(\u65e0\u53c2\u6570)" : raw));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = Ui.dp(ctx, 20);
            dlp.topMargin = Ui.dp(ctx, 6);
            item.addView(det, dlp);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    det.setVisibility(det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    toggleInPlace(item);
                    follow();
                }
            });
            Ui.press(item, ctx, 0x00000000, Ui.R_CHIP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, 6);
            body.addView(item, lp);
            lastToolDet = det;
            lastToolArgs = raw;
            follow();
        }

        void result(String text, boolean err) {
            String t = text == null ? "" : text.trim();
            if (t.length() > 4000) t = t.substring(0, 4000) + "\u2026";
            if (lastToolDet == null) {          // \u7ed3\u679c\u5148\u5230\uff08\u5386\u53f2\u56de\u653e\uff09\u2192 \u81ea\u5df1\u843d\u4e00\u884c
                TextView tx = line(err ? "\u2717" : "\u21b3", err ? Ui.RED : Ui.MUT, Ui.DIM);
                tx.setText(t.length() == 0 ? "(\u7a7a)" : (t.length() > 300 ? t.substring(0, 300) + "\u2026" : t));
                follow();
                return;
            }
            StringBuilder sb = new StringBuilder(lastToolArgs);
            if (t.length() > 0) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(t);
            }
            lastToolDet.setText(sb.length() == 0 ? "(\u7a7a)" : sb.toString());
            if (err) lastToolDet.setTextColor(Ui.RED);
            follow();
        }

        /** \u6536\u5c3e\uff1a\u6807\u9898\u5b9a\u7a3f\uff0c\u6b63\u6587\u6298\u53e0\uff08\u6807\u9898\u5e38\u9a7b\uff0c\u70b9\u4e00\u4e0b\u8fd8\u80fd\u5c55\u5f00\uff09\u3002 */
        void finish() {
            if (done) return;
            if (paraTv != null && paraBuf.length() > 0) paraTv.setText(paraBuf);
            done = true;
            open = false;                                  // 思考结束自动收起，只留表头
            body.setVisibility(View.GONE);
            if (bodyWrap != null) bodyWrap.setVisibility(View.GONE);
            refresh();
        }
    }

}
