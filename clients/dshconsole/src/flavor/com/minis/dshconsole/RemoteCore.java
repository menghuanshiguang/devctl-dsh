package com.minis.dshconsole;

/** 远端版：协议头指向用户在侧栏里配对的那台 DSH。 */
final class RemoteCore implements Core {

    public String id() {
        return "remote";
    }

    public boolean local() {
        return false;
    }

    public Store.Dev device(Store store, String dshName) {
        Store.Dev d = store.find("dsh", dshName);
        if (d != null) return d;
        Store.Dev fallback = new Store.Dev();
        fallback.name = dshName == null ? "home" : dshName;
        return fallback;
    }

    public String webKey(Store store, String dshName) {
        Store.Dev d = device(store, dshName);
        return "web:" + (d == null ? "" : d.name);
    }

    public boolean hasRuntime() {
        return false;
    }

    public LocalEnv runtime() {
        return null;
    }
}
