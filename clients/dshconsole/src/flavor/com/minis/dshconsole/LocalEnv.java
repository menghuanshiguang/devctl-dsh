package com.minis.dshconsole;

/** 本地版那块"环境"的对外接口：设置页/诊断只认它，实现细节锁在 LocalRuntime 里。 */
interface LocalEnv {

    /** 一句话状态：装到哪一步了、有没有在跑。 */
    String state(android.content.Context ctx);

    /** 自检：proot 能不能执行（本地模式的第一块砖）。 */
    String selfCheck(android.content.Context ctx);
}
