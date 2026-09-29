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
        // 沉浸式：内容一直画到状态栏/导航栏底下，栏位颜色 = app 自己那一层（靠下面 inset 补内边距）
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | (Ui.DARK ? 0 : android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        }
        installInsets();            // 沉浸式 + 系统栏内边距 + 把 IME 高度转给聊天页做动画
        store = new Store(this);
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
        // 回到上次那个会话（顺带也是调试入口：渲染历史会把可疑原文打进 logcat）
        try {
            String last = store.lastSession(dshName);
            if (last != null && last.length() > 0) {
                openChat(last, "");
            }
        } catch (Throwable ignored) {
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

    /**
     * 一套统一的 inset 处理（装在 content 容器上，避免和别处抢 decor 的监听）：
     *   - 状态栏/导航栏内边距补给 root（配合透明栏 = 沉浸式）；
     *   - IME 高度转给聊天页，由它做整页上抬动画（键盘起时不再叠一层导航栏内边距）。
     */
    private void installInsets() {
        if (root == null) return;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int top = 0, bottom = 0, ime = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                    ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, ime > 0 ? 0 : bottom);      // 键盘起来时底部交给动画那层
                if (tabChat instanceof TabChat) {
                    ((TabChat) tabChat).onImeInset(ime);
                }
                return insets;
            }
        });
        root.requestApplyInsets();
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
        sidebar.refresh();
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
        final EditText token = Ui.input(this, "② token（dsh host 状态页可复制）");
        box.addView(paste);
        box.addView(Ui.dim(this, "直接粘贴 host 面板里那条命令即可，自动填好下面全部："));
        box.addView(Ui.dim(this, "dshctl add home 192.168.2.7:7788 --token xxx"));
        box.addView(name);
        box.addView(host);
        box.addView(port);
        box.addView(token);
        new AlertDialog.Builder(this).setTitle("添加 DSH 设备").setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Store.Dev dev = new Store.Dev();
                        dev.name = name.getText().toString().trim();
                        dev.host = host.getText().toString().trim();
                        dev.port = parseInt(port.getText().toString().trim(), 7788);
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
        final EditText token = Ui.input(this, "token");
        token.setText(dev.token);
        box.addView(host);
        box.addView(port);
        box.addView(token);
        new AlertDialog.Builder(this).setTitle("编辑 " + dev.name).setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        dev.host = host.getText().toString().trim();
                        dev.port = parseInt(port.getText().toString().trim(), 5556);
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
                            setStatus("连接失败：" + e.getMessage(), Ui.RED);
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
