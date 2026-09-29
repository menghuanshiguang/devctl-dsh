# 任务：把 devctl-dsh 更新到 1.3.0，让「原版网页」这条路真正通

仓库：https://github.com/menghuanshiguang/devctl-dsh —— `git pull origin main`（目标提交 `0ec0ea2`），
host 侧代码是根目录的 `index.js` + `remote-web.js`。改完**重载/重启这个插件**（Dsh 网页窗口要重开，
`installRemoteWeb` 是装机时启动的）。

## 为什么手机端一直拿不到原版页面

手机端的设置二级页是「有原版网页就显示原版，没有才退回 app 内视图」。插件原来靠**扫 loopback 端口**找 Host 网页服务，
两个坑叠在一起：

1. 探针裸请求 `/devctl-dsh/status`，而这条路由后面坐着 Host 的浏览器信任检查（`rejectUntrustedRequest`）——
   裸请求必然 401，扫多少端口都判成「没找到」，于是 `ready:false / host-web-not-found`，手机只能退回内视图。
2. 明明 Host 自己就知道网页服务在哪个端口（`webServer` 服务和插件在同一个进程里），却没人去问它。

## 1.3.0 已经做了什么（拉下来就有，别重复改）

* **探针带令牌**：`probePort()` 现在请求 `${statusPath}?probe=<控制令牌>`；`index.js` 的 `guarded()` 里
  `isWebSelfProbe()` 用常量时间比较对令牌，对得上就直接回 status（只有 `STATUS_PATH` 这一条走这个口子）。
* **优先直读 Host 服务端口**：`publishWebServiceOrigin()` 在装设置路由时读 `host.webServer` 的端口
  （`webServerPort()` 把 `port` / `address.port` / `server.address.port` / `url` / `origin` 这些形状都试一遍），
  读到就 `lanWeb.setTarget('http://127.0.0.1:<port>')`，不再扫。
* **兜底**：自识别探针全空时，试一发 `dsh web` 的默认端口 `3081`（只要它有任何 HTTP 应答就算数），
  `reason` 记成 `host-web-default`。
* **协议里的 `web` 块齐了**（`hello` 和 `status` 都带）：

```json
"web": { "port": 7790, "ready": true, "reason": "discovered",
         "target": "http://127.0.0.1:3081", "url": "http://192.168.2.7:7790/?token=…",
         "source": "host|config|default|discovered|none",
         "seen": [ { "port": 3081, "status": 200 } ] }
```

  `ready` 为真时手机端直接开 `url`（令牌已经拼好），不再自己拼端口；`seen` 是「哪些 loopback 端口应答过、
  什么状态码」，探不到时用它说明原因。`GET /__devctl-web/health?token=…` 同样返回这些字段。

## 2. 你要做的事（很少）

1. `git pull origin main`，确认 `index.js` 的 `VERSION` 是 `1.3.0`。
2. 重载插件（或重启 DSH 的网页窗口那套 profile），让 `installRemoteWeb()` 重新跑一次。
3. 验收：手机上开「设置 → 任意分区」，应当是 **DSH 原版网页**，而不是 app 自绘的卡片。
   或者直接看 hello：

```
→ {"id":1,"method":"hello","params":{"token":"<控制令牌>","client":"dshctl/1.3.0","device":{"name":"phone"}}}
← {"result":{"...,"web":{"port":7790,"ready":true,"reason":"discovered","target":"http://127.0.0.1:3081",…}}}
```

   若 `ready:false`，请把 `reason` 和 `seen` 原样报回来 —— 那就是 Host 上真的没有网页服务在跑（`dsh web` 没起）。

## 约束

* 别动 `web.port` 默认值（7790）和 `web.target` 的语义；`web.port=0` 仍然是关掉整个局域网窗口。
* 别把控制令牌写进任何未经鉴权的响应；`?probe=` 只放行 `STATUS_PATH` 这一条，且必须常量时间比较。
* 改动完跑 `node test-remote-web.mjs`，应当是「发现 → 302 → cookie 通行 → 健康检查 → POST 透传」全绿。
