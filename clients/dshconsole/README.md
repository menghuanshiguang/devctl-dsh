# dshconsole

`dsh` host（devctl-dsh）的 **Android 原生客户端**。单个 Activity，纯 Java + 手写 View，不依赖 AndroidX / Compose，编译产物 ~76 KB。

## 功能

- **多设备管理**：粘贴 `dshctl add <name> <host:port> --token <hex>` 就能自动解析配对信息
- **侧栏**：工作区可展开成父节点（子会话挂在下面）、会话搜索、设备/事件/模型权限/设置抽屉
- **聊天**：流式正文、markdown（粗体 / 行内代码 / 标题 / 列表 / 引用 / **真表格**）、工具调用与结果折叠卡、运行期注入上下文单独成块
- **工具组**：连续的工具调用 / 结果自动收成一张 `⚙ 工具调用 ×N · M 字` 卡，点标题整体展开；组内条目收起时从视图树摘除，不占高度
- **展开不跳底**：任何卡片原地展开 / 收起（记住点击那一刻的位置，重排后还原）
- **发送模式**：默认排队等本回合结束（↑）；`⏎` 立刻插话（`steer`）
- **思考内容**：host 放行 `reasoning-delta` 后渲染成 `✦ 思考` 折叠块（见 `host-patch/`）
- **连接自愈**：短连接 + 失败自动重连重试；流式断开自动重新 `sessions.watch`

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
- `assistant-stream` 只转发 `text-delta`（`index.js`），**`reasoning-delta` 会被丢弃** → 想显示思考内容必须打 `host-patch/` 里的补丁

## 目录

```
src/com/minis/dshconsole/   纯 Java 源码（单包）
host-patch/                 host 侧补丁说明（思考内容放行）
design/mock.html            UI 草图
build.py                    构建脚本
```
