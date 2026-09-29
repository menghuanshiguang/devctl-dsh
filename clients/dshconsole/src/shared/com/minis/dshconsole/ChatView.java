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
        user(text, false);
    }

    /** 用户气泡；steering（插话）时加一个小标记，跟普通消息区分开。 */
    public void user(String text, boolean steering) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S3);
        if (steering) {
            LinearLayout tagRow = new LinearLayout(ctx);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setGravity(Gravity.END);
            TextView tag = Ui.tv(ctx, "\u23CE 插话", Ui.FS_TINY, Ui.AMBER);
            tag.setPadding(0, 0, Ui.dp(ctx, 4), 0);
            tagRow.addView(tag);
            col.addView(tagRow, fullLp());
        }
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
        // 流式期间是逐字改同一个 TextView，塞不进真表格 / 代码块；收尾时按最终文本重建一次，
        // 让 markdown 升级成真 View（否则会一直留着等宽文本表格 + 字面 ** 和裸 ```）
        if (hasRich(raw)) {
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
    // ==================== 需要人拍板的卡片（审批 / 提问）和回合页脚 ====================

    /** 审批卡：harness 里"要不要允许这个动作"是 waterfall 请求，插件转发过来，这里出两个按钮。 */
    public void approval(final String id, String tool, String reason) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S1);
        LinearLayout card = Ui.col(ctx);
        card.setBackground(Ui.bg(Ui.TINT_WARN, 10, ctx, Ui.AMBER, 1));
        card.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Ui.tv(ctx, "\u26A0", Ui.FS_SMALL, Ui.AMBER);
        mark.setTypeface(Typeface.MONOSPACE);
        mark.setPadding(0, 0, Ui.dp(ctx, 6), 0);
        head.addView(mark);
        head.addView(Ui.tv(ctx, "需要你确认", Ui.FS_SMALL, Ui.TEXT));
        card.addView(head);
        String t = tool == null ? "" : tool.trim();
        String r = reason == null ? "" : reason.trim();
        if (t.length() > 0) {
            TextView body = Ui.tv(ctx, "工具 · " + t, Ui.FS_TINY, Ui.DIM);
            body.setPadding(0, Ui.dp(ctx, 3), 0, 0);
            card.addView(body);
        }
        if (r.length() > 0) {
            TextView why = Ui.tv(ctx, clean(r, 300), Ui.FS_TINY, Ui.MUT);
            why.setPadding(0, Ui.dp(ctx, 2), 0, 0);
            card.addView(why);
        }
        LinearLayout btns = Ui.row(ctx);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        TextView allow = chip("允许一次", Ui.ACCENT);
        allow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (approvalCb != null) approvalCb.onDecide(id, true);
                note("\u00B7 已允许", Ui.DIM);
            }
        });
        TextView deny = chip("拒绝", Ui.RED);
        deny.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (approvalCb != null) approvalCb.onDecide(id, false);
                note("\u00B7 已拒绝", Ui.DIM);
            }
        });
        btns.addView(allow);
        btns.addView(deny);
        card.addView(btns);
        col.addView(card, fullLp());
        scroll(true);
    }

    /** 审批卡上的按钮回调（App 侧负责发 approvals.decide）。 */
    public interface ApprovalCb {
        void onDecide(String id, boolean allow);
    }

    private ApprovalCb approvalCb;

    public void setApprovalCb(ApprovalCb cb) {
        approvalCb = cb;
    }

    /** 提问卡：agent 的 ask_user_question 也是 waterfall，插件转发过来；每个问题一组选项按钮。 */
    public void question(String id, org.json.JSONArray questions) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S1);
        LinearLayout card = Ui.col(ctx);
        card.setBackground(Ui.bg(Ui.TINT_INFO, 10, ctx, Ui.ACCENT, 1));
        card.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
        card.addView(Ui.tv(ctx, "\u2753 agent 需要你回答", Ui.FS_SMALL, Ui.TEXT));
        for (int i = 0; questions != null && i < questions.length(); i++) {
            org.json.JSONObject q = questions.optJSONObject(i);
            if (q == null) continue;
            final String qid = q.optString("id", "");
            String header = q.optString("header", "");
            String text = q.optString("question", "");
            TextView qt = Ui.tv(ctx, (header.length() > 0 ? header + " \u00B7 " : "") + clean(text, 300),
                    Ui.FS_TINY, Ui.DIM);
            qt.setPadding(0, Ui.dp(ctx, 5), 0, Ui.dp(ctx, 2));
            card.addView(qt);
            LinearLayout opts = Ui.row(ctx);
            opts.setGravity(Gravity.CENTER_VERTICAL);
            org.json.JSONArray os = q.optJSONArray("options");
            for (int k = 0; os != null && k < os.length(); k++) {
                org.json.JSONObject o = os.optJSONObject(k);
                if (o == null) continue;
                final String label = o.optString("label", "");
                if (label.length() == 0) continue;
                TextView b = chip(clean(label, 40), Ui.ACCENT);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (questionCb != null) questionCb.onAnswer(id, qid, label);
                        note("\u00B7 已答：" + label, Ui.DIM);
                    }
                });
                opts.addView(b);
            }
            card.addView(opts);
        }
        col.addView(card, fullLp());
        scroll(true);
    }

    /** 提问卡的回调（App 侧发 questions.answer）。 */
    public interface QuestionCb {
        void onAnswer(String requestId, String questionId, String option);
    }

    private QuestionCb questionCb;

    public void setQuestionCb(QuestionCb cb) {
        questionCb = cb;
    }

    /** 交付物卡：harness 的 deliverables/presented。 */
    public void deliverables(String title, org.json.JSONArray items) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S1);
        LinearLayout card = Ui.col(ctx);
        card.setBackground(Ui.bg(Ui.SURF2, 10, ctx));
        card.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
        card.addView(Ui.tv(ctx, "\uD83D\uDCE6 " + (title == null || title.length() == 0 ? "交付物" : title),
                Ui.FS_SMALL, Ui.TEXT));
        for (int i = 0; items != null && i < items.length(); i++) {
            org.json.JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String name = it.optString("name", it.optString("path", ""));
            String size = it.optString("sizeText", "");
            TextView row = Ui.tv(ctx, "\u00B7 " + clean(name, 80) + (size.length() > 0 ? "  " + size : ""),
                    Ui.FS_TINY, Ui.DIM);
            row.setPadding(0, Ui.dp(ctx, 2), 0, 0);
            card.addView(row);
        }
        col.addView(card, fullLp());
        scroll(true);
    }

    /** 回合页脚：耗时/用量 + 「本轮过程」一键收起。 */
    public void turnFooter(String text, final java.util.List<View> traces, final int rows) {
        LinearLayout card = Ui.col(ctx);
        LinearLayout row = Ui.row(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 2));
        row.addView(Ui.tv(ctx, text == null ? "" : text, Ui.FS_TINY, Ui.MUT),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (traces != null && traces.size() > 0 && rows > 0) {
            final boolean[] open = {true};
            final TextView t = Ui.tv(ctx, "收起过程", Ui.FS_TINY, Ui.ACCENT);
            t.setPadding(Ui.dp(ctx, 8), 0, 0, 0);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    open[0] = !open[0];
                    for (int i = 0; i < traces.size(); i++) {
                        View tr = traces.get(i);
                        tr.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                    }
                    t.setText(open[0] ? "收起过程" : "过程 \u00D7" + rows);
                }
            });
            row.addView(t);
        }
        card.addView(row);
        col.addView(card, fullLp());
        snapToBottom();
    }

    /** 小胶囊按钮（审批 / 提问用）。 */
    private TextView chip(String label, int color) {
        TextView t = Ui.tv(ctx, label, 12.5f, color);
        t.setGravity(Gravity.CENTER);
        int p = Ui.dp(ctx, 10);
        t.setPadding(p, Ui.dp(ctx, 5), p, Ui.dp(ctx, 5));
        t.setBackground(Ui.bg(Ui.SURF2, 12, ctx, Ui.STROKE, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(ctx, 6);
        t.setLayoutParams(lp);
        Ui.press(t, ctx, Ui.SURF3, 12);
        return t;
    }

    public void note(String text, int color) {
        note(text, color, true);
    }

    /** \u9759\u9ed8\u63d0\u793a\uff1a\u53ea\u5728\u672c\u6765\u5c31\u8d34\u5e95\u65f6\u624d\u8ddf\u7740\u6eda\uff0c\u7edd\u4e0d\u628a\u6b63\u5728\u7ffb\u5386\u53f2\u7684\u4eba\u62fd\u5230\u5e95\u90e8\uff08\u91cd\u8fde\u63d0\u793a\u7528\uff09 */
    public void noteQuiet(String text, int color) {
        note(text, color, false);
    }

    /**
     * 本轮失败：把原因摊在对话里。
     * 以前调用方报错（额度用尽 / 上下文超限 / 空响应）手机上就是「什么都没发生」，
     * 看着像卡死。这里用 harness 客户端的口径给一条红卡。
     */
    public void fail(String message, String code) {
        dropEmpty();
        hasContent = true;
        spacer(Ui.S1);
        String m = message == null ? "" : message.trim();
        String c = code == null ? "" : code.trim();
        String head;
        if ("QUOTA".equals(c) || "ACCOUNT_QUOTA".equals(c)) {
            head = "本轮未输出：当前请求的额度已用尽";
        } else if ("CONTEXT_WINDOW_EXCEEDED".equals(c)) {
            head = "本轮未输出：上下文超出上限";
        } else if ("EMPTY_RESPONSE".equals(c)) {
            head = "本轮未输出：模型返回了空响应";
        } else if ("RATE_LIMIT".equals(c)) {
            head = "本轮未输出：请求被限流";
        } else if ("INVALID_CREDENTIAL".equals(c) || "MISSING_CREDENTIAL".equals(c)) {
            head = "本轮未输出：密钥无效或缺失";
        } else {
            head = "本轮未输出";
            if (m.length() == 0 && c.length() > 0) head += "：" + c;
        }
        LinearLayout card = Ui.col(ctx);
        card.setBackground(Ui.bg(Ui.TINT_ERR, 10, ctx, Ui.RED, 1));
        card.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
        LinearLayout headRow = new LinearLayout(ctx);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = Ui.tv(ctx, "⚠", Ui.FS_SMALL, Ui.RED);
        mark.setTypeface(Typeface.MONOSPACE);
        mark.setPadding(0, 0, Ui.dp(ctx, 6), 0);
        headRow.addView(mark);
        TextView t = Ui.tv(ctx, head, Ui.FS_SMALL, Ui.RED);
        t.setSingleLine(false);
        headRow.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (c.length() > 0) {
            TextView badge = Ui.tv(ctx, c, Ui.FS_TINY, Ui.MUT);
            badge.setTypeface(Typeface.MONOSPACE);
            headRow.addView(badge);
        }
        card.addView(headRow, fullLp());
        if (m.length() > 0) {
            TextView body = Ui.tv(ctx, m, Ui.FS_TINY, Ui.DIM);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = fullLp();
            lp.topMargin = Ui.dp(ctx, 6);
            card.addView(body, lp);
        }
        col.addView(card, fullLp());
        spacer(Ui.S2);
        scroll(false);
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
        if (jump && following) {
            scroll(true);                         // 本来就贴在底部：钉死到底（收尾那一下）
        } else {
            scroll(false);                        // 用户翻上去在看东西：绝不抢位置
        }
    }

    public void clear() {
        col.removeAllViews();
        cur = null;
        curRaw = "";
        toolItems.clear();          // 工具行跟着会话一起换：不然新会话的结果会落进旧会话的行
        autoToolId = 0;
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

    // ──────────────── 图片：右对齐（我发的）/ 整宽（工具、别人的）────────────────

    /** 一条消息里的图片。缩略图先用元数据占位，字节从 host 现取，点开看大图。 */
    public void images(java.util.ArrayList<Img> imgs, boolean mine) {
        if (imgs == null || imgs.isEmpty()) {
            return;
        }
        dropEmpty();
        hasContent = true;
        int size = Ui.dp(ctx, mine ? 138 : 168);
        LinearLayout row = Ui.row(ctx);
        row.setGravity(android.view.Gravity.START);
        for (Img img : imgs) {
            row.addView(imgThumb(img, size));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        if (mine) {
            endRow(hs);
        } else {
            col.addView(hs, fullLp());
        }
        scroll(true);
    }

    private View imgThumb(final Img img, final int size) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(ctx);
        int w = size;
        int h = size;
        if (img.width > 0 && img.height > 0) {
            if (img.width >= img.height) {
                h = Math.max(Ui.dp(ctx, 64), size * img.height / img.width);
            } else {
                w = Math.max(Ui.dp(ctx, 64), size * img.width / img.height);
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = Ui.dp(ctx, 6);
        frame.setLayoutParams(lp);

        final android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setBackground(Ui.bg(Ui.SURF3, 12, ctx));
        frame.addView(iv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView ph = Ui.tv(ctx, img.label().length() > 0 ? img.label() : "图片", 10.5f, Ui.MUT);
        ph.setGravity(android.view.Gravity.CENTER);
        frame.addView(ph, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        if (img.bmp != null) {                         // 自己发的图：本地就有，别再去问 host
            iv.setImageBitmap(img.bmp);
            ph.setVisibility(View.GONE);
        } else {
            img.into(iv, ph);
        }
        frame.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ctx instanceof android.app.Activity) {
                    Img.view((android.app.Activity) ctx, img);
                }
            }
        });
        return frame;
    }

    private static final int CELL_MAX = 16;

    /**
     * 解析表格块：从 start 起若确是 GFM 表格就返回行数组（end[0] = 块结束下标），否则 null。
     * 流式渲染（等宽文本）与真表格 View 共用这一份扫描逻辑。
     */
    private java.util.ArrayList<java.util.ArrayList<String>> parseTable(String s, int start, int[] end) {
        return parseTable(s, start, end, null);
    }

    /** align 可以传 null；非空时按分隔行（{@code :---} / {@code :---:} / {@code ---:}）填 0左/1中/2右。 */
    private java.util.ArrayList<java.util.ArrayList<String>> parseTable(String s, int start, int[] end, int[] align) {
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
                if (align != null) {                       // GFM 的对齐就看分隔行两头的冒号
                    for (int k = 0; k < cells.size() && k < align.length; k++) {
                        String c = cells.get(k);
                        boolean l = c.startsWith(":");
                        boolean r = c.endsWith(":");
                        align[k] = (l && r) ? 1 : (r ? 2 : 0);
                    }
                }
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

    private View tableView(java.util.ArrayList<java.util.ArrayList<String>> rows, int[] align) {
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
                int al = (align != null && c < align.length) ? align[c] : 0;   // 0左 1中 2右
                if (al == 1) {
                    tv.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                } else if (al == 2) {
                    tv.setGravity(android.view.Gravity.END);
                }
                tv.setTextIsSelectable(true);          // 表格里的字也能长按选中复制
                float wt = Math.max(3f, Math.min(18f, w[c]));   // 按内容宽定权重：均分会把「值」列挤到换行
                line.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, wt));
            }
            box.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        // 窄表（≤3 列，如 harness 的 md-table）：撑满气泡、文字换行，别为一点点宽度逼人横滑；
        // 宽表才交给横滑容器，保持自然宽、不换行。
        if (n <= 3) {
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return box;
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.addView(box, new android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    /**
     * 找一段围栏代码块：` ```lang ` 起、` ``` ` 收。
     * out = [块起点, 块结束(含结束行), 正文起点, 正文终点]；没找到返回 false，
     * 只有开头没有收尾（流式里很常见）就把剩下的都当正文。
     */
    private static boolean fenceAt(String s, int from, int[] out) {
        int i = lineStart(s, Math.max(0, from));
        while (i < s.length()) {
            String line = lineOf(s, i);
            if (isFenceLine(line)) {
                int codeStart = afterLine(s, i);
                int j = codeStart;
                while (j < s.length()) {
                    int nl = lineStart(s, j);
                    String l2 = lineOf(s, nl);
                    if (isFenceLine(l2)) {
                        int blockEnd = afterLine(s, nl);
                        int codeEnd = nl;
                        if (codeEnd > codeStart && s.charAt(codeEnd - 1) == '\n') codeEnd--;
                        out[0] = i;
                        out[1] = blockEnd;
                        out[2] = codeStart;
                        out[3] = Math.max(codeStart, codeEnd);
                        return true;
                    }
                    int next = s.indexOf('\n', nl);
                    if (next < 0) break;
                    j = next + 1;
                }
                out[0] = i;
                out[1] = s.length();
                out[2] = codeStart;
                int ce = s.length();
                if (ce > codeStart && s.charAt(ce - 1) == '\n') ce--;
                out[3] = Math.max(codeStart, ce);
                return true;
            }
            int next = s.indexOf('\n', i);
            if (next < 0) return false;
            i = next + 1;
        }
        return false;
    }

    /** 行首（该行第一个字符的下标）。 */
    private static int lineStart(String s, int at) {
        int nl = s.lastIndexOf('\n', Math.max(0, at - 1));
        return nl < 0 ? 0 : nl + 1;
    }

    private static String lineOf(String s, int start) {
        int nl = s.indexOf('\n', start);
        return nl < 0 ? s.substring(start) : s.substring(start, nl);
    }

    /** 下一行的行首（跳过本行）。 */
    private static int afterLine(String s, int start) {
        int nl = s.indexOf('\n', start);
        return nl < 0 ? s.length() : nl + 1;
    }

    private static boolean isFenceLine(String line) {
        String t = line.trim();
        return t.startsWith("```") || t.startsWith("~~~");
    }

    /** 围栏的语言标记（```js 里的 js）。 */
    private static String langOf(String s, int blockStart) {
        String t = lineOf(s, blockStart).trim();
        if (t.startsWith("```")) t = t.substring(3);
        else if (t.startsWith("~~~")) t = t.substring(3);
        t = t.trim();
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        return t;
    }

    /**
     * 代码块：顶部一条横幅（语言 + 复制），正文等宽、按 harness 的 pre-wrap 换行。
     * 手机上没有语法高亮引擎，就不假装高亮——干净等宽比乱着色好读。
     */
    private View codeBlock(String lang, final String code) {
        String text = code == null ? "" : code;
        LinearLayout box = Ui.col(ctx);
        box.setBackground(Ui.bg(Ui.SURF2, 12, ctx));
        box.setClipToOutline(true);                        // 圆角裁切，横幅不会顶出角外

        LinearLayout bar = Ui.row(ctx);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.SURF3);
        int bp = Ui.dp(ctx, 10);
        bar.setPadding(bp, Ui.dp(ctx, 6), bp, Ui.dp(ctx, 6));
        TextView tag = Ui.tv(ctx, lang == null || lang.length() == 0 ? "code" : lang, 10.5f, Ui.MUT);
        tag.setTypeface(Typeface.MONOSPACE);
        tag.setSingleLine(true);
        tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(tag, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView copy = Ui.tv(ctx, "复制", 11f, Ui.ACCENT);
        int cp = Ui.dp(ctx, 8);
        copy.setPadding(cp, Ui.dp(ctx, 2), cp, Ui.dp(ctx, 2));
        bar.addView(copy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView body = Ui.tv(ctx, text, 12.5f, Ui.TEXT);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setLineSpacing(Ui.dp(ctx, 3), 1.0f);
        body.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 12));
        box.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text));
                    note("代码已复制 · " + text.split("\n").length + " 行", Ui.DIM, true);
                } catch (Exception e) {
                }
            }
        });
        return box;
    }

    /**
     * 独占一行的 markdown 图片且指向本地图片文件时，返回它的路径（out = 行起止）。
     * 只认「整行就是 ![](...)」，免得把行内的小图也拆成块。
     */
    private String loneImageTarget(String s, int from, int[] out) {
        int start = lineStart(s, from);
        if (start < from) return null;
        String line = lineOf(s, start).trim();
        if (line.length() < 6 || !line.startsWith("![") || !line.endsWith(")")) return null;
        int mid = line.indexOf("](");
        if (mid < 0) return null;
        String target = line.substring(mid + 2, line.length() - 1).trim();
        if (target.length() == 0 || isWebUrl(target) || !Img.looksImage(target)) return null;
        out[0] = start;
        out[1] = afterLine(s, start);
        return target;
    }

    /** host 上的图：缩略图现取（取不到就显示文件名），点开全屏。 */
    private View hostImage(String path) {
        Img img = new Img();
        img.path = path;
        img.name = baseName(path);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(ctx);
        row.addView(imgThumb(img, Ui.dp(ctx, 196)));
        hs.addView(row);
        return hs;
    }

    /** 点开 host 上的图：先落库取字节，取到了就全屏看。 */
    private void openHostImage(final String path) {
        final Img img = new Img();
        img.path = path;
        img.name = baseName(path);
        note("正在取图…", Ui.DIM, true);
        Img.resolve(img, "full", new Img.Cb() {
            public void done(android.graphics.Bitmap b) {
                if (b == null) {
                    note("取不到这张图（host 没读到 / 不是图片 / PC 插件太旧）", Ui.AMBER, true);
                    return;
                }
                Img.put(img, "full", b);
                if (ctx instanceof android.app.Activity) {
                    Img.view((android.app.Activity) ctx, img);
                }
            }
        });
    }

    /** 正文里有没有需要重建成长 View 的东西（真表格 / 代码块）。 */
    private boolean hasRich(String s) {
        if (hasTable(s)) return true;
        if (s == null) return false;
        if (s.indexOf("```") >= 0) return true;
        int i = 0;
        int[] out = new int[2];
        while (i < s.length()) {                       // 独占一行的本地图片
            String p = loneImageTarget(s, i, out);
            if (p != null) return true;
            int nl = s.indexOf('\n', i);
            if (nl < 0) return false;
            i = nl + 1;
        }
        return false;
    }

    /** 正文：普通段落与真表格混排；没有表格就退回纯文本路径。 */
    private View richBody(String s, int color) {
        if (s == null) {
            s = "";
        }
        if (s.indexOf('|') < 0 && s.indexOf("```") < 0) {
            return plainBody(s, color);
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        StringBuilder buf = new StringBuilder();
        int[] end = new int[1];
        int i = 0;
        boolean any = false;
        while (i < s.length()) {
            int[] lone = new int[2];
            String lonePath = loneImageTarget(s, i, lone);     // 独占一行的 ![说明](本地图) → 直接出图
            if (lonePath != null) {
                if (buf.length() > 0) {
                    box.addView(plainBody(buf.toString(), color));
                    buf.setLength(0);
                }
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ilp.topMargin = Ui.dp(ctx, 8);
                ilp.bottomMargin = Ui.dp(ctx, 8);
                box.addView(hostImage(lonePath), ilp);
                any = true;
                i = lone[1] > i ? lone[1] : afterLine(s, i);
                continue;
            }
            int[] fence = new int[4];
            if (fenceAt(s, i, fence)) {                       // ```lang ... ``` → 真代码块
                if (buf.length() > 0) {
                    box.addView(plainBody(buf.toString(), color));
                    buf.setLength(0);
                }
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.topMargin = Ui.dp(ctx, 8);
                clp.bottomMargin = Ui.dp(ctx, 8);
                box.addView(codeBlock(langOf(s, fence[0]), s.substring(fence[2], fence[3])), clp);
                any = true;
                i = fence[1] > i ? fence[1] : afterLine(s, i);   // 防呆：绝不允许原地打转
                continue;
            }
            int[] align = new int[64];
            java.util.ArrayList<java.util.ArrayList<String>> rows = parseTable(s, i, end, align);
            if (rows != null) {
                if (buf.length() > 0) {
                    box.addView(plainBody(buf.toString(), color));
                    buf.setLength(0);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(ctx, 6);
                lp.bottomMargin = Ui.dp(ctx, 6);
                box.addView(tableView(rows, align), lp);
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
        if (hasLink(s)) {
            markLinks(t, s);                           // 有链接：可点（这条消息放弃长按选词）
        } else {
            t.setTextIsSelectable(true);               // 没链接：保持长按选词、出系统复制菜单
        }
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
                if (h > 0 && h <= 6) {  