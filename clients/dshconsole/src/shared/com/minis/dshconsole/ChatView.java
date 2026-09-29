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
        user(text, steering, -1, -1);
    }

    /** forkSeq = 这条消息"之前"那个节点的 seq（编辑重发要从那儿分叉）。 */
    public void user(final String text, boolean steering, final int forkSeq) {
        user(text, steering, forkSeq, -1);
    }

    public void user(final String text, boolean steering, final int forkSeq, final int keep) {
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
        if (forkSeq >= 0 && userEditCb != null) {                 // 长按 = 编辑并重发
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    userEditCb.onEdit(forkSeq, text == null ? "" : text);
                    return true;
                }
            });
        }
        endRow(b);
        forkPoint = forkSeq;
        col.addView(copyBar(new String[]{text == null ? "" : text}), fullLp());
        forkPoint = -1;
        scroll(true);
    }

    /** 长按用户气泡：编辑并重发。 */
    public interface UserEditCb {
        void onEdit(int forkSeq, String text);
    }

    private UserEditCb userEditCb;

    public void setUserEditCb(UserEditCb cb) {
        userEditCb = cb;
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
        if (forkPoint >= 0 && userEditCb != null) {
            // 「编辑」摆在「复制」右边：长按气泡也能编辑，这里给个看得见的入口
            TextView e = new TextView(ctx);
            e.setText("\u270E \u7F16\u8F91");
            e.setTextSize(Ui.FS_SMALL - 1);
            e.setTextColor(Ui.MUT);
            e.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6));
            e.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    userEditCb.onEdit(forkPoint, src[0] == null ? "" : src[0]);
                }
            });
            row.addView(e, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /** 这条气泡对应的分叉点（<0 表示不能编辑，比如助手消息）。 */
    private int forkPoint = -1;

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
                        if ("hidethink".equals(tr.getTag())) continue;   // 纯思考的那块保持隐藏
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

    /**
     * 行内代码的小底：圆角 + 细描边（照 harness 的 radius-sm + border-l1）。
     * BackgroundColorSpan 只能画直角矩形，那种硬方块贴在深色正文里特别扎眼，所以自己画。
     */
    static class RoundBg extends android.text.style.ReplacementSpan {
        private final int bg;
        private final int border;
        private final int radius;
        private final int padX;

        RoundBg(int bg, int border, int radius, int padX) {
            this.bg = bg;
            this.border = border;
            this.radius = radius;
            this.padX = padX;
        }

        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
                           android.graphics.Paint.FontMetricsInt fm) {
            return (int) Math.ceil(paint.measureText(text, start, end)) + padX * 2;
        }

        public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, android.graphics.Paint paint) {
            float w = paint.measureText(text, start, end);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setAntiAlias(true);
            p.setColor(bg);
            android.graphics.RectF r = new android.graphics.RectF(x, top + 1, x + w + padX * 2, bottom - 1);
            canvas.drawRoundRect(r, radius, radius, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(border);
            canvas.drawRoundRect(r, radius, radius, p);
            canvas.drawText(text, start, end, x + padX, y, paint);
        }
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
        t.setLineSpacing(Ui.dp(ctx, 3), 1.06f);        // 中文长段落密排太累，给一点呼吸
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
            int tag = tagAt(s, i);
            int m = imgAt(s, i);
            if (tag >= 0 && (m < 0 || tag < m)) {
                int e = s.indexOf('>', tag);
                if (e > tag && e - tag <= 60) {
                    out.append(s.substring(i, tag));
                    int ts = out.length();
                    String raw = s.substring(tag, e + 1);
                    out.append(raw);
                    // 像标签又不像 markdown 的尖括号片段：等宽 + 次级色，跟"正文乱了"区分开
                    out.setSpan(new android.text.style.TypefaceSpan("monospace"), ts, out.length(), 0);
                    out.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUT), ts, out.length(), 0);
                    i = e + 1;
                    continue;
                }
            }
            int l = linkAt(s, i);
            int u = urlAt(s, i);
            int f = pathAt(s, i);
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
            if (f >= 0 && (best < 0 || f < best)) {
                best = f;
            }
            if (best < 0) {
                out.append(s.substring(i));
                return;
            }
            if (best == m) {                                  // ![说明](目标) → 图片行
                int mid = s.indexOf("](", m);
                int end = s.indexOf(')', mid + 2);
                out.append(s.substring(i, m));
                int ms = out.length();
                String alt = s.substring(m + 2, mid);
                String target = s.substring(mid + 2, end);
                out.append("\uD83D\uDDBC " + (alt.trim().length() > 0 ? alt.trim() : baseName(target)));
                mediaChip(out, ms, out.length(), target, alt);
                i = end + 1;
                continue;
            }
            if (best == f) {                                  // 文件路径 → 文件芯片（点一下复制路径）
                int e = f;
                while (e < s.length() && isPathChar(s.charAt(e))) e++;
                out.append(s.substring(i, f));
                int fs = out.length();
                out.append(s.substring(f, e));
                fileLink(out, fs, out.length(), s.substring(f, e));
                i = e;
                continue;
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
                // 照 harness 的 inline code：只是"等宽 + 略小 + 淡底"的小片，
                // 颜色跟正文一致 —— 以前染成琥珀色，满屏花花绿绿，读起来特别累。
                out.setSpan(new android.text.style.TypefaceSpan("monospace"), st, out.length(), 0);
                out.setSpan(new android.text.style.RelativeSizeSpan(0.92f), st, out.length(), 0);
                out.setSpan(new RoundBg(Ui.ICODE_BG, Ui.ICODE_BD, Ui.dp(ctx, 4), Ui.dp(ctx, 3)),
                        st, out.length(), 0);
                // 行内代码里装的就是个链接（harness 也这么干）→ 顺手让它能点
                String inner = out.subSequence(st, out.length()).toString().trim();
                if (isWebUrl(inner)) {
                    link(out, st, out.length(), inner);
                } else if (Img.looksImage(inner)) {
                    final String ip = inner;                 // `D:\\a\\b.jpg` 这种：点一下去 host 取
                    out.setSpan(new android.text.style.ClickableSpan() {
                        @Override
                        public void onClick(View v) {
                            openHostImage(ip);
                        }
                        @Override
                        public void updateDrawState(android.text.TextPaint ds) {
                            ds.setUnderlineText(false);
                        }
                    }, st, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            i = e + mark.length();
        }
    }

    /** 正文里有没有可点的东西（没有就保持原来的长按选词）。 */
    static boolean hasLink(String s) {
        return s != null && (s.indexOf("http://") >= 0 || s.indexOf("https://") >= 0
                || s.indexOf("](") >= 0 || pathAt(s, 0) >= 0);
    }

    /** 文件路径的字符集（比 URL 窄，别把中文和标点吃进来）。 */
    private static boolean isPathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '\\' || c == '.' || c == '_'
                || c == '-' || c == '~' || c == ':' || c == '+';
    }

    /** 找一处文件路径起点（相对/绝对，可带 :行号）；找不到返回 -1。 */
    private static int pathAt(String s, int from) {
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetter(c) || c == '/' || c == '~')) continue;
            if (i > 0) {                              // 紧跟在路径字符后面的不算起点（说明是中途）
                char pv = s.charAt(i - 1);
                if (Character.isLetterOrDigit(pv) || pv == '/' || pv == '.' || pv == '_'
                        || pv == '-' || pv == '~') continue;
            }
            int e = i;
            while (e < s.length() && isPathChar(s.charAt(e))) e++;
            if (e - i < 3) continue;
            String tok = s.substring(i, e);
            int cut = tok.indexOf(':');
            String p = cut > 0 ? tok.substring(0, cut) : tok;
            if (cut > 0 && !isLineSuffix(tok.substring(cut + 1))) continue;
            if (p.indexOf('/') < 0 && p.indexOf('\\') < 0) continue;   // 得像个路径（也认 Windows 的反斜杠）
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            int dot = p.lastIndexOf('.');
            if (dot <= slash || dot == p.length() - 1 || p.length() - dot > 7) continue;   // 还要有扩展名
            return i;
        }
        return -1;
    }

    /** :12 / :12:5 / :12-30 都算行号后缀。 */
    private static boolean isLineSuffix(String s) {
        if (s.length() == 0) return false;
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** 文件芯片：等宽 + 正文色，点一下复制路径（手机上没有 host 的工作区可翻，复制最实用）。 */
    private void fileLink(android.text.SpannableStringBuilder out, int st, int en, final String path) {
        if (en <= st) return;
        out.setSpan(new android.text.style.TypefaceSpan("monospace"), st, en, 0);
        out.setSpan(new android.text.style.RelativeSizeSpan(0.94f), st, en, 0);
        out.setSpan(new android.text.style.ClickableSpan() {
            @Override
            public void onClick(View v) {
                copyPath(path);
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setUnderlineText(false);
            }
        }, st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void copyPath(String path) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
            note("路径已复制 · " + path, Ui.DIM, true);
        } catch (Exception e) {
        }
    }

    /**
     * 找形如 <tag> / </tag> / <tag attr> 的尖括号片段（单个 token，不含空白与换行）。
     * llm 偶尔会吐这种半截 XML；harness 里它们就是普通文本，我们至少别让它看起来像正文乱码。
     */
    private static int tagAt(String s, int from) {
        int p = s.indexOf('<', from);
        while (p >= 0) {
            int e = s.indexOf('>', p + 1);
            if (e > p && e - p <= 60) {
                String body = s.substring(p + 1, e);
                if (body.length() > 0 && body.indexOf('\n') < 0 && body.indexOf('\t') < 0
                        && body.indexOf('<') < 0) {
                    return p;
                }
            }
            p = s.indexOf('<', p + 1);
        }
        return -1;
    }

    /** 找 ![说明](目标) 的起点；没有返回 -1。 */
    private static int imgAt(String s, int from) {
        int p = s.indexOf("![", from);
        while (p >= 0) {
            int mid = s.indexOf("](", p);
            if (mid > 0 && s.indexOf(')', mid + 2) > 0) {
                return p;
            }
            p = s.indexOf("![", p + 2);
        }
        return -1;
    }

    private static String baseName(String path) {
        if (path == null) return "图片";
        int cut = path.lastIndexOf('/');
        String b = cut >= 0 ? path.substring(cut + 1) : path;
        int q = b.indexOf('?');
        if (q > 0) b = b.substring(0, q);
        return b.length() == 0 ? "图片" : b;
    }

    /**
     * 图片行：手机端拿不到 host 工作区的字节（协议没有读文件的口子），所以做成可点的芯片 ——
     * http(s) 的交给浏览器打开，本地路径的点一下复制。绝不把 ![]() 拆成满屏乱码。
     */
    private void mediaChip(android.text.SpannableStringBuilder out, int st, int en, final String target,
                           final String alt) {
        if (en <= st) return;
        boolean web = isWebUrl(target);
        out.setSpan(new android.text.style.ForegroundColorSpan(web ? Ui.ACCENT : Ui.TEXT), st, en, 0);
        out.setSpan(new android.text.style.ClickableSpan() {
            @Override
            public void onClick(View v) {
                if (isWebUrl(target)) {
                    openLink(target);
                } else if (Img.looksImage(target)) {
                    openHostImage(target);            // host 上的图：落库 → 取字节 → 全屏看
                } else {
                    copyPath(target);
                    note("图片在 host 上：" + target, Ui.DIM, true);
                }
            }
            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setUnderlineText(false);
            }
        }, st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    /** 找裸链接（http(s):// 或 mailto:）的起点；前面紧挨着字母数字的（如 xhttp://）不算。 */
    private static int urlAt(String s, int from) {
        int a = schemeAt(s, from, "http");
        int b = schemeAt(s, from, "mailto:");
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static int schemeAt(String s, int from, String key) {
        int p = s.indexOf(key, from);
        while (p >= 0) {
            boolean ok = key.endsWith(":") || s.startsWith("http://", p) || s.startsWith("https://", p);
            if (ok && p > 0) {
                char pv = s.charAt(p - 1);
                ok = !(Character.isLetterOrDigit(pv) || pv == '_' || pv == '.' || pv == '/');
            }
            if (ok) {
                return p;
            }
            p = s.indexOf(key, p + key.length());
        }
        return -1;
    }

    /** 真能交给系统打开的协议；其余（本地路径）走文件芯片。 */
    private static boolean isWebUrl(String u) {
        if (u == null) return false;
        String l = u.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("mailto:")
                || l.startsWith("tel:") || l.startsWith("ftp://");
    }

    private static boolean isUrlStop(char ch) {
        return ch == ' ' || ch == '\t' || ch == '<' || ch == '>' || ch == '"' || ch == '\u3000';
    }

    /** 给 [st,en) 打上可点链接；目标是本地路径的话做成文件芯片（点一下复制路径）。 */
    private void link(android.text.SpannableStringBuilder out, int st, int en, final String url) {
        if (en <= st || url.length() == 0) {
            return;
        }
        if (!isWebUrl(url)) {
            fileLink(out, st, en, url);
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
        if (!hasLink(s)) {
            return;
        }
        if (tv.getMovementMethod() instanceof android.text.method.LinkMovementMethod) {
            return;
        }
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tv.setHighlightColor(0);
        tv.setLinkTextColor(Ui.ACCENT);
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
        if (!following) {
            return;                               // 用户翻上去在看（例如读思考过程）：绝不抢位置
        }
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
    /** 本轮创建过的过程组：回合页脚用它做「收起过程」。 */
    private final java.util.List<View> turnTraces = new java.util.ArrayList<View>();

    /** 新回合开始：清掉上一轮的登记。 */
    public void beginTurn() {
        turnTraces.clear();
    }

    public java.util.List<View> turnTraces() {
        return turnTraces;
    }

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
        if (!turnTraces.contains(trace.box)) turnTraces.add(trace.box);
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
        tool(null, name, args);
        scroll(false);
    }

    /** \u5de5\u5177\u7ed3\u679c\uff1a\u585e\u8fdb\u4e0a\u4e00\u884c\u5de5\u5177\u884c\u7684\u8be6\u60c5\u91cc\uff1b\u6ca1\u6709\u5bf9\u5e94\u884c\u5c31\u81ea\u5df1\u843d\u4e00\u884c\u3002 */
    public void toolResult(String text, boolean err) {
        if (traceLive() == null) thinkStart();
        toolResult(null, text, err, false);
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
    /**
     * 一条轨迹：表头 + 竖轨正文（思考段落 / 工具行混排）。
     *
     * 对齐 harness 客户端 ui-chat/ReasoningRow：
     *  · 折叠摘要取「最新一段的首行」（latestCompletedParagraphFirstLine），
     *    不是字数、不是尾行、也不是往上滚的截尾；摘要里剥掉 ** 标记；
     *  · 结束时摘要换成全文首行（harness 的 settled 行为）；
     *  · 正文不截尾（harness 展开就是全文），只做刷新节流，免得每帧 setText 把点击打断。
     */
    private class Trace {
        final LinearLayout box = Ui.col(ctx);
        final LinearLayout headerRow = new LinearLayout(ctx);
        final TextView hMark = new TextView(ctx);
        final TextView hTitle = new TextView(ctx);
        final TextView hPrev = new TextView(ctx);
        final TextView hCare = new TextView(ctx);
        final LinearLayout body = Ui.col(ctx);
        final TextView head = new TextView(ctx);            // 兼容旧引用（不再挂到树上）
        final StringBuilder paraBuf = new StringBuilder();
        final long at = System.currentTimeMillis();
        long endAt;
        final java.util.ArrayList<ToolItem> items = new java.util.ArrayList<ToolItem>();
        final java.util.LinkedHashMap<String, Integer> cats = new java.util.LinkedHashMap<String, Integer>();
        int tools, paras;
        long lastPaint;
        boolean done, open = false, lastTool;
        ThinkRow curThink;
        TextView paraTv;
        View bodyWrap, scroll;

        Trace() {
            box.setBackgroundColor(0x00000000);      // harness 的过程行不套卡片：直接长在消息底色上
            box.setPadding(0, 0, 0, 0);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            headerRow.setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2));
            hMark.setTextSize(Ui.FS_SMALL);
            hMark.setTypeface(Typeface.MONOSPACE);
            hMark.setPadding(0, 0, Ui.dp(ctx, 6), 0);
            headerRow.addView(hMark);
            hTitle.setTextSize(Ui.FS_SMALL);
            hTitle.setTypeface(Typeface.MONOSPACE);
            hTitle.setSingleLine(true);
            headerRow.addView(hTitle);
            hPrev.setTextSize(Ui.FS_SMALL);
            hPrev.setTextColor(Ui.DIM);
            hPrev.setSingleLine(true);
            hPrev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            hPrev.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 6), 0);
            headerRow.addView(hPrev, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            hCare.setTextSize(Ui.FS_SMALL);
            hCare.setTextColor(Ui.MUT);
            hCare.setTypeface(Typeface.MONOSPACE);
            headerRow.addView(hCare);
            body.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 2), 0, 0);
            headerRow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    open = !open;
                    body.setVisibility(open ? View.VISIBLE : View.GONE);
                    if (bodyWrap != null) bodyWrap.setVisibility(open ? View.VISIBLE : View.GONE);
                    if (open) {
                        setFollowing(false);          // 自己在看思考过程：别让流式把外层列表拽走
                        jumpBody();                   // 再展开：回到思考内容最底部（最新流式输出）
                    }
                    refresh();
                    toggleInPlace(headerRow);
                }
            });
            Ui.press(headerRow, ctx, 0x00000000, Ui.R_CHIP);
            box.addView(headerRow, new LinearLayout.LayoutParams(
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
            // 默认收起（harness 标准模式：过程组正文初始收起，实时状态写在组头那一行）
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            frame.setVisibility(open ? View.VISIBLE : View.GONE);
            refresh();
        }

        void refresh() {
            paintHead();
        }

        /**
         * 组头：照 harness 的过程组规则 —— 运行中写「当前活动」，结算后写「前三个类别」（不带次数）。
         * 图标始终是类别的业务图标，运行中整行走一道高光（TextShimmer）。
         */
        void paintHead() {
            boolean run = !done;
            String act = run ? runningActivity() : topActivity();
            boolean thinking = "thinking".equals(act);
            hMark.setText(thinking ? "\u2726" : toolIcon(act));
            hMark.setTextColor(thinking ? (run ? Ui.AMBER : Ui.MUT) : toolColor(act));
            String label;
            if (run) {
                label = thinking ? "\u601D\u8003\u4E2D"
                        : (runningPreparing() ? "\u51C6\u5907" + toolTitle(act) : toolTitle(act));
            } else {
                label = closedTitle();
            }
            hTitle.setText(label);
            hTitle.setTextColor(run ? Ui.MUT : Ui.DIM);
            if (run) {
                Ui.shimmer(hTitle, Ui.MUT, Ui.TEXT);
            } else {
                Ui.stopShimmer(hTitle);
            }
            String detail;
            if (run) {
                detail = liveDetail();
            } else {
                long secs = Math.max(1, ((endAt > 0 ? endAt : System.currentTimeMillis()) - at + 999) / 1000);
                detail = "\u7528\u65F6 " + secs + " \u79D2";
            }
            hPrev.setText(detail);
            if (run) {
                Ui.shimmer(hPrev, Ui.DIM, Ui.TEXT);
            } else {
                Ui.stopShimmer(hPrev);
            }
            hCare.setText(open ? "\u25BE" : "\u25B8");
        }

        /** 正在跑的类别：有工具在跑就是它，否则算思考。 */
        String runningActivity() {
            for (int i = items.size() - 1; i >= 0; i--) {
                ToolItem it = items.get(i);
                if (it.phase <= 1) return toolVariant(it.name);
            }
            return "thinking";
        }

        boolean runningPreparing() {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).phase == 0) return true;
            }
            return false;
        }

        /** 类别按次数排序，次数一样就按出现先后（harness 的 processActivity）。 */
        java.util.ArrayList<String> ranked() {
            java.util.ArrayList<String> keys =
                    new java.util.ArrayList<String>(cats.keySet());
            final java.util.HashMap<String, Integer> c = cats;
            java.util.Collections.sort(keys, new java.util.Comparator<String>() {
                public int compare(String a, String b) {
                    return c.get(b) - c.get(a);        // 稳定排序：平手保持插入顺序
                }
            });
            return keys;
        }

        String topActivity() {
            java.util.ArrayList<String> r = ranked();
            return r.isEmpty() ? "thinking" : r.get(0);
        }

        /** 结算后的组名：前三个类别，用「、」串起来；没有工具就写「思考」。 */
        String closedTitle() {
            java.util.ArrayList<String> r = ranked();
            if (r.isEmpty()) return "\u601D\u8003";
            StringBuilder sb = new StringBuilder();
            int n = Math.min(3, r.size());
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append("\u3001");
                sb.append(toolTitle(r.get(i)));
            }
            if (r.size() > 3) sb.append("\u7B49");
            return sb.toString();
        }

        /** 运行中的实时详情：工具在跑就给它的摘要，否则给最新一段思考的首行。 */
        String liveDetail() {
            for (int i = items.size() - 1; i >= 0; i--) {
                ToolItem it = items.get(i);
                if (it.phase <= 1) return it.summary();
            }
            return preview();
        }

        /** harness latestCompletedParagraphFirstLine：最后一段首行写完了才认它，否则退回上一段。 */
        String preview() {
            if (curThink == null) return "";
            return curThink.preview(done);
        }

        String oldPreview() {
            if (paraBuf.length() == 0) return "";
            String s = done ? paraBuf.toString() : trimEnd(paraBuf.toString());
            if (s.length() == 0) return "";
            String para;
            if (done) {
                para = s;                                   // 结束：全文首行
            } else {
                int cut = s.lastIndexOf("\n\n");
                para = cut >= 0 ? s.substring(cut + 2) : s;
                int nl = para.indexOf('\n');
                if (cut >= 0 && nl < 0) {                    // 最后一段还没写完 → 用上一段的首行
                    int prev = s.lastIndexOf("\n\n", cut - 1);
                    String prevPara = prev >= 0 ? s.substring(prev + 2, cut) : s.substring(0, cut);
                    if (prevPara.trim().length() > 0) para = prevPara;
                }
            }
            int nl = para.indexOf('\n');
            String line = (nl < 0 ? para : para.substring(0, nl)).trim();
            line = line.replace("**", "").replaceAll("\\s+", " ").trim();
            if (line.length() > 32) line = line.substring(0, 32) + "\u2026";
            return line;
        }

        private String trimEnd(String s) {
            int e = s.length();
            while (e > 0) {
                char c = s.charAt(e - 1);
                if (c == '\n' || c == ' ' || c == '\t' || c == '\r') e--;
                else break;
            }
            return s.substring(0, e);
        }

        /** 展开大框时拉到底：看最新流式输出，而不是每次都从头重看。 */
        void jumpBody() {
            if (bodyWrap == null) return;
            final android.widget.ScrollView sv = (android.widget.ScrollView) bodyWrap;
            sv.post(new Runnable() {
                public void run() {
                    sv.fullScroll(View.FOCUS_DOWN);
                }
            });
        }

        void follow() {
            if (scroll == null) return;
            final android.widget.ScrollView sv = (android.widget.ScrollView) scroll;
            sv.post(new Runnable() {
                public void run() {
                    if (sv.getScrollY() + sv.getHeight() >= body.getHeight() - Ui.dp(ctx, 60)) {
                        sv.fullScroll(View.FOCUS_DOWN);    // 用户自己翻上去看时，不硬拽回底部
                    }
                }
            });
        }

        /** 正文行：左 20dp 放圆点/图标（竖轨从正中穿过），右边是文字。 */
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

        /**
         * 思考增量：落进当前这条思考行（ThinkRow）。
         * 上一行是工具行就另起一条（harness 的 reasoning block 也是一段一块）。
         */
        void para(String chunk) {
            if (curThink == null || lastTool) {
                curThink = new ThinkRow();
                curThink.attach(body);
                paras++;
                lastTool = false;
                refresh();                  // 只在起新条时刷标题：流式期间别每帧重排
            }
            curThink.append(chunk);
            follow();
        }

        void paintPara() {
            if (curThink != null) curThink.paint();
        }

        ToolItem addTool(String callId, String name, String args) {
            tools++;
            lastTool = true;
            curThink = null;
            paraTv = null;
            refresh();
            ToolItem it = new ToolItem(callId, name);
            it.attach(body);
            it.setArgs(args);
            items.add(it);
            String v = toolVariant(name);            // 类别计数：结算后的组名就是它排出来的
            Integer n = cats.get(v);
            cats.put(v, n == null ? 1 : n + 1);
            toolItems.put(callId, it);
            follow();
            return it;
        }

        /** 收尾：标题定稿（摘要换全文首行），正文折叠，只留表头。 */
        void finish() {
            if (done) return;
            if (curThink != null) curThink.settle();
            paintPara();
            for (int i = 0; i < items.size(); i++) {
                ToolItem it = items.get(i);
                if (it.phase <= 1) it.setPhase(2);      // 没收到的结果：别再挂着「运行中」
            }
            done = true;
            Ui.stopShimmer(hTitle);                        // 跑完了就不再扫高光
            Ui.stopShimmer(hPrev);
            endAt = System.currentTimeMillis();            // 用时定格：重画时不能再变
            open = false;                                  // 思考结束自动收起，只留表头（DeepSeek 的框也是这样）
            body.setVisibility(View.GONE);
            if (bodyWrap != null) bodyWrap.setVisibility(View.GONE);
            if (tools == 0) {                              // 纯思考的过程组：结束后整块隐掉，不留"思考 · 用时"
                box.setTag("hidethink");
                box.setVisibility(View.GONE);
            }
            refresh();
        }

        /** 找不到对应工具行（结果先到 / 历史残缺）：内容不能丢，自己落一行。 */
        void resultFallback(String text, boolean err) {
            String t = text == null ? "" : text.trim();
            if (t.length() > 4000) t = t.substring(0, 4000) + "\u2026";
            TextView tx = line(err ? "\u2717" : "\u21B3", err ? Ui.RED : Ui.MUT,
                    err ? Ui.RED : Ui.DIM);
            tx.setText(t.length() == 0 ? "(\u7A7A)" : t);
            follow();
        }
    }

    /** 一条思考（harness 的 ReasoningRow）：默认一行「思考 · 摘要」，点开才是全文。 */
    class ThinkRow {
        final LinearLayout item = Ui.col(ctx);
        final LinearLayout line = new LinearLayout(ctx);
        final TextView title = new TextView(ctx);
        final TextView prev = new TextView(ctx);
        final TextView full = new TextView(ctx);
        final StringBuilder buf = new StringBuilder();
        long lastPaint;
        boolean open;

        ThinkRow() {
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView mark = new TextView(ctx);
            mark.setText("\u2726");
            mark.setTextSize(Ui.FS_SMALL);
            mark.setTextColor(Ui.AMBER);
            mark.setTypeface(Typeface.MONOSPACE);
            mark.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            line.addView(mark, new LinearLayout.LayoutParams(Ui.dp(ctx, 20),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            title.setText("\u601D\u8003");                       // 思考
            title.setTextSize(Ui.FS_SMALL);
            title.setTextColor(Ui.DIM);
            title.setSingleLine(true);
            line.addView(title);
            prev.setTextSize(Ui.FS_SMALL);
            prev.setTextColor(Ui.MUT);
            prev.setSingleLine(true);
            prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            prev.setPadding(Ui.dp(ctx, 5), 0, 0, 0);
            line.addView(prev, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            item.addView(line);
            full.setTextSize(Ui.FS_SMALL);
            full.setTextColor(Ui.DIM);
            full.setLineSpacing(Ui.dp(ctx, 4), 1f);
            full.setTextIsSelectable(true);
            full.setVisibility(View.GONE);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.leftMargin = Ui.dp(ctx, 20);
            flp.topMargin = Ui.dp(ctx, 2);
            item.addView(full, flp);
            line.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    open = !open;
                    if (open) {
                        full.setText(buf);                   // 展开就是全文（harness 也不截尾）
                        full.setVisibility(View.VISIBLE);
                        prev.setText("");
                        Ui.stopShimmer(prev);
                    } else {
                        full.setVisibility(View.GONE);
                        paint();
                    }
                    toggleInPlace(item);
                    if (trace != null) trace.follow();
                }
            });
            Ui.press(line, ctx, 0x00000000, Ui.R_CHIP);
        }

        void attach(LinearLayout host) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, 4);
            host.addView(item, lp);
        }

        void append(String chunk) {
            buf.append(chunk);
            if (open) {
                full.setText(buf);
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastPaint > 240) {          // 节流：长思考别每帧重排
                lastPaint = now;
                paint();
            }
        }

        /** 折叠态：一行摘要；还在流就整条扫高光（harness 的 TextShimmer）。 */
        void paint() {
            if (open) return;
            prev.setText(preview(false));
            if (trace != null && !trace.done) {
                Ui.shimmer(prev, Ui.MUT, Ui.TEXT);
            } else {
                Ui.stopShimmer(prev);
            }
        }

        void settle() {
            Ui.stopShimmer(prev);
            paint();
        }

        /**
         * harness 的摘要规则：流式时取「最后一段的首行」（上一段写完了才认它），
         * 结算后取全文首行；** 标记剥掉。
         */
        String preview(boolean finished) {
            String s = buf.length() == 0 ? "" : buf.toString();
            if (s.length() == 0) return "";
            if (!finished) {
                int e = s.length();
                while (e > 0) {
                    char c = s.charAt(e - 1);
                    if (c == '\n' || c == ' ' || c == '\t' || c == '\r') e--;
                    else break;
                }
                s = s.substring(0, e);
            }
            String para = s;
            if (!finished) {
                int cut = s.lastIndexOf("\n\n");
                para = cut >= 0 ? s.substring(cut + 2) : s;
                if (cut >= 0 && para.indexOf('\n') < 0) {          // 最后一段还没写完 → 退回上一段
                    int pv = s.lastIndexOf("\n\n", cut - 1);
                    String prevPara = pv >= 0 ? s.substring(pv + 2, cut) : s.substring(0, cut);
                    if (prevPara.trim().length() > 0) para = prevPara;
                }
            }
            int nl = para.indexOf('\n');
            String line = (nl < 0 ? para : para.substring(0, nl)).trim();
            line = line.replace("**", "").replaceAll("\\s+", " ").trim();
            return line;
        }
    }

    /** 工具行按 callId 认领：结果落回自己那一行，不是笼统塞给「最后一行」。 */
    private final java.util.HashMap<String, ToolItem> toolItems =
            new java.util.HashMap<String, ToolItem>();
    private int autoToolId;

    /**
     * 一行工具调用：图标 + 标题 · 摘要 + 右侧状态，点开看参数与输出。
     * 结构抄 ui-tool/ToolCallRow：variant → 图标/标题，args → 摘要，phase → 状态文案。
     */
    public class ToolItem {
        final String callId;
        final String name;
        final LinearLayout item = Ui.col(ctx);
        final TextView glyph = new TextView(ctx);
        final TextView label = new TextView(ctx);      // 业务标题（读取文件 / 调用工具）
        final TextView sum = new TextView(ctx);        // · 摘要（失败时换成错误首行）
        final TextView det = new TextView(ctx);
        String args = "";
        String out = "";
        int phase;                       // 0 准备中 · 1 运行中 · 2 完成 · 3 失败 · 4 已打断

        ToolItem(String callId, String name) {
            this.callId = callId;
            this.name = name;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            glyph.setTextSize(Ui.FS_SMALL);
            glyph.setTypeface(Typeface.MONOSPACE);
            glyph.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            row.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(ctx, 20),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            label.setTextSize(Ui.FS_SMALL);
            label.setTextColor(Ui.DIM);                    // harness：标题走次级色
            label.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            sum.setTextSize(Ui.FS_SMALL);
            sum.setTextColor(Ui.MUT);                      // 摘要再暗一档
            sum.setSingleLine(true);
            sum.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sum.setPadding(Ui.dp(ctx, 5), 0, 0, 0);
            row.addView(sum, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            item.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            det.setTextSize(Ui.FS_MONO);
            det.setTextColor(Ui.DIM);
            det.setTypeface(Typeface.MONOSPACE);
            det.setLineSpacing(Ui.dp(ctx, 3), 1f);
            det.setBackground(Ui.bg(Ui.SURF2, 10, ctx));
            det.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
            det.setVisibility(View.GONE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = Ui.dp(ctx, 20);
            dlp.topMargin = Ui.dp(ctx, 6);
            item.addView(det, dlp);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean show = det.getVisibility() != View.VISIBLE;
                    det.setVisibility(show ? View.VISIBLE : View.GONE);
                    if (show) paintDet();
                    toggleInPlace(item);
                    if (trace != null) trace.follow();
                }
            });
            Ui.press(item, ctx, 0x00000000, Ui.R_CHIP);
            paint();
        }

        void attach(LinearLayout host) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, 6);
            host.addView(item, lp);
        }

        void setArgs(String a) {
            if (a == null) return;
            args = a;
            if (phase == 0 && args.trim().length() > 0) phase = 1;
            paint();
        }

        void appendArgs(String chunk) {
            if (chunk == null || chunk.length() == 0) return;
            args += chunk;
            if (phase < 1) phase = 0;                 // 参数还在长 → 准备中
            paint();
        }

        void setPhase(int p) {
            phase = p;
            paint();
        }

        void setResult(String text, boolean err) {
            out = text == null ? "" : text;
            phase = err ? 3 : 2;
            paint();
            if (det.getVisibility() == View.VISIBLE) paintDet();
        }

        /**
         * 一行说完：业务图标 + 标题 + · 摘要。
         * 状态不写字（harness 不挂状态文案）：运行中扫高光、失败摘要转红、打断转琥珀，
         * 图标从头到尾都是这个工具的业务图标。
         */
        void paint() {
            String v = toolVariant(name);
            glyph.setText(toolIcon(v));
            glyph.setTextColor(toolColor(v));
            label.setText("others".equals(v)
                    ? "\u8C03\u7528\u5DE5\u5177" : toolTitle(v));      // 调用工具
            String sm = summary();
            sum.setText(sm.length() == 0 ? "" : " \u00B7 " + sm);
            if (phase == 3) {
                sum.setTextColor(Ui.RED);
            } else if (phase == 4) {
                sum.setTextColor(Ui.AMBER);
            } else {
                sum.setTextColor(Ui.MUT);
            }
            boolean running = phase == 0 || phase == 1;
            if (running) {
                Ui.shimmer(label, Ui.DIM, Ui.TEXT);
                Ui.shimmer(sum, Ui.MUT, Ui.TEXT);
            } else {
                Ui.stopShimmer(label);
                Ui.stopShimmer(sum);
            }
        }

        /** 折叠行的那句摘要（harness 的 SUMMARY_KEYS + 失败用结果首行）。 */
        String summary() {
            String v = toolVariant(name);
            if (phase == 3) {
                String e = firstLineOf(out);
                if (e.length() > 0) return e;
            }
            if (phase == 4) return "\u5DF2\u4E2D\u65AD";             // 已中断
            if (phase == 0 && ("write".equals(v) || "edit".equals(v))) {
                long kb = (long) Math.ceil((args == null ? 0 : args.length()) / 1024.0);
                return "\u6B63\u5728\u51C6\u5907\u5185\u5BB9 " + kb + "KB";
            }
            String s = toolSummary(v, args);
            if ("others".equals(v) && name != null && name.length() > 0) {
                s = s.length() == 0 ? name : name + " \u00B7 " + s;
            }
            return s;
        }

        private String firstLineOf(String t) {
            if (t == null) return "";
            String s = t.trim();
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(0, nl).trim();
            return clean(s, 120);
        }

        private String stateText() {
            if (phase == 0) return "\u51C6\u5907\u4E2D";     // 准备中
            if (phase == 1) return "\u8FD0\u884C\u4E2D";     // 运行中
            if (phase == 3) return "\u2717";                 // ✗
            if (phase == 4) return "\u2298";                 // ⊘ 打断
            return "\u2713";                                 // ✓
        }

        private int stateColor() {
            if (phase == 3) return Ui.RED;
            if (phase == 0 || phase == 1) return Ui.AMBER;
            if (phase == 4) return Ui.MUT;
            return Ui.GREEN;
        }

        void paintDet() {
            StringBuilder sb = new StringBuilder();
            sb.append(pretty(args));
            if (out.trim().length() > 0) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(out.length() > 4000 ? out.substring(0, 4000) + "\u2026" : out);
            }
            det.setText(sb.length() == 0 ? "(\u7A7A)" : sb.toString());   // (空)
            det.setTextColor(phase == 3 ? Ui.RED : Ui.DIM);
        }

        /** 参数：能当 JSON 解析就缩进一下，不然原样贴。 */
        String pretty(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.length() == 0) return "";
            try {
                org.json.JSONObject o = new org.json.JSONObject(s);
                return o.toString(2);
            } catch (Exception ignored) {
            }
            try {
                org.json.JSONArray a = new org.json.JSONArray(s);
                return a.toString(2);
            } catch (Exception ignored) {
            }
            return s;
        }
    }

    /** 类别名：过程组表头用（harness 的 message.stepProcess.*）。 */
    private static String toolTitle(String v) {
        if ("read".equals(v)) return "\u8BFB\u53D6\u6587\u4EF6";      // 读取文件
        if ("search".equals(v)) return "\u641C\u7D22";                  // 搜索
        if ("bash".equals(v)) return "\u8FD0\u884C\u547D\u4EE4";      // 运行命令
        if ("write".equals(v)) return "\u5199\u5165\u6587\u4EF6";     // 写入文件
        if ("edit".equals(v)) return "\u7F16\u8F91\u6587\u4EF6";      // 编辑文件
        if ("code".equals(v)) return "\u8FD0\u884C\u4EE3\u7801";      // 运行代码
        if ("thinking".equals(v)) return "\u601D\u8003";                // 思考
        return "\u8C03\u7528\u5DE5\u5177";                            // 调用工具
    }

    /** 工具名 → harness 的行类型（search/read/bash/write/edit/code/others）。 */
    private static String toolVariant(String name) {
        String s = name == null ? "" : name.toLowerCase();
        if (s.equals("bash") || s.equals("pwsh") || s.indexOf("shell") >= 0
                || s.indexOf("terminal") >= 0) return "bash";
        if (s.indexOf("search") >= 0 || s.indexOf("grep") >= 0 || s.indexOf("glob") >= 0) return "search";
        if (s.indexOf("fetch") >= 0 || s.indexOf("read") >= 0 || s.indexOf("cat") >= 0
                || s.indexOf("inspect") >= 0) return "read";
        if (s.indexOf("write") >= 0) return "write";
        if (s.indexOf("edit") >= 0 || s.indexOf("patch") >= 0) return "edit";
        if (s.indexOf("code") >= 0 || s.indexOf("run_code") >= 0) return "code";
        return "others";
    }

    /** 图标：harness 用 SVG，这里用等宽字形顶替，颜色跟着行类型走。 */
    private static String toolIcon(String v) {
        if ("bash".equals(v)) return "$";
        if ("read".equals(v)) return "\u25A4";      // ▤
        if ("search".equals(v)) return "\u2315";    // ⌕
        if ("write".equals(v) || "edit".equals(v)) return "\u270E";   // ✎
        if ("code".equals(v)) return "{}";
        return "\u2699";                            // ⚙
    }

    private static int toolColor(String v) {
        if ("bash".equals(v)) return Ui.AMBER;
        if ("read".equals(v)) return Ui.ACCENT;
        if ("search".equals(v)) return Ui.VIOLET;
        if ("write".equals(v) || "edit".equals(v)) return Ui.GREEN;
        if ("code".equals(v)) return Ui.ACCENT;
        return Ui.MUT;
    }

    /** 行标题 + 摘要：bash 直接「$ 命令」，其余「标题 · 摘要」。 */
    private String toolHead(String v, String name, String args) {
        String summary = toolSummary(v, args);
        if ("bash".equals(v)) return summary.length() == 0 ? "$" : "$ " + summary;
        String title;
        if ("read".equals(v)) title = "\u8BFB\u53D6\u6587\u4EF6";        // 读取文件
        else if ("search".equals(v)) title = "\u641C\u7D22";             // 搜索
        else if ("write".equals(v)) title = "\u5199\u5165\u6587\u4EF6";  // 写入文件
        else if ("edit".equals(v)) title = "\u7F16\u8F91\u6587\u4EF6";   // 编辑文件
        else if ("code".equals(v)) title = "\u8FD0\u884C\u4EE3\u7801";   // 运行代码
        else title = (name == null || name.length() == 0) ? "\u5DE5\u5177" : name;
        return summary.length() == 0 ? title : title + " \u00B7 " + summary;
    }

    /** 摘要：照抄 harness 的 SUMMARY_KEYS（bash=description|command，read=path|file_path|url …）。 */
    private String toolSummary(String v, String args) {
        String s = "";
        if ("bash".equals(v)) s = pickArg(args, "description");
        if (s.length() == 0 && "bash".equals(v)) s = pickArg(args, "command");
        if (s.length() == 0 && ("read".equals(v) || "search".equals(v))) {
            s = pickArg(args, "path");
            if (s.length() == 0) s = pickArg(args, "file_path");
            if (s.length() == 0) s = pickArg(args, "url");
            if (s.length() == 0) s = pickArg(args, "query");
            if (s.length() == 0) s = pickArg(args, "pattern");
        }
        if (s.length() == 0 && ("write".equals(v) || "edit".equals(v))) {
            s = pickArg(args, "path");
            if (s.length() == 0) s = pickArg(args, "file_path");
        }
        if (s.length() == 0 && "code".equals(v)) s = pickArg(args, "description");
        if (s.length() == 0) s = pickArg(args, "description");
        if (s.length() == 0) {
            String c = clean(args, 120);
            s = c.length() > 0 && c.charAt(0) == '{' ? "" : c;
        }
        s = s.replace("\\n", " ").replace("\n", " ").replace("\r", " ").trim();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl);
        return s.length() > 120 ? s.substring(0, 120) + "\u2026" : s;
    }

    /** 工具调用：callId 认领一行（老 host 没 callId 时按名字自编号，退化成旧行为）。 */
    public void tool(String callId, String name, String args) {
        String id = callId == null || callId.length() == 0 ? autoKey(name) : callId;
        thinkStart();
        ToolItem it = toolItems.get(id);
        if (it == null) {
            trace.addTool(id, name, args);
        } else {
            it.setArgs(args);
            it.setPhase(1);
        }
        scroll(false);
    }

    /** 参数流式到达（host 的 tool-call-delta）：行先出现，参数边长边补。 */
    public void toolDelta(String callId, String name, String chunk) {
        if (callId == null || callId.length() == 0) return;
        ToolItem it = toolItems.get(callId);
        if (it == null) {
            thinkStart();
            it = trace.addTool(callId, name, "");
            it.setPhase(0);
        }
        it.appendArgs(chunk);
        scroll(false);
    }

    /** 工具结果：认领回自己那一行，落成 ✓/✗。 */
    public void toolResult(String callId, String text, boolean err, boolean stopped) {
        ToolItem it = callId == null ? null : toolItems.get(callId);
        if (it != null) {
            if (stopped) it.setPhase(4);
            else it.setResult(text, err);
            scroll(false);
            return;
        }
        if (traceLive() == null) thinkStart();
        trace.resultFallback(text, err);
        scroll(false);
    }

    private String autoKey(String name) {
        autoToolId++;
        return "~" + (name == null ? "tool" : name) + "#" + autoToolId;
    }
}
