# host 侧补丁：设置页协议（settings.*）

客户端「设置」入口已改成**实时读取 DSH 设置**：新开一个 Activity，用 WebView 渲染，侧边栏＝一级页面、
点击进二级页面。窗口部分 app 侧已经写完（`SettingsActivity`），**host 侧只需在 devctl 的 TCP 控制端口补三个方法**。

没实现时 app 不会报错：分区表回落到内置的 8 项，`devctl` 分区用 app 自己的配对记录（地址/令牌/配对命令）+ 一次握手探测
（Host 版本）实时拼出来；其余分区显示「需要 host 侧协议扩展」。

## 一、settings.sections

```
→ {"id":1,"method":"settings.sections","params":{}}
← {"sections":[{"id":"general","label":"通用设置","order":10},
               {"id":"models","label":"模型","order":20}]}
```
* 顺序＝侧边栏顺序，`order` 可省。
* `devctl` 是否返回都行：app 内置表里已有，host 返回了就以 host 为准。

## 二、settings.panel

```
→ {"id":2,"method":"settings.panel","params":{"id":"devctl"}}
← {"panel":{"title":"devctl 远程控制","subtitle":"从手机或其他设备用 dshctl 驱动这台 DSH",
            "blocks":[ ... ]}}
```

`blocks[]` 目前支持（字段都可省，缺省不渲染）：

```json
{"kind":"card",
 "title":"卡片标题","text":"说明文字","note":"灰字备注",
 "rows":[{"k":"监听地址","v":"0.0.0.0:7788","badge":"运行中",
          "btns":[{"label":"复制","action":"copy","arg":"0.0.0.0:7788"}]}],
 "svg":"<svg .../>"}
```
* `badge` 渲染成绿点（用于「运行中」这类状态）。
* `svg` 是**原始 SVG 文本**，会被内联进 DOM —— 二维码用它。
* 设备表这类还没做专门块类型，先用 `rows` 表达（`k`=设备名，`v`=地址 · 最后活动 · 状态）。

## 三、settings.action

```
→ {"id":3,"method":"settings.action","params":{"id":"devctl","action":"refresh","arg":""}}
← {"message":"已刷新","reload":true}
```
* `action` 名字由 host 自己定义，app 原样回传，只负责 toast `message`。
* `reload:true` → app 重新拉一次该分区的 `settings.panel`。
* app 自己处理、**host 不用实现**的动作：`copy`（把 `arg` 写进剪贴板）、`reveal-token` / `hide-token`。

## 四、devctl 分区建议

直接复用 host 现有的 `/devctl-dsh/status` 那套数据：监听地址（含运行状态）、局域网地址、版本 + 已运行时长、
访问令牌（掩码 + 显示/复制）、配对命令、配对二维码（SVG）、已连接设备表。

## 五、报错约定

方法不存在就照现状返回错误，app 会回落。**只支持一部分分区也可以**：`settings.sections` 返回哪些，侧边栏就显示哪些。
