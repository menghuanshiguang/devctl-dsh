package com.minis.dshconsole;

/**
 * 口味的唯一开关：整个项目里**只有这个文件**允许读 {@link Flavor}。
 * 其它代码（包括 shared 里的全部 UI / 协议）都必须经 {@link Core} 接口。
 * build.py 会扫 src/shared，出现 Flavor / LocalRuntime 直接判失败。
 */
final class Cores {

    private static Core instance;

    private Cores() {
    }

    static synchronized Core get() {
        if (instance == null) {
            instance = Flavor.LOCAL ? new LocalCore() : new RemoteCore();
        }
        return instance;
    }
}
