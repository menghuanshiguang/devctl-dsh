package com.minis.dshconsole;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** devctl 设备调试页：把 agent 的方法全面板化。 */
public class TabDevice extends Tab {

    private EditText host, port, token, shellCmd, logFilter, devPath, pkgName, volText, argA, argB;
    private TextView status;
    private LogView out;

    private Devctl conn;
    private Devctl streamConn;
    private volatile boolean logcatOn = false;

    public TabDevice(MainActivity a) {
        super(a);
    }

    protected View build() {
        ScrollView scroll = Ui.scroller(act, body());
        return scroll;
    }

    private LinearLayout body() {
        LinearLayout col = Ui.col(act);
        col.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10));

        status = Ui.tv(act, "未连接", 12, Ui.DIM, false);
        col.addView(status);

        // ---- 连接配置 ----
        col.addView(Ui.section(act, "连接 · agent (TLS)", null));
        Store.Dev d = act.store.find("devctl", act.store.def("devctl"));
        if (d == null) d = act.store.find("devctl", "phone");
        host = Ui.input(act, "设备 IP");
        port = Ui.input(act, "端口");
        token = Ui.input(act, "token");
        host.setText(d == null ? "192.168.2.5" : d.host);
        port.setText(d == null ? "5556" : String.valueOf(d.port));
        token.setText(d == null ? "devctl" : d.token);
        col.addView(host);
        col.addView(port);
        col.addView(token);
        LinearLayout r0 = Ui.row(act);
        r0.addView(Ui.btn(act, "连接", new View.OnClickListener() {
            public void onClick(View v) {
                connect(true);
            }
        }));
        r0.addView(Ui.btn(act, "系统信息", new View.OnClickListener() {
            public void onClick(View v) {
                call("sysinfo", "sysinfo", new String[]{}, null);
            }
        }));
        r0.addView(Ui.btn(act, "应用列表", new View.OnClickListener() {
            public void onClick(View v) {
                call("apps", "apps", new String[]{}, null);
            }
        }));
        r0.addView(Ui.btn(act, "谁在连", new View.OnClickListener() {
            public void onClick(View v) {
                call("peers", "peers", new String[]{}, null);
            }
        }));
        r0.addView(Ui.btn(act, "op 记录", new View.OnClickListener() {
            public void onClick(View v) {
                shell("/data/local/devctl/ops.log 查看", "cat /data/local/devctl/ops.log | tail -50");
            }
        }));
        col.addView(r0);

        // ---- Shell ----
        col.addView(Ui.section(act, "Shell · 任意命令", null));
        shellCmd = Ui.input(act, "命令，例如 ps -A | head");
        col.addView(shellCmd);
        LinearLayout r1 = Ui.row(act);
        r1.addView(Ui.btn(act, "执行", new View.OnClickListener() {
            public void onClick(View v) {
                shell(null, shellCmd.getText().toString());
            }
        }));
        r1.addView(Ui.btn(act, "dash.json", new View.OnClickListener() {
            public void onClick(View v) {
                shell("dash", "cat /data/local/devctl/dash.json");
            }
        }));
        r1.addView(Ui.btn(act, "一键白屏", new View.OnClickListener() {
            public void onClick(View v) {
                call("screen on", "screen", new String[]{"on"}, null);
            }
        }));
        r1.addView(Ui.btn(act, "一键息屏", new View.OnClickListener() {
            public void onClick(View v) {
                call("screen off", "screen", new String[]{"off"}, null);
            }
        }));
        col.addView(r1);

        // ---- 日志 ----
        col.addView(Ui.section(act, "Logcat · 实时日志", null));
        logFilter = Ui.input(act, "tag 过滤（可留空＝全部）");
        col.addView(logFilter);
        LinearLayout r2 = Ui.row(act);
        r2.addView(Ui.btn(act, "开始", new View.OnClickListener() {
            public void onClick(View v) {
                startLogcat(logFilter.getText().toString().trim());
            }
        }));
        r2.addView(Ui.btn(act, "停止", new View.OnClickListener() {
            public void onClick(View v) {
                stopLogcat();
            }
        }));
        col.addView(r2);

        // ---- 文件 ----
        col.addView(Ui.section(act, "文件 · 安装 / 提取 / 传输", null));
        devPath = Ui.input(act, "设备上的路径（APK / 文件）");
        col.addView(devPath);
        pkgName = Ui.input(act, "包名（提取 APK 用）");
        col.addView(pkgName);
        LinearLayout r3 = Ui.row(act);
        r3.addView(Ui.btn(act, "安装 APK", new View.OnClickListener() {
            public void onClick(View v) {
                call("install", "install", new String[]{devPath.getText().toString().trim()}, null);
            }
        }));
        r3.addView(Ui.btn(act, "提取 APK", new View.OnClickListener() {
            public void onClick(View v) {
                call("extract", "extract", new String[]{pkgName.getText().toString().trim(),
                        "/sdcard/Download/devctl-apk"}, null);
            }
        }));
        r3.addView(Ui.btn(act, "拉取文本", new View.OnClickListener() {
            public void onClick(View v) {
                call("pull", "pull", new String[]{devPath.getText().toString().trim()}, null);
            }
        }));
        col.addView(r3);

        // ---- 控制 ----
        col.addView(Ui.section(act, "设备控制", null));
        volText = Ui.input(act, "音量百分比 0-100");
        col.addView(volText);
        LinearLayout r4 = Ui.row(act);
        r4.addView(Ui.btn(act, "音量", new View.OnClickListener() {
            public void onClick(View v) {
                call("音量", "shell", new String[]{"media volume --show --stream 3 --set "
                        + volText.getText().toString().trim()}, null);
            }
        }));
        r4.addView(Ui.btn(act, "解锁", new View.OnClickListener() {
            public void onClick(View v) {
                call("解锁", "shell", new String[]{"input keyevent 82; input swipe 500 1800 500 600"}, null);
            }
        }));
        r4.addView(Ui.btn(act, "蓝牙开", new View.OnClickListener() {
            public void onClick(View v) {
                call("蓝牙开", "shell", new String[]{"svc bluetooth enable"}, null);
            }
        }));
        r4.addView(Ui.btn(act, "蓝牙关", new View.OnClickListener() {
            public void onClick(View v) {
                call("蓝牙关", "shell", new String[]{"svc bluetooth disable"}, null);
            }
        }));
        col.addView(r4);
        LinearLayout r5 = Ui.row(act);
        r5.addView(Ui.btn(act, "WiFi 开", new View.OnClickListener() {
            public void onClick(View v) {
                call("wifi on", "shell", new String[]{"svc wifi enable"}, null);
            }
        }));
        r5.addView(Ui.btn(act, "WiFi 关", new View.OnClickListener() {
            public void onClick(View v) {
                call("wifi off", "shell", new String[]{"svc wifi disable"}, null);
            }
        }));
        r5.addView(Ui.btn(act, "进程状态", new View.OnClickListener() {
            public void onClick(View v) {
                shell("进程", "ps -A -o PID,NAME,ARGS | grep -i devctl");
            }
        }));
        col.addView(r5);

        // ---- 高级方法 ----
        col.addView(Ui.section(act, "原始方法调用 · run", null));
        argA = Ui.input(act, "方法名，例如 mem_pid / mem_refs / ui_status");
        argB = Ui.input(act, "参数（逗号分隔，可留空）");
        col.addView(argA);
        col.addView(argB);
        LinearLayout r6 = Ui.row(act);
        r6.addView(Ui.btn(act, "调用", new View.OnClickListener() {
            public void onClick(View v) {
                raw();
            }
        }));
        col.addView(r6);

        out = new LogView(act);
        out.setMinHeightDp(220);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 260));
        out.setLayoutParams(lp);
        col.addView(out);

        LinearLayout r7 = Ui.row(act);
        r7.addView(Ui.btn(act, "清屏", new View.OnClickListener() {
            public void onClick(View v) {
                out.clear();
            }
        }));
        r7.addView(Ui.btn(act, "断开", new View.OnClickListener() {
            public void onClick(View v) {
                disconnect();
            }
        }));
        col.addView(r7);
        return col;
    }

    private void setStatus(final String s, final int color) {
        act.ui(new Runnable() {
            public void run() {
                status.setText(s);
                status.setTextColor(color);
            }
        });
    }

    private void log(final String s, final int color) {
        out.line(s, color);
    }

    // ---------------- 连接 ----------------

    private Store.Dev currentDev() {
        Store.Dev d = new Store.Dev();
        d.name = "phone";
        d.host = host.getText().toString().trim();
        d.port = MainActivity.parseInt(port.getText().toString().trim(), 5556);
        d.token = token.getText().toString().trim();
        return d;
    }

    public void connect(final boolean loud) {
        act.bg(new Runnable() {
            public void run() {
                try {
                    if (conn != null) {
                        conn.close();
                        conn = null;
                    }
                    Store.Dev d = currentDev();
                    act.store.putDevice("devctl", d);
                    act.store.setDef("devctl", d.name);
                    String known = act.store.pin(d.port + "/" + d.host);
                    setStatus("连接中 " + d.host + ":" + d.port + " …", Ui.AMBER);
                    Devctl c = Devctl.open(d, known.length() == 0 ? null : known, true, 15000);
                    conn = c;
                    if (known.length() == 0 && c.observedPin.length() > 0) {
                        act.store.setPin(d.port + "/" + d.host, c.observedPin);
                        log("已固定证书指纹 (TOFU)："
                                + c.observedPin.substring(0, 16) + "…", Ui.DIM);
                    }
                    final String info = "已连接 " + d.host + ":" + d.port
                            + "  agent=" + c.agentVersion;
                    setStatus(info, Ui.GREEN);
                    log(info, Ui.GREEN);
                    if (c.agentDevice.length() > 0) log("对端设备：" + c.agentDevice, Ui.DIM);
                } catch (final Exception e) {
                    final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                    setStatus("连接失败：" + m, Ui.RED);
                    log("连接失败：" + m, Ui.RED);
                    out.line("提示：确认设备 IP/端口/token，以及 agent 是否在跑。", Ui.AMBER);
                }
            }
        });
    }

    private Devctl ensure() throws Exception {
        if (conn != null && conn.alive()) return conn;
        final Exception[] err = new Exception[1];
        final Devctl[] got = new Devctl[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Store.Dev d = currentDev();
                    String known = act.store.pin(d.port + "/" + d.host);
                    Devctl c = Devctl.open(d, known.length() == 0 ? null : known, true, 15000);
                    if (known.length() == 0 && c.observedPin.length() > 0) {
                        act.store.setPin(d.port + "/" + d.host, c.observedPin);
                    }
                    got[0] = c;
                } catch (Exception e) {
                    err[0] = e;
                }
            }
        });
        t.start();
        try {
            t.join(20000);
        } catch (InterruptedException ignored) {
        }
        if (err[0] != null) throw err[0];
        if (got[0] == null) throw new Exception("连接超时");
        conn = got[0];
        setStatus("已连接 " + host.getText() + ":" + port.getText(), Ui.GREEN);
        return conn;
    }

    private void disconnect() {
        logcatOn = false;
        try {
            if (streamConn != null) streamConn.close();
        } catch (Exception ignored) {
        }
        try {
            if (conn != null) conn.close();
        } catch (Exception ignored) {
        }
        conn = null;
        streamConn = null;
        setStatus("已断开", Ui.DIM);
    }

    // ---------------- 命令执行 ----------------

    private void shell(String label, final String command) {
        if (command == null || command.trim().length() == 0) {
            act.toast("命令为空");
            return;
        }
        label = label == null ? command : label;
        final String tag = label;
        final String cmd = command;
        if (!waitBg()) return;
        log("$ " + tag, Ui.ACCENT);
        act.bg(new Runnable() {
            public void run() {
                try {
                    Devctl c = ensure();
                    JSONObject r = c.cmd("shell", new String[]{cmd}, null, 120000, null);
                    render(r);
                } catch (Exception e) {
                    log("× " + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void call(final String label, final String method, final String[] args, final String data) {
        final String tag = label;
        final String m = method;
        if (!waitBg()) return;
        log("$ " + tag, Ui.ACCENT);
        act.bg(new Runnable() {
            public void run() {
                try {
                    Devctl c = ensure();
                    JSONObject r = c.cmd(m, args, data, 120000, null);
                    render(r);
                } catch (Exception e) {
                    log("× " + e.getMessage(), Ui.RED);
                }
            }
        });
    }

    private void raw() {
        String method = argA.getText().toString().trim();
        if (method.length() == 0) {
            act.toast("填方法名");
            return;
        }
        String[] parts = argB.getText().toString().trim().split(",");
        String[] args = new String[parts.length];
        for (int i = 0; i < parts.length; i++) args[i] = parts[i].trim();
        call(method, method, args, null);
    }

    private void render(JSONObject r) {
        if (r == null) {
            log("(无响应)", Ui.RED);
            return;
        }
        if (!r.optBoolean("ok", true)) {
            log("✗ " + r.optString("stderr", "失败"), Ui.RED);
        }
        String so = r.optString("stdout", "");
        if (so.length() > 0) log(DshConsole.clamp(so, 6000), Ui.TEXT);
        String se = r.optString("stderr", "");
        if (se.length() > 0) log(DshConsole.clamp(se, 2000), Ui.RED);
        String data = r.optString("data", "");
        if (data.length() > 0) {
            if (data.startsWith("{") || data.startsWith("[")) {
                try {
                    String pretty = data.startsWith("{")
                            ? new JSONObject(data).toString(2)
                            : new org.json.JSONArray(data).toString(2);
                    log(DshConsole.clamp(pretty, 8000), Ui.GREEN);
                } catch (Exception e) {
                    log(DshConsole.clamp(data, 6000), Ui.GREEN);
                }
            } else {
                log(DshConsole.clamp(data, 6000), Ui.GREEN);
            }
        }
        if (so.length() == 0 && se.length() == 0 && data.length() == 0) log("(空响应)", Ui.DIM);
    }

    private boolean waitBg() {
        return true;
    }

    // ---------------- logcat 流 ----------------

    private void startLogcat(final String filter) {
        if (logcatOn) {
            act.toast("已在监听");
            return;
        }
        log("$ logcat " + (filter.length() == 0 ? "(全部)" : "-s " + filter), Ui.ACCENT);
        logcatOn = true;
        setStatus("logcat 监听中…", Ui.GREEN);
        act.bg(new Runnable() {
            public void run() {
                try {
                    Store.Dev d = currentDev();
                    String known = act.store.pin(d.port + "/" + d.host);
                    final Devctl c = Devctl.open(d, known.length() == 0 ? null : known, true, 15000);
                    streamConn = c;
                    c.send("logcat", new String[]{filter});
                    c.pump(new Devctl.EvtSink() {
                        public void onEvt(JSONObject evt) {
                            String line = evt.optString("data", "");
                            if (line.length() > 0) log(line.trim(), Ui.TEXT);
                        }
                    }, new Dsh.Stop() {
                        public boolean stop() {
                            return !logcatOn;
                        }
                    });
                    logcatOn = false;
                    setStatus("已连接", Ui.GREEN);
                } catch (final Exception e) {
                    logcatOn = false;
                    log("logcat 失败：" + e.getMessage(), Ui.RED);
                    setStatus("logcat 失败", Ui.RED);
                }
            }
        });
    }

    private void stopLogcat() {
        if (!logcatOn) {
            act.toast("未在监听");
            return;
        }
        logcatOn = false;
        final Devctl c = streamConn;
        streamConn = null;
        act.bg(new Runnable() {
            public void run() {
                if (c != null) c.close();
            }
        });
        log("— logcat 已停止 —", Ui.AMBER);
        setStatus("已连接", Ui.GREEN);
    }

    public void onHide() {
        stopLogcat();
    }
}
