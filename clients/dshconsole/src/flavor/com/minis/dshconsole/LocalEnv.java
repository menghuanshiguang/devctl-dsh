package com.minis.dshconsole;

/**
 * 本地版那块"环境"的对外契约。设置页、启用引导这些**写在 src/shared 里的界面**
 * 只认这个接口，实现细节（proot / rootfs / node / harness）锁在 LocalRuntime 里 ——
 * 这样"一份 UI 两边跑"不会被本地版的特例污染。
 */
interface LocalEnv {

    /** 装到哪一步了 / 有没有在跑（一句话）。 */
    String state(android.content.Context ctx);

    /** 自检：proot 能不能执行（本地环境的第一块砖）。 */
    String selfCheck(android.content.Context ctx);

    /** 环境装好了没有（文件齐、状态 ready）。 */
    boolean ready(android.content.Context ctx);

    /** harness 正在跑（进程活着或端口已监听）。 */
    boolean running(android.content.Context ctx);

    /** 一键安装：rootfs → Node → harness → 插件。回调在主线程之外，界面自己 runOnUiThread。 */
    void install(android.content.Context ctx, Progress cb);

    /** 启动 harness（proot 里跑 dsh web）。 */
    void start(android.content.Context ctx, Progress cb);

    /** 安装/启动的进度回调。 */
    interface Progress {
        void onStep(String message, int pct);

        void onDone(boolean ok, String message);
    }
}
