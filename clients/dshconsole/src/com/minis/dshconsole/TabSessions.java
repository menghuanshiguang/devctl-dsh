package com.minis.dshconsole;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TabSessions extends Tab {
    private ListView list;
    private TextView info;
    private final List<JSONObject> items = new ArrayList<JSONObject>();
    private Adapter adapter;

    public TabSessions(MainActivity a) {
        super(a);
    }

    private class Adapter extends BaseAdapter {
        public int getCount() {
            return items.size();
        }

        public Object getItem(int i) {
            return items.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View reuse, ViewGroup parent) {
            JSONObject s = items.get(i);
            LinearLayout box = Ui.col(act);
            box.setBackground(Ui.bg(Ui.PANEL, 10, act));
            int p = Ui.dp(act, 10);
            box.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(act, 6);
            box.setLayoutParams(lp);

            LinearLayout line = Ui.row(act);
            line.setGravity(Gravity.CENTER_VERTICAL);
            boolean running = s.optBoolean("running", false);
            TextView dot = Ui.tv(act, running ? "●" : "·", 13f, running ? Ui.GREEN : Ui.DIM);
            line.addView(dot);
            String title = s.optString("title", "");
            if (title.length() == 0) title = s.optBoolean("blank", false) ? "(新会话)" : "(无标题)";
            TextView t = Ui.tv(act, title, 14f, Ui.TEXT);
            t.setMaxLines(2);
            line.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(Ui.tv(act, age(s.optLong("updatedAt", 0)), 11f, Ui.DIM));
            box.addView(line);

            StringBuilder sub = new StringBuilder();
            sub.append(shortId(s.optString("sessionId", "")));
            String cwd = s.optString("cwd", "");
            if (cwd.length() > 0) sub.append("  ").append(cwd);
            String m = DshConsole.flattenModel(s.opt("model"));
            if (m != null) sub.append("  ").append(m);
            String perm = DshConsole.flattenPermission(s.opt("permissions"));
            if (perm != null) sub.append("  ").append(perm);
            box.addView(Ui.tv(act, sub.toString(), 11f, Ui.DIM));
            return box;
        }
    }

    public static String shortId(String id) {
        if (id == null) return "?";
        if (id.length() <= 13) return id;
        return id.substring(0, 8) + "…" + id.substring(id.length() - 4);
    }

    public static String age(long ms) {
        if (ms <= 0) return "-";
        long d = (System.currentTimeMillis() - ms) / 1000;
        if (d < 60) return d + "s";
        if (d < 3600) return (d / 60) + "m";
        if (d < 86400) return (d / 3600) + "h";
        return (d / 86400) + "d";
    }

    protected View build() {
        LinearLayout box = Ui.col(act);
        LinearLayout bar = Ui.row(act);
        Button refresh = Ui.btn(act, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });
        Button create = Ui.btn(act, "＋新建会话");
        create.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialogCreate();
            }
        });
        Button search = Ui.btn(act, "搜索");
        search.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialogSearch();
            }
        });
        bar.addView(refresh);
        bar.addView(create);
        bar.addView(search);
        box.addView(bar);

        info = Ui.tv(act, "点“刷新”加载会话列表", 11.5f, Ui.DIM);
        box.addView(info);

        list = new ListView(act);
        list.setDividerHeight(0);
        list.setAdapter(adapter = new Adapter());
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> a, View v, int i, long id) {
                JSONObject s = items.get(i);
                act.openChat(s.optString("sessionId"), s.optString("title", ""));
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> a, View v, int i, long id) {
                actions(items.get(i));
                return true;
            }
        });
        box.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    public void onShow() {
        if (items.isEmpty()) load();
    }

    private void load() {
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject r = c.request("sessions.list", new JSONObject(), 30000, null);
                    final JSONArray arr = r.optJSONArray("items");
                    final List<JSONObject> got = new ArrayList<JSONObject>();
                    int total = 0;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            if ("subagent".equals(o.optString("origin"))) continue;
                            total++;
                            got.add(o);
                        }
                    }
                    final int t = total;
                    act.ui(new Runnable() {
                        public void run() {
                            items.clear();
                            items.addAll(got);
                            adapter.notifyDataSetChanged();
                            info.setText(items.size() + " 个会话" + (t > items.size() ? "（已隐藏子代理）" : ""));
                        }
                    });
                } catch (final Exception e) {
                    act.ui(new Runnable() {
                        public void run() {
                            info.setText("加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void dialogCreate() {
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        final EditText cwd = Ui.input(act, "工作目录（可留空 = 默认）");
        final EditText preset = Ui.input(act, "agentPreset（可留空）");
        box.addView(cwd);
        box.addView(preset);
        box.addView(Ui.dim(act, "工作区默认沿用上次选择，可在“工作区”页切换。"));
        new AlertDialog.Builder(act).setTitle("新建会话").setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        create(cwd.getText().toString().trim(), preset.getText().toString().trim());
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void create(final String cwd, final String preset) {
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject params = new JSONObject();
                    if (cwd.length() > 0) params.put("cwd", cwd);
                    if (preset.length() > 0) params.put("agentPreset", preset);
                    String ws = act.store.lastWorkspace(act.dshName);
                    if (ws.length() > 0) params.put("workspaceId", ws);
                    JSONObject r;
                    try {
                        r = c.request("sessions.create", params, 30000, null);
                    } catch (Dsh.Remote re) {
                        if (!params.has("workspaceId")) throw re;
                        params.remove("workspaceId");
                        act.store.setLastWorkspace(act.dshName, "");
                        r = c.request("sessions.create", params, 30000, null);
                    }
                    final String id = r.optString("sessionId", "");
                    act.store.setLastSession(act.dshName, id);
                    act.ui(new Runnable() {
                        public void run() {
                            act.toast("已创建 " + shortId(id));
                            load();
                            act.openChat(id, "新会话");
                        }
                    });
                } catch (final Exception e) {
                    act.toast("创建失败：" + e.getMessage());
                }
            }
        });
    }

    private void dialogSearch() {
        final EditText q = Ui.input(act, "搜索会话标题/内容");
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        box.addView(q);
        new AlertDialog.Builder(act).setTitle("搜索会话").setView(box)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        search(q.getText().toString().trim());
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void search(final String query) {
        if (query.length() == 0) return;
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject params = new JSONObject();
                    params.put("query", query);
                    final JSONObject r = c.request("sessions.search", params, 30000, null);
                    final JSONArray arr = r.optJSONArray("items");
                    final List<JSONObject> got = new ArrayList<JSONObject>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o != null) got.add(o);
                        }
                    }
                    act.ui(new Runnable() {
                        public void run() {
                            items.clear();
                            items.addAll(got);
                            adapter.notifyDataSetChanged();
                            info.setText("搜索 “" + query + "” → " + got.size() + " 条");
                        }
                    });
                } catch (final Exception e) {
                    act.toast("搜索失败：" + e.getMessage());
                }
            }
        });
    }

    private void actions(final JSONObject s) {
        final String id = s.optString("sessionId");
        new AlertDialog.Builder(act).setTitle(shortId(id))
                .setItems(new String[]{"进入会话", "重命名", "打断当前回合", "复制 ID"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) {
                                    act.openChat(id, s.optString("title", ""));
                                } else if (w == 1) {
                                    rename(id);
                                } else if (w == 2) {
                                    cancel(id);
                                } else {
                                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                            act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("session", id));
                                    act.toast("已复制");
                                }
                            }
                        }).show();
    }

    private void rename(final String id) {
        final EditText t = Ui.input(act, "新标题");
        LinearLayout box = Ui.col(act);
        int p = Ui.dp(act, 14);
        box.setPadding(p, p, p, p);
        box.addView(t);
        new AlertDialog.Builder(act).setTitle("重命名").setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String title = t.getText().toString().trim();
                        act.bg(new Runnable() {
                            public void run() {
                                try {
                                    Dsh c = act.requireDsh();
                                    JSONObject params = new JSONObject();
                                    params.put("sessionId", id);
                                    params.put("title", title);
                                    c.request("sessions.rename", params, 30000, null);
                                    act.ui(new Runnable() {
                                        public void run() {
                                            act.toast("已重命名");
                                            load();
                                        }
                                    });
                                } catch (final Exception e) {
                                    act.toast("失败：" + e.getMessage());
                                }
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void cancel(final String id) {
        act.bg(new Runnable() {
            public void run() {
                try {
                    Dsh c = act.requireDsh();
                    JSONObject params = new JSONObject();
                    params.put("sessionId", id);
                    c.request("sessions.cancel", params, 30000, null);
                    act.ui(new Runnable() {
                        public void run() {
                            act.toast("已请求打断");
                            load();
                        }
                    });
                } catch (final Exception e) {
                    act.toast("失败：" + e.getMessage());
                }
            }
        });
    }
}
