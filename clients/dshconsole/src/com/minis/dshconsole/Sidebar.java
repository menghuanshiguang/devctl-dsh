package com.minis.dshconsole;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 侧边抽屉：工作区 + 对话列表（ChatGPT 式）。 */
public class Sidebar {
    private android.widget.PopupWindow menu;
    private final MainActivity act;
    private final LinearLayout root;
    private final LinearLayout wsBox;
    private final LinearLayout sessBox;
    private final TextView info;

    public Sidebar(MainActivity a) {
        act = a;
        root = Ui.col(a);
        root.setBackgroundColor(Ui.BG);                 // Minis：抽屉与页面同底，靠发丝线分界
        int p = Ui.dp(a, 14);
        root.setPadding(p, Ui.dp(a, 12), p, Ui.dp(a, 10));

        LinearLayout top = Ui.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.tv(a, "DshConsole", 16f, Ui.TEXT);
        top.addView(t);
        View sp = new View(a);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(icon("⟳", new Runnable() {
            public void run() {
                refresh();
            }
        }));
        top.addView(icon("＋", new Runnable() {
            public void run() {
                newSession();
            }
        }));
        root.addView(top);

        info = Ui.tv(a, "", 11f, Ui.DIM);
        info.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 4));
        root.addView(info);

        // 搜索框：只过滤已经拉到本地的列表，不打网络
        final android.widget.EditText q = new android.widget.EditText(a);
        q.setHint("搜索会话…");
        q.setSingleLine(true);
        q.setTextSize(13.5f);
        q.setTextColor(Ui.TEXT);
        q.setHintTextColor(Ui.MUT);
        q.setBackground(Ui.bg(Ui.SURF2, 12, a));
        q.setPadding(Ui.dp(a, 14), Ui.dp(a, 9), Ui.dp(a, 14), Ui.dp(a, 9));
        root.addView(q);
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable ed) {
                query = ed.toString().trim();
                renderSessions(allSess);
                renderWorkspaces(allWs);
            }
            public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            public void onTextChanged(CharSequence s, int st, int bf, int c) { }
        });

        ScrollView sc = new ScrollView(a);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout list = Ui.col(a);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        list.addView(section("工作区", "＋", new Runnable() {
            public void run() {
                newWorkspace();
            }
        }));
        wsBox = Ui.col(a);
        list.addView(wsBox);

        list.addView(section("对话", "⟳", new Runnable() {
            public void run() {
                loadSessions();
            }
        }));
        sessBox = Ui.col(a);
        list.addView(sessBox);

        LinearLayout foot = Ui.row(a);
        foot.setPadding(0, Ui.dp(a, 6), 0, 0);
        LinearLayout seg = Ui.row(a);                                   // 一整条分段控件，不再是三块砖
        seg.setGravity(Gravity.CENTER_VERTICAL);
        seg.setBackground(Ui.bg(Ui.SURF2, 9, a));
        int sp2 = Ui.dp(a, 3);
        seg.setPadding(sp2, sp2, sp2, sp2);
        seg.addView(footBtn("设备", 3));
        seg.addView(segLine(a));
        seg.addView(footBtn("事件", 4));
        seg.addView(segLine(a));
        seg.addView(footBtn("模型/权限", 2));
        foot.addView(seg);
        root.addView(foot);

        // 底部设置入口：点开是本地暗色二级菜单（原顶栏 ⋮ 的白底弹窗已撤）
        View line = new View(act);
        line.setBackgroundColor(Ui.STROKE);
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout setRow = Ui.row(act);
        setRow.setGravity(Gravity.CENTER_VERTICAL);
        setRow.setMinimumHeight(Ui.dp(act, 44));
        setRow.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
        Ui.press(setRow, act, 0x00000000, Ui.R_CHIP);
        setRow.addView(Ui.tv(act, "\u2699  \u8bbe\u7f6e", 13.5f, Ui.DIM),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        setRow.addView(Ui.tv(act, "\u203a", 15f, Ui.MUT));
        setRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                settingsMenu(v);
            }
        });
        root.addView(setRow);
    }

    /** 二级菜单：贴着「设置」行弹出的暗色小面板。 */
    private void settingsMenu(View anchor) {
        LinearLayout box = Ui.col(act);
        box.setBackground(Ui.bg(Ui.SURF2, Ui.R_CARD, act));
        int p = Ui.dp(act, 6);
        box.setPadding(p, p, p, p);
        box.addView(menuItem("\u65b0\u5efa\u5bf9\u8bdd", "\u5728\u5f53\u524d\u5de5\u4f5c\u533a\u5f00\u65b0\u4f1a\u8bdd", 0));
        box.addView(menuItem("\u5237\u65b0\u5217\u8868", "\u91cd\u62c9\u5de5\u4f5c\u533a / \u4f1a\u8bdd", 1));
        box.addView(menuItem("\u5386\u53f2\u6d88\u606f", "\u91cd\u62c9\u6700\u8fd1\u8bb0\u5f55", 2));
        box.addView(menuItem("\u6362\u4f1a\u8bdd", "\u6311\u4e00\u4e2a\u5df2\u6709\u4f1a\u8bdd", 3));
        box.addView(menuItem("\u6e05\u5c4f", "\u53ea\u6e05\u672c\u5730\u663e\u793a", 4));
        box.addView(menuItem("\u6253\u65ad\u5f53\u524d\u56de\u5408", null, 5));
        box.addView(menuItem("\u5207\u6362\u6743\u9650 / \u6a21\u578b", null, 6));
        box.addView(menuItem("\u4e8b\u4ef6", null, 7));
        box.addView(menuItem("\u8bbe\u5907\u7ba1\u7406", null, 8));
        box.addView(menuItem("\u91cd\u65b0\u8fde\u63a5", "\u4e8b\u4ef6\u6d41\u65ad\u4e86\u5c31\u70b9\u8fd9\u4e2a", 9));

        ScrollView sc = new ScrollView(act);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        menu = new android.widget.PopupWindow(sc, Ui.dp(act, 244),
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(Ui.bg(0x00000000, 0, act));
        menu.setOutsideTouchable(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            menu.setElevation(Ui.dp(act, 10));
        }
        menu.showAtLocation(root, Gravity.BOTTOM | Gravity.START,
                Ui.dp(act, 10), Ui.dp(act, 118));
    }

    private View menuItem(String title, String sub, final int idx) {
        LinearLayout r = Ui.col(act);
        r.setMinimumHeight(Ui.dp(act, sub == null ? 40 : 48));
        r.setPadding(Ui.dp(act, 10), Ui.dp(act, 7), Ui.dp(act, 10), Ui.dp(act, 7));
        Ui.press(r, act, 0x00000000, Ui.R_CHIP);
        r.addView(Ui.tv(act, title, 13.5f, Ui.TEXT));
        if (sub != null) {
            r.addView(Ui.tv(act, sub, 10.5f, Ui.MUT));
        }
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (menu != null) {
                    menu.dismiss();
                }
                act.runMenuAction(idx);
            }
        });
        return r;
    }

    public View view() {
        return root;
    }

    public void refresh() {
        loadWorkspaces();
        loadSessions();
    }

    // ---------------- 数据 ----------------

    public void loadWorkspaces() {
        wsBox.removeAllViews();
        wsBox.addView(row("加载中…", "", false, null, null, Ui.DIM));
        act.bg(new Runnable() {
            public void run() {
                try {
                    final Dsh c = act.requireDsh();
                    final JSONObject r = c.request("workspaces.list", new JSONObject(), 30000, null);
                    act.ui(new Runnable() {
                        public void run() {
                            renderWorkspaces(r.optJSONArray("items"));
                        }
                    });
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            wsBox.removeAllViews();
                            wsBox.addView(row("加载失败：" + e.getMessage(), "点此填 token / 改地址", false, new Runnable() {
                                public void run() {
                                    act.openConnSettings();
                                }
                            }, null, Ui.RED));
                            info.setText("未连接 · " + act.dshName);
                            info.setTextColor(Ui.RED);
                        }
                    });
                }
            }
        });
    }

    private void renderWorkspaces(JSONArray items) {
        allWs = items;
        wsBox.removeAllViews();
        if (items == null || items.length() == 0) {
            wsBox.addView(row("(无工作区)", "", false, null, null, Ui.DIM));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            final JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            final String id = o.optString("workspaceId", "");
            final String title = o.optString("title", id);
            final String path = o.optString("path", "");
            final boolean open = openWs.contains(id);
            final JSONArray kids = wsSessions(o);
            final boolean active = o.optBoolean("default", false)
                    || id.equals(act.store.lastWorkspace(act.dshName));
            String sub = (path.length() > 0 ? path : id) + " · " + kids.length() + " 个会话";
            // 一个工作区 = 一张卡：父行 + 子会话 + 新建入口全收在卡里
            LinearLayout grp = Ui.col(act);                          // 扁平树，不套卡
            grp.setPadding(0, 0, 0, Ui.dp(act, 4));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.bottomMargin = Ui.dp(act, 6);
            wsBox.addView(grp, glp);

            // 点父节点 = 就地展开/收起二次列表（不关抽屉，不然看不到子项）；长按重命名
            grp.addView(row((open ? "▾ " : "▸ ") + title, sub, false, new Runnable() {
                public void run() {
                    if (openWs.contains(id)) openWs.remove(id);
                    else openWs.add(id);
                    act.store.setLastWorkspace(act.dshName, id);   // ＋ 新建会话默认落在这里
                    renderWorkspaces(allWs);
                }
            }, new Runnable() {
                public void run() {
                    renameWorkspace(id, title);
                }
            }, Ui.TEXT));
            if (!open) continue;
            if (kids.length() == 0) {
                grp.addView(subRow("(该工作区还没有会话)", "", false, null, null));
            } else {
                for (int j = 0; j < kids.length(); j++) {
                    final JSONObject s = kids.optJSONObject(j);
                    if (s == null) continue;
                    final String sid = s.optString("sessionId", "");
                    final String st = s.optString("title", "");
                    final String t2 = st.length() > 0 ? st : "(未命名)";
                    grp.addView(subRow(t2, TabSessions.age(s.optLong("updatedAt", 0)),
                            sid.equals(act.chatTab().currentSessionId()), new Runnable() {
                                public void run() {
                                    act.openChat(sid, t2);      // openChat 里会顺手回收抽屉
                                }
                            }, new Runnable() {
                                public void run() {
                                    actions(s);
                                }
                            }));
                }
            }
            grp.addView(subRow("＋ 在此工作区新建会话", "", false, new Runnable() {
                public void run() {
                    newSessionIn(id);
                }
            }, null));
        }
    }

    /** 某个工作区下的会话：优先用 workspaces.list 的 sessionIds，没有就按 cwd 匹配。 */
    private JSONArray wsSessions(JSONObject ws) {
        JSONArray out = new JSONArray();
        if (allSess == null) return out;
        JSONArray ids = ws.optJSONArray("sessionIds");
        String path = ws.optString("path", "");
        for (int i = 0; i < allSess.length(); i++) {
            JSONObject s = allSess.optJSONObject(i);
            if (s == null) continue;
            if ("subagent".equals(s.optString("origin"))) continue;
            String sid = s.optString("sessionId", "");
            boolean hit = false;
            if (ids != null && ids.length() > 0) {
                for (int k = 0; k < ids.length(); k++) {
                    if (sid.equals(ids.optString(k))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit && path.length() > 0) {
                // Windows 盘符大小写 / 反斜杠差异会让 equals 失手，归一后再比
                String a = path.replace('\\', '/');
                String b = s.optString("cwd", "").replace('\\', '/');
                hit = a.equalsIgnoreCase(b);
            }
            if (hit) out.put(s);
        }
        return out;
    }

    /** 二次列表里的子行：左侧缩进，颜色压暗，跟父节点区分开。 */
    /** 工作区里的会话行：缩进一级 + 前面一个状态圆点 + 时间右对齐（跟主机端一个样）。 */
    private View subRow(String title, String sub, boolean active, final Runnable onClick, final Runnable onLong) {
        LinearLayout wrap = Ui.row(act);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setMinimumHeight(Ui.dp(act, 44));
        wrap.setPadding(Ui.dp(act, 20), Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6));

        if (onClick != null && !title.startsWith("＋")) {         // 新建入口 / 空态提示不挂圆点
            View dot = new View(act);
            android.graphics.drawable.GradientDrawable dg =
                    new android.graphics.drawable.GradientDrawable();
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dg.setColor(active ? Ui.ACCENT : 0xFF3FBF5F);         // 当前会话蓝点，其余绿点
            dot.setBackground(dg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(act, 7), Ui.dp(act, 7));
            dlp.rightMargin = Ui.dp(act, 10);
            wrap.addView(dot, dlp);
        }

        TextView t = Ui.tv(act, title, 13.5f, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wrap.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (sub != null && sub.length() > 0) {
            TextView r = Ui.tv(act, sub, 11f, Ui.MUT);
            r.setPadding(Ui.dp(act, 10), 0, 0, 0);
            wrap.addView(r);
        }

        Ui.press(wrap, act, active ? Ui.SURF : 0x00000000, Ui.dp(act, 8));   // 选中 = 灰底卡片
        if (onClick != null) {
            wrap.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    onClick.run();
                }
            });
        }
        wrap.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (onLong != null) onLong.run();
                return true;
            }
        });
        return wrap;
    }

    public void loadSessions() {
        sessBox.removeAllViews();
        sessBox.addView(row("加载中…", "", false, null, null, Ui.DIM));
        act.bg(new Runnable() {
            public void run() {
                try {
                    final Dsh c = act.requireDsh();
                    final JSONObject r = c.request("sessions.list", new JSONObject(), 30000, null);
                    act.ui(new Runnable() {
                        public void run() {
                            allSess = r.optJSONArray("items");
                            renderSessions(allSess);
                            renderWorkspaces(allWs);     // 工作区二次列表跟着刷新
                        }
                    });
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            sessBox.removeAllViews();
                            sessBox.addView(row("加载失败：" + e.getMessage(), "点此填 token / 改地址", false, new Runnable() {
                                public void run() {
                                    act.openConnSettings();
                                }
                            }, null, Ui.RED));
                            info.setText("未连接 · " + act.dshName);
                            info.setTextColor(Ui.RED);
                        }
                    });
                }
            }
        });
    }

    private void renderSessions(JSONArray items) {
        sessBox.removeAllViews();
        int n = 0;
        String lastBucket = "";                              // 时间分段：今天 / 7 天内 / 30 天内 / 年月
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject s = items.optJSONObject(i);
                if (s == null) continue;
                if ("subagent".equals(s.optString("origin"))) continue;
                final String id = s.optString("sessionId", "");
                final String title = s.optString("title", "");
                String sub = TabSessions.age(s.optLong("updatedAt", 0));
                String cwd = s.optString("cwd", "");
                if (cwd.length() > 0) {
                    int k = cwd.lastIndexOf('/');
                    sub = sub + " · " + (k >= 0 && k + 1 < cwd.length() ? cwd.substring(k + 1) : cwd);
                } else {
                    sub = sub + " · " + TabSessions.shortId(id);
                }
                final String t2 = title.length() > 0 ? title : "(未命名)";
                if (query.length() > 0) {                        // 搜索：标题或副标题命中才留
                    String ql = query.toLowerCase();
                    if (!t2.toLowerCase().contains(ql) && !sub.toLowerCase().contains(ql)) continue;
                }
                String bk = bucket(s.optLong("updatedAt", 0));
                if (!bk.equals(lastBucket)) {
                    sessBox.addView(section(bk, "", null));
                    lastBucket = bk;
                }
                final JSONObject so = s;
                final boolean active = id.equals(act.chatTab().currentSessionId());
                sessBox.addView(row(t2, sub, active, new Runnable() {
                    public void run() {
                        act.openChat(id, t2);
                    }
                }, new Runnable() {
                    public void run() {
                        actions(so);
                    }
                }, Ui.TEXT));
                n++;
            }
        }
        if (n == 0) sessBox.addView(row("(还没有对话)", "点 ＋ 开一个", false, null, null, Ui.DIM));
        info.setText(act.dshName + " · " + n + " 个对话");
        info.setTextColor(Ui.DIM);
    }

    // ---------------- 动作 ----------------

    /** 工作区二次列表：缓存 list 结果，展开/收起纯本地渲染，不再打网络。 */
    private String query = "";                              // 侧栏搜索框的过滤词（本地过滤）
    private JSONArray allWs = null;
    private JSONArray allSess = null;
    private final java.util.HashSet<String> openWs = new java.util.HashSet<String>();

    /** 在指定工作区里新建会话（父节点二次列表里的 ＋）。 */
    private void newSessionIn(final String wsId) {
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject p = new JSONObject();
                    p.put("workspaceId", wsId);
                    JSONObject r = c.request("sessions.create", p, 30000, null);
                    final String id = r.optString("sessionId", "");
                    act.store.setLastWorkspace(act.dshName, wsId);
                    act.ui(new Runnable() {
                        public void run() {
                            act.openChat(id, "新会话");    // openChat 里会回收抽屉
                            loadSessions();
                        }
                    });
                } catch (final Exception e) {
                    act.toast("新建失败：" + e.getMessage());
                }
            }
        });
    }

    public void newSession() {
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject p = new JSONObject();
                    String ws = act.store.lastWorkspace(act.dshName);
                    if (ws.length() > 0) p.put("workspaceId", ws);
                    JSONObject r;
                    try {
                        r = c.request("sessions.create", p, 30000, null);
                    } catch (Dsh.Remote re) {
                        if (!p.has("workspaceId")) throw re;
                        p.remove("workspaceId");
                        act.store.setLastWorkspace(act.dshName, "");
                        r = c.request("sessions.create", p, 30000, null);
                    }
                    final String id = r.optString("sessionId", "");
                    act.ui(new Runnable() {
                        public void run() {
                            act.openChat(id, "新会话");
                            loadSessions();
                        }
                    });
                } catch (final Exception e) {
                    act.toast("新建失败：" + e.getMessage());
                }
            }
        });
    }

    private void newWorkspace() {
        final EditText path = Ui.input(act, "工作区路径，例如 /sdcard/repo");
        final EditText title = Ui.input(act, "标题（可留空）");
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        String last = act.store.lastWorkspace(act.dshName);
        if (last.length() > 0 && last.indexOf('/') == 0) path.setText(last);
        box.addView(path);
        box.addView(title);
        new AlertDialog.Builder(act).setTitle("新建工作区").setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String pp = path.getText().toString().trim();
                        final String tt = title.getText().toString().trim();
                        if (pp.length() == 0) {
                            act.toast("路径不能为空");
                            return;
                        }
                        act.bg(new Runnable() {
                            public void run() {
                                try {
                                    Dsh c = act.requireDsh();
                                    JSONObject p = new JSONObject();
                                    p.put("path", pp);
                                    if (tt.length() > 0) p.put("title", tt);
                                    c.request("workspaces.create", p, 60000, null);
                                    act.ui(new Runnable() {
                                        public void run() {
                                            act.toast("已创建");
                                            loadWorkspaces();
                                        }
                                    });
                                } catch (final Exception e) {
                                    act.toast("创建失败：" + e.getMessage());
                                }
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void renameWorkspace(final String id, String cur) {
        final EditText t = Ui.input(act, "新标题");
        t.setText(cur);
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        box.addView(t);
        new AlertDialog.Builder(act).setTitle("重命名工作区").setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String tt = t.getText().toString().trim();
                        if (tt.length() == 0) return;
                        act.bg(new Runnable() {
                            public void run() {
                                try {
                                    Dsh c = act.requireDsh();
                                    JSONObject p = new JSONObject();
                                    p.put("workspaceId", id);
                                    p.put("title", tt);
                                    c.request("workspaces.rename", p, 30000, null);
                                    act.ui(new Runnable() {
                                        public void run() {
                                            loadWorkspaces();
                                        }
                                    });
                                } catch (final Exception e) {
                                    act.toast("重命名失败：" + e.getMessage());
                                }
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void actions(final JSONObject s) {
        final String id = s.optString("sessionId");
        final String title = s.optString("title", "");
        new AlertDialog.Builder(act).setTitle(TabSessions.shortId(id))
                .setItems(new String[]{"进入", "重命名", "打断当前回合", "复制 ID"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) {
                                    act.openChat(id, title);
                                } else if (w == 1) {
                                    renameSession(id, title);
                                } else if (w == 2) {
                                    act.chatTab().cancel();
                                } else {
                                    android.content.ClipboardManager cm =
                                            (android.content.ClipboardManager) act.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("session", id));
                                    act.toast("已复制");
                                }
                            }
                        }).show();
    }

    private void renameSession(final String id, String cur) {
        final EditText t = Ui.input(act, "新标题");
        t.setText(cur);
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        box.addView(t);
        new AlertDialog.Builder(act).setTitle("重命名对话").setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String tt = t.getText().toString().trim();
                        if (tt.length() == 0) return;
                        act.bg(new Runnable() {
                            public void run() {
                                try {
                                    Dsh c = act.requireDsh();
                                    JSONObject p = new JSONObject();
                                    p.put("sessionId", id);
                                    p.put("title", tt);
                                    c.request("sessions.rename", p, 30000, null);
                                    act.ui(new Runnable() {
                                        public void run() {
                                            loadSessions();
                                        }
                                    });
                                } catch (final Exception e) {
                                    act.toast("重命名失败：" + e.getMessage());
                                }
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    // ---------------- 小控件 ----------------

    private View section(String label, String action, final Runnable r) {
        LinearLayout row = Ui.row(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 2), Ui.dp(act, 12), 0, Ui.dp(act, 4));
        TextView t = Ui.tv(act, label, 11.5f, Ui.DIM);
        row.addView(t);
        View sp = new View(act);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        if (action != null && action.length() > 0) row.addView(icon(action, r));   // 空动作就不摆按钮
        return row;
    }

    private TextView icon(String text, final Runnable r) {
        final TextView t = Ui.tv(act, text, 15f, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(act, 8), Ui.dp(act, 2), Ui.dp(act, 8), Ui.dp(act, 2));
        Ui.press(t, act, 0x00000000, Ui.dp(act, 8));     // 同上：跟周围同底，不留灰药丸
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
        return t;
    }

    /** 分段控件里的竖分隔线。 */
    private View segLine(android.content.Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(c, 14)));
        return v;
    }

    private TextView footBtn(String text, final int tab) {
        TextView t = Ui.tv(act, text, 12.5f, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        Ui.press(t, act, 0x00000000, Ui.dp(act, 8));            // 底由整条控件兜，按键只留按压反馈
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                act.closeDrawer();
                act.select(tab);
            }
        });
        return t;
    }

    /** 会话列表的时间分段：今天 / 7 天内 / 30 天内 / 年月。 */
    private String bucket(long t) {
        if (t <= 0) return "更早";
        long d = System.currentTimeMillis() - t;
        if (d < 86400000L) return "今天";
        if (d < 7 * 86400000L) return "7 天内";
        if (d < 30 * 86400000L) return "30 天内";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(java.util.Calendar.YEAR) + " 年 " + (c.get(java.util.Calendar.MONTH) + 1) + " 月";
    }

    /** 一行：标题 + 副标题，active 高亮，tap/long 两个动作。 */
    private View row(String title, String sub, boolean active, final Runnable tap,
                     final Runnable hold, int color) {
        LinearLayout wrap = Ui.row(act);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setMinimumHeight(Ui.dp(act, 48));                  // Minis 列表行高，触区统一
        wrap.setPadding(Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8));
        int rr = Ui.dp(act, 16);                                // Minis：会话行圆角 16
        if (active) {
            wrap.setBackground(Ui.bg(Ui.SURF, rr, act));   // 选中 = 灰底，不带边线
        } else {
            Ui.press(wrap, act, 0x00000000, rr);                 // 无底色 + 按压涟漪反馈
        }

        LinearLayout col = Ui.col(act);
        TextView t = Ui.tv(act, title, 13.5f, active ? Ui.TEXT : color);   // 选中行提亮
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = Ui.tv(act, sub, 11f, Ui.DIM);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        wrap.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (hold != null && active) {                            // 选中行右侧露个 ⋯，点了就是长按那套动作
            TextView dots = Ui.tv(act, "⋯", 15f, Ui.MUT);
            dots.setGravity(Gravity.CENTER);
            dots.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 2), 0);
            dots.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    hold.run();
                }
            });
            wrap.addView(dots);
        }

        if (tap != null) {
            wrap.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    tap.run();
                }
            });
        }
        if (hold != null) {
            wrap.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    hold.run();
                    return true;
                }
            });
        }
        LinearLayout holder = Ui.col(act);
        holder.addView(wrap);
        return holder;
    }
}
