package com.minis.dshconsole;

/**
 * 两套 app 的「核心差异」都收在这里：UI、协议、Tabs 全部共用，只有这个文件里的东西不同。
 *
 * - 远端版（Flavor.LOCAL == false）：协议头指向用户在侧栏里配对的那台 DSH（局域网 PC）。
 * - 本地版（Flavor.LOCAL == true）：协议头固定指回本机回环，harness 跑在 app 自己的私有目录里，
 *   回环连接免令牌，所以不需要用户填地址和令牌。
 *
 * Flavor.java 由 build.py 按 --flavor 生成，源码里不要手改。
 */
final class Core {

    private Core() {
    }

    static boolean local() {
        return Flavor.LOCAL;
    }

    /** 协议头指向谁：本地版永远是本机回环，远端版是侧栏里选中的那台。 */
    static Store.Dev device(Store store, String dshName) {
        if (!local()) {
            Store.Dev d = store.find("dsh", dshName);
            if (d != null) return d;
        }
        Store.Dev d = new Store.Dev();
        d.name = local() ? "local" : (dshName == null ? "home" : dshName);
        d.host = local() ? store.get("local:host", "127.0.0.1") : "127.0.0.1";
        try {
            d.port = Integer.parseInt(store.get("local:port", "7788"));
        } catch (Throwable ignored) {
            d.port = 7788;
        }
        // 本地版走回环免鉴权；远端版这里拿到的也是本机地址，令牌留空按未配对处理
        d.token = local() ? "" : store.get("local:token", "");
        return d;
    }

    /** 原版网页地址按设备名分开存：本地版存 web:local，远端版存 web:<设备名>。 */
    static String webKey(Store store, String dshName) {
        return "web:" + device(store, dshName).name;
    }

    /** 本地版才需要「把 harness 起起来」；远端版什么都不用做。 */
    static boolean needsRuntime() {
        return local();
    }
}
