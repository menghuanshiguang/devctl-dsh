package com.minis.dshconsole;

/**
 * 本地版：协议头固定指回本机回环，harness 跑在 app 自己的私有目录里。
 * 只绑回环 + 回环免令牌（插件侧 allowLocalNoAuth），所以不需要用户填地址和令牌。
 */
final class LocalCore implements Core {

    public String id() {
        return "local";
    }

    public boolean local() {
        return true;
    }

    public Store.Dev device(Store store, String dshName) {
        Store.Dev d = new Store.Dev();
        d.name = "local";
        d.host = store.get("local:host", "127.0.0.1");
        try {
            d.port = Integer.parseInt(store.get("local:port", "7788"));
        } catch (Throwable ignored) {
            d.port = 7788;
        }
        d.token = "";                       // 回环免鉴权
        return d;
    }

    public String webKey(Store store, String dshName) {
        return "web:local";
    }

    public boolean hasRuntime() {
        return true;
    }

    public LocalEnv runtime() {
        return new LocalRuntime();
    }
}
