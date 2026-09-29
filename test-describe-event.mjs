// describeEvent 的投影自测：不启动 DSH，直接把 index.js 里那几个纯函数抠出来跑。
// 用法：node test-describe-event.mjs
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('./index.js', import.meta.url), 'utf8')
const lines = src.split('\n')

/** 从 index.js 里抠出函数/常量定义（都是单层缩进的纯函数，按行抓足够）。 */
function grab(decl) {
  const start = lines.findIndex((line) => line.startsWith(decl))
  if (start < 0) throw new Error(`没找到 ${decl}`)
  const out = [lines[start]]
  if (decl.startsWith('function')) {
    for (let i = start + 1; i < lines.length; i += 1) {
      out.push(lines[i])
      if (lines[i] === '}') break
    }
  }
  return out.join('\n')
}

const code = [
  'const TRUNCATE_CHARS = 1200;',
  'const QUEUE_TEXT_CHARS = 400;',
  'const IMAGE_TYPES = new Set(["image/png","image/jpeg","image/webp","image/gif"]);',
  'class BridgeError extends Error { constructor(code, message) { super(message); this.code = code } }',
  grab('function textOf'),
  grab('function truncate'),
  grab('function imagesOf'),
  grab('function inboxItem'),
  grab('function reasoningOf'),
  grab('function callsOf'),
  grab('function turnReason'),
  grab('function describeEvent'),
  grab('function imageRef'),
  grab('function queueAction'),
  'return { textOf, truncate, imagesOf, inboxItem, reasoningOf, callsOf, turnReason, describeEvent, imageRef, queueAction };',
].join('\n')
const api = new Function(code)()

let bad = 0
const check = (label, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want)
  if (!ok) bad += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: got ${JSON.stringify(got)} want ${JSON.stringify(want)}`)
}

const assistant = api.describeEvent({
  type: 'assistant/message',
  seq: 7,
  data: {
    turn: 2,
    step: 1,
    stream: 'done',
    message: {
      content: [
        { type: 'reasoning', text: '先看看目录' },
        { type: 'text', text: '我改好了' },
        { type: 'tool-call', id: 'call_9', name: 'read', arguments: '{"path":"/tmp/a"}' },
      ],
    },
  },
})
check('assistant.text', assistant.text, '我改好了')
check('assistant.reasoning', assistant.reasoning, '先看看目录')
check('assistant.calls', assistant.calls, [{ callId: 'call_9', name: 'read', arguments: '{"path":"/tmp/a"}' }])
check('assistant.turn/step', [assistant.turn, assistant.step], [2, 1])

const call = api.describeEvent({
  type: 'tool/call',
  seq: 8,
  data: { turn: 2, step: 1, callId: 'call_9', name: 'read', arguments: '{"path":"/tmp/a"}' },
})
check('tool-call.callId', call.callId, 'call_9')
check('tool-call.kind', call.kind, 'tool-call')

const result = api.describeEvent({
  type: 'tool/result',
  seq: 9,
  data: { turn: 2, step: 1, message: { source: { callId: 'call_9' }, content: [{ type: 'text', text: 'file body' }] } },
})
check('tool-result.callId', result.callId, 'call_9')
check('tool-result.text', result.text, 'file body')

const failed = api.describeEvent({
  type: 'tool/result',
  seq: 10,
  data: { message: { source: { callId: 'call_9' } }, error: { name: 'ENOENT', reason: 'no file' } },
})
check('tool-result.error', failed.error, { name: 'ENOENT', code: undefined, reason: 'no file' })

check('reasoningOf 拼接', api.reasoningOf([{ type: 'reasoning', text: 'A' }, { type: 'text', text: 'x' }, { type: 'reasoning', text: 'B' }]), 'AB')
check('textOf 不含思考', api.textOf([{ type: 'reasoning', text: 'A' }, { type: 'text', text: 'B' }]), 'B')
check('callsOf 空', api.callsOf(undefined), [])

// 额度用尽这种失败就藏在 reason.error 里，只留 kind = 手机端白屏
const quota = api.describeEvent({
  type: 'turn/end',
  seq: 11,
  data: { turn: 2, reason: { kind: 'error', error: { code: 'QUOTA', message: 'Insufficient Balance', status: 402 } } },
})
check('turn-end.quota.code', quota.reason.code, 'QUOTA')
check('turn-end.quota.message', quota.reason.message, 'Insufficient Balance')
check('turn-end.quota.kind', quota.reason.kind, 'error')
check('turn-end.quota.status', quota.reason.status, 402)
check('turn-end 打断带上 cause',
  api.turnReason({ kind: 'aborted', reason: { kind: 'user' } }),
  { kind: 'aborted', cause: 'user' })
check('turn-end 正常收尾', api.turnReason({ kind: 'completed' }), { kind: 'completed' })

// 图片：记录里只放元数据，字节让客户端按 attachmentId 现取
const withImage = api.describeEvent({
  type: 'user/message',
  seq: 12,
  data: {
    turn: 3,
    id: 'msg_1',
    source: { kind: 'user', rpcId: 'rpc_1' },
    content: [
      { type: 'text', text: '看看这张图' },
      {
        type: 'image',
        attachment: { attachmentId: 'img_1', mediaType: 'image/jpeg', bytes: 4242, width: 1200, height: 800, name: 'a.jpg' },
      },
    ],
  },
})
check('user.rpcId', withImage.rpcId, 'rpc_1')
check('user.messageId', withImage.id, 'msg_1')
check('user.images', withImage.images, [
  { attachmentId: 'img_1', mediaType: 'image/jpeg', bytes: 4242, width: 1200, height: 800, name: 'a.jpg' },
])
check('user.text 不含图片', withImage.text, '看看这张图')
check('内联 base64 不进帧', api.imagesOf([{ type: 'image', mediaType: 'image/png', data: 'AAAA' }]), [])
const toolImage = api.describeEvent({
  type: 'tool/result',
  seq: 13,
  data: { message: { source: { callId: 'c1' }, content: [{ type: 'image', attachment: { attachmentId: 'img_2', mediaType: 'image/png' } }] } },
})
check('tool-result.images', toolImage.images, [
  { attachmentId: 'img_2', mediaType: 'image/png', bytes: 0, width: 0, height: 0 },
])

// 排队：spliced 里插进来的就是完整 UserMessage，内容能直接给手机端画队列坞站
const splice = api.describeEvent({
  type: 'agent/inbox/spliced',
  seq: 14,
  data: {
    target: 'next-turn',
    start: 0,
    removedCount: 0,
    inserted: [
      {
        id: 'msg_q1',
        source: { kind: 'user', rpcId: 'rpc_q1' },
        content: [
          { type: 'text', text: '等会儿再跑这个' },
          { type: 'image', attachment: { attachmentId: 'img_3', mediaType: 'image/png', bytes: 10, width: 4, height: 4 } },
        ],
      },
    ],
  },
})
check('inbox.kind', splice.kind, 'inbox')
check('inbox.target', splice.target, 'next-turn')
check('inbox.removedCount', splice.removedCount, 0)
check('inbox.inserted[0].id', splice.inserted[0].id, 'msg_q1')
check('inbox.inserted[0].rpcId', splice.inserted[0].rpcId, 'rpc_q1')
check('inbox.inserted[0].text', splice.inserted[0].text, '等会儿再跑这个')
check('inbox.inserted[0].images[0].attachmentId', splice.inserted[0].images[0].attachmentId, 'img_3')
const removal = api.describeEvent({
  type: 'agent/inbox/spliced',
  seq: 15,
  data: { target: 'next-turn', start: 0, removedCount: 1, inserted: [], outcome: 'canceled' },
})
check('inbox 撤回带 outcome', [removal.removedCount, removal.outcome, removal.inserted], [1, 'canceled', []])

// 附件引用：attachmentId 是唯一权威字段
check('imageRef 只给 id', api.imageRef({ attachmentId: 'img_9', mediaType: 'image/webp', bytes: 7, width: 2, height: 3 }),
  { attachmentId: 'img_9', mediaType: 'image/webp', bytes: 7, width: 2, height: 3 })
check('imageRef 媒体类型兜底', api.imageRef({ attachmentId: 'x', mediaType: 'text/plain' }).mediaType, 'image/png')
check('imageRef 缺 id 报错', (() => { try { api.imageRef({}) } catch (error) { return error.code } return 'no-throw' })(), 'bad-request')

// 队列动作：只有 remove / steer / edit(纯文本) 三种
check('queueAction.remove', api.queueAction({ kind: 'remove' }), { kind: 'remove' })
check('queueAction.steer', api.queueAction({ kind: 'steer' }), { kind: 'steer' })
check('queueAction.edit', api.queueAction({ kind: 'edit', text: '改一下' }), { kind: 'edit', content: [{ type: 'text', text: '改一下' }] })
check('queueAction.edit 空文本报错', (() => { try { api.queueAction({ kind: 'edit', text: '  ' }) } catch (error) { return error.code } return 'no-throw' })(), 'bad-request')
check('queueAction 未知动作报错', (() => { try { api.queueAction({ kind: 'drop' }) } catch (error) { return error.code } return 'no-throw' })(), 'bad-request')

console.log(bad === 0 ? '\nALL PASS' : `\n${bad} FAILED`)
process.exit(bad === 0 ? 0 : 1)
