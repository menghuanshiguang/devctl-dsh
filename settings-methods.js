/**
 * devctl-dsh —— 手机端（clients/dshconsole）「设置」面板的 Host 侧实现。
 *
 * 提供三个协议方法：
 *   settings.sections  侧边栏分区表
 *   settings.panel     某个分区的正文（card 块）
 *   settings.action    分区里的按钮动作
 *
 * 契约详见 clients/dshconsole/host-patch/settings-protocol.md。
 *
 * 接入 index.js（两处，共 3 行）：
 *   1) 顶部：import { settingsMethods } from './settings-methods.js'
 *   2) dispatch() 开头（switch 之前）：
 *        const settingsHandler = settingsMethods[method]
 *        if (settingsHandler) return await settingsHandler({ bridge, params, statusPayload })
 *
 * 手机端是「一次性连接」——连上、发一条请求、立刻 close，不会先 create session，
 * 所以这三个方法都不依赖 connection / session。
 */
import { svg as qrSvg } from './qr.js'

/** 与 index.js 的 BridgeError 同形（Host 的顶层 catch 只看 isBridgeError / code）。 */
class SettingsError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'BridgeError'
    this.code = code
    this.isBridgeError = true
  }
}

/** 侧边栏顺序照 DSH 自己的设置页；做不到的分区返回说明卡，不会抛错。 */
const SECTIONS = [
  { id: 'general', label: '通用设置', order: 10 },
  { id: 'models', label: '模型', order: 20 },
  { id: 'plugins', label: '内置插件', order: 30 },
  { id: 'presets', label: 'Agent 预设', order: 40 },
  { id: 'devctl', label: 'devctl', order: 50 },
  { id: 'rules', label: '规则设定', order: 60 },
  { id: 'market', label: '插件市场', order: 70 },
  { id: 'cards', label: '侧边卡片', order: 80 },
]

/* ---------------------------------------------------------------- 工具 */

/** 取服务，取不到就返回 undefined（不发异常，交给调用方决定降级方式）。 */
function softService(bridge, name) {
  try {
    return typeof bridge.ctx?.get === 'function' ? bridge.ctx.get(name) : undefined
  } catch {
    return undefined
  }
}

function service(bridge, name) {
  const found = softService(bridge, name)
  if (!found) throw new SettingsError('unavailable', `${name} is not composed in this Host`)
  return found
}

function text(value) {
  if (value === undefined || value === null) return ''
  return String(value)
}

function fmtDuration(ms) {
  const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000))
  const day = Math.floor(total / 86400)
  const hour = Math.floor((total % 86400) / 3600)
  const minute = Math.floor((total % 3600) / 60)
  const second = total % 60
  if (day > 0) return `${day} 天 ${hour} 小时`
  if (hour > 0) return `${hour} 小时 ${minute} 分`
  if (minute > 0) return `${minute} 分 ${second} 秒`
  return `${second} 秒`
}

function fmtTime(ms) {
  if (!ms) return ''
  try {
    return new Date(ms).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return new Date(ms).toISOString()
  }
}

function textOf(value, ...keys) {
  if (typeof value === 'string') return value
  if (!value || typeof value !== 'object') return ''
  for (const key of keys) {
    if (typeof value[key] === 'string' && value[key]) return value[key]
  }
  return ''
}

/** 行尾按钮：客户端自己处理 copy / reveal-token / hide-token。 */
function copyBtn(label, arg) {
  return { label, action: 'copy', arg: text(arg) }
}

function card(fields) {
  const out = { kind: 'card' }
  if (fields.title) out.title = fields.title
  if (fields.text) out.text = fields.text
  if (fields.note) out.note = fields.note
  if (Array.isArray(fields.rows) && fields.rows.length > 0) out.rows = fields.rows
  if (fields.svg) out.svg = fields.svg
  return out
}

function explain(title, note) {
  return card({ title, text: '这一项需要 Host 侧继续扩展协议。', note })
}

/** 收集所有分区的构造器：sync 或 async，返回 {title, subtitle, blocks}。 */
const PANELS = {}

/** 令牌显隐状态：客户端点「显示」时会把 reveal-token 拦在本地，所以 Host 自己记住状态。 */
const TOKEN_REVEAL = new WeakMap()

function maskToken(token) {
  if (!token) return '（Host 未启用令牌）'
  if (token.length <= 8) return '••••••••'
  return `${token.slice(0, 4)}••••••••${token.slice(-4)}`
}

function devctlPanel({ bridge, statusPayload }) {
  const status = statusPayload(bridge)
  const port = status.port
  const addresses = (status.addresses ?? []).map((address) => `${address}:${port}`)
  const peers = status.peers ?? []
  const revealed = TOKEN_REVEAL.get(bridge) === true
  const blocks = []

  blocks.push(
    card({
      title: '服务状态',
      text: '手机或其他设备用 dshctl 连上这个地址，就能驱动本机 DSH。',
      rows: [
        {
          k: '监听地址',
          v: `${status.listenHost}:${port}`,
          badge: '运行中',
          btns: [copyBtn('复制', `${status.ip}:${port}`)],
        },
        ...(addresses.length > 0 ? [{ k: '局域网地址', v: addresses.join('   ') }] : []),
        { k: 'Host 版本', v: `devctl-dsh ${status.version}（协议 ${status.protocol}）` },
        { k: '已运行', v: fmtDuration(status.uptimeMs) },
      ],
      note: `主机名 ${status.hostname}　启动于 ${fmtTime(status.startedAt)}`,
    }),
  )

  blocks.push(
    card({
      title: '访问令牌',
      text: '第一次连接时用来握手。默认脱敏，点「显示」可临时展开。',
      rows: [
        {
          k: '令牌',
          v: revealed ? status.token || '—' : maskToken(status.token),
          btns: [
            { label: revealed ? '隐藏' : '显示', action: 'toggle-token' },
            copyBtn('复制', status.token),
          ],
        },
      ],
    }),
  )

  blocks.push(
    card({
      title: '配对命令',
      text: '在另一台设备的终端里粘贴执行即可完成配对。',
      rows: [{ k: '命令', v: status.command, btns: [copyBtn('复制', status.command)] }],
    }),
  )

  blocks.push(
    card({
      title: '扫码配对',
      text: '用另一台手机上的 dshconsole 扫这个码。',
      note: '当前这台手机可以直接截图发给对方；码里就是上面那条配对命令。',
      svg: qrSvg(status.command, { dark: '#000000', light: '#ffffff', quiet: 2, scale: 8 }),
    }),
  )

  blocks.push(
    card({
      title: `已连接设备（在线 ${status.livePeers}/${peers.length}）`,
      text: peers.length === 0 ? '还没有设备连上来。' : '每条命令都记在下面的连接上。',
      rows: peers.map((peer) => ({
        k: peer.name,
        v: [peer.platform || '未知平台', `${peer.commands} 条命令`, peer.lastSeenAt ? `最后活动 ${fmtTime(peer.lastSeenAt)}` : '']
          .filter(Boolean)
          .join(' · '),
        badge: peer.live ? '在线' : '离线',
      })),
    }),
  )

  return { title: 'devctl', subtitle: '这台 DSH 的远程控制通道', blocks }
}

function generalPanel({ bridge, statusPayload }) {
  const status = statusPayload(bridge)
  const blocks = [
    card({
      title: '关于本机',
      text: '下面是从 Host 直接读到的实时信息。',
      rows: [
        { k: 'Host 版本', v: `devctl-dsh ${status.version}（协议 ${status.protocol}）` },
        { k: '主机名', v: status.hostname },
        { k: '运行时', v: `Node ${process.version} · ${process.platform} ${process.arch}` },
        { k: '已运行', v: fmtDuration(status.uptimeMs) },
      ],
    }),
    card({
      title: '偏好设置',
      text: '主题、语言、快捷键这类偏好由 DSH 主程序管理。',
      note: '可在上一页用「网页窗口」打开 DSH 自己的设置页修改。',
    }),
  ]
  return { title: '通用设置', subtitle: 'Host 侧的运行信息', blocks }
}

function modelsPanel({ bridge }) {
  const controller = service(bridge, 'sessionController')
  const catalog = typeof controller.modelCatalog === 'function' ? controller.modelCatalog() : {}
  const groups = catalog?.groups ?? []
  const blocks = []
  for (const group of groups) {
    const rows = (group.models ?? []).map((model) => {
      const row = {
        k: textOf(model, 'model', 'id', 'name') || '未命名',
        v: textOf(model, 'provider', 'providerId', 'vendor'),
      }
      if (model.default === true || model.isDefault === true) row.badge = '默认'
      return row
    })
    blocks.push(card({ title: textOf(group, 'label', 'name', 'id') || '分组', rows }))
  }
  if (blocks.length === 0) blocks.push(explain('模型', 'Host 没有返回模型目录。'))
  return { title: '模型', subtitle: `Host 已配置 ${groups.length} 个分组`, blocks }
}

function pluginsPanel({ bridge }) {
  const loader = softService(bridge, 'loader')
  const entries = typeof loader?.entries === 'function' ? loader.entries() : []
  const rows = entries.map((entry) => {
    const options = entry?.options ?? {}
    return {
      k: textOf(options, 'name') || entry?.id || '未知插件',
      v: options.disabled === true ? '已停用' : '启用中',
      badge: options.disabled === true ? undefined : '激活',
    }
  })
  const blocks = [
    card({
      title: `已装插件（${rows.length}）`,
      text: 'Host 当前加载的插件列表。',
      rows,
      note: '启用 / 停用请改 DSH 的插件配置，这里只做只读展示。',
    }),
  ]
  return { title: '内置插件', subtitle: 'Host 侧加载的插件', blocks }
}

function presetsPanel({ bridge }) {
  const presets = softService(bridge, 'agentPresets')
  const catalog = typeof presets?.catalog === 'function' ? presets.catalog() : undefined
  const items = catalog?.presets ?? (Array.isArray(catalog) ? catalog : [])
  if (items.length === 0) {
    return {
      title: 'Agent 预设',
      subtitle: '',
      blocks: [explain('Agent 预设', '这个 Host 没有暴露 agentPresets 目录。')],
    }
  }
  const rows = items.map((item) => {
    const row = {
      k: textOf(item, 'label', 'name', 'id') || '未命名',
      v: textOf(item, 'description', 'detail', 'note'),
    }
    if (item.default === true) row.badge = '默认'
    return row
  })
  return { title: 'Agent 预设', subtitle: `共 ${rows.length} 项`, blocks: [card({ rows })] }
}

function rulesPanel({ bridge }) {
  const presetService = service(bridge, 'permissionPresets')
  const catalog = typeof presetService.catalog === 'function' ? presetService.catalog() : presetService
  const items = catalog?.presets ?? (Array.isArray(catalog) ? catalog : [])
  const rows = items.map((item) => {
    const row = {
      k: textOf(item, 'label', 'name', 'id') || '未命名',
      v: textOf(item, 'description', 'detail', 'note'),
    }
    if (item.default === true) row.badge = '默认'
    return row
  })
  return {
    title: '规则设定',
    subtitle: '权限预设（只读）',
    blocks: [
      card({
        title: `预设（${rows.length}）`,
        text: '新建会话时选用的权限档位。',
        rows,
        note: '自定义规则请在 DSH 自己的设置页里改。',
      }),
    ],
  }
}

PANELS.devctl = devctlPanel
PANELS.general = generalPanel
PANELS.models = modelsPanel
PANELS.plugins = pluginsPanel
PANELS.presets = presetsPanel
PANELS.rules = rulesPanel
PANELS.market = () => ({
  title: '插件市场',
  subtitle: '',
  blocks: [
    card({
      title: '插件市场',
      text: '安装 / 卸载插件请在 DSH 的插件市场页面里操作。',
      note: 'Host 侧未暴露市场目录，所以这里只给个入口说明。',
    }),
  ],
})
PANELS.cards = () => ({
  title: '侧边卡片',
  subtitle: '',
  blocks: [
    card({
      title: '侧边卡片',
      text: '侧边卡片是 Web 客户端自己的界面配置。',
      note: '在 DSH 网页窗口里调整即可，手机端不做复刻。',
    }),
  ],
})

function unknownPanel(id) {
  return {
    title: id,
    subtitle: '',
    blocks: [
      card({
        title: '暂未接入',
        text: '这个分区还没有 Host 侧数据源。',
        note: '可以先用上一页的「网页窗口」打开 DSH 原始设置页。',
      }),
    ],
  }
}

/* ------------------------------------------------------------ 三个方法 */

/**
 * dispatch() 用：方法名 → 处理函数。
 * 处理函数拿到 { bridge, params, statusPayload }，返回值就是协议 result。
 */
export const settingsMethods = {
  /** 侧边栏分区表。Host 说了算；手机端拿不到就用内置表。 */
  'settings.sections': () => ({
    sections: [...SECTIONS].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).map((section) => ({ ...section })),
  }),

  /** 某个分区的正文。未知 id 返回说明卡，不抛错（手机端会原样渲染）。 */
  'settings.panel': async ({ bridge, params, statusPayload }) => {
    const id = text(params?.id).trim()
    if (!id) throw new SettingsError('bad-request', 'settings.panel requires params.id')
    if (typeof statusPayload !== 'function') {
      throw new SettingsError('internal', 'settings.panel requires the statusPayload helper')
    }
    const build = PANELS[id]
    const panel = build ? await build({ bridge, params, statusPayload }) : unknownPanel(id)
    if (!panel.title) panel.title = id
    if (!Array.isArray(panel.blocks)) panel.blocks = []
    return { panel }
  },

  /** 分区里的按钮。copy / reveal-token / hide-token 由手机端本地处理，不会走到这里。 */
  'settings.action': async ({ bridge, params }) => {
    const id = text(params?.id).trim()
    const action = text(params?.action).trim()
    if (!action) throw new SettingsError('bad-request', 'settings.action requires params.action')

    if (action === 'toggle-token') {
      const next = TOKEN_REVEAL.get(bridge) !== true
      TOKEN_REVEAL.set(bridge, next)
      return { message: next ? '已显示令牌' : '已隐藏令牌', reload: true }
    }
    if (action === 'refresh') {
      return { message: '已刷新', reload: true }
    }
    return { message: `Host 未实现该操作：${id}/${action}` }
  },
}
