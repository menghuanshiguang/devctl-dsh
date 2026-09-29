// sessions.file（读会话工作区里的图片）自测：不起 DSH，抠出纯函数 + 假服务跑。
// 用法：node test-workspace-image.mjs
import { readFileSync, mkdtempSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
process.env.TMPDIR = '/tmp'
import { join } from 'node:path'

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
  'class BridgeError extends Error { constructor(code, message) { super(message); this.code = code } }',
  grab('function errorText'),
  grab('function sessionCwd'),
  grab('function readWorkspaceImage'),
  'let services = {};',
  'const setServices = (next) => { services = next };',
  'const requireService = (bridge, name) => {',
  '  const service = services[name];',
  '  if (!service) throw new BridgeError("unavailable", name + " is not composed in this Host");',
  '  return service;',
  '};',
  'const timeoutSignal = () => new AbortController().signal;',
  'const IMAGE_EXT = { ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp", ".gif": "image/gif" };',
  'const MAX_WORKSPACE_FILE_BYTES = 12 * 1024 * 1024;',
  'return { readWorkspaceImage, setServices, IMAGE_EXT, MAX_WORKSPACE_FILE_BYTES };',
].join('\n')

// 沙箱里要能拿到 node:path / node:fs 的那几个函数
const sandbox = new Function(
  'readFile', 'stat', 'isAbsolute', 'resolve', 'normalize', 'extname', 'basename', 'sep',
  code,
)

const { readFile, stat } = await import('node:fs/promises')
const path = await import('node:path')
const api = sandbox(
  readFile, stat.bind(undefined) && stat, path.isAbsolute, path.resolve, path.normalize,
  path.extname, path.basename, path.sep,
)

const root = mkdtempSync(join('/tmp', 'dsh-file-'))
const work = join(root, 'work')
const outside = join(root, 'outside')
mkdirSync(work)
mkdirSync(outside)
writeFileSync(join(work, 'shot.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47]))
writeFileSync(join(work, 'notes.txt'), 'not an image')
writeFileSync(join(outside, 'secret.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47]))
writeFileSync(join(work, 'big.png'), Buffer.alloc(api.MAX_WORKSPACE_FILE_BYTES + 1))

let saved = null
api.setServices({
  attachments: {
    saveImage: async ({ mediaType, data, name }) => {
      saved = { mediaType, bytes: data.length, name }
      return { attachmentId: 'att_1', mediaType, bytes: data.length, width: 4, height: 4 }
    },
  },
})

const bridge = {
  ctx: { sessionController: { list: async () => ({ items: [{ sessionId: 's1', cwd: work }] }) } },
  log: () => {},
}

let bad = 0
const check = (label, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want)
  if (!ok) bad += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: got ${JSON.stringify(got)} want ${JSON.stringify(want)}`)
}
const codeOf = async (fn) => {
  try { await fn(); return 'no-throw' } catch (error) { return error.code ?? error.message }
}

// 绝对路径（工作区内）
const abs = await api.readWorkspaceImage(bridge, 's1', join(work, 'shot.png'))
check('绝对路径: 落库 mediaType', saved.mediaType, 'image/png')
check('绝对路径: 回传 attachmentId', abs.attachmentId, 'att_1')
check('绝对路径: 文件名', saved.name, 'shot.png')

// 相对路径（相对会话 cwd）
const rel = await api.readWorkspaceImage(bridge, 's1', 'shot.png')
check('相对路径: 也能读', rel.path, join(work, 'shot.png'))

// 越界 / 类型 / 不存在 / 过大
check('工作区外: 拒绝', await codeOf(() => api.readWorkspaceImage(bridge, 's1', join(outside, 'secret.png'))), 'forbidden')
check('非图片: 拒绝', await codeOf(() => api.readWorkspaceImage(bridge, 's1', join(work, 'notes.txt'))), 'bad-request')
check('不存在: 拒绝', await codeOf(() => api.readWorkspaceImage(bridge, 's1', join(work, 'nope.png'))), 'not-found')
check('过大: 拒绝', await codeOf(() => api.readWorkspaceImage(bridge, 's1', join(work, 'big.png'))), 'image-too-large')
check('越界路径用 ../ 也拦得住', await codeOf(() => api.readWorkspaceImage(bridge, 's1', '../outside/secret.png')), 'forbidden')

console.log(bad === 0 ? '\nALL PASS' : `\n${bad} FAILED`)
process.exit(bad === 0 ? 0 : 1)
