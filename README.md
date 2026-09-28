# devctl-dsh

devctl 家族的 DSH 接入层：**从另一台设备用 CLI 控制这台机器上跑着的 DSH。**

devctl 管的是设备本身（Android / Windows 的壳层、文件、日志）；devctl-dsh 管的是 **DSH 会话**——列会话、发消息、看流式回复、打断、换模型。
被控端是一个 DSH Host 插件，在 DSH 进程内开一个 token 鉴权的 JSON-Lines TCP 端口；控制端是 `cli/dshctl.py`，单文件、零依赖的 Python 3 脚本。

```
手机 / iSH / Termux / 笔记本            跑着 DSH 的机器
  dshctl.py  ────── TCP :7788 ──────►  devctl-dsh 插件 ──► sessionController
   （控制端）          JSON Lines         （被控端）        列会话 / 发消息 / 收事件
```

CLI 的参数手感对齐 `devctl`：

```bash
devctl add phone --type android --host 192.168.1.20 --token devctl
dshctl add desk  --type dsh     --host 192.168.1.10 --port 7788 --token <TOKEN>
```

`--type dsh` 已在 `dshctl` 里落地，方便 devctl 主仓库日后直接接管这条通道；本仓库不修改 devctl。

## 被控端：安装插件

```bash
plugin_manager install_bundle  target=D:\dsh\devdsh\devctl-dsh
```

装完端口立即生效（profile 是 `patchReload: live`）。确认：

```bash
netstat -ano | findstr :7788
```

首次启动生成 token 并写入 `%USERPROFILE%\.dsh\devctl-dsh.json`：

```json
{ "token": "7fb9b946…", "host": "0.0.0.0", "port": 7788, "version": "1.1.0", "startedAt": 1790610287877 }
```

改端口 / 绑定地址：编辑本包 `cordis.patch.yml` 的 `config`，或在 profile 补丁层覆盖同一 `id`。

## 控制端：安装 CLI

只有一个文件。最省事的办法是在被控端临时起个 HTTP 服务，手机直接取：

```bash
cd D:\dsh\devdsh\devctl-dsh\cli
python -m http.server 7799
```

```bash
# 手机 / iSH / Termux
curl -O http://192.168.2.7:7799/dshctl.py
python3 dshctl.py add desk --host 192.168.2.7 --port 7788 --token 7fb9b946…
```

iSH 装 Python：`apk add python3`；Termux：`pkg install python`。

`add` 之后 `--token` 只在第一次需要——同机运行时能自动读 `$DSH_HOME/devctl-dsh.json`。

## 用法

```bash
dshctl.py add <name> <host:port> [--token T] [--default]   # 注册设备
dshctl.py devices                                          # 列出设备
dshctl.py use <name>                                       # 设默认设备
dshctl.py rm <name>                                        # 删除设备

dshctl.py ping                     # 连通性 + 鉴权 + 延迟

dshctl.py workspaces               # 列出工作区（别名 ws）
dshctl.py ws-new D:\proj\thing     # 把目录注册成工作区
dshctl.py ws-use d431cc0a          # 选定工作区，之后 new 默认建在这里
dshctl.py ws-rename d431cc0a "报价系统"
dshctl.py ws-rm d431cc0a           # 只摘掉注册，目录与会话都留着

dshctl.py sessions [-n 25]         # 列出会话（--roots 只留顶层，-n 0 列全部）
dshctl.py new [--workspace W] [--cwd P] [--preset N]
dshctl.py send "跑一遍测试并修掉失败"     # 发消息，流式打印回复
dshctl.py send "这张图什么颜色？" --image shot.png   # --image 可重复
dshctl.py permissions              # 看会话当前权限 + 可用预设（别名 perm）
dshctl.py permissions workspace-write
dshctl.py tail [-n 40]             # 最近的消息
dshctl.py watch [--for 60]         # 实时事件流，Ctrl-C 停止
dshctl.py cancel                   # 打断正在跑的那一轮
dshctl.py rename "新标题"
dshctl.py search "关键词"
dshctl.py models                   # 模型目录
dshctl.py models --select provider/model [--effort high]
```

全局选项可放在命令前后任意位置：`--device NAME`、`--json`、`--timeout SEC`。

会话用完整 id 或不歧义前缀指名——`sessions` 里显示的 `4863366b…bbe4` 可以直接粘回去：

```bash
dshctl send -s 4863366b "继续"
```

不给 `-s` 时用上次用过的会话，没有记忆则取最近活跃的那个。
`ws-use` 选中的工作区同理记在控制端本地，`new` 不带 `--workspace` 时就用它；若那个工作区在别处被删掉，`new` 会自动忘掉它并以默认工作区重试一次。

常用组合：

```bash
dshctl --json sessions | jq '.result.items[0].sessionId'
echo "解释这个报错" | dshctl send -
dshctl send --no-wait "长任务，后台跑着" && dshctl watch
dshctl send --steer "停，换成方案 B"
dshctl send "比对这两张设计稿" --image before.png --image after.png
```

### 工作区

工作区的增删改查走 Host 的 `workspaceController`。`ws-rm` 与 Web 端一致——**只注销工作区条目**，磁盘上的目录和该工作区下的会话都保留。

```bash
dshctl ws-new /root/repo            # 幂等：已注册就返回既有的那条
dshctl ws-rename d431cc0a 报价系统
dshctl workspaces --json | jq -r '.result.items[].path'
```

工作区可以用完整 id、id 前缀或不重名的标题来指名，`--json` 里字段是 `workspaceId`。

### 权限

`permissions` 不带参数时只从会话的权限投影里读当前值——**不唤醒冷会话**：

```bash
dshctl perm                       # current / 可用预设 / 默认预设
dshctl perm -s 4863366b
```

带预设名才会真正改写，作用于目标会话：

```bash
dshctl perm workspace-write       # 沙箱限当前工作区，审批改回 ask
dshctl perm danger-full-access    # 放开沙箱，审批 never
```

改的是那个会话的运行时策略，和 Web 端 `/permission` 是同一份状态。远端能改权限意味着**能把自己提权到 `danger-full-access`**，见下面的安全边界。

### 图片

`--image` 直接读本地文件、按扩展名定媒体类型、base64 塞进 prompt，走 DSH 的图片附件通道（`.png` `.jpg` `.jpeg` `.webp` `.gif`）。可以只发图不发字：

```bash
dshctl send --image screenshot.png      # 不写文本也合法
```

被控端读的是**控制端本机的文件**——CLI 在手机上，发的是手机里的图。

`--json` 输出恒为 `{"ok":true,"result":…}` 或 `{"ok":false,"error":{"code","message"}}`，退出码 0/1，适合塞进脚本或交给 agent。

`send` 默认只把回复正文写到 stdout（进度提示走 stderr）；加 `--detail` 才会带上你自己的 prompt、DSH 注入的 runtime context 和 turn 边界。

## 协议

JSON Lines over TCP，请求与响应按 `id` 配对，事件不请自来。

```jsonc
// 鉴权（必须是第一条；失败即断开）
{"id":1,"method":"hello","params":{"token":"…","client":"dshctl/1.1.0"}}

// 请求 → 响应
{"id":2,"method":"sessions.prompt","params":{"sessionId":"…","mode":"queue","text":"…","images":[{"mediaType":"image/png","data":"<base64>","name":"shot.png"}]}}
{"id":2,"ok":true,"result":{"accepted":true}}

// 事件推送
{"evt":"snapshot","data":{"sessionId":"…","cursor":282,"records":[…]}}
{"evt":"event","data":{"kind":"tool-call","name":"pwsh","arguments":"…"}}
{"evt":"delta","data":{"sessionId":"…","text":"正在"}}
```

方法：`ping`、`sessions.list|create|prompt|cancel|rename|search|tail|watch|unwatch`、`workspaces.list|create|rename|delete`、`permissions.catalog|current|set`、`models.catalog|select`。

`workspaces.list` 是靠订阅 `workspaceController.follow` 拿首帧 baseline 实现的（Host 只暴露流式接口）；`permissions.set` 与 `permissions.current` 需要会话对象，被控端经 `sessionController.resolveAgent` 取，因此**对冷会话会触发一次 resume**——不带预设的 `permissions` 命令走投影，绕开这一点。

`send` 的实现顺序是**先挂 watch 再 prompt**，两者之间到达的事件一条不丢，`turn/end` 用于判断本轮结束。

在本机这类关了 session-query 索引的部署上，`sessions.search` 返回 `search-disabled`；CLI 会提示改用 `sessions` + `tail`。

## 改代码

profile 补丁层给 `dsh-hmr` 扩了监视根，插件源码目录纳入热重载：

```yaml
# %USERPROFILE%\.dsh\profiles\desktop\cordis.patch.yml
- id: hmr
  name: "@deepseek-ai/dsh-hmr"
  config:
    root:
      - "."
      - "D:/dsh/devdsh/devctl-dsh"
```

没有这层时，`dsh-hmr` 的 `baseDir` 落在 DSH 安装目录（`ctx.baseUrl`），而插件源码在 `D:\dsh\devdsh` 下——写文件不触发重载，`disable`/`enable` 和重装 bundle 也不清 ESM 缓存，改动静默失效。加上之后存盘即重载：`dshctl ping` 的 `uptime` 归零、`version` 跟着变。删掉那几行即可恢复默认。

## 安全边界

- **token 等于整台机器的控制权。** 它能列出、创建、驱动本机上的任意会话，等于让远端以本机身份执行任务。
- **权限预设也能被远端改写。** `dshctl perm danger-full-access` 会放开目标会话的沙箱、把审批设成 never——控制端不必再向本机要一次同意。
- **只在内网或 VPN 里跑。** 不要做任何公网端口映射；`0.0.0.0` 监听意味着同网段任何设备都能尝试连接——唯一阻碍就是这个 token。
- 明文传输，没有 TLS。同网段嗅探可以拿到 token。
- 换 token：停掉插件、删掉 `%USERPROFILE%\.dsh\devctl-dsh.json`、重新启用插件，然后在控制端重新 `add`。
- 不需要时把插件整个停掉，或把 `cordis.patch.yml` 里的 `host` 改成 `127.0.0.1`。
- Windows 防火墙默认拦入站，需要显式放行（管理员 PowerShell）：

```powershell
New-NetFirewallRule -DisplayName "devctl-dsh" -Direction Inbound -Protocol TCP `
  -LocalPort 7788 -RemoteAddress LocalSubnet -Action Allow
```

`-RemoteAddress LocalSubnet` 把来源限制在同网段；要更紧就限定具体网段或改用 VPN 地址。

## 文件

| 路径 | 说明 |
| --- | --- |
| `index.js` | 被控端插件：TCP 监听、token 鉴权、事件推送 |
| `cli/dshctl.py` | 控制端 CLI，单文件零依赖 |
| `cordis.patch.yml` | 加载器补丁：`host` / `port` |
| `package.json` | bundle 清单 |

## License

MIT © menghuanshiguang
