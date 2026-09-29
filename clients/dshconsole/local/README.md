# 本地模式：把手机自己变成 DSH host

App 的「运行模式」有两种，**协议完全一样，只是协议头指向不同**：

| 模式 | 指向 | 用途 |
| --- | --- | --- |
| 远端 | 局域网 PC 上的 `devctl-dsh` 插件（如 `192.168.2.7:7788`） | 平时用，算力在 PC |
| 本地 | 本机回环 `127.0.0.1:7788` | 手机自己跑 harness，出门也能用 |

本地模式对 App 来说是"零改动"的：它照旧说 devctl 那套 JSON Lines 协议，只是把目标换成回环地址，
令牌从「本地 harness」那条配置里取（设置 → 运行模式 → 本地 → 本机地址/令牌…）。

## 手机上把 harness 跑起来

在**手机上**任何一个能跑 Node 的环境里执行（Termux + proot-distro 的 Ubuntu、或你自己的 Linux 环境）：

```sh
sh start-local.sh            # 默认装到 $HOME/dsh-local，DSH_HOME=$HOME/.dsh
```

脚本做四件事：

1. `npm i @deepseek-ai/dsh@0.2.0-rc.1`（harness 官方 CLI，公开在 npm 上）；
2. 把本仓库（devctl-dsh 插件）装进 `web` profile；
3. 用 `cordis.patch.yml` 覆盖 `host`/`port`，启动 `dsh web`；
4. 读出 `$DSH_HOME/devctl-dsh.json` 里的 **端口和令牌** 打印出来 —— 填进 App 的「本地」设置即可。

之后 App 里切到「本地」，它就是一台不用 PC 的完整 DSH 控制台。

## 实测结论（重要，别踩坑）

我在手机上的 Alpine(PRoot) 沙箱里实测过 0.2.0-rc.1：

- ✅ `@deepseek-ai/dsh` 能装、CLI 能跑：`dsh --version` → `0.2.0-rc.1`，`dsh web --help` 正常；
- ❌ **`dsh web` 起不来**：`node-addon-require-builtin` 只有 **glibc** 预编译（`linux-arm64-gnu`），
  没有 `linux-arm64-musl`。Alpine/musl 下报
  `No usable native binding found for node-addon-require-builtin-linux-arm64-musl`；
  用 gcompat 装官方 glibc node 也不行（`fcntl64: symbol not found`）。

**结论**：本地模式需要一个 **glibc 用户态**。手机上最省事的是 Termux + `proot-distro install ubuntu`
（Ubuntu 里跑本脚本），或者在 App 里内置一份运行时（那是另一个工程，见下）。

### 内置运行时的后续路线（未做）

1. 把 Termux 的 `nodejs` 打成 `jniLibs/arm64-v8a/libnode.so`（Android 只允许从 native lib 目录执行），
   `Runtime.exec` 到 `nativeLibraryDir` 里跑；
2. 把 `@deepseek-ai/dsh` 与它的依赖（含 `node-addon-require-builtin-linux-arm64-gnu` 不行 → 要 glibc）
   一起塞进 assets，用 proot 起一个精简 Ubuntu rootfs；
3. App 加「启动/停止本地 harness」按钮 + 日志面板。

第 2 步是硬骨头：Android 上没有 glibc，得靠 proot 带一个 rootfs。真要做，建议先做 1+3，
把 proot 版本单独当一个实验特性。
