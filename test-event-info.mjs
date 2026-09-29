// describeEvent 的 default 分支要带上可显示的小字段（手机端的事件行才有内容）
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('./index.js', import.meta.url), 'utf8')
const lines = src.split('\n')
function grab(decl) {
  const start = lines.findIndex((l) => l.startsWith(decl) || l.startsWith('async ' + decl))
  if (start < 0) throw new Error('没找到 ' + decl)
  const out = [lines[start]]
  for (let i = start + 1; i < lines.length; i += 1) {
    out.push(lines[i])
    if (lines[i] === '}') break
  }
  return out.join('\n')
}
const api = new Function([
  'const TRUNCATE_CHARS = 4000;',
  'const QUEUE_TEXT_CHARS = 1200;',
  'const CONTEXT_TEXT_CHARS = 4000;',
  'function truncate(value, limit = TRUNCATE_CHARS) {',
  '  if (typeof value !== "string") return value;',
  '  return value.length <= limit ? value : `${value.slice(0, limit)}…(+${value.length - limit} chars)`;',
  '}',
  grab('function textOf'),
  grab('function eventInfo'),
  'return { eventInfo };',
].join('\n'))()

let bad = 0
const check = (label, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want)
  if (!ok) { bad += 1 }
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: got ${JSON.stringify(got)} want ${JSON.stringify(want)}`)
}
check('llm/retry 取 reason+attempt', api.eventInfo('llm/retry', { reason: { message: '上游 429' }, attempt: 2 }),
  { reason: '上游 429', attempt: 2, model: '' })
check('compaction/summary 带 token 数', api.eventInfo('compaction/summary', { summary: '摘要写入', shadowedTokenCount: 1234 }),
  { reason: '摘要写入', tokens: 1234 })
check('command/run 取命令名', api.eventInfo('command/run', { command: '/compact' }), { command: '/compact', text: '' })
check('approval/asked 取工具名', api.eventInfo('approval/asked', { toolName: 'bash', reason: '要跑 rm -rf' }),
  { name: 'bash', text: '要跑 rm -rf' })
check('request/context 带截断正文（这条分支以前没测到，漏了常量定义）',
  api.eventInfo('request/context', { message: { content: [{ type: 'text', text: 'x'.repeat(50) }] } }),
  { text: 'x'.repeat(50) })
check('未知事件不给字段', api.eventInfo('whatever/happened', { big: 'x'.repeat(100000) }), {})
console.log(bad === 0 ? '\nALL PASS' : `\n${bad} FAILED`)
process.exit(bad === 0 ? 0 : 1)
