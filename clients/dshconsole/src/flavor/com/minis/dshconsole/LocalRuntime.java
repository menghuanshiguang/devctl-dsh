package com.minis.dshconsole;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地版的那块"环境"：把 harness 装进 app 私有目录并跑起来。
 *
 * 为什么不是直接用 Android 的 node：harness 启动时**无条件**加载原生模块
 * `node-addon-require-builtin`，它只有 glibc 预编译、且没有公开源码 —— bionic 上根本加载不了。
 * 所以这里走 proot + 一个精简 Ubuntu rootfs 的路子（这正是 Termux 的 proot-distro 干的事，
 * 只是全部收进 app 里，用户不需要装 Termux）。
 *
 * 目录布局（都在 app 私有目录，卸载即清）：
 *   files/local/rootfs/     Ubuntu base 解出来的根
 *   files/local/rootfs/opt/node       glibc 版 Node
 *   files/local/rootfs/opt/dsh        harness（npm i @deepseek-ai/dsh）
 *   files/local/rootfs/opt/devctl-dsh 插件仓库（含本地专用 patch）
 *   files/local/harness.log           运行日志
 *   files/local/state.json            装机状态
 *
 * 每一步都往 logcat 打 `DshLocal`，方便对着日志排查（也方便在没界面的情况下验证）。
 */
final class LocalRuntime implements LocalEnv {

    private static final String TAG = "DshLocal";
    private static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz";
    private static final String NODE_URL =
            "https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-arm64.tar.gz";
    private static final String PLUGIN_URL =
            "https://codeload.github.com/menghuanshiguang/devctl-dsh/tar.gz/refs/heads/main";
    private static final String DSH_VERSION = "0.2.0-rc.1";

    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static final AtomicBoolean STARTING = new AtomicBoolean(false);
    private static Process HARNESS;

    // ---------------- 对外（LocalEnv） ----------------

    public String state(Context c) {
        JSONObject s = readState(c);
        if (isRunning()) return "已就绪 · harness 在跑";
        String phase = s.optString("phase", "none");
        if ("ready".equals(phase)) return "已装好 · 还没启动";
        if ("installing".equals(phase)) return "正在安装 · " + s.optString("message", "");
        if ("failed".equals(phase)) return "上次安装失败 · " + s.optString("message", "");
        return "还没装（点下面的「一键启用」）";
    }

    public boolean ready(Context c) {
        return isReady(c);
    }

    public boolean running(Context c) {
        return isRunning() || portOpen(c);
    }

    public void install(Context c, LocalEnv.Progress cb) {
        installStatic(c, cb);
    }

    public void start(Context c, LocalEnv.Progress cb) {
        startStatic(c, cb);
    }

    /**
     * 自检加一条关键判定：**app 私有目录里的 ELF 能不能被 execve**。
     * Android 10+ 的 W^X 会把 app_data_file 的 execute 权限收掉，proot 里的 Linux 用户态
     * 就是踩在这一条上（报 "execve(...): No such file or directory / loader was not found"）。
     * 把 proot 复制到 filesDir 下跑一次，能立刻分清是"权限被拒"还是"rootfs/加载器的问题"。
     */
    private String datadirExecProbe(Context c) {
        try {
            File src = proot(c);
            File copy = new File(home(c), "exec-probe");
            copyFile(src, copy);
            if (!copy.setExecutable(true, false)) return "私有目录 exec：setExecutable 失败";
            ProcessBuilder pb = new ProcessBuilder(copy.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(readAll(p.getInputStream()), "UTF-8").trim();
            int code = p.waitFor();
            return "私有目录 exec：可以（" + (out.length() > 0 ? out.split("\n")[0] : ("exit " + code)) + "）";
        } catch (Throwable t) {
            return "私有目录 exec：被拒（" + t.getMessage() + "）";
        }
    }

    public String selfCheck(Context c) {
        File p = proot(c);
        if (!p.exists()) return "缺 libproot.so（包没打进去？）";
        try {
            String out = exec(new String[]{p.getAbsolutePath(), "--version"}, 20);
            return "proot " + out.trim().replace('\n', ' ');
        } catch (Throwable t) {
            return "proot 起不来：" + t;
        }
    }

    // ---------------- 路径 ----------------

    static File home(Context c) {
        File d = new File(c.getFilesDir(), "local");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File rootfs(Context c) {
        return new File(home(c), "rootfs");
    }

    static File proot(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    static File logFile(Context c) {
        return new File(home(c), "harness.log");
    }

    private static File stateFile(Context c) {
        return new File(home(c), "state.json");
    }

    // ---------------- 状态 ----------------

    static JSONObject readState(Context c) {
        try {
            File f = stateFile(c);
            if (!f.exists()) return new JSONObject();
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            return new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void writeState(Context c, String phase, String message, int pct) {
        try {
            JSONObject o = readState(c);
            o.put("phase", phase);
            o.put("message", message);
            o.put("pct", pct);
            o.put("at", System.currentTimeMillis());
            FileOutputStream out = new FileOutputStream(stateFile(c));
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, phase + " " + pct + "% " + message);
    }

    /**
     * "装好了没有"就看文件，不看状态字。
     * 以前用 phase=="ready"，结果启动成功把状态写成 running 之后，界面又以为没装 ✗
     */
    static boolean isReady(Context c) {
        File root = rootfs(c);
        return new File(root, "opt/node/bin/node").exists()
                && new File(root, "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").exists();
    }

    static boolean isRunning() {
        Process p = HARNESS;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    // ---------------- 一键安装 ----------------

    /** 装：rootfs → Node → harness → 插件。已经在装就直接返回。 */
    static void installStatic(final Context ctx, final LocalEnv.Progress cb) {
        if (!INSTALLING.compareAndSet(false, true)) {
            cb.onDone(false, "已经在装了");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 只在"这套 rootfs 是用旧版解包器解的"时才清一次（旧版会丢权限位/拼错路径）。
                    // 以前是"失败就清"，白白把 83MB 的下载和几万个文件重来一遍 —— 时间都花在重复劳动上。
                    File mark = new File(rootfs(ctx), ".extract-v" + EXTRACTOR_VERSION);
                    if (!mark.exists() && new File(rootfs(ctx), "bin/bash").exists()) {
                        Log.i(TAG, "rootfs 是旧版解包器解的，清一次重解");
                        cb.onStep("升级解包方式，重解一次…", 3);
                        deleteTree(rootfs(ctx));
                    }
                    File root = rootfs(ctx);
                    File dl = new File(home(ctx), "dl");
                    dl.mkdirs();
                    writeState(ctx, "installing", "准备中", 0);
                    cb.onStep("准备中…", 0);

                    // 1) rootfs
                    if (!new File(root, "bin/bash").exists()) {
                        File t = new File(dl, "rootfs.tar.gz");
                        cb.onStep("下载 Ubuntu 根文件系统（约 30MB）…", 2);
                        download(ROOTFS_URL, t, 2, 45, ctx, cb);
                        cb.onStep("解开根文件系统…", 45);
                        root.mkdirs();
                        extractTarGz(t, root, 45, 60, ctx, cb);
                        // 压缩包留着当缓存；整套装完再删（见 cleanDownloads）
                    } else {
                        cb.onStep("根文件系统已存在，跳过", 60);
                    }
                    writeState(ctx, "installing", "rootfs 就绪", 60);

                    try {
                        new File(root, ".extract-v" + EXTRACTOR_VERSION).createNewFile();
                    } catch (Throwable ignored) {
                    }

                    // 2) Node（glibc 版，Ubuntu 里能直接跑）
                    File node = new File(root, "opt/node/bin/node");
                    if (!node.exists()) {
                        File t = new File(dl, "node.tar.gz");
                        cb.onStep("下载 Node（约 53MB）…", 60);
                        download(NODE_URL, t, 60, 78, ctx, cb);
                        cb.onStep("解开 Node…", 78);
                        File opt = new File(root, "opt");
                        opt.mkdirs();
                        extractTarGz(t, opt, 78, 84, ctx, cb);
                        // 解出来是 node-v22.14.0-linux-arm64/，改名成 node/
                        File un = new File(opt, "node-v22.14.0-linux-arm64");
                        if (un.exists()) un.renameTo(new File(opt, "node"));
                    } else {
                        cb.onStep("Node 已存在，跳过", 84);
                    }
                    writeState(ctx, "installing", "node 就绪", 84);

                    // 3) 插件仓库（含本地专用 patch：只绑回环 + 回环免令牌）
                    File plugin = new File(root, "opt/devctl-dsh");
                    if (!plugin.exists()) {
                        File t = new File(dl, "plugin.tar.gz");
                        cb.onStep("下载 devctl-dsh 插件…", 84);
                        download(PLUGIN_URL, t, 84, 88, ctx, cb);
                        extractTarGz(t, new File(root, "opt"), 88, 90, ctx, cb);
                        File un = new File(root, "opt/devctl-dsh-main");
                        if (un.exists()) un.renameTo(plugin);
                    }
                    writeState(ctx, "installing", "插件就绪", 90);

                    writeResolvConf(ctx, root);

                    // 4) harness 本体（在 rootfs 里用 npm 装；--ignore-scripts 避开原生编译）
                    File dshBin = new File(root, "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js");
                    if (!dshBin.exists()) {
                        cb.onStep("安装 harness（npm，可能要几分钟）…", 90);
                        File dshDir = new File(root, "opt/dsh");
                        dshDir.mkdirs();
                        // guest 里 PATH 得自己给全：proot 进来时环境很干净，连 /usr/bin/tail 都找不到
                        String cmd = "export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                                + "export HOME=/root; cd /opt/dsh && "
                                + "npm init -y >/dev/null 2>&1; "
                                + "npm i --no-audit --no-fund --ignore-scripts @deepseek-ai/dsh@" + DSH_VERSION
                                + " 2>&1 | tail -5";
                        String out = prootExec(ctx, cmd, 900);
                        Log.i(TAG, "npm: " + out);
                        if (!dshBin.exists()) throw new Exception("harness 没装上：" + tail(out));
                    }
                    writeState(ctx, "installing", "harness 就绪", 96);

                    writeState(ctx, "ready", "装好了", 100);
                    cleanDownloads(ctx);              // 装完了，压缩包就不用留着了
                    cb.onDone(true, "装好了");
                } catch (Throwable t) {
                    Log.i(TAG, "install failed: " + t);
                    writeState(ctx, "failed", String.valueOf(t.getMessage()), 0);
                    cb.onDone(false, String.valueOf(t.getMessage()));
                } finally {
                    INSTALLING.set(false);
                }
            }
        }).start();
    }

    // ---------------- 启动 / 停止 ----------------

    /** 起 harness：proot 里跑 `dsh web`，带本地 patch（只绑回环 + 回环免令牌）。 */
    static void startStatic(final Context ctx, final LocalEnv.Progress cb) {
        if (isRunning()) {
            cb.onDone(true, "已经在跑");
            return;
        }
        if (!STARTING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    File root = rootfs(ctx);
                    String script = "export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                            + "export HOME=/root; "
                            + "cd /opt/dsh && exec node node_modules/@deepseek-ai/dsh/lib/bin.js web "
                            + "--patch /opt/devctl-dsh/clients/dshconsole/local/cordis.local.patch.yml";
                    List<String> cmd = prootArgs(ctx);
                    cmd.add("/bin/bash");
                    cmd.add("-lc");
                    cmd.add(script);
                    Log.i(TAG, "start: " + cmd);
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    pb.environment().put("DSH_HOME", "/root/.dsh");
                    // 起 harness 这条路上漏了两件事，结果 proot 自己都链不起来：
                    // `CANNOT LINK EXECUTABLE libproot.so: library "libtalloc.so.2" not found`
                    pb.environment().put("LD_LIBRARY_PATH", ctx.getApplicationInfo().nativeLibraryDir);
                    pb.environment().put("PROOT_TMP_DIR", home(ctx).getAbsolutePath());
                    putLoaderEnv(pb, ctx);
                    // 日志落文件：app 里能看，devctl/logcat 也能看
                    // 每次启动都清空日志：否则新问题的尾巴会被上次的报错盖住（排查时被坑过）
                    final OutputStream log = new BufferedOutputStream(new FileOutputStream(logFile(ctx), false));
                    HARNESS = pb.start();
                    final InputStream in = HARNESS.getInputStream();
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    log.write(buf, 0, n);
                                    log.flush();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }).start();
                    cb.onStep("等 harness 监听端口…", 97);
                    boolean up = false;
                    for (int i = 0; i < 180 && !up; i++) {
                        Thread.sleep(1000);
                        up = portOpen(ctx);
                        if (i % 15 == 14) {
                            // 每 15 秒把 harness 自己的日志尾巴打到 logcat ——
                            // 这样出问题我直接看日志，不用让用户反复截图
                            Log.i(TAG, "harness.log tail:\n" + tailOfLog(ctx, 15));
                        }
                    }
                    if (up) {
                        writeState(ctx, "running", "harness 在跑", 100);
                        cb.onDone(true, "已启动（127.0.0.1:7788）");
                    } else {
                        // 别写成 failed —— 文件都装好了，只是还没起来，按钮应该是"启动"而不是"一键启用"
                        writeState(ctx, "ready", "harness 还没起来（端口没监听）", 100);
                        cb.onDone(false, "harness 没起来：端口没监听（看 files/local/harness.log）");
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "start failed: " + t);
                    writeState(ctx, "ready", "启动失败：" + t.getMessage(), 100);
                    cb.onDone(false, String.valueOf(t.getMessage()));
                } finally {
                    STARTING.set(false);
                }
            }
        }).start();
    }

    static void stop() {
        Process p = HARNESS;
        HARNESS = null;
        if (p != null) p.destroy();
    }

    /** 端口通了没（harness 真的在监听）。 */
    static boolean portOpen(Context c) {
        Store store = new Store(c);
        String host = store.get("local:host", "127.0.0.1");
        int port;
        try {
            port = Integer.parseInt(store.get("local:port", "7788"));
        } catch (Throwable ignored) {
            port = 7788;
        }
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), 800);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- proot ----------------

    private static List<String> prootArgs(Context c) {
        File root = rootfs(c);
        List<String> cmd = new ArrayList<String>();
        cmd.add(proot(c).getAbsolutePath());
        cmd.add("-r");
        cmd.add(root.getAbsolutePath());
        cmd.add("-0");                                   // 假装是 root（proot 只改映射，不动系统）
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        // 关键：guest 里的 ELF 解释器（glibc 的 ld）必须落在"允许 exec"的地方。
        // app 私有目录的文件在 Android 10+ 被判成 app_data_file，execve 直接 ENOENT ——
        // proot 报的正是 `execve("/usr/bin/env"): No such file or directory / loader was not found`。
        // 所以加载器随包放进 lib/arm64-v8a（安装时解到 nativeLibraryDir，那里允许执行），
        // 再用 -b 盖到 guest 的那两条路径上。
        if (ldLib(c).exists()) {
            cmd.add("-b");
            cmd.add(ldLib(c).getAbsolutePath() + ":/lib/ld-linux-aarch64.so.1");
            cmd.add("-b");
            cmd.add(ldLib(c).getAbsolutePath() + ":/usr/lib/ld-linux-aarch64.so.1");
        }
        cmd.add("--kill-on-exit");
        return cmd;
    }

    /**
     * 失败时用 `proot -v 3` 再跑一次同一条命令：proot 默认那句
     * "loader was not found or doesn't work" 什么也没说清，verbose 里才有具体路径。
     */
    private static String prootVerboseProbe(Context c, String shellCmd) {
        try {
            List<String> cmd = prootArgs(c);
            cmd.add(1, "-v");
            cmd.add(2, "3");
            cmd.add("/bin/bash");
            cmd.add("-lc");
            cmd.add(shellCmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", c.getApplicationInfo().nativeLibraryDir);
            pb.environment().put("PROOT_TMP_DIR", home(c).getAbsolutePath());
            putLoaderEnv(pb, c);
            Process p = pb.start();
            String out = new String(readAll(p.getInputStream()), "UTF-8");
            String[] lines = out.split("\n");
            StringBuilder tail = new StringBuilder();
            int from = Math.max(0, lines.length - 25);
            for (int i = from; i < lines.length; i++) {
                tail.append(lines[i]).append("\n");
            }
            return tail.toString();
        } catch (Throwable t) {
            return "verbose 也跑不起来：" + t;
        }
    }

    /** 在 rootfs 里跑一条命令，拿回输出（安装期用）。 */
    private static String prootExec(Context c, String shellCmd, int timeoutSec) throws Exception {
        List<String> cmd = prootArgs(c);
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add(shellCmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", c.getApplicationInfo().nativeLibraryDir);
        pb.environment().put("PROOT_TMP_DIR", home(c).getAbsolutePath());
        putLoaderEnv(pb, c);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        InputStream in = p.getInputStream();
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int n = in.read(buf);
                if (n < 0) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
            } else if (!alive(p)) {
                break;
            } else {
                Thread.sleep(120);
            }
        }
        if (alive(p)) p.destroy();
        String out = sb.toString();
        if (out.indexOf("proot error") >= 0 || out.indexOf("loader was not found") >= 0) {
            // 默认那句太笼统，顺手再跑一次 verbose，把真正的路径/原因留在日志里
            Log.i(TAG, "proot 失败了，跑一次 verbose");
            out = out + "\n---- proot -v 3 ----\n" + prootVerboseProbe(c, shellCmd);
        }
        return out;
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static String exec(String[] cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        InputStream in = p.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[2048];
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int n = in.read(buf);
                if (n < 0) break;
                sb.append(new String(buf, 0, n, "UTF-8"));
            } else if (!alive(p)) {
                break;
            } else {
                Thread.sleep(100);
            }
        }
        if (alive(p)) p.destroy();
        return sb.toString();
    }

    private static String tail(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 200 ? t.substring(t.length() - 200) : t;
    }

    // ---------------- 下载 / 解包 ----------------

    /** 带进度下载；进度按 (done,total) 映射到 [from,to] 百分比。 */
    /**
     * guest 里的 /etc/resolv.conf：Ubuntu base 根文件系统**不带**它，
     * 于是 npm 一联网就 `getaddrinfo EAI_AGAIN registry.npmjs.org`。
     * 优先用 Android 自己解析到的 DNS（getprop net.dns1），拿不到再写公共 DNS。
     */
    private static void writeResolvConf(Context c, File root) {
        try {
            File etc = new File(root, "etc");
            etc.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (String prop : new String[]{"net.dns1", "net.dns2"}) {
                try {
                    Process p = new ProcessBuilder("/system/bin/getprop", prop).redirectErrorStream(true).start();
                    String v = new String(readAll(p.getInputStream()), "UTF-8").trim();
                    p.waitFor();
                    if (v.length() > 0 && v.indexOf('.') > 0) sb.append("nameserver ").append(v).append("\n");
                } catch (Throwable ignored) {
                }
            }
            if (sb.length() == 0) {
                sb.append("nameserver 223.5.5.5\n")     // 阿里的公共 DNS
                  .append("nameserver 1.1.1.1\n")
                  .append("nameserver 8.8.8.8\n");
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(new File(etc, "resolv.conf"));
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            Log.i(TAG, "resolv.conf: " + sb.toString().replace('\n', ' '));
        } catch (Throwable t) {
            Log.i(TAG, "写 resolv.conf 失败: " + t);
        }
    }

    /** harness 自己日志的尾巴（诊断用：直接进 logcat）。 */
    private static String tailOfLog(Context c, int lines) {
        try {
            String all = new String(readAll(new FileInputStream(logFile(c))), "UTF-8");
            String[] ls = all.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, ls.length - lines);
            for (int i = from; i < ls.length; i++) sb.append(ls[i]).append('\n');
            return sb.length() == 0 ? "(日志还是空的)" : sb.toString();
        } catch (Throwable t) {
            return "(读不到 harness.log: " + t + ")";
        }
    }

    /** 已经下过的包就别再下：本地环境下 83MB 重下一次要好几分钟。 */
    private static boolean cached(File f, long atLeast) {
        return f.exists() && f.length() >= atLeast;
    }

    private static void download(String url, File dst, int from, int to, Context c, LocalEnv.Progress cbState)
            throws Exception {
        if (cached(dst, 1 << 20)) {                 // 已经下过：直接跳过，别重复拉
            cbState.onStep("已缓存，跳过下载 " + dst.getName(), to);
            Log.i(TAG, "download 命中缓存: " + dst + " (" + dst.length() + " bytes)");
            return;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code / 100 != 2) throw new Exception("下载失败 HTTP " + code + "：" + url);
        long total = conn.getContentLength();
        InputStream in = new BufferedInputStream(conn.getInputStream());
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int n;
        int lastPct = -1;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = (int) (from + (to - from) * done / total);
                if (pct != lastPct) {
                    lastPct = pct;
                    writeState(c, "installing", "下载中 " + (done / 1048576) + "/" + (total / 1048576) + "MB", pct);
                    cbState.onStep("下载中 " + (done / 1048576) + "/" + (total / 1048576) + "MB", pct);
                }
            }
        }
        out.flush();
        out.close();
        in.close();
        conn.disconnect();
    }

    /**
     * 解 tar.gz。不用外部 tar：不同 Android 版本上 toybox 有没有 tar 说不准，
     * 自己解最稳（也是唯一能在 app 里保证可行性的办法）。
     * 只处理普通文件 / 目录 / 符号链接；设备节点一类的跳过（proot 会 bind /dev）。
     */
    /**
     * 解 tar.gz。
     *
     * 这里踩过一个大坑：原来把 header[345..500] 一律当 ustar 的 prefix 用，
     * 可 **GNU tar** 那 155 字节放的是 uname/gname/devmajor… —— 于是第一条之后的路径全被拼上垃圾前缀，
     * 解到一半就 `open failed: EISDIR`（npm 里有超长路径，正是 GNU longname/PAX 的常客）。
     * 现在按 magic 区分：POSIX ustar 才认 prefix；GNU 的 'L'/'K' 长名字和 PAX 'x' 头都单独处理。
     */
    private static void extractTarGz(File tarGz, File destRoot, int from, int to, Context c,
                                     LocalEnv.Progress cb) throws Exception {
        destRoot.mkdirs();
        java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(tarGz), 64 * 1024), 64 * 1024);
        byte[] header = new byte[512];
        long entries = 0;
        String pendingName = null;
        String pendingLink = null;
        while (true) {
            if (!readFully(gz, header, 512)) break;
            if (isZero(header)) break;
            String name = str(header, 0, 100);
            long size = octal(header, 124, 12);
            long mode = octal(header, 100, 8);      // 权限位：不还原它，/bin/bash 就不是可执行的
            int type = header[156] & 0xFF;
            String link = str(header, 157, 100);
            String magic = str(header, 257, 8);
            boolean posix = magic.startsWith("ustar") && !magic.startsWith("ustar ");
            String prefix = posix ? str(header, 345, 155) : "";
            String path = prefix.length() > 0 ? prefix + "/" + name : name;

            if (type == 'L') {                       // GNU 长文件名：内容就是下一个条目的路径
                pendingName = readText(gz, size);
                continue;
            }
            if (type == 'K') {                       // GNU 长链接名
                pendingLink = readText(gz, size);
                continue;
            }
            if (type == 'x' || type == 'g') {        // PAX 扩展头：path=/linkpath=
                String body = readText(gz, size);
                String p = paxValue(body, "path");
                String l = paxValue(body, "linkpath");
                if (p != null && type == 'x') pendingName = p;
                if (l != null && type == 'x') pendingLink = l;
                continue;
            }
            if (pendingName != null) {
                path = pendingName;
                pendingName = null;
            }
            if (pendingLink != null) {
                link = pendingLink;
                pendingLink = null;
            }
            File f = new File(destRoot, path);
            if (type == '5') {                                    // 目录
                f.mkdirs();
                chmod(f, mode == 0 ? 0755 : mode);
            } else if (type == '0' || type == 0 || type == '7' || type == '\0') {
                if (f.isDirectory()) deleteTree(f);               // 先前的同名目录让位
                f.getParentFile().mkdirs();
                java.io.OutputStream out = new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(f), 64 * 1024);
                long left = size;
                byte[] buf = new byte[64 * 1024];
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = gz.read(buf, 0, want);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
                out.close();
                chmod(f, mode == 0 ? 0644 : mode);
                skip(gz, pad(size));
                entries++;
                if ((entries & 0x3F) == 0) {
                    cb.onStep("解包 " + path, (int) Math.min(to, from + entries / 400));
                }
                continue;
            } else if (type == '2') {                             // 符号链接
                f.getParentFile().mkdirs();
                if (f.exists() && !isSymlink(f)) {
                    if (f.isDirectory()) deleteTree(f);
                    else f.delete();
                }
                try {
                    android.system.Os.symlink(link, f.getAbsolutePath());
                } catch (Throwable ignored) {
                }
                entries++;
                continue;
            } else if (type == '1') {                             // 硬链接：复制一份
                File src = new File(destRoot, link);
                f.getParentFile().mkdirs();
                try {
                    copyFile(src, f);
                    chmod(f, mode == 0 ? 0644 : mode);
                } catch (Throwable ignored) {
                }
                entries++;
                continue;
            } else {
                skip(gz, size + pad(size));                       // 设备/管道等：跳过
                entries++;
                continue;
            }
            skip(gz, pad(size));
            entries++;
            if ((entries & 0x3F) == 0) {
                cb.onStep("解包 " + path, (int) Math.min(to, from + entries / 400));
            }
        }
        gz.close();
        writeState(c, "installing", "解包完成（" + entries + " 项）", to);
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 读 state.json 里的一个字符串字段（没有就返回空串）。 */
    static String readStateField(Context c, String key) {
        try {
            String t = new String(readAll(new FileInputStream(stateFile(c))), "UTF-8");
            JSONObject o = new JSONObject(t);
            return o.optString(key, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * proot 自己也要一个"抓手"：它靠注入 libexec/proot/loader 来执行 guest 的 ELF。
     * 不告诉它 loader 在哪，它就会去找 Termux 的前缀（不存在）→ 报
     * `execve("/usr/bin/env"): No such file or directory ... the loader was not found or doesn't work`
     * —— 这个 loader 说的是 **proot 的 loader**，不是 guest 里 glibc 的 ld.so。
     */
    private static void putLoaderEnv(ProcessBuilder pb, Context c) {
        File dir = new File(c.getApplicationInfo().nativeLibraryDir);
        File l = new File(dir, "proot-loader");
        File l32 = new File(dir, "proot-loader32");
        if (l.exists()) pb.environment().put("PROOT_LOADER", l.getAbsolutePath());
        if (l32.exists()) pb.environment().put("PROOT_LOADER_32", l32.getAbsolutePath());
    }

    /** nativeLibraryDir 里的 glibc 加载器（打包在 lib/arm64-v8a/ld-linux-aarch64.so.1）。 */
    private static File ldLib(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "ld-linux-aarch64.so.1");
    }

    /** 解包器版本：改了 extractTarGz 的行为就 +1，让旧 rootfs 自动重解一次。 */
    private static final int EXTRACTOR_VERSION = 2;

    /** 整套装完之后再删压缩包（装不完整就留着，别让用户重复下载）。 */
    private static void cleanDownloads(Context c) {
        File dl = new File(home(c), "dl");
        File[] kids = dl.listFiles();
        if (kids == null) return;
        for (File k : kids) k.delete();
    }

    private static long pad(long size) {
        return (512 - (size % 512)) % 512;
    }

    /** 读一段条目数据（顺带吃掉填充），返回去掉尾部 NUL 的字符串。 */
    private static String readText(java.io.InputStream in, long size) throws Exception {
        byte[] data = new byte[(int) Math.max(0, size)];
        readFully(in, data, data.length);
        skip(in, pad(size));
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) end--;
        return new String(data, 0, end, "UTF-8");
    }

    /** PAX 记录格式：`<长度> <key>=<value>\n`。 */
    private static String paxValue(String body, String key) {
        int i = 0;
        while (i < body.length()) {
            int sp = body.indexOf(' ', i);
            if (sp < 0) return null;
            int len;
            try {
                len = Integer.parseInt(body.substring(i, sp).trim());
            } catch (Throwable t) {
                return null;
            }
            if (len <= 0 || i + len > body.length()) return null;
            String record = body.substring(sp + 1, i + len);
            int eq = record.indexOf('=');
            if (eq > 0 && key.equals(record.substring(0, eq))) {
                return record.substring(eq + 1, record.endsWith("\n") ? record.length() - 1 : record.length());
            }
            i += len;
        }
        return null;
    }

    /** 还原 tar 里的权限位（Android 上就是 Os.chmod）。 */
    private static void chmod(File f, long mode) {
        try {
            android.system.Os.chmod(f.getAbsolutePath(), (int) (mode & 07777));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return android.system.Os.lstat(f.getAbsolutePath()).st_mode != 0
                    && android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getAbsolutePath()).st_mode);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory() && !isSymlink(k)) deleteTree(k);
                else k.delete();
            }
        }
        f.delete();
    }

    private static void copyFile(File src, File dst) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return off > 0;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws Exception {
        long left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int r = in.read(buf, 0, want);
            if (r < 0) return;
            left -= r;
        }
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
