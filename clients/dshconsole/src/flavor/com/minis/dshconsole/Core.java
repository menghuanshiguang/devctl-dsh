package com.minis.dshconsole;

/**
 * 两套 app 唯一的分叉点。
 *
 * 共用的是：UI（Activities / Tabs / ChatView / Ui / Sidebar）、协议客户端（Dsh / Wire / Devctl）、
 * 数据与存储（Store）、渲染与图片（Img / DsWeb）。这些代码只认这个接口，不认具体实现，
 * 所以一份 UI 两边跑，不存在"两处维护"。
 *
 * 各自的实现：
 *   - {@link RemoteCore}：协议头指向侧栏里配对的那台 DSH（局域网 PC）。
 *   - {@link LocalCore}：协议头固定指回本机回环，harness 跑在 app 自己内部（回环免令牌）。
 *
 * 想加"两边不一样"的能力，往这个接口上加方法，再在两个实现里各写一遍；
 * 想加"两边一样"的界面，直接写进 src/shared —— 别在这儿开洞。
 */
interface Core {

    /** 构建口味："remote" / "local"，写进日志、诊断和 About 用。 */
    String id();

    boolean local();

    /** 协议头指向谁。 */
    Store.Dev device(Store store, String dshName);

    /** 原版网页地址存在哪个键下（两套各自记一份，互不干扰）。 */
    String webKey(Store store, String dshName);

    /** 有没有"本地环境"这回事（远端版永远 false，设置页据此少画一张卡）。 */
    boolean hasRuntime();

    /** 本地环境句柄；远端版返回 null。 */
    LocalEnv runtime();
}
