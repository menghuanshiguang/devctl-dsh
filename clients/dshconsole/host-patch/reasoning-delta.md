# dsh host 补丁：放行「思考」增量，让控制台能显示 reasoning

## 为什么必须改 host
`index.js` 的 `assistant-stream` 分支只认 `text-delta`，其它 chunk 类型**直接丢弃**：

```js
if (inner?.type === 'chunk' && inner.chunk?.type === 'text-delta' && typeof inner.chunk.text === 'string') {
  connection.send({ evt: 'delta', data: { sessionId, text: inner.chunk.text } })
}
return
```

模型产的思考增量从没离开过 host —— 客户端再改也拿不到不存在的数据。

## 改哪（index.js 约 983 行，`case 'assistant-stream'`）

**推荐版（不挑名字，任何带 text 的增量都转发，非 text-delta 一律标记为思考）**
好处：不用先去抓底层 chunk 到底叫什么名字。

```js
    case 'assistant-stream': {
      const inner = frame.frame
      const ck = inner?.type === 'chunk' ? inner.chunk : null
      if (ck && typeof ck.text === 'string' && ck.text.length > 0) {
        connection.send({
          evt: 'delta',
          data: { sessionId, text: ck.text, reasoning: ck.type !== 'text-delta' },
        })
      }
      return
    }
```

**保守版（只放行确定是思考的那一种）** —— 如果上面那版把不该当思考的东西也塞进来了：
把 `ck.type === 'text-delta' || ck.type === 'reasoning-delta'` 写在条件里，并且
`reasoning: ck.type === 'reasoning-delta'`。
不确定底层叫什么名字时，临时加一行 `console.log('chunk', ck.type)` 抓一次就清楚了。

## 兼容性
- 老客户端：不读 `reasoning` 字段，思考会被当成正文拼进消息（不想要就同时升级 dshconsole）。
- 新客户端（本轮已实现）：`reasoning:true` 的增量攒起来，正文开始前落成一个折叠的 `✦ 思考 ▸` 块；
  只思考没正文的回合，也会在回合结束时把思考块留下。

## 生效
改完**重启 dsh host**（面板 reload / 重启 node 插件）→ 新回合的思考就会实时出现在控制台里。
