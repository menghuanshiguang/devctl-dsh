package com.minis.dshconsole;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 主壳：顶栏 + 聊天页（主区）+ 左侧抽屉（工作区 / 对话列表）。 */
public class MainActivity extends Activity {
    public Store store;
    public String dshName;
    public Dsh dsh;
    public Devctl devctl;

    private FrameLayout root;
    private LinearLayout col;
    private FrameLayout content;
    private LinearLayout side;
    private View scrim;
    private TextView title;
    private TextView status;
    private Spinner devSpin; // 兼容旧代码，实际不再显示

    private Tab[] tabs;
    private int current = 1; // 默认就是聊天页
    public Tab tabChat;
    public Sidebar sidebar;

    private boolean drawerOpen = false;
    private int sideW = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        // 关键：代码里的 setSoftInputMode 会盖过清单 —— 原来这里是 ADJUST_RESIZE，
        // 所以键盘一弹整个窗口被系统硬压上去（没有过渡），我加在输入卡上的平移动画就白做了。
        // 改成 ADJUST_NOTHING：窗口不动，位移完全由 TabChat.installImeAnimation() 做动画。
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);   // 回到前台不自动弹输入法（点输入框才弹）          // 先刷调色板，后面所有控件才拿得到对的颜色
        // 用自绘顶栏：去掉系统 ActionBar（重复标题栏 + 多占 56dp）
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        // Android 15(API 35) 起 setStatusBarColor 失效（设了也没用）——想"状态栏跟界面同色"只剩正路：
        // 内容画到栏底下 + 栏透明 + 由 inset 给内容补内边距。监听装在 decor 上（装 content 上收不到）。
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        android.view.View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            if (!Ui.DARK) {
                getWindow().getInsetsController().setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (Ui.DARK ? 0 : android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        }
        installInsets();            // 系统栏内边距 + IME 高度转给聊天页
        store = new Store(this);
        if (b == null) {
            handlePairIntent(getIntent());      // 只认"全新启动带进来的"那一单，别重放旧 intent
        }
        // 本地版：环境没装好/没跑起来，先把启用引导摆出来（远端版 hasRuntime() 为 false，永不进这里）
        final LocalEnv env = Cores.get().runtime();
        if (env != null && !(env.ready(this) && env.running(this))) {
            startActivity(new android.content.Intent(this, LocalSetupActivity.class));
        }
        List<Store.Dev> list = store.devices("dsh");
        if (list.isEmpty()) {
            Store.Dev d = new Store.Dev();
            d.name = "home";
            d.host = "127.0.0.1";   // agent 与本机 app 同机
            d.port = 5556;
            store.putDevice("dsh", d);
            store.setDef("dsh", "home");
        }
        dshName = store.def("dsh");
        if (dshName.length() == 0 || store.find("dsh", dshName) == null) {
            dshName = store.devices("dsh").get(0).name;
            store.setDef("dsh", dshName);
        }

        tabs = new Tab[]{new TabSessions(this), new TabChat(this), new TabManage(this),
                new TabDevice(this), new TabEvents(this)};
        tabChat = tabs[1];

        sideW = (int) (getResources().getDisplayMetrics().widthPixels * 0.82);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        col = Ui.col(this);
        col.setBackgroundColor(Ui.BG);
        col.addView(topBar());
        content = new FrameLayout(this);
        col.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        buildDrawer();
        setContentView(root);

        sidebar = new Sidebar(this);
        side.addView(sidebar.view(), new LinearLayout.LayoutParams(
                sideW, LinearLayout.LayoutParams.MATCH_PARENT));

        select(1);
        connectDsh(false);
        if (reopenDrawerAfterRecreate) {         // 换主题重建后，把抽屉还回来
            reopenDrawerAfterRecreate = false;
            side.post(new Runnable() {
                public void run() {
                    openDrawer();
                }
            });
        }
        // 回到上次那个会话（顺带也是调试入口：渲染历史会把可疑原文打进 logcat）
        try {
            String last = store.lastSession(dshName);
            if (last != null && last.length() > 0) {
                openChat(last, "");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 一套统一的 inset 处理，**装在 decor 上**（装在 content 容器上收不到，上一版就是这么翻的车）：
     *   - 状态栏/导航栏内边距补给 root（配合透明栏 = 沉浸式，栏底色就是 app 自己那层）；
     *   - 键盘弹起时底部内边距让位给聊天页的整页上抬动画。
     */
    private void installInsets() {
        final android.view.View decor = getWindow().getDecorView();
        decor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            private String lastTag = "";

            public android.view.WindowInsets onApplyWindowInsets(android.view.View v,
                                                                 android.view.WindowInsets insets) {
                int top = 0, bottom = 0, ime = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                    ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                String tag = top + "/" + bottom + "/" + ime;
                if (!tag.equals(lastTag)) {
                    lastTag = tag;
                    android.util.Log.i("DshInset", "bars top=" + top + " bottom=" + bottom + " ime=" + ime);
                }
                if (root != null) {
                    root.setPadding(0, top, 0, ime > 0 ? 0 : bottom);   // 键盘起来时底部交给动画层
                }
                if (tabChat instanceof TabChat) {
                    ((TabChat) tabChat).onImeInset(ime);
                }
                return insets;
            }
        });
        decor.requestApplyInsets();
    }

    @Override
    protected void onNewIntent(android.content.Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handlePairIntent(it);                   // app 已在前台时来一单分享/深链
    }

    /**
     * 扫码配对：系统相机（或任何 app）扫到配对二维码后，
     *   - 「分享」文本 → ACTION_SEND + text/plain
     *   - 或点 dshconsole://pair?host=…&port=…&token=… 深链
     * 都由这里接住：填进设备，然后直接连。
     */
    private void handlePairIntent(android.content.Intent it) {
        if (it == null) return;
        try {
            setIntent(new android.content.Intent());   // 用完即弃：绝不让它下次启动再跑一遍
            String text = null;
            if (android.content.Intent.ACTION_SEND.equals(it.getAction())) {
                text = it.getStringExtra(android.content.Intent.EXTRA_TEXT);
            } else if (android.content.Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null) {
                android.net.Uri u = it.getData();
                if (u != null && "dshconsole".equals(u.getScheme())) {
                    Store.Dev d = new Store.Dev();
                    d.name = "home";
                    d.host = u.getQueryParameter("host") == null ? "" : u.getQueryParameter("host");
                    try {
                        d.port = Integer.parseInt(String.valueOf(u.getQueryParameter("port")));
                    } catch (Throwable ignored) {
                        d.port = 7788;
                    }
                    d.token = u.getQueryParameter("token") == null ? "" : u.getQueryParameter("token");
                    if (d.host.length() > 0) {
                        store.putDevice("dsh", d);
                        store.setDef("dsh", d.name);
                        dshName = d.name;
                        android.util.Log.i("DshPair", "深链配对: " + d.host + ":" + d.port);
                        toast("已从二维码配对 · " + d.addr());
                        connectDsh(true);
                    }
                    return;
                }
            }
            if (text == null || text.trim().length() == 0) return;
            Store.Dev d = store.find("dsh", dshName);
            if (d == null) {
                d = new Store.Dev();
                d.name = "home";
            }
            android.util.Log.i("DshPair", "分享进来的配对文本: " + text.trim());
            applyPairCmd(text.trim(), d);        // 复用"粘贴配对命令"那套解析
            store.putDevice("dsh", d);           // 解析出来要落盘，不然只是改了内存里的副本
            store.setDef("dsh", d.name);
            dshName = d.name;
            toast("已配对 · " + d.addr());
            connectDsh(true);
        } catch (Throwable e) {
            android.util.Log.i("DshPair", "配对失败: " + e);
        }
    }

    // ---------------- 顶栏 ----------------

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BG);          // 顶栏跟消息区同色，靠下面的渐隐收口
        int p = Ui.dp(this, 8);
        bar.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));

        TextView menu = icon("☰", new Runnable() {
            public void run() {
                openDrawer();
            }
        });
        bar.addView(menu);

        LinearLayout mid = Ui.col(this);
        mid.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        title = Ui.tv(this, "DSH 控制台", 15.5f, Ui.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(title);
        status = Ui.tv(this, "未连接", 11f, Ui.DIM);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(status);
        bar.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(icon("＋", new Runnable() {
            public void run() {
                newSession();
            }
        }));
        return bar;
    }

    private TextView icon(String text, final Runnable r) {
        TextView t = Ui.tv(this, text, 17f, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        Ui.press(t, this, 0x00000000, Ui.dp(this, 9));   // 融进顶栏：平时无底，只有按压反馈
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
        return t;
    }

    /** 侧栏「设置」二级菜单的动作分发（原来是顶栏 ⋮ 的系统白弹窗）。 */
    void runMenuAction(int w) {
        switch (w) {
            case 0: newSession(); break;
            case 1: sidebar.refresh(); toast("已刷新"); break;
            case 2: chatTab().menuHistory(); break;
            case 3: chatTab().menuPick(); break;
            case 4: chatTab().menuClear(); break;
            case 5: chatTab().cancel(); break;
            case 6: select(2); break;
            case 7: select(4); break;
            case 8: dialogDevices(); break;
            default: closeDsh(); connectDsh(true); break;
        }
        if (w != 1) closeDrawer();          // 动作做完收回抽屉；刷新留着看结果
    }

    /** 侧栏那个按钮：深⇄浅 直接切。改完必须重建（颜色都是构造时定的），顺手做个淡入淡出。 */
    /** 因为换主题而重建时，重建后把抽屉再拉开（不然用户点一下主题就被"弹出抽屉"）。 */
    private boolean reopenDrawerAfterRecreate = false;

    public void toggleTheme() {
        reopenDrawerAfterRecreate = drawerOpen;
        String cur = store.get("theme", "system");
        boolean darkNow = Ui.DARK;
        String next = darkNow ? "light" : "dark";
        store.set("theme", next);
        android.util.Log.i("DshTheme", cur + " -> " + next + "（当前深色=" + darkNow + "）");
        Ui.themeDirty = false;
        recreate();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
        }
    }

    /** 长按侧栏那个主题按钮：出三档弹层（照参考图的样子）。 */
    public void openThemePicker() {
        ThemePicker.show(this);
    }

    // ---------------- 抽屉 ----------------

    private void buildDrawer() {
        final FrameLayout layer = new DrawerLayer(this);      // 支持左滑关闭
        scrim = new View(this);
        scrim.setBackgroundColor(0xAA000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
            }
        });
        layer.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        side = Ui.col(this);
        side.setBackgroundColor(Ui.BG);          // 抽屉跟消息区同色，白色的卡片才有层次
        side.setTranslationX(-sideW);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                sideW, FrameLayout.LayoutParams.MATCH_PARENT);
        layer.addView(side, slp);
        layer.setVisibility(View.GONE);
        layer.setTag("layer");
        root.addView(layer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View layer() {
        return root.findViewWithTag("layer");
    }

    /**
     * 抽屉层：左滑关闭（跟手位移 + 松手判定）。
     * 用 onInterceptTouchEvent 抢手势，这样抽屉里那些 ScrollView 竖着滚不受影响，
     * 只有"明显横向、往左"的拖动才被我们接管。
     */
    private class DrawerLayer extends FrameLayout {
        private float downX, downY;
        private boolean dragging;
        private android.view.VelocityTracker vt;
        private final int slop;

        DrawerLayer(android.content.Context c) {
            super(c);
            slop = Ui.dp(c, 10);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            if (!drawerOpen) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = android.view.VelocityTracker.obtain();
                    vt.addMovement(e);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (!dragging && dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;                    // 明显往左 → 接管
                        side.animate().cancel();            // 别和入场动画打架
                        scrim.animate().cancel();
                        return true;
                    }
                    return false;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (!drawerOpen) return false;
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE: {
                    float t = Math.min(0f, e.getX() - downX);      // 只允许往左拉
                    side.setTranslationX(t);
                    scrim.setAlpha(Math.max(0f, 1f + t / Math.max(1, sideW)));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    float t = Math.min(0f, e.getX() - downX);
                    float vx = 0f;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        vx = vt.getXVelocity();
                    }
                    boolean close = t < -sideW * 0.3f || vx < -900f;
                    if (close) {
                        closeDrawer();                             // 从当前位移继续滑出
                    } else {
                        side.animate().translationX(0).setDuration(160).start();
                        scrim.animate().alpha(1f).setDuration(160).start();
                    }
                    if (vt != null) {
                        vt.recycle();
                        vt = null;
                    }
                    dragging = false;
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }
    }

    public void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        View l = layer();
        root.bringChildToFront(l);          // 蒙层必须压在最上层，否则 FAB/其它视图会吃掉"点空白关闭"
        l.setVisibility(View.VISIBLE);
        side.setTranslationX(-sideW);
        scrim.setVisibility(View.VISIBLE);   // 之前始终是 GONE：蒙层不存在 → 点空白永远关不掉
        scrim.setAlpha(0f);
        side.animate().translationX(0).setDuration(220).start();
        scrim.animate().alpha(1f).setDuration(220).start();
        // 数据刷新挪到入场动画之后：动画期间不碰列表，别自己给自己掉帧
        side.postDelayed(new Runnable() {
            public void run() {
                if (drawerOpen) sidebar.refresh();
            }
        }, 280);
    }

    public void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        final View l = layer();
        side.animate().translationX(-sideW).setDuration(200).start();
        scrim.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            public void run() {
                l.setVisibility(View.GONE);
            }
        }).start();
    }

    public boolean drawerOpen() {
        return drawerOpen;
    }

    // ---------------- 页面 ----------------

    public void select(int idx) {
        if (tabs == null) return;
        if (idx == 0) {
            openDrawer();
            return;
        }
        if (idx < 0 || idx >= tabs.length) return;
        closeDrawer();                        // 从抽屉里选了入口 → 自己收回，不用再手动关
        if (current != idx && current >= 0 && tabs[current] != null) tabs[current].onHide();
        current = idx;
        content.removeAllViews();
        content.addView(tabs[idx].view(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tabs[idx].onShow();
        String[] names = {"会话", "DSH 控制台", "权限与模型", "设备", "事件"};
        if (idx == 1) {
            String t = chatTab().currentTitle();
            setTitle(t.length() > 0 ? t : names[1]);
        } else {
            setTitle(names[idx]);
        }
    }

    public void setTitle(String t) {
        title.setText(t == null || t.length() == 0 ? "DSH 控制台" : t);
    }

    public void newSession() {
        closeDrawer();
        sidebar.newSession();
    }

    public void openChat(String sessionId, String t) {
        store.setLastSession(dshName, sessionId);
        chatTab().loadSession(sessionId, t);
        select(1);
        setTitle(t == null || t.length() == 0 ? "会话" : t);
        closeDrawer();
    }

    // ---------------- 设备管理 ----------------

    /** 报错处一键进连接设置（填 dsh token / 改地址）。 */
    public void openConnSettings() {
        Store.Dev d = store.find("dsh", dshName);
        if (d == null) dialogAddDsh();
        else dialogEditDsh(d);
    }

    public void reloadDevices() {
        if (devSpin == null) {
            sidebar.refresh();
            return;
        }
        List<String> names = new ArrayList<String>();
        for (Store.Dev d : store.devices("dsh")) names.add(d.name);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        devSpin.setAdapter(ad);
        int idx = names.indexOf(dshName);
        if (idx >= 0) devSpin.setSelection(idx);
    }

    private void dialogAddDsh() {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 14);
        box.setPadding(p, p, p, p);
        final EditText paste = Ui.input(this, "① 粘贴配对命令（推荐）");
        final EditText name = Ui.input(this, "② 设备名，例如 home");
        name.setText("home");
        final EditText host = Ui.input(this, "② 主机 IP，例如 192.168.2.7");
        host.setText("127.0.0.1");
        final EditText port = Ui.input(this, "② 端口，dsh host 默认 7788");
        port.setText("7788");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText webPort = Ui.input(this, "③ 网页端口（走穿透时填，留空=7790）");
        webPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText token = Ui.input(this, "② token（dsh host 状态页可复制）");
        box.addView(paste);
        box.addView(Ui.dim(this, "直接粘贴 host 面板里那条命令即可，自动填好下面全部："));
        box.addView(Ui.dim(this, "dshctl add home 192.168.2.7:7788 --token xxx"));
        box.addView(name);
        box.addView(host);
        box.addView(port);
        box.addView(webPort);
        box.addView(token);
        new AlertDialog.Builder(this).setTitle("添加 DSH 设备").setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Store.Dev dev = new Store.Dev();
                        dev.name = name.getText().toString().trim();
                        dev.host = host.getText().toString().trim();
                        dev.port = parseInt(port.getText().toString().trim(), 7788);
                        dev.webPort = parseInt(webPort.getText().toString().trim(), 0);
                        dev.token = token.getText().toString().trim();
                        String cmd = paste.getText().toString().trim();
                        if (cmd.length() > 0) {
                            applyPairCmd(cmd, dev);
                            toast("已解析：" + dev.name + " " + dev.host + ":" + dev.port);
                        }
                        if (dev.name.length() == 0 || dev.host.length() == 0) {
                            toast("名称和主机不能为空");
                            return;
                        }
                        store.putDevice("dsh", dev);
                        dshName = dev.name;
                        store.setDef("dsh", dev.name);
                        reloadDevices();
                        closeDsh();
                        connectDsh(true);
                    }
                }).setNegativeButton("取消", null).show();
    }

    /** 解析 dshctl 配对命令：dshctl add home 192.168.2.7:7788 --token xxx */
    private void applyPairCmd(String cmd, Store.Dev dev) {
        String[] t = cmd.trim().replaceAll("\\s+", " ").split(" ");
        for (int i = 0; i < t.length; i++) {
            String s = t[i];
            if (s.equals("--token") && i + 1 < t.length) {
                dev.token = t[++i];
            } else if (s.equals("add") && i + 1 < t.length && !t[i + 1].startsWith("-")) {
                dev.name = t[++i];
            } else if (s.matches("[0-9A-Za-z_.\\-]+(:[0-9]+)?")) {
                int c = s.indexOf(':');
                String h = c > 0 ? s.substring(0, c) : s;
                int pp = c > 0 ? parseInt(s.substring(c + 1), 0) : 0;
                if (h.indexOf('.') > 0 || pp > 0) {
                    dev.host = h;
                    if (pp > 0) {
                        dev.port = pp;
                    }
                }
            }
        }
    }

    private void dialogDevices() {
        final List<Store.Dev> list = store.devices("dsh");
        final String[] items = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            Store.Dev d = list.get(i);
            items[i] = d.name + "   " + d.addr() + "   token:" + mask(d.token);
        }
        items[list.size()] = "＋ 添加设备…";
        new AlertDialog.Builder(this).setTitle("DSH 设备").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == list.size()) {
                    dialogAddDsh();
                    return;
                }
                final Store.Dev dev = list.get(which);
                new AlertDialog.Builder(MainActivity.this).setTitle(dev.name)
                        .setItems(new String[]{"设为当前", "编辑", "删除"}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dd, int w) {
                                if (w == 0) {
                                    dshName = dev.name;
                                    store.setDef("dsh", dev.name);
                                    reloadDevices();
                                    closeDsh();
                                    connectDsh(true);
                                    sidebar.refresh();
                                } else if (w == 1) {
                                    dialogEditDsh(dev);
                                } else {
                                    store.remove("dsh", dev.name);
                                    reloadDevices();
                                    toast("已删除 " + dev.name);
                                }
                            }
                        }).show();
            }
        }).show();
    }

    private void dialogEditDsh(Store.Dev dev) {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 14);
        box.setPadding(p, p, p, p);
        final EditText host = Ui.input(this, "主机");
        host.setText(dev.host);
        final EditText port = Ui.input(this, "端口");
        port.setText(String.valueOf(dev.port));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText webPort = Ui.input(this, "网页端口（走穿透时填，留空=7790）");
        webPort.setText(dev.webPort > 0 ? String.valueOf(dev.webPort) : "");
        webPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText token = Ui.input(this, "token");
        token.setText(dev.token);
        box.addView(host);
        box.addView(port);
        box.addView(webPort);
        box.addView(token);
        new AlertDialog.Builder(this).setTitle("编辑 " + dev.name).setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        dev.host = host.getText().toString().trim();
                        dev.port = parseInt(port.getText().toString().trim(), 7788);
                        dev.webPort = parseInt(webPort.getText().toString().trim(), 0);
                        dev.token = token.getText().toString().trim();
                        store.putDevice("dsh", dev);
                        closeDsh();
                        toast("已保存");
                    }
                }).setNegativeButton("取消", null).show();
    }

    // ---------------- 连接 ----------------

    public TabChat chatTab() {
        return (TabChat) tabChat;
    }

    /** 选图 / 拍照的结果转给聊天页（TabChat 自己发起的选择器）。 */
    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.themeDirty) {                 // 设置页换了主题：颜色都是构造时定的，重建一次
            Ui.themeDirty = false;
            recreate();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 4711 && res == RESULT_OK && tabChat instanceof TabChat) {
            ((TabChat) tabChat).onPickResult(data);
        }
    }

    public Dsh requireDsh() throws Exception {
        if (dsh != null && dsh.alive()) return dsh;
        Store.Dev dev = store.find("dsh", dshName);
        if (dev == null) throw new Exception("未配置 DSH 设备");
        final Dsh c = Dsh.open(dev, 12000, "dshconsole/1.0", "Android " + Build.VERSION.RELEASE);
        synchronized (this) {
            dsh = c;
        }
        ui(new Runnable() {
            public void run() {
                setStatus("已连接 " + dev.addr() + " · " + c.hostName, Ui.GREEN);
            }
        });
        return c;
    }

    /** 开一条独立连接（长驻事件流用，避免和 request/response 抢读）。 */
    public Dsh openDsh(int timeoutMs) throws Exception {
        Store.Dev dev = Cores.get().device(store, dshName);
        if (dev == null) throw new Exception("未配置 DSH 设备");
        return Dsh.open(dev, timeoutMs, "dshconsole/1.0", "Android " + Build.VERSION.RELEASE);
    }

    public void connectDsh(final boolean loud) {
        setStatus("连接中…", Ui.AMBER);
        bg(new Runnable() {
            public void run() {
                try {
                    requireDsh();
                    if (sidebar != null) sidebar.refresh();
                } catch (final Exception e) {
                    ui(new Runnable() {
                        public void run() {
                            String m = String.valueOf(e.getMessage());
                            String low = m.toLowerCase();
                            if (low.contains("token") || low.contains("unauthorized")) {
                                // 最常见的一种：配对信息过期/被改过 —— 直接把话说明白
                                setStatus("连不上：token 不对 · 重新粘贴一次配对命令", Ui.RED);
                            } else {
                                setStatus("连接失败：" + m, Ui.RED);
                            }
                        }
                    });
                    if (loud) toast(e.getMessage());
                }
            }
        });
    }

    public void closeDsh() {
        final Dsh c = dsh;
        dsh = null;
        if (c != null) {
            bg(new Runnable() {
                public void run() {
                    c.close();
                }
            });
        }
    }

    public void resetDsh() {
        closeDsh();
    }

    // ---------------- 工具 ----------------

    public void bg(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    public void ui(Runnable r) {
        runOnUiThread(r);
    }

    public void toast(String msg) {
        final String m = msg == null ? "" : msg;
        ui(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    public static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    public static String mask(String t) {
        if (t == null) return "无";
        if (t.length() <= 8) return t;
        return t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
        } else if (current != 1) {
            select(1);
        } else {
            super.onBackPressed();
        }
    }
}
