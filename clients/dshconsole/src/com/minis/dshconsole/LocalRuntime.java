package com.minis.dshconsole;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/**
 * 本地模式的运行时。
 *
 * 目标：用户切到「本地模式」，App 自己把 harness 装进自己的私有目录并跑起来 —— 不依赖 Termux。
 *
 * 现在的落地程度：
 *   1. proot 已经随包走（lib/arm64-v8a/libproot.so，连同它依赖的 libtalloc / libandroid-shmem）。
 *      只有 nativeLibraryDir 允许 exec，所以必须走 jniLibs 这条路，不能在 data 目录里放可执行文件。
 *   2. rootfs / node / harness 计划放进 files/local/ 下，首次使用下载 + 解包。
 *   3. 起服务时用 proot 进 rootfs，在里面跑 dsh web + devctl-dsh 插件，监听 127.0.0.1:7788。
 */
final class LocalRuntime {

    private LocalRuntime() {
    }

    static File nativeDir(android.content.Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir);
    }

    /** proot 本体（包里的 libproot.so，解出来就是可执行的）。 */
    static File proot(android.content.Context c) {
        return new File(nativeDir(c), "libproot.so");
    }

    /** 运行时大本营：files/local/。 */
    static File home(android.content.Context c) {
        File d = new File(c.getFilesDir(), "local");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 解出来的 glibc 根文件系统。 */
    static File rootfs(android.content.Context c) {
        return new File(home(c), "rootfs");
    }

    /** rootfs 里要跑的 Node（glibc 版，官方 tar.gz 解进去）。 */
    static File node(android.content.Context c) {
        return new File(rootfs(c), "opt/node/bin/node");
    }

    /** harness CLI 的入口。 */
    static File dshBin(android.content.Context c) {
        return new File(rootfs(c), "opt/harness/node_modules/@deepseek-ai/dsh/lib/bin.js");
    }

    /** 装到哪一步了（给引导页显示进度）。 */
    static String state(android.content.Context c) {
        if (!proot(c).exists()) return "缺 proot（包不完整？）";
        if (!rootfs(c).exists()) return "还没装 rootfs";
        if (!node(c).exists()) return "还没装 Node";
        if (!dshBin(c).exists()) return "还没装 harness";
        return "已就绪";
    }

    /** 自检：proot 能不能真的跑起来。这是本地模式的第一块砖，先单独验它。 */
    static String selfCheck(android.content.Context c) {
        File p = proot(c);
        if (!p.exists()) return "缺 libproot.so（包没打进去？）";
        if (!p.canExecute()) return "libproot.so 没有执行权限";
        try {
            ProcessBuilder pb = new ProcessBuilder(p.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", nativeDir(c).getAbsolutePath());
            Process pr = pb.start();
            String out = readAll(pr.getInputStream()).trim();
            int code = pr.waitFor();
            if (out.length() == 0) out = "退出码 " + code;
            return "proot " + out.replace('\n', ' ');
        } catch (Throwable e) {
            return "proot 起不来：" + e;
        }
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
