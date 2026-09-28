package com.minis.dshconsole;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 工作区 / 模型 / 权限 管理页。 */
public class TabManage extends Tab {

    private TextView wsList, modelList, permList, status;
    private EditText wsPath, wsTitle, modelValue, chooser, preset;
    private ScrollView scroll;

    public TabManage(MainActivity a) {
        super(a);
    }

    protected View build() {
        LinearLayout col = Ui.col(act);
        col.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10));

        status = Ui.tv(act, "就绪", 12, Ui.DIM, false);
        col.addView(status);

        // ---- 工作区 ----
        col.addView(Ui.sectionBtn(act, "工作区 · workspaces", new View.OnClickListener() {
            public void onClick(View v) {
                loadWorkspaces();
            }
        }));
        wsList = Ui.tv(act, "(点右上角刷新)", 12, Ui.TEXT, true);
        col.addView(Ui.box(act, wsList));

        wsPath = Ui.input(act, "新工作区路径，例如 C:\\Users\\me\\proj");
        wsTitle = Ui.input(act, "标题（可留空）");
        col.addView(wsPath);
        col.addView(wsTitle);
        LinearLayout r1 = Ui.row(act);
        r1.addView(Ui.btn(act, "新建", new View.OnClickListener() {
            public void onClick(View v) {
                wsNew();
            }
        }));
        r1.addView(Ui.btn(act, "改名", new View.OnClickListener() {
            public void onClick(View v) {
                wsRename();
            }
        }));
        r1.addView(Ui.btn(act, "设为默认", new View.OnClickListener() {
            public void onClick(View v) {
                wsUse();
            }
        }));
        r1.addView(Ui.btn(act, "删除", new View.OnClickListener() {
            public void onClick(View v) {
                wsRemove();
            }
        }));
        col.addView(r1);

        // ---- 模型 ----
        col.addView(Ui.sectionBtn(act, "模型 · models", new View.OnClickListener() {
            public void onClick(View v) {
                loadModels();
            }
        }));
        modelList = Ui.tv(act, "(点右上角刷新)", 12, Ui.TEXT, true);
        col.addView(Ui.box(act, modelList));
        chooser = Ui.input(act, "序号 / provider / model（给“选择”用）");
        col.addView(chooser);
        modelValue = Ui.input(act, "provider/model（可留空＝重置）");
        col.addView(modelValue);
        LinearLayout r2 = Ui.row(act);
        r2.addView(Ui.btn(act, "选择模型", new View.OnClickListener() {
            public void onClick(View v) {
                modelSelect();
            }
        }));
        col.addView(r2);

        // ---- 权限 ----
        col.addView(Ui.sectionBtn(act, "权限 · permissions", new View.OnClickListener() {
            public void onClick(View v) {
                loadPermissions();
            }
        }));
        permList = Ui.tv(act, "(点右上角刷新)", 12, Ui.TEXT, true);
        col.addView(Ui.box(act, permList));
        preset = Ui.input(act, "预设名 / 序号（留空＝重置）");
        col.addView(preset);
        LinearLayout r3 = Ui.row(act);
        r3.addView(Ui.btn(act, "应用到当前会话", new View.OnClickListener() {
            public void onClick(View v) {
                permSet();
            }
        }));
        col.addView(r3);

        scroll = Ui.scroller(act, col);
        return scroll;
    }

    private void setStatus(final String s, final int color) {
        act.ui(new Runnable() {
            public void run() {
                status.setText(s);
                status.setTextColor(color);
            }
        });
    }

    private Dsh client() throws Exception {
        return act.requireDsh();
    }

    // ---- 工作区 ----

    public void loadWorkspaces() {
        setStatus("加载工作区…", Ui.AMBER);
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject r = client().request("workspaces.list", new JSONObject(), 30000, null);
                    final JSONArray items = r.optJSONArray("items");
                    final StringBuilder sb = new StringBuilder();
                    if (items == null || items.length() == 0) {
                        sb.append("(空)");
                    } else {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject o = items.optJSONObject(i);
                            if (o == null) continue;
                            sb.append(i + 1).append(". ").append(o.optString("title", "(无标题)"));
                            sb.append("  ·  ").append(o.optInt("sessionCount", 0)).append(" 会话");
                            if (o.optBoolean("default", false)) sb.append("  ★默认");
                            sb.append("\n    ").append(o.optString("path", ""));
                            sb.append("\n    ").append(TabSessions.shortId(o.optString("workspaceId")));
                            sb.append('\n');
                        }
                    }
                    act.ui(new Runnable() {
                        public void run() {
                            wsList.setText(sb.toString());
                        }
                    });
                    setStatus("工作区 " + (items == null ? 0 : items.length()) + " 个", Ui.GREEN);
                } catch (final Exception e) {
                    setStatus("加载失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void wsNew() {
        final String path = wsPath.getText().toString().trim();
        final String title = wsTitle.getText().toString().trim();
        if (path.length() == 0) {
            act.toast("填路径");
            return;
        }
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("path", path);
                    if (title.length() > 0) p.put("title", title);
                    JSONObject r = client().request("workspaces.create", p, 60000, newMsgSink());
                    act.toast("已创建：" + r.optString("title", ""));
                    loadWorkspaces();
                } catch (final Exception e) {
                    setStatus("新建失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void wsRename() {
        final String title = wsTitle.getText().toString().trim();
        if (title.length() == 0) {
            act.toast("填新标题");
            return;
        }
        final String which = chooser.getText().toString().trim();
        act.bg(new Runnable() {
            public void run() {
                try {
                    String id = resolveWorkspace(which);
                    JSONObject p = new JSONObject();
                    p.put("workspaceId", id);
                    p.put("title", title);
                    client().request("workspaces.rename", p, 30000, null);
                    act.toast("已改名");
                    loadWorkspaces();
                } catch (final Exception e) {
                    setStatus("改名失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void wsUse() {
        final String which = chooser.getText().toString().trim();
        act.bg(new Runnable() {
            public void run() {
                try {
                    act.store.setLastWorkspace(act.dshName, resolveWorkspace(which));
                    act.toast("已设为默认工作区");
                    loadWorkspaces();
                    loadWorkspaces();
                } catch (final Exception e) {
                    setStatus("失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void wsRemove() {
        final String which = chooser.getText().toString().trim();
        final String[] opts = {"不再列出（remove）", "删除工作区（delete）"};
        act.ui(new Runnable() {
            public void run() {
                new AlertDialog.Builder(act).setTitle("移除工作区").setItems(opts,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                final String method = w == 0 ? "workspaces.remove" : "workspaces.delete";
                                act.bg(new Runnable() {
                                    public void run() {
                                        try {
                                            String id = resolveWorkspace(which);
                                            JSONObject p = new JSONObject();
                                            p.put("workspaceId", id);
                                            client().request(method, p, 30000, null);
                                            act.toast("已执行");
                                            loadWorkspaces();
                                        } catch (final Exception e) {
                                            setStatus("失败：" + e.getMessage(), Ui.RED);
                                        }
                                    }
                                });
                            }
                        }).show();
            }
        });
    }

    private String resolveWorkspace(String which) throws Exception {
        if (which.length() >= 4 && which.indexOf('.') < 0 && which.indexOf('/') < 0
                && which.indexOf('\\') < 0) {
            return which;
        }
        JSONObject r = client().request("workspaces.list", new JSONObject(), 30000, null);
        JSONArray items = r.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("default", false)) {
                if (which.length() == 0) return o.optString("workspaceId");
            }
            if (o.optString("title", "").equals(which) || o.optString("path", "").equals(which)) {
                return o.optString("workspaceId");
            }
        }
        if (which.length() > 0) {
            int idx = -1;
            try {
                idx = Integer.parseInt(which) - 1;
            } catch (Exception ignored) {
            }
            if (idx >= 0 && items != null && idx < items.length()) {
                return items.optJSONObject(idx).optString("workspaceId");
            }
        }
        throw new Exception("找不到工作区：" + which);
    }

    // ---- 模型 ----

    public void loadModels() {
        setStatus("加载模型…", Ui.AMBER);
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject r = client().request("models.catalog", new JSONObject(), 30000, newMsgSink());
                    JSONObject current = r.optJSONObject("current");
                    final JSONArray groups = r.optJSONArray("groups");
                    final StringBuilder sb = new StringBuilder();
                    sb.append("当前：").append(DshConsole.flattenModel(current) == null
                            ? "(默认)" : DshConsole.flattenModel(current)).append("\n\n");
                    if (groups == null || groups.length() == 0) {
                        sb.append("(目录为空 — 到 DSH 主机上配置 provider)");
                    } else {
                        for (int g = 0; g < groups.length(); g++) {
                            JSONObject grp = groups.optJSONObject(g);
                            if (grp == null) continue;
                            sb.append("[").append(grp.optString("provider", "?")).append("]\n");
                            JSONArray ms = grp.optJSONArray("models");
                            for (int m = 0; ms != null && m < ms.length(); m++) {
                                JSONObject mo = ms.optJSONObject(m);
                                if (mo == null) continue;
                                sb.append("   ").append(m + 1).append(". ").append(mo.optString("model", "?"));
                                if (mo.optBoolean("default", false)) sb.append("  ★");
                                if (mo.optBoolean("active", false)) sb.append("  ●当前");
                                sb.append('\n');
                            }
                        }
                    }
                    act.ui(new Runnable() {
                        public void run() {
                            modelList.setText(sb.toString());
                        }
                    });
                    setStatus("模型目录已加载", Ui.GREEN);
                } catch (final Exception e) {
                    setStatus("加载失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void modelSelect() {
        final String val = modelValue.getText().toString().trim();
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    if (val.length() == 0) {
                        p.put("model", JSONObject.NULL);
                    } else {
                        String provider = "", model = val;
                        int slash = val.indexOf('/');
                        if (slash > 0) {
                            provider = val.substring(0, slash);
                            model = val.substring(slash + 1);
                        }
                        JSONObject m = new JSONObject();
                        m.put("provider", provider);
                        m.put("model", model);
                        p.put("model", m);
                    }
                    JSONObject r = client().request("models.select", p, 30000, newMsgSink());
                    act.toast("已切换：" + (DshConsole.flattenModel(r.optJSONObject("current")) == null
                            ? "默认" : DshConsole.flattenModel(r.optJSONObject("current"))));
                    loadModels();
                } catch (final Exception e) {
                    setStatus("切换失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    // ---- 权限 ----

    public void loadPermissions() {
        setStatus("加载权限…", Ui.AMBER);
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject r = client().request("permissions.catalog", new JSONObject(), 30000, newMsgSink());
                    final JSONArray cats = r.optJSONArray("catalog");
                    final StringBuilder sb = new StringBuilder();
                    sb.append("当前会话权限：").append(DshConsole.flattenPermission(r.opt("current")));
                    sb.append("\n\n预设：");
                    if (cats == null || cats.length() == 0) {
                        sb.append("(无)");
                    } else {
                        for (int i = 0; i < cats.length(); i++) {
                            JSONObject c = cats.optJSONObject(i);
                            if (c == null) continue;
                            sb.append("\n  ").append(c.optString("name", "?"));
                            sb.append("  —  ").append(c.optString("description", ""));
                            JSONArray ps = c.optJSONArray("presets");
                            for (int j = 0; ps != null && j < ps.length(); j++) {
                                JSONObject po = ps.optJSONObject(j);
                                if (po == null) continue;
                                sb.append("\n      ").append(po.optString("name", "?"));
                                String desc = po.optString("description", "");
                                if (desc.length() > 0) sb.append("  ·  ").append(desc);
                            }
                        }
                    }
                    act.ui(new Runnable() {
                        public void run() {
                            permList.setText(sb.toString());
                        }
                    });
                    setStatus("权限已加载", Ui.GREEN);
                } catch (final Exception e) {
                    setStatus("加载失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void permSet() {
        final String val = preset.getText().toString().trim();
        act.bg(new Runnable() {
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    if (val.length() == 0) {
                        p.put("permissions", JSONObject.NULL);
                    } else {
                        p.put("permissions", val);
                    }
                    client().request("permissions.set", p, 30000, newMsgSink());
                    act.toast("权限已更新");
                    loadPermissions();
                } catch (final Exception e) {
                    setStatus("设置失败：" + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    /** models/permissions 可能推送进度事件，这里只把终态显示出来。 */
    private Dsh.EvtSink newMsgSink() {
        return new Dsh.EvtSink() {
            public void onEvt(String evt, JSONObject data) {
                if (data == null) return;
                String msg = data.optString("message", "");
                if (msg.length() > 0) setStatus(msg, Ui.DIM);
            }
        };
    }
}
