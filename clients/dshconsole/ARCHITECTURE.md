# 架构与构建（clients/dshconsole）

一句话：**一份 UI + 一份协议 + 两个口味**。远端版和本地版只在"协议头指向谁、有没有本地环境"这两件事上分叉，
其余全部共用，避免一个界面两处维护。

## 目录

```
clients/dshconsole/
├─ build.py                  # 无 Gradle 构建：javac → d8 → pyaxml 清单 → zip → apksigner
├─ AndroidManifest.xml       # 单一清单；包名/应用名由 build.py 按口味改写
├─ src/
│  ├─ shared/com/minis/dshconsole/     ← 两套共用，谁都不许带口味
│  │    Activities：MainActivity / SettingsActivity / SettingsPanelActivity
│  │    UI：Ui / Sidebar / ChatView / Tab*        （聊天、轨迹、表格、代码块、图片…… 都在 ChatView）
│  │    协议：Dsh / Wire / Devctl / DsWeb         （JSON Lines over TCP、令牌、网页注入）
│  │    数据：Store / Img / DshConsole
│  └─ flavor/com/minis/dshconsole/     ← 只放"两套不一样"的东西
│       Core.java        # 唯一的分叉接口
│       Cores.java       # 唯一读 Flavor 的地方（整个项目仅此一处）
│       RemoteCore.java  # 远端：指向侧栏配对的那台 DSH
│       LocalCore.java   # 本地：固定 127.0.0.1，回环免令牌
│       LocalEnv.java    # 本地环境的对外接口（状态 / 自检 / 将来的安装与启停）
│       LocalRuntime.java# 本地环境实现（proot、rootfs、node、harness）
│       Flavor.java      # build.py 生成，勿手改
├─ libs/                     # 本地版的原生件（fetch-libs.sh 取，进 APK 的 lib/arm64-v8a/）
├─ local/                    # 手机侧 harness 的补丁与启动脚本（本地版调试也用得上）
└─ tools/                    # android.jar / d8.jar / apksigner.jar / 生成器
```

## 边界规则（build.py 会拦）

`src/shared` 里**不允许**出现 `Flavor.LOCAL`、`new LocalRuntime`、`import …Flavor`；
需要口味相关的能力，一律经接口：

- `Cores.get().device(store, dshName)` —— 协议头指向谁
- `Cores.get().webKey(store, dshName)` —— 原版网页地址存哪个键
- `Cores.get().hasRuntime()` / `runtime()` —— 有没有本地环境、拿到句柄

违反规则时构建直接失败并列出违规文件（`[guard] shared 干净` 是正常输出）。

## 构建

```bash
python3 build.py --flavor remote     # 远端版：com.minis.dshconsole      / DSH 远端版
python3 build.py --flavor local      # 本地版：com.minis.dshconsole.local / DSH 本地版
python3 build.py --flavor all        # 一次出两个
```

产物在 `out/`：`dshconsole-remote.apk` / `dshconsole-local.apk`。两个包名不同，**可以并存**。

清单里有两处坑，build.py 已经处理：

1. 本地版换包名后，Activity 的相对名（`.MainActivity`）会被解析到新包名下 → 启动即崩；
   所以打包时把相对名补成绝对名（**Java 包名不动，只动清单**）。
2. 本地版的 proot 依赖必须以 `lib/arm64-v8a/libXXX.so` 进包 + `extractNativeLibs="true"`，
   否则解不到 `nativeLibraryDir` —— 而只有那里允许 `exec`（Android 10+ 的 W^X）。

## 常见开发任务

| 想做的事 | 改哪里 |
| --- | --- |
| 加一个界面 / 调样式 | `src/shared`（两套同时生效） |
| 加一个协议方法 | `src/shared`：`Dsh` 里发请求 + 对应 Tab/View 渲染 |
| 加"只有本地版才有"的能力 | `Core` 接口加方法 → `RemoteCore` / `LocalCore` 各实现一遍 |
| 加"只有本地版才显示"的界面 | `if (Cores.get().hasRuntime()) { … }`，卡片自己写在 shared |
| 本地环境安装/启停/日志 | `LocalRuntime`（对外只暴露 `LocalEnv`） |

## 两个口味的身份

| | 远端版 | 本地版 |
| --- | --- | --- |
| 包名 | `com.minis.dshconsole` | `com.minis.dshconsole.local` |
| 应用名 | DSH 远端版 | DSH 本地版 |
| 协议头 | 侧栏配对的那台（如 `192.168.2.7:7788`） | `127.0.0.1:7788`，回环免令牌 |
| 原生件 | 无 | proot + 依赖库（`libs/fetch-libs.sh`） |
| 核心实现 | `RemoteCore` | `LocalCore` + `LocalRuntime` |

## 本地版的运行时刻（进展）

- ✅ proot（Android/aarch64，来自 Termux 仓库）随包、安装后可 exec；`LocalEnv.selfCheck()` 能跑通自检。
- 🚧 rootfs / Node / harness 的下载与解包、`dsh web` 的启停与日志面板：在 `LocalRuntime` 里接着做。
  难点记录在 `local/README.md`：harness 的 `dsh-app-boot` **无条件**加载 `node-addon-require-builtin`，
  该模块只有 glibc 预编译且无公开源码 → 本地版必须带一个 glibc 用户态（proot + rootfs），
  不能直接用 Android 的 bionic。
