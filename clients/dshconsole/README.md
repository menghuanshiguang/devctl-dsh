# dshconsole

`dsh` host（devctl-dsh）的 **Android 原生客户端**。单个 Activity，纯 Java + 手写 View，不依赖 AndroidX / Compose，编译产物 ~86 KB。

## 功能

### 对话

- **思考块**：正文之前的 `✦ 思考 · N 字 ▸` **默认折叠**；流式期间只在标题下显示**最新一行**（超 60 字前缀省略号、`Ellipsize.START`），点标题才展开全文；回合结束后预览行隐藏，块保持折叠但随时可再展开
- **流式正文** + markdown：粗体 / 行内代码 / 标题 / 列表 / 引用 / 删除线 / **真表格**（按 Minis 规格：外框圆角 + 列间发丝线 + 表头底色 + 单元格递归渲染行内标记，窄屏横向可滚）
- **工具卡**：连续的工具调用 / 结果自动收成一张 `⚙ 工具调用 ×N · M 字` 卡，点标题整体展开；组内条目会在收起时从视图树摘除，不占高度
- **原地展开**：任何卡片展开 / 收起都记住点击那一刻的位置，重排后还原，不跳底
- **自动跟随**：非强制场景用瞬时滚动 + 尾部补滚（trailing-edge），流式期间不抖动；用户手动往上翻时不会被硬拽回底部

### 输入与发送

- **模式栏**：输入框上方一排可横滚胶囊，直接切 `模型 / 思考强度 / 权限`；档位来自 `models.catalog` 的 `reasoning.efforts`，权限预设取自 `permissions.catalog`
- **发送模式**：默认 `↑` **本地挂起**（气泡左侧标 `⏎`），等当前回合结束自动合并放行；按 `⏎` 则 `steer` 立即插进当前回合。协议没有撤回已排队消息的方法，所以这条消息在放行前只存在本地
- **复制**：每条消息右上角一键复制原文

### 连接与管理

- **多设备**：粘贴 `dshctl add <name> <host:port> --token <hex>` 即可自动解析配对信息
- **侧栏**：工作区展开成父节点（子会话挂在下面）、会话搜索、设备 / 事件 / 模型权限 / 设置抽屉
- **连接自愈**：短连接 + 失败自动重连重试；流式断开自动重新 `sessions.watch`
- **调试**：把 `TabChat.DEBUG_FRAMES` 置 true，帧级事件会写进 `files/frames.log`（`reasoning` / `R-render` / `T-pass` 三类标记，256KB 封顶）。排查「流式没内容」时，先看这份日志能直接分清是帧没到、还是到了没画

## 构建

需要 JDK + Android SDK build-tools（`aapt2` / `d8` / `apksigner`）：

```bash
python3 build.py        # 产物 out/dshconsole.apk
```

`build.py` 顶部的 `SDK` 等路径是硬编码的，换机器请改。

## 协议要点（都是踩出来的）

- 信封是 `{"id":n,"method":"...","params":{...}}`；`hello` 的 `params` 里 client / device 缺失会被拒
- **回包里的 `host` 是字符串**（不是对象，也不是 `hostName`）
- 实录方法名：`sessions.list|create|prompt|cancel|rename|search|tail|watch|unwatch`、`workspaces.list|create|rename|delete`、`models.catalog|select`、`permissions.catalog|current|set`（**不存在** `workspaces.use` / `sessions.new` / `models.list` / `permissions.list`）
- **长连接空闲 ~30s 被掐**（errno 103 `Connection aborted` → errno 32 `Broken pipe`），每 10s ping 也救不回来 → 默认「一条命令一条短连接」，只有流式持长连接
- `sessions.prompt` 的 `mode` 只有 `queue`（默认，排队等当前回合）和 `steer`（立刻插进当前回合）；**没有**撤回已排队消息的方法，所以「等 / 立刻提交」必须在发送那一刻决定
- `sessions.tail` 只有 `limit`（≤500），**没有游标翻页**参数 → 客户端做不了「加载更早」
- `describeRecord` 是「能显示什么」的天花板：未知事件只给 `{kind:'event',type}`，`data` 会被丢掉
- 运行期上下文注入是以 **user 记录**下发的，协议里没有标记位 → 客户端只能自己用启发式识别
- `assistant-stream` 的 `reasoning-delta` 已在 host 侧放行（见 #1，`host-patch/` 保留作说明）→ 思考内容可以实时渲染；**注意它必须排在回合门之前处理**，否则 PC / CLI 侧发起的回合在手机上永远看不到思考
- `models.select` 要**扁平**参数 `{sessionId, provider, model, reasoningEffort?}`，且 provider / model 必须传 `id` 而不是 `name`；`permissions.set` 只要 `{sessionId, preset}`

## 目录

```
src/com/minis/dshconsole/   纯 Java 源码（单包）
host-patch/                 host 侧补丁说明（思考内容放行）
design/mock.html            UI 草图
build.py                    构建脚本
```
