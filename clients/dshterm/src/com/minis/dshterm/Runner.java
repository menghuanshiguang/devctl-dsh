package com.minis.dshterm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.util.Log;

/**
 * 设备上跑 DeepSeek Harness 的"引擎"：proot + Ubuntu 根文件系统 + glibc Node + npm。
 *
 * 为什么长这样（都是踩出来的）：
 *  - harness 依赖的原生模块只有 glibc 预编译 → 必须给它一个 glibc 用户态（Ubuntu rootfs）。
 *  - proot 必须从 nativeLibraryDir 执行，且要带上它自己的 libexec/proot/loader
 *    （PROOT_LOADER），否则报 "loader was not found"。
 *  - rootfs 里的 ELF 要能 execve：app 的 targetSdk 必须是 28（Android 10+ 的 W^X）。
 *  - guest 里没有 /etc/resolv.conf 的话 npm 会 EAI_AGAIN，得自己写。
 *  - tar 解包要按 magic 分 POSIX/GNU，处理 GNU L/K 与 PAX x，并且还原权限位。
 */
final class Runner {

    static final String TAG = "DshTerm";
    private static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz";
    private static final String NODE_URL =
            "https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-arm64.tar.gz";
    private static final String DSH_VERSION = "0.2.0-rc.1";

    interface Out {
        void line(String text);
    }

    private static Process current;

    private Runner() {
    }

    static File home(Context c) {
        File d = new File(c.getFilesDir(), "rt");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File rootfs(Context c) {
        return new File(home(c), "rootfs");
    }

    static File logFile(Context c) {
        return new File(home(c), "term.log");
    }

    static File proot(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    static File ldLib(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "ld-linux-aarch64.so.1");
    }

    static boolean installed(Context c) {
        return new File(rootfs(c), "bin/bash").exists()
                && new File(rootfs(c), "opt/node/bin/node").exists();
    }

    static boolean harnessInstalled(Context c) {
        return new File(rootfs(c), "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").exists();
    }

    static boolean busy() {
        if (current == null) return false;
        try {
            current.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    static void stop() {
        if (current != null) {
            try {
                current.destroy();
            } catch (Throwable ignored) {
            }
            current = null;
        }
    }

    // ---------------- proot ----------------

    private static void putEnv(ProcessBuilder pb, Context c) {
        pb.environment().put("LD_LIBRARY_PATH", c.getApplicationInfo().nativeLibraryDir);
        pb.environment().put("PROOT_TMP_DIR", home(c).getAbsolutePath());
        File loader = new File(c.getApplicationInfo().nativeLibraryDir, "proot-loader");
        File loader32 = new File(c.getApplicationInfo().nativeLibraryDir, "proot-loader32");
        if (loader.exists()) pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        if (loader32.exists()) pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());
    }

    private static List<String> prootArgs(Context c) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(proot(c).getAbsolutePath());
        cmd.add("-r");
        cmd.add(rootfs(c).getAbsolutePath());
        cmd.add("-0");
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        if (ldLib(c).exists()) {                       // guest 的 ELF 解释器必须是可执行的那份
            cmd.add("-b");
            cmd.add(ldLib(c).getAbsolutePath() + ":/lib/ld-linux-aarch64.so.1");
            cmd.add("-b");
            cmd.add(ldLib(c).getAbsolutePath() + ":/usr/lib/ld-linux-aarch64.so.1");
        }
        cmd.add("--kill-on-exit");
        return cmd;
    }

    /** 在 guest 里跑一条命令；输出按行流出去（同时进 logcat 与日志文件）。 */
    static void exec(final Context ctx, String shellCmd, final Out out) throws Exception {
        List<String> cmd = prootArgs(ctx);
        cmd.add("/bin/bash");
        cmd.add("-lc");
        cmd.add("export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                + "export HOME=/root; " + shellCmd);
        // 输出统一由 MainActivity 镜像到 logcat，这里不再重复打
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        putEnv(pb, ctx);
        current = pb.start();
        InputStream in = current.getInputStream();
        final FileOutputStream log = new FileOutputStream(logFile(ctx), true);
        byte[] buf = new byte[4096];
        StringBuilder line = new StringBuilder();
        int n;
        while ((n = in.read(buf)) > 0) {
            String s = new String(buf, 0, n, "UTF-8");
            log.write(buf, 0, n);
            log.flush();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\n') {
                    String t = line.toString();
                    line.setLength(0);
                    if (out != null) out.line(t);
                } else {
                    line.append(ch);
                }
            }
        }
        if (line.length() > 0) {
            if (out != null) out.line(line.toString());
        }
        log.close();
        current = null;
    }

    // ---------------- 安装 ----------------

    static void install(Context c, Out cb) throws Exception {
        File dl = new File(home(c), "dl");
        dl.mkdirs();
        File root = rootfs(c);
        if (!new File(root, "bin/bash").exists()) {
            cb.line("[1/3] 下载 Ubuntu 根文件系统（约 30MB）…");
            File tar = new File(dl, "ubuntu-base.tar.gz");
            download(ROOTFS_URL, tar, cb);
            cb.line("[1/3] 解包到 " + root.getAbsolutePath());
            extractTarGz(tar, root, c, cb);
        } else {
            cb.line("[1/3] 根文件系统已存在，跳过");
        }
        File node = new File(root, "opt/node");
        if (!new File(node, "bin/node").exists()) {
            cb.line("[2/3] 下载 Node v22（glibc，约 53MB）…");
            File tar = new File(dl, "node.tar.gz");
            download(NODE_URL, tar, cb);
            cb.line("[2/3] 解包 Node");
            extractTarGz(tar, new File(root, "opt"), c, cb);
            File moved = new File(root, "opt/node-v22.14.0-linux-arm64");
            if (moved.exists() && !node.exists()) moved.renameTo(node);
        } else {
            cb.line("[2/3] Node 已存在，跳过");
        }
        writeResolvConf(root, cb);
        cb.line("[3/3] 环境就绪");
        cb.line("下一步：点「装 harness」跑 npm i @deepseek-ai/dsh@" + DSH_VERSION);
    }

    static void installHarness(final Context c, final Out cb) throws Exception {
        if (!installed(c)) throw new Exception("先装环境（rootfs + Node）");
        if (harnessInstalled(c)) {
            cb.line("harness 已安装，跳过");
            return;
        }
        cb.line("npm i @deepseek-ai/dsh@" + DSH_VERSION + "（几分钟）…");
        exec(c, "mkdir -p /opt/dsh && cd /opt/dsh && npm init -y >/dev/null 2>&1; "
                + "npm i --no-audit --no-fund --ignore-scripts @deepseek-ai/dsh@" + DSH_VERSION
                + " 2>&1 | tail -20", cb);
        cb.line(harnessInstalled(c) ? "harness 装好了：node /opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"
                : "装完但没找到入口，看上面的报错");
    }

    private static void writeResolvConf(File root, Out cb) {
        try {
            File etc = new File(root, "etc");
            etc.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (String prop : new String[]{"net.dns1", "net.dns2"}) {
                try {
                    Process p = new ProcessBuilder("/system/bin/getprop", prop)
                            .redirectErrorStream(true).start();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[256];
                    int k;
                    while ((k = p.getInputStream().read(b)) > 0) bo.write(b, 0, k);
                    p.waitFor();
                    String v = new String(bo.toByteArray(), "UTF-8").trim();
                    if (v.length() > 0 && v.indexOf('.') > 0) sb.append("nameserver ").append(v).append("\n");
                } catch (Throwable ignored) {
                }
            }
            if (sb.length() == 0) {
                sb.append("nameserver 223.5.5.5\nnameserver 1.1.1.1\nnameserver 8.8.8.8\n");
            }
            FileOutputStream fo = new FileOutputStream(new File(etc, "resolv.conf"));
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            cb.line("/etc/resolv.conf: " + sb.toString().replace('\n', ' '));
        } catch (Throwable t) {
            cb.line("写 resolv.conf 失败: " + t);
        }
    }

    private static void download(String url, File dst, Out cb) throws Exception {
        if (dst.exists() && dst.length() > (1 << 20)) {
            cb.line("已缓存 " + dst.getName() + "（" + (dst.length() / 1048576) + "MB），跳过下载");
            return;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        long last = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (System.currentTimeMillis() - last > 1000) {
                last = System.currentTimeMillis();
                cb.line("  … " + (total / 1048576) + "MB");
            }
        }
        out.close();
        in.close();
        cb.line("  下载完成 " + (total / 1048576) + "MB");
    }

    // ---------------- tar.gz 解包 ----------------

    private static void extractTarGz(File tarGz, File destRoot, Context c, Out cb) throws Exception {
        destRoot.mkdirs();
        java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                new BufferedInputStream(new FileInputStream(tarGz), 64 * 1024), 64 * 1024);
        byte[] header = new byte[512];
        long entries = 0;
        String pendingName = null;
        String pendingLink = null;
        while (true) {
            if (!readFully(gz, header, 512)) break;
            if (isZero(header)) break;
            String name = str(header, 0, 100);
            long size = octal(header, 124, 12);
            long mode = octal(header, 100, 8);
            int type = header[156] & 0xFF;
            String link = str(header, 157, 100);
            String magic = str(header, 257, 8);
            boolean posix = magic.startsWith("ustar") && !magic.startsWith("ustar ");
            String prefix = posix ? str(header, 345, 155) : "";
            String path = prefix.length() > 0 ? prefix + "/" + name : name;

            if (type == 'L') {
                pendingName = readText(gz, size);
                continue;
            }
            if (type == 'K') {
                pendingLink = readText(gz, size);
                continue;
            }
            if (type == 'x' || type == 'g') {
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
            if (type == '5') {
                f.mkdirs();
                chmod(f, mode == 0 ? 0755 : mode);
            } else if (type == '0' || type == 0 || type == '7') {
                if (f.isDirectory()) deleteTree(f);
                f.getParentFile().mkdirs();
                FileOutputStream out = new FileOutputStream(f);
                long left = size;
                byte[] buf = new byte[64 * 1024];
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int got = gz.read(buf, 0, want);
                    if (got < 0) break;
                    out.write(buf, 0, got);
                    left -= got;
                }
                out.close();
                chmod(f, mode == 0 ? 0644 : mode);
                skip(gz, pad(size));
                entries++;
                if ((entries & 0xFF) == 0) cb.line("  解包 " + entries + " 项…");
                continue;
            } else if (type == '2') {
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
            } else if (type == '1') {
                f.getParentFile().mkdirs();
                try {
                    copyFile(new File(destRoot, link), f);
                    chmod(f, mode == 0 ? 0644 : mode);
                } catch (Throwable ignored) {
                }
                entries++;
                continue;
            } else {
                skip(gz, size + pad(size));
                entries++;
                continue;
            }
            skip(gz, pad(size));
            entries++;
            if ((entries & 0xFF) == 0) cb.line("  解包 " + entries + " 项…");
        }
        gz.close();
        cb.line("  解包完成（" + entries + " 项）");
    }

    private static long pad(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static String readText(InputStream in, long size) throws Exception {
        byte[] data = new byte[(int) Math.max(0, size)];
        readFully(in, data, data.length);
        skip(in, pad(size));
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) end--;
        return new String(data, 0, end, "UTF-8");
    }

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

    private static boolean readFully(InputStream in, byte[] buf, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void skip(InputStream in, long count) throws Exception {
        long left = count;
        while (left > 0) {
            long got = in.skip(left);
            if (got <= 0) break;
            left -= got;
        }
    }

    private static boolean isZero(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        try {
            return new String(b, off, end - off, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static long octal(byte[] b, int off, int len) {
        String s = str(b, off, len).trim();
        if (s.length() == 0) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void chmod(File f, long mode) {
        try {
            android.system.Os.chmod(f.getAbsolutePath(), (int) (mode & 07777));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getAbsolutePath()).st_mode);
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
        InputStream in = new FileInputStream(src);
        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}
