// sessions.image / sessions.queue / sessions.inbox 的形状自测：不起 DSH，抠出纯函数 + 假服务跑。
// 用法：node test-image-queue.mjs
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('./index.js', import.meta.url), 'utf8')
const lines = src.split('\n')

function grab(decl) {
  const start = lines.findIndex((line) => line.startsWith(decl) || line.startsWith(`async ${decl}`))
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
  'const CALL_TIMEOUT_MS = 30000;',
  'const QUEUE_TEXT_CHARS = 1200;',
  'const IMAGE_TYPES = new Set(["image/png","image/jpeg","image/webp","image/gif"]);',
  'const IMAGE_TARGETS = { thumb: { width: 704, height: 704, maxBytes: 320000 }, full: { width: 2048, height: 2048, maxBytes: 1600000 } };',
  'const MAX_IMAGE_BASE64 = 3200000;',
  'class BridgeError extends Error { constructor(code, message) { super(message); this.code = code } }',
  grab('function truncate'),
  grab('function textOf'),
  grab('function imagesOf'),
  grab('function inboxItem'),
  grab('function imageRef'),
  grab('function queueAction'),
  grab('function errorText'),
  'const timeoutSignal = () => new AbortController().signal;',
  'let services = {};',
  'const setServices = (next) => { services = next };',
  'const requireService = (bridge, name) => {',
  '  const service = services[name];',
  '  if (!service) throw new BridgeError("unavailable", name + " is not composed in this Host");',
  '  return service;',
  '};',
  grab('function readImage'),
  'return { readImage, imageRef, queueAction, imagesOf, inboxItem, setServices };',
].join('\n')
const api = new Function(code)()

let bad = 0
const check = (label, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want)
  if (!ok) bad += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: got ${JSON.stringify(got)} want ${JSON.stringify(want)}`)
}
const codeOf = async (fn) => {
  try { await fn(); return 'no-throw' } catch (error) { return error.code ?? error.message }
}

const png = { attachmentId: 'img_1', mediaType: 'image/png', bytes: 12, width: 8, height: 6 }
const bridge = () => ({ ctx: {}, log: () => {} })

// 1) 正常档：走 readImageRequest 的重编码结果
{
  const calls = []
  api.setServices({
    attachments: {
      readImageRequest: async (ref, target) => {
        calls.push([ref.attachmentId, target.maxBytes])
        return { data: new Uint8Array([1, 2, 3, 4]), mediaType: 'image/jpeg', width: 704, height: 528, variantId: 'v1' }
      },
    },
  })
  const out = await api.readImage(bridge(), 'sess_1', png, 'thumb')
  check('thumb.bytes', out.bytes, 4)
  check('thumb.base64', out.base64, Buffer.from([1, 2, 3, 4]).toString('base64'))
  check('thumb.mediaType', out.mediaType, 'image/jpeg')
  check('thumb.size', out.size, 'thumb')
  check('thumb.target', calls, [['img_1', 320000]])
  check('thumb 保留 attachmentId', out.attachmentId, 'img_1')
}

// 2) 重编码失败 → 退回 sessionController.attachment 的原尺寸 base64
{
  api.setServices({ attachments: { readImageRequest: async () => { throw new Error('no re-encode backend') } } })
  const controller = {
    attachment: async ({ attachmentId }) => ({
      attachment: { mediaType: 'image/png', bytes: 3, width: 1, height: 1 },
      data: 'AAEC',
      attachmentId,
    }),
  }
  const b = bridge()
  b.ctx.sessionController = controller
  const out = await api.readImage(b, 'sess_1', png, 'full')
  check('fallback.size', out.size, 'raw')
  check('fallback.base64', out.base64, 'AAEC')
  check('fallback.mediaType', out.mediaType, 'image/png')
}

// 3) 超大图必须报错，不能把整帧撑爆
{
  api.setServices({ attachments: { readImageRequest: async () => ({ data: new Uint8Array(4_000_000) }) } })
  check('超大图报 image-too-large', await codeOf(() => api.readImage(bridge(), 'sess_1', png, 'full')), 'image-too-large')
}

// 4) 附件服务缺失 → 明确的 unavailable，而不是 TypeError
{
  api.setServices({})
  check('缺服务报 unavailable', await codeOf(() => api.readImage(bridge(), 'sess_1', png, 'thumb')), 'unavailable')
}

// 5) 队列动作 → 交给 Host 的结构
check('queue.edit 带 content', api.queueAction({ kind: 'edit', text: 'hi' }), { kind: 'edit', content: [{ type: 'text', text: 'hi' }] })
check('queue 未知动作', await codeOf(() => api.queueAction({ kind: 'nope' })), 'bad-request')

console.log(bad === 0 ? '\nALL PASS' : `\n${bad} FAILED`)
process.exit(bad === 0 ? 0 : 1)
