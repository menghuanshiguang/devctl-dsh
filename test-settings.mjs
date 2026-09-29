// 本地自测：settings.sections / settings.panel / settings.action 的契约
// 不启 socket，直接调 settingsMethods（dispatch 里就是这么一个转发）。
import { settingsMethods } from './settings-methods.js'

let failed = 0
const ok = (label, condition, extra = '') => {
  console.log(`${condition ? '  ok  ' : ' FAIL '} ${label}${extra ? '  ' + extra : ''}`)
  if (!condition) failed += 1
}

/* ---------------------------------------------------------- 假 Host */

const fakeSessions = {
  modelCatalog: () => ({
    groups: [
      { id: 'fast', label: '快速', models: [{ provider: 'acme', model: 'flash', default: true }] },
      { id: 'deep', label: '深度', models: [{ provider: 'acme', model: 'pro' }] },
    ],
  }),
}
const fakePermissionPresets = {
  catalog: () => ({ presets: [{ id: 'read-only', label: '只读', default: true }, { id: 'full', label: '完全' }] }),
}
const fakeAgentPresets = { catalog: () => ({ presets: [{ id: 'coder', label: '编码' }] }) }
const fakeLoader = {
  entries: () => [{ options: { name: 'devctl-dsh' } }, { options: { name: 'disabled-thing', disabled: true } }],
}
const services = {
  sessionController: fakeSessions,
  permissionPresets: fakePermissionPresets,
  agentPresets: fakeAgentPresets,
  loader: fakeLoader,
}

const bridge = {
  ctx: { get: (name) => services[name] },
  token: 'tok-abcdefghijklmnop',
  startedAt: Date.now() - 90_000,
  bound: { host: '0.0.0.0', port: 7788 },
}

const statusPayload = () => ({
  ok: true,
  version: '1.2.1',
  protocol: 1,
  listenHost: '0.0.0.0',
  port: 7788,
  ip: '192.168.1.9',
  addresses: ['192.168.1.9'],
  hostname: 'phone-host',
  token: bridge.token,
  command: 'dshctl pair 192.168.1.9:7788 --token tok-abcdefghijklmnop',
  qrPath: '/devctl-dsh/qr.svg',
  startedAt: bridge.startedAt,
  uptimeMs: Date.now() - bridge.startedAt,
  peers: [
    { name: 'Pixel 8', platform: 'android', commands: 12, live: true, lastSeenAt: Date.now() },
    { name: 'old-laptop', platform: 'linux', commands: 3, live: false, lastSeenAt: Date.now() - 900_000 },
  ],
  livePeers: 1,
})

const call = (method, params = {}) => settingsMethods[method]({ bridge, params, statusPayload })

/* ---------------------------------------------------------- 断言 */

console.log('— settings.sections')
const sections = (await call('settings.sections')).sections
const ids = sections.map((s) => s.id)
ok('有分区', sections.length > 0, JSON.stringify(ids))
ok('id 唯一', new Set(ids).size === ids.length)
ok('含 devctl', ids.includes('devctl'))
ok('按 order 升序', sections.every((s, i) => i === 0 || sections[i - 1].order <= s.order))

console.log('— settings.panel / devctl')
const devctl = (await call('settings.panel', { id: 'devctl' })).panel
const blocks = devctl.blocks ?? []
const rows = blocks.flatMap((b) => b.rows ?? [])
ok('有 title', devctl.title === 'devctl')
ok('有 blocks', blocks.length >= 4, `${blocks.length} 块`)
ok('卡片都是 kind:card', blocks.every((b) => b.kind === 'card'))
ok('有监听地址行', rows.some((r) => r.v === '0.0.0.0:7788'), JSON.stringify(rows[0] ?? {}))
ok('状态行有 badge', rows.some((r) => r.badge === '运行中'))
const qr = blocks.find((b) => typeof b.svg === 'string')
ok('二维码是 svg', !!qr && qr.svg.startsWith('<svg'))
const tokenRow = rows.find((r) => r.k === '令牌')
ok('令牌默认脱敏', !!tokenRow && tokenRow.v.includes('•') && !tokenRow.v.includes('abcdefghijklmnop'))
ok('复制按钮带真令牌', !!tokenRow.btns.find((b) => b.action === 'copy' && b.arg === bridge.token))
const peerRows = rows.filter((r) => r.badge === '在线' || r.badge === '离线')
ok('设备表两行', peerRows.length === 2)

console.log('— settings.action')
const toggle = await call('settings.action', { id: 'devctl', action: 'toggle-token' })
ok('toggle-token → reload', toggle.reload === true, JSON.stringify(toggle))
const revealed = (await call('settings.panel', { id: 'devctl' })).panel.blocks
  .flatMap((b) => b.rows ?? [])
  .find((r) => r.k === '令牌')
ok('再取时令牌明文', revealed.v === bridge.token)
const hidden = await call('settings.action', { id: 'devctl', action: 'toggle-token' })
ok('可以再藏回去', hidden.message.includes('隐藏'))
const refresh = await call('settings.action', { id: 'devctl', action: 'refresh' })
ok('refresh → reload', refresh.reload === true)
const unknown = await call('settings.action', { id: 'devctl', action: 'nope' })
ok('未知动作不抛错', typeof unknown.message === 'string' && unknown.reload !== true)

console.log('— 其它分区')
for (const id of ['general', 'models', 'plugins', 'presets', 'rules', 'market', 'cards']) {
  const panel = (await call('settings.panel', { id })).panel
  ok(`${id} 有正文`, Array.isArray(panel.blocks) && panel.blocks.length > 0 && !!panel.title)
}
const models = (await call('settings.panel', { id: 'models' })).panel
ok('models 读到分组', models.blocks.length === 2 && models.blocks[0].rows[0].k === 'flash')
const rules = (await call('settings.panel', { id: 'rules' })).panel
ok('rules 读到预设', (rules.blocks[0].rows ?? []).length === 2)
const plugins = (await call('settings.panel', { id: 'plugins' })).panel
ok('plugins 读到加载器', (plugins.blocks[0].rows ?? []).length === 2)

console.log('— 边界')
const missing = (await call('settings.panel', { id: 'not-a-section' })).panel
ok('未知分区给说明卡', missing.blocks.length === 1 && missing.blocks[0].kind === 'card')
try {
  await call('settings.panel', {})
  ok('缺 id 要抛 bad-request', false)
} catch (error) {
  ok('缺 id 抛 bad-request', error?.code === 'bad-request' && error?.isBridgeError === true)
}

console.log('— 无 statusPayload helper 时')
try {
  await settingsMethods['settings.panel']({ bridge, params: { id: 'devctl' } })
  ok('缺 helper 要抛 internal', false)
} catch (error) {
  ok('缺 helper 抛 internal', error?.code === 'internal')
}

console.log(failed === 0 ? '\n全部通过' : `\n${failed} 项失败`)
process.exit(failed === 0 ? 0 : 1)
