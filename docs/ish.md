# 在 iSH / 纯命令行环境里用 dshctl

iSH 是 iOS 上的 Alpine Linux 用户态模拟器：只有终端，没有 UI。它在这条链路里的位置是**控制端**——手机上敲命令，电脑上的 DSH 干活。

```
iSH（手机）                     跑着 DSH 的电脑
  dshctl  ────── TCP :7788 ──────►  devctl-dsh 插件
  纯 Python 3，零依赖              （监听 0.0.0.0 的那个端口）
```

`cli/dshctl.py` 只 import 标准库（`argparse` `base64` `json` `os` `platform` `socket` `sys` `time`），
所以任何有 Python 3 的终端都能当控制端：iSH、Termux、另一台 Linux、路由器上的 busybox + python。

`python -S`（禁用 site-packages）下依然可用：

```sh
$ python3 -S dshctl.py --version
dshctl 1.2.1
```

## 一、iSH 上装 dshctl

### 1. 换成官方 Alpine 仓库

iSH 自带的是自己维护的旧快照（Alpine 3.14 一带），python3 太老。先关掉仓库文件的自动覆盖，
否则 iSH 每次版本更新都会把你的改动冲掉：

```sh
rm -rf /ish
cat /etc/apk/repositories
```

输出里会有类似 `v3.14-2023-08-15` 的片段，`v3.14` 就是当前快照的 Alpine 版本。
换成官方的新分支（x86 架构上 3.21 起就有 python3 3.12 和 nodejs 22）：

```sh
echo https://dl-cdn.alpinelinux.org/alpine/v3.21/main > /etc/apk/repositories
echo https://dl-cdn.alpinelinux.org/alpine/v3.21/community >> /etc/apk/repositories
apk upgrade -U && apk fix
```

### 2. 装 python3 和 curl

```sh
apk add python3 curl
```

### 3. 取 dshctl

```sh
curl -fsSL https://raw.githubusercontent.com/menghuanshiguang/devctl-dsh/main/cli/dshctl.py \
  -o /usr/local/bin/dshctl
chmod +x /usr/local/bin/dshctl
dshctl --version
```

`raw.githubusercontent.com` 在国内经常连不上，换下面任一个（都实测可达）：

```sh
curl -fsSL https://cdn.jsdelivr.net/gh/menghuanshiguang/devctl-dsh@main/cli/dshctl.py \
  -o /usr/local/bin/dshctl
curl -fsSL https://ghproxy.net/https://raw.githubusercontent.com/menghuanshiguang/devctl-dsh/main/cli/dshctl.py \
  -o /usr/local/bin/dshctl
```

不想联网的话，把 `cli/dshctl.py` 的内容整段粘进 iSH 也行——单文件，没有任何依赖要装。

## 二、配对

电脑上的 DSH **设置 → devctl**，「配对二维码」卡下面那行命令直接抄进 iSH：

```sh
dshctl add desk 192.168.1.10:7788 --token 7fb9b9469808d4141a2ccd1143937f1b3c0d99ee12b8032
dshctl ping --device desk
```

第一次连接会自动验证一次；`add` 会把设备记进 `~/.dshctl.json`，之后不写 `--device`
就用最近一次 `use` 过的设备。

设备在同一个局域网、但 `ping` 不通的话，先确认被控端那个 IP 和端口能通：

```sh
curl -sv telnet://192.168.1.10:7788   # 或者
nc -vz 192.168.1.10:7788
```

被控端是 Windows 的话，第一次监听会弹防火墙授权框，要允许；被控端是 Linux 的话，
`host` 配成 `0.0.0.0` 之后还要确认本机防火墙放行了那个端口。

## 三、给手机起个名字

iSH 里 `gethostname()` 通常返回 `localhost`，几台手机在设备表里就分不出来。用 `DSHCTL_NAME` 覆盖：

```sh
export DSHCTL_NAME=iphone
echo 'export DSHCTL_NAME=iphone' >> ~/.profile
dshctl ping --device desk
```

设置页的「已连接设备」里就会显示这个名字，平台一栏读的是 `/etc/os-release` 的 `PRETTY_NAME`，
iSH 上会显示成 `Alpine Linux v3.x`。

## 四、日常用法

```sh
dshctl sessions                  # 列会话
dshctl send "把 README 的安装段重写一下"     # 发给最近活跃的会话
dshctl watch                     # 看流式回复和事件，Ctrl-C 停止
dshctl models --select <provider>/<model>    # 换模型
dshctl perm auto                 # 换操作权限
dshctl send "看看这张图" --image ./shot.png   # 带图片
dshctl ws                        # 列工作区
dshctl ws-new ~/project          # 建工作区
```

手机上的键盘不趁手，`--json` 配合 `jq` 会省事：

```sh
dshctl --json sessions | jq -r '.result.items[] | "\(.sessionId[0:8])  \(.title)"'
```

## 五、iSH 当被控端（iSH 上跑 DSH）

只有当你想让 iSH 自己跑 DSH、被别的设备控制时才需要这一节。**这一段不是开箱即用的**，
下面把实测过的部分和没验证过的部分分开写。

### 能用的部分

Alpine 的 x86 架构是**有 nodejs 的**（官方 APKINDEX 实测）：

| Alpine | x86 上的 nodejs | python3 |
| --- | --- | --- |
| v3.19 | 20.15.1 | — |
| v3.21 | **22.23.2** | 3.12.14 |
| v3.23 | **24.18.1** | 3.12.14 |

DSH 的 loader 里有一道 `if (major < 22) return`，所以 **v3.19 不够，要从 v3.21 起**。

DSH 自带的原生加载器 `node-addon-require-builtin` 只发布了 7 个平台的包
（darwin-arm64/x64、linux-arm64-**gnu**、linux-x64-**gnu**、win32-arm64/x64/ia32-msvc），
**没有 linux-ia32，也没有任何 musl 版本**，在 iSH 上装不上。

但它不是硬依赖。`cordis-plugin-loader/lib/index.js:9-17` 长这样：

```js
function requireInternal(id) {
  const require = createRequire(import.meta.url);
  if (process.execArgv.includes("--expose-internals")) try {
    return require(id);
  } catch {}
  try {
    return require("node-addon-require-builtin").requireBuiltin(id);
  } catch {}
}
```

两个分支都被 `try/catch` 吞掉，失败只让调用方走「no-internals path」。

实测（在 Windows 上把 native binding 彻底禁用后复现 iSH 的处境）：

```sh
NARB_DISABLE_OPTIONAL_PACKAGE=1 NARB_DISABLE_LOCAL_BUILD=1 \
  node --expose-internals $(npm root -g)/@deepseek-ai/dsh/lib/bin.js \
  --profile remote --port 3081 --host 0.0.0.0 --no-open
# dsh web: http://127.0.0.1:3081/?token=...
```

Web 服务起来了，devctl-dsh 的端口也在监听。

**结论：`--expose-internals` 是必备 flag。** 不加它，native binding 也不在的时候，
`@deepseek-ai/cordis-plugin-hmr` 会以
`--expose-internals is required for HMR service` 中止启动。

### 卡点

DSH 的依赖树里还有三个原生包，而且都是**必需依赖**（`dependencies`，不是 `optionalDependencies`）：

| 包 | 谁依赖它 |
| --- | --- |
| `koffi` | `dsh-fs-local`、`dsh-session-persistence-jsonl`、`dsh-subprocess-local`、`dsh-sandbox-windows-acl` |
| `node-pty` | `dsh-subprocess-local` |
| `sharp` | `dsh-attachment-local` |

它们的可选平台包里没有 linux-ia32。iSH 上 `npm install` 会退到源码编译，
要 `apk add build-base python3`，能不能编过取决于这些库对 32 位 musl 的支持程度。

**这一段没有真机验证过。** 有 iSH 的话，装完先别启动，看有没有 `gyp ERR!`：

```sh
apk add nodejs npm build-base python3 git
npm i -g @deepseek-ai/dsh
npm ls -g 2>&1 | grep -i 'gyp ERR'
```

### 更稳的做法

iSH 里装 `proot`，拉一个 glibc 的 x86_64 rootfs（Debian / Ubuntu），在里面跑 DSH——
那样 `linux-x64-gnu` 的预编译包可以直接用，绕开全部 musl / ia32 问题：

```sh
apk add proot
# 然后把一个 Debian x86_64 rootfs 挂进去，在 chroot 里 npm i -g @deepseek-ai/dsh
```

代价是 x86_64 模拟再叠一层，慢；但链路是通的。

## 六、纯命令行 Linux 当被控端

这是「无 UI 机器上跑 DSH」的**正常路径**，在本机（Windows）上完整验证过：

```sh
# 1. 从一个自带的模板建 profile（web 模板的 bundles 是 dsh-base + dsh-web-app）
dsh --profile remote --from-default-profile web --dump-config

# 2. 往 $DSH_HOME/profiles/remote/cordis.patch.yml 里插两行，装本插件（绝对路径即可，不用 pnpm）
cat >> ~/.dsh/profiles/remote/cordis.patch.yml <<'EOF'
- insert:
    - id: devctl-dsh
      name: /absolute/path/to/devctl-dsh/index.js
      config:
        host: 0.0.0.0
        port: 7788
EOF

# 3. 启动。--no-open 是给没有浏览器的机器用的
dsh --profile remote --port 3081 --host 0.0.0.0 --no-open
```

`dsh web` 会把 launch token 直接打在 stdout：

```
dsh web: http://127.0.0.1:3081/?token=DoBG7b9BSunQnU3L0WLi2XawtSewUhkL_E-X-9w6JP0
```

没有 UI 的环境就拿这个 token 开浏览器（或者直接不管它——`dshctl` 走的是插件自己的 TCP 端口，
用的是 `$DSH_HOME/devctl-dsh.json` 里那个 token，两者不是一回事）。

这是纯 Node 进程，不需要 Electron、不需要 GUI。

`dsh --profile headless` **不行**：那个 bundle 里没有 Host、没有 `sessionController`，
devctl-dsh 不会被激活。要的是带 `dsh-web-app` 的 profile。

## 七、排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `Connection refused` | 被控端没监听，或者不是同一个网段。被控端上 `netstat -ano \| findstr :7788` 看有没有 `0.0.0.0:7788` |
| `remote error [unauthorized]` | token 不对。设置页里点「显示」看全，或读被控端的 `$DSH_HOME/devctl-dsh.json` |
| 设备表里一堆 `localhost` | 控制端没设 `DSHCTL_NAME` |
| `dshctl workspaces` 报 `cannot get property "workspaceController" without inject` | 被控端的插件版本旧了，升级到 1.1.0 以上 |
| 设置页里 devctl 分区不出现 | 被控端是 `headless` 这类没有 `sessionController` 的 profile，或者插件没加载成功——看被控端日志 |
| iSH 里 `apk add` 网络失败 | 先 `apk update`；换源之后如果 `dl-cdn` 不通，换国内镜像 |
