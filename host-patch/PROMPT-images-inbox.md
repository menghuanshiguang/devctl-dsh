# 给 Host 侧 agent 的提示词：更新到带「图片 / 信箱队列」的版本

手机端（DshConsole）这一轮加了 **图片收发** 和 **排队消息坞（撤回 / 插话）**，两边靠这几个方法对话。
Host 侧插件如果还是旧版，手机端只会看到「一直是排队中、撤回没反应」——把仓库更新到最新 `main` 并重启加载器即可。

## 1. 更新

```bash
cd <devctl-dsh 仓库>
git pull --ff-only origin main        # 需要 c088c88 或更新
node --check index.js
# 重启 dsh（或让加载器重载插件），确认 /devctl-dsh/status 的 version 变了
```

## 2. 这一轮新增/变更的协议面（Host 侧必须认）

| 方法 | 请求 | 返回 |
| --- | --- | --- |
| `sessions.prompt` | **新增** `requestId`（客户端自铸的 UUID，可缺省）、`images[]` | `{..., requestId}`（原样回传，客户端拿它对账） |
| `sessions.image` | `{sessionId, attachment:{attachmentId,mediaType,bytes,width,height}, size:'thumb'\|'full'}` | `{attachmentId, mediaType, bytes, width, height, size, base64}` |
| `sessions.inbox` | `{sessionId}` | `{sessionId, asOfSeq, nextTurn:[item], nextStep:[item]}` |
| `sessions.queue` | `{sessionId, itemId, action:{kind:'remove'\|'steer'\|'edit', text?}}` | `{accepted, itemId, action}` |
| `sessions.file` | `{sessionId, path}`（绝对或相对会话 cwd） | `{path, attachmentId, mediaType, bytes, width, height}` |

`sessions.file` 是给「agent 把图写盘、回复里只留一个路径」这种场景准备的：
手机端拿不到 PC 的硬盘，唯一的口子就是它。它只读**会话工作区内**、后缀是
`.png/.jpg/.jpeg/.webp/.gif` 的文件（越界/非图片/超过 12MB 一律拒绝），
读完先 `attachments.saveImage()` 落库成附件引用，客户端再照常走 `sessions.image` 取缩略图/原图。
自测：`node test-workspace-image.mjs`（9 条断言，含 `../` 越界）。


`item = {id, source, rpcId?, text, images:[{attachmentId,mediaType,bytes,width,height,name?}]}`

`sessions.prompt` 的 `images[]` 元素是 `{type:'image', mediaType, data(base64), name?}`；
`user/message`、`agent/inbox/spliced` 里的图片一律给**引用元数据**（不带 base64，几 MB 的内联会让每条记录都变成巨型帧）。

事件侧：`agent/inbox/spliced` → `{kind:'inbox', target, start, removedCount, inserted:[item], outcome}`。
手机端**不解析 splice 的增量语义**，收到它就重新调 `sessions.inbox` 对一次账（权威、且省得两边算法对不上）。

## 3. 自测（不接 UI 也能跑）

```bash
node test-image-queue.mjs        # prompt 带图 / image 取字节 / inbox 快照 / queue 三动作
node test-workspace-image.mjs    # sessions.file：工作区内读图 / 越界与非图片被拒
```

## 4. 更新后手机端应该看到什么

- 空闲时发一条：输入卡上方短暂出现「⌛ 文本 · 已送出 · 等 host 回执」，**约 2 秒内消失**（说明它已经开始跑了）。
- 回合进行中发一条：那一行**留在坞里**，右侧有「撤回」「⏎ 插话」两个按钮，按 host 的信箱快照走。
- 带图发送：图片先本地渲染，host 回程的记录里带 `images[]`，滚动回看时按需调 `sessions.image(size:'thumb')` 取缩略图。
- agent 只给路径（`D:\…\新年大吉-原图-1080x1921.jpg` 或 `![说明](相对路径)`）：手机端会调 `sessions.file` 把这张图取过来显示；取不到时是**文字芯片**，不再是一行乱码。
