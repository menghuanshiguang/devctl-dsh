# 任务：给 devctl-dsh 协议层补三个方法（settings.sections / settings.panel / settings.action）

仓库：https://github.com/menghuanshiguang/devctl-dsh
先 `git pull origin main`，本文档与客户端改动都已经在 main 上（取到最新即可）。host 侧代码是根目录的 index.js，
本提示词和契约文档在 clients/dshconsole/host-patch/ 下，两份都读一下再动手。

## 背景

手机客户端 `clients/dshconsole` 的「设置」入口已经落地：点齿轮 → 新开 `SettingsActivity` → 侧边栏＝一级分区，
点分区进 `SettingsPanelActivity`（WebView 渲染二级正文）。**侧边栏和正文都是实时问 host 的**，而不是写死的。

现在 host 侧还没实现这三个方法，客户端会回落：分区表用内置 8 项，`devctl` 分区用 app 自己的配对记录 + 一次握手探测
（Host 版本）拼出来，其余分区显示「需要 host 侧协议扩展」。你的任务就是把 host 侧补上。

完整契约见同目录的 `settings-protocol.md`（已随本次提交进仓库）。

## 要做的事

在 `index.js` 的 `dispatch()` 里加三个 `case`，**风格照现有代码**：`BridgeError` 抛错、`requireService(bridge, name)`
取服务、`timeoutSignal(CALL_TIMEOUT_MS)` 控制超时。不改动现有方法的行为。

### 1. settings.sections —— 侧边栏分区表

```
→ {"id":1,"method":"settings.sections","params":{}}
← {"sections":[{"id":"general","label":"通用设置","order":10},
               {"id":"models","label":"模型","order":20}]}
```

* `id` 必须非空且唯一，`label` 是显示名，`order` 可省（省了就按数组顺序）。
* 建议顺序照 DSH 自己的设置页：`general` / `models` / `plugins` / `presets` / `devctl` / `rules` / `market` / `cards`。
* 允许只返回你真正能做的分区（客户端不会因此报错），但**至少要包含 `devctl`**。

### 2. settings.panel —— 某个分区的正文

```
→ {"id":2,"method":"settings.panel","params":{"id":"devctl"}}
← {"panel":{"title":"devctl 远程控制",
            "subtitle":"从手机或其他设备用 dshctl 驱动这台 DSH",
            "blocks":[ ... ]}}
```

`blocks[]` **目前只支持 `kind:"card"` 一种**（客户端按下面的契约渲染，所有字段都可省，缺省不渲染）：

```json
{"kind":"card",
 "title":"卡片标题","text":"正文说明","note":"灰字备注",
 "rows":[{"k":"监听地址","v":"0.0.0.0:7788","badge":"运行中",
          "btns":[{"label":"复制","action":"copy","arg":"0.0.0.0:7788"}]}],
 "svg":"<svg .../>"}
```

* `badge` 客户端渲染成绿点 `● 运行中`（状态类文字用这个）。
* `svg` 是**原始 SVG 文本**，客户端 `innerHTML` 内联进去 —— 二维码放这里。
* `row.k` 是固定宽度的左标签（约 58px），`row.v` 是值，`btns` 是行尾按钮。
* 分区做不到 / 未知 id → **返回一张说明卡**，不要抛错（抛错客户端也会兜底成「读取失败」，但白屏体验更差）。

### 3. settings.action —— 分区里的按钮

```
→ {"id":3,"method":"settings.action","params":{"id":"devctl","action":"refresh","arg":""}}
← {"message":"已刷新","reload":true}
```

* `action` 名字自己定，客户端原样回传，只负责 toast `message`；`reload:true` → 客户端重新拉一次该分区 panel。
* 客户端**自己处理、host 不用实现**的动作：`copy`（把 `arg` 写进剪贴板）、`reveal-token` / `hide-token`。

## 各分区的建议数据源（用现成的，别新造一套）

* **devctl**：直接复用 `statusPayload(bridge)` —— `listenHost / port / ip / addresses / hostname / version / token /
  command / qrPath / startedAt / uptimeMs / peers / livePeers` 全是现成的。二维码用 `qrSvg(status.command, {...})`
  （`qr.js` 已经 import 成 `qrSvg`，在 `/devctl-dsh/qr.svg` 那个路由里用过）。
  建议卡片：① 状态行（监听地址 + badge「运行中」、局域网地址、Host 版本、已运行时长）② 访问令牌（掩码 + btns
  `reveal-token` / `copy`）③ 配对命令（btns `copy`）④ 二维码（`svg`）⑤ 已连接设备表（`rows`：`k`=设备名，
  `v`=平台 · 最后活动 · 命令数，`badge`=在线/离线）。
* **rules**：`requireService(bridge, 'permissionPresets').catalog()`。
* **models**：`controller.modelCatalog()`。
* **plugins**：`ctx.get('loader')`（`refreshClientBundleGraph` 里 `host.loader.entries()` 那套）——列插件名 + 是否激活。
* **presets**：Agent 预设（`sessions.create` 的 `agentPreset` 那一套），取不到就返回说明卡。
* **general / market / cards**：host 侧没有可靠数据源就先返回说明卡（例：「这一项请在 DSH 自己的设置页里改」）。

## 约束

* 这三个方法要能**不依赖 session** 工作：手机端是「一次性连接」——连上、发一条请求、立刻 close，不会先 create session。
* 服务缺失照现状抛 `BridgeError('unavailable', ...)`；但**别让异常逃出去**，`serveLine` 只兜住顶层。
* 剪贴板、token 显隐全是客户端的事，host 只回 `message` / `reload`。
* `README.md` 的协议表里补这三行 + 一段请求/返回示例。
* 加完自测：照 `test-remote-web.mjs` 的路子写个 `test-settings.mjs`，断言 —— `devctl` panel 有 `blocks`、
  某行 `v` 是监听地址、二维码 `svg` 以 `<svg` 开头、未知 id 返回说明卡而不抛错。跑通再提交。

## 参照

客户端侧的完整契约与字段说明：`clients/dshconsole/host-patch/settings-protocol.md`。
