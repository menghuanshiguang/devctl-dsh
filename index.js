/**
 * Host half of `devctl-dsh`.
 *
 * Opens one token-authenticated JSON-Lines TCP port so another device can drive
 * this DSH from its own CLI: Workspaces, Sessions, prompts (text and images),
 * model and permission selection, live output, and cancellation. Every capability
 * is delegated to the live Host services (`sessionController`,
 * `workspaceController`, `permissionPresets`); this plugin owns transport,
 * authentication, and wire shaping only.
 */
import { createServer } from 'node:net'
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { readFile, stat } from 'node:fs/promises'
import { homedir, hostname, networkInterfaces } from 'node:os'
import { basename, dirname, extname, isAbsolute, join, normalize, resolve, sep } from 'node:path'
import { svg as qrSvg } from './qr.js'
import { DEFAULT_WEB_PORT, installRemoteWeb } from './remote-web.js'

const VERSION = '1.4.0'
const PROTOCOL = 1
/** This package's name: the loader row id, the client bundle id, and the graph key. */
const PACKAGE_NAME = 'devctl-dsh'
/** Settings-page endpoints, served from the Host web server so the page stays same-origin. */
const STATUS_PATH = '/devctl-dsh/status'
const QR_PATH = '/devctl-dsh/qr.svg'
/** A disconnected peer keeps its row in the settings list for this long. */
const PEER_LINGER_MS = 5 * 60_000
/** Prompt image parts accept exactly these media types. */
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
/** Where the control token is persisted, relative to `$DSH_HOME`. */
const STATE_NAME = 'devctl-dsh.json'
/** Pre-rename state file, read once so already-paired clients keep working. */
const LEGACY_STATE_NAME = 'dsh-remote.json'
const MAX_LINE_BYTES = 12 * 1024 * 1024
const DEFAULT_HOST = '0.0.0.0'
const DEFAULT_PORT = 7788
/** Every short control call is bounded; `follow` is the only unbounded read. */
const CALL_TIMEOUT_MS = 30_000
const TAIL_TIMEOUT_MS = 20_000
const TRUNCATE_CHARS = 4_000
/** Queue previews stay short: the phone shows one or two lines per row. */
const QUEUE_TEXT_CHARS = 1_200
/**
 * Re-encode targets for `sessions.image`. The Host stores a normalized object already, but a
 * full-size photo still base64s past the transport frame, so ask the attachment service for a
 * bounded variant instead of shipping raw bytes.
 */
const IMAGE_TARGETS = {
  thumb: { width: 704, height: 704, maxBytes: 320_000 },
  full: { width: 2048, height: 2048, maxBytes: 1_600_000 },
}
/** One `sessions.image` reply must stay well under MAX_LINE_BYTES. */
const MAX_IMAGE_BASE64 = 3_200_000

/** A rejection the CLI receives as `{ok:false, error:{code,message}}`. */
class BridgeError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'BridgeError'
    this.code = code
    // HMR can leave two copies of this class alive, so identity is not reliable.
    this.isBridgeError = true
  }
}

/** Best-effort text for any thrown value (Host errors may cross a realm boundary). */
function errorText(error) {
  if (typeof error === 'string') return error
  if (error instanceof Error) return error.message
  if (error !== null && typeof error === 'object') {
    const parts = [error.name, error.message, error.code].filter((part) => typeof part === 'string' && part.length > 0)
    if (parts.length > 0) return parts.join(': ')
    try {
      return JSON.stringify(error)
    } catch {
      return Object.prototype.toString.call(error)
    }
  }
  return String(error)
}

// Only the Session controller is a hard dependency; Workspaces and permission
// presets are read through `ctx.get` so the port still comes up without them.
export const inject = ['sessionController']

export function apply(ctx, config) {
  const settings = config ?? {}
  const host = typeof settings.host === 'string' && settings.host.length > 0 ? settings.host : DEFAULT_HOST
  const port = Number.isInteger(settings.port) && settings.port >= 0 && settings.port <= 65535 ? settings.port : DEFAULT_PORT
  // LAN window onto the Host web UI, so a paired phone can show the real
  // settings page (and with it every plugin's own section) without a browser
  // token. `web.port = 0` turns it off; `web.target` pins the loopback origin.
  const webPort = Number.isInteger(settings.web?.port) ? settings.web.port : DEFAULT_WEB_PORT
  const webTarget = typeof settings.web?.target === 'string' ? settings.web.target : ''

  const stateDir = process.env.DSH_HOME && process.env.DSH_HOME.length > 0 ? process.env.DSH_HOME : join(homedir(), '.dsh')
  const stateFile = join(stateDir, STATE_NAME)
  const legacyStateFile = join(stateDir, LEGACY_STATE_NAME)
  const token = resolveToken(settings, stateFile, legacyStateFile)
  // 本地模式（harness 就跑在这台手机上）：回环连接免令牌。默认关，patch 里显式打开。
  const allowLocalNoAuth = settings.allowLocalNoAuth === true

  const connections = new Set()
  /**
   * Every peer that has authenticated once, keyed by connection id, so the
   * settings page can still list a device that dropped off moments ago.
   */
  const peers = new Map()
  /** Filled from `server.address()` once the port is bound, for the settings page. */
  const bound = { host, port }
  const startedAt = Date.now()
  const log = (message) => {
    try {
      ctx.logger?.info?.(`[devctl-dsh] ${message}`)
    } catch {
      /* logging must never take the port down */
    }
  }
  const warn = (error) => {
    try {
      ctx.logger?.warn?.(error)
    } catch {
      /* logging must never take the port down */
    }
  }

  const bridge = {
    ctx,
    token,
    allowLocalNoAuth,
    stateFile,
    startedAt,
    connections,
    peers,
    bound,
    /** `serveLine` is module-level, so the bridge is what carries logging to it. */
    log,
    warn,
    isAuthenticated: (candidate) => {
      if (typeof candidate !== 'string') return false
      const offered = Buffer.from(candidate, 'utf8')
      const expected = Buffer.from(token, 'utf8')
      return offered.length === expected.length && timingSafeEqual(offered, expected)
    },
  }

  const server = createServer((socket) => {
    const connection = {
      id: randomUUID(),
      socket,
      authenticated: false,
      client: null,
      device: null,
      commands: 0,
      connectedAt: Date.now(),
      lastSeenAt: Date.now(),
      peer: `${socket.remoteAddress ?? 'unknown'}:${socket.remotePort ?? 0}`,
      // 回环连接（同一台手机上的客户端）在 allowLocalNoAuth 打开时免令牌
      loopback: isLoopbackAddress(socket.remoteAddress),
      watches: new Map(),
      buffer: '',
      send(payload) {
        if (socket.destroyed) return
        try {
          socket.write(`${JSON.stringify(payload)}\n`)
        } catch (error) {
          warn(error)
        }
      },
      dispose() {
        for (const controller of connection.watches.values()) controller.abort()
        connection.watches.clear()
        if (!socket.destroyed) socket.destroy()
      },
    }
    connections.add(connection)
    socket.setEncoding('utf8')
    socket.setNoDelay(true)
    socket.on('data', (chunk) => {
      connection.buffer += chunk
      if (connection.buffer.length > MAX_LINE_BYTES) {
        connection.send({ ok: false, error: { code: 'frame-too-large', message: 'request frame exceeded 4 MiB' } })
        connection.dispose()
        return
      }
      let index = connection.buffer.indexOf('\n')
      while (index >= 0) {
        const line = connection.buffer.slice(0, index).trim()
        connection.buffer = connection.buffer.slice(index + 1)
        if (line.length > 0) {
          // A rejected serveLine must never escape as an unhandled rejection:
          // Node turns that into a process-level throw and the Host dies with it.
          serveLine(bridge, connection, line).catch((error) => {
            warn(error)
            connection.dispose()
          })
        }
        index = connection.buffer.indexOf('\n')
      }
    })
    socket.on('error', (error) => {
      warn(error)
      connection.dispose()
    })
    socket.on('close', () => {
      connection.dispose()
      connections.delete(connection)
      if (connection.authenticated) {
        connection.disconnectedAt = Date.now()
        peers.set(connection.id, connection)
      }
    })
  })

  ctx.effect(() => {
    server.on('error', warn)
    server.listen(port, host, () => {
      const address = server.address()
      const actualPort = address && typeof address === 'object' ? address.port : port
      bound.port = actualPort
      writeState(stateFile, { token, host, port: actualPort, version: VERSION, startedAt })
      log(`listening on ${host}:${actualPort}; control token in ${stateFile}`)
    })
    return () =>
      new Promise((resolve) => {
        for (const connection of connections) connection.dispose()
        connections.clear()
        peers.clear()
        server.close(() => resolve())
      })
  }, 'devctl-dsh.listen')

  ctx.effect(() => {
    const lanWeb = installRemoteWeb({
      port: webPort,
      token,
      target: webTarget,
      statusPath: STATUS_PATH,
      log,
      warn,
    })
    if (!lanWeb) return () => {}
    // 手机端从这里问「网页窗口在几号端口」，不用再猜。
    bridge.web = lanWeb.info
    return () => {
      bridge.web = null
      lanWeb.dispose()
    }
  }, 'devctl-dsh.web-window')

  installSettingsRoutes(ctx, bridge)
  refreshClientBundleGraph(ctx)
}

/**
 * The Web client graph caches a "not a client package" verdict per loader
 * specifier until the host process restarts. A host that was already running
 * when this package gained its `dsh.client` declaration keeps that stale
 * negative verdict, so the settings section stays invisible until the next
 * restart. Clear it once and let the graph reconcile this package; on a fresh
 * host the verdict is already positive and this is a no-op.
 */
function refreshClientBundleGraph(ctx) {
  if (typeof ctx?.inject !== 'function') return
  ctx.inject(['clientModules'], (host) => {
    const modules = host?.clientModules
    if (typeof modules?.clientPath !== 'function') return
    try {
      if (modules.clientPath(PACKAGE_NAME) !== undefined) return
      let matched = false
      for (const entry of host.loader?.entries?.() ?? []) {
        if (entry?.options?.name !== PACKAGE_NAME) continue
        const baseUrl = entry.parent?.tree?.ctx?.baseUrl
        if (typeof baseUrl !== 'string') continue
        // The graph keys its verdicts by `<baseUrl>\0<loader name>`.
        modules.pkgMeta?.delete?.(modules.sourceKey(PACKAGE_NAME, baseUrl))
        matched = true
      }
      if (!matched) return
      modules.dirty?.add?.(PACKAGE_NAME)
      modules.flush?.((error) => host.logger?.warn?.(error))
      host.logger?.info?.('[devctl-dsh] reconciled the Web client bundle graph')
    } catch (error) {
      host.logger?.warn?.(error)
    }
  })
}

/** Read the configured token, the one a previous run persisted, or mint a new one. */
function resolveToken(settings, stateFile, legacyStateFile) {
  if (typeof settings.token === 'string' && settings.token.length > 0) return settings.token
  for (const candidate of [stateFile, legacyStateFile]) {
    if (!candidate) continue
    try {
      const saved = JSON.parse(readFileSync(candidate, 'utf8'))
      if (saved && typeof saved.token === 'string' && saved.token.length > 0) return saved.token
    } catch {
      /* first run, unreadable file, or foreign content: try the next name */
    }
  }
  return randomBytes(24).toString('hex')
}

function writeState(stateFile, value) {
  try {
    mkdirSync(dirname(stateFile), { recursive: true })
    writeFileSync(stateFile, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
  } catch {
    /* a read-only home only costs the CLI its zero-config path */
  }
}

/** Parse one request frame, enforce authentication, and answer exactly once. */
async function serveLine(bridge, connection, line) {
  let frame
  try {
    frame = JSON.parse(line)
  } catch {
    connection.send({ ok: false, error: { code: 'bad-json', message: 'frame is not valid JSON' } })
    return
  }
  if (!frame || typeof frame !== 'object') {
    connection.send({ ok: false, error: { code: 'bad-frame', message: 'frame must be a JSON object' } })
    return
  }
  const id = frame.id ?? null
  const method = frame.method
  const params = frame.params && typeof frame.params === 'object' ? frame.params : {}

  if (method === 'hello') {
    if (!bridge.isAuthenticated(params.token)) {
      // 本地模式：harness 跑在同一台设备上，回环连接不必再拿令牌（显式开开关才生效）
      if (!(bridge.allowLocalNoAuth === true && connection.loopback === true)) {
        connection.send({ id, ok: false, error: { code: 'unauthorized', message: 'token rejected' } })
        connection.dispose()
        return
      }
      connection.localNoAuth = true
      bridge.log?.(`loopback peer accepted without token: ${connection.peer}`)
    }
    connection.authenticated = true
    connection.client = typeof params.client === 'string' ? params.client : 'unknown'
    connection.device = readDevice(params.device, connection)
    connection.lastSeenAt = Date.now()
    bridge.peers?.set(connection.id, connection)
    connection.send({
      id,
      ok: true,
      result: {
        server: 'devctl-dsh',
        protocol: PROTOCOL,
        version: VERSION,
        host: hostname(),
        platform: process.platform,
        time: Date.now(),
        localNoAuth: bridge.allowLocalNoAuth === true,
        // 手机端（clients/dshconsole）的「设置」面板直接吃 DSH 自己的网页界面，
        // 这里告诉它窗口在不在、在几号端口，省得客户端猜端口或探测。
        web: webPayload(bridge),
      },
    })
    return
  }

  if (!connection.authenticated) {
    connection.send({ id, ok: false, error: { code: 'unauthorized', message: 'send hello with a valid token first' } })
    connection.dispose()
    return
  }
  if (typeof method !== 'string' || method.length === 0) {
    connection.send({ id, ok: false, error: { code: 'bad-request', message: 'method is required' } })
    return
  }
  connection.lastSeenAt = Date.now()
  connection.commands += 1

  try {
    const result = await dispatch(bridge, connection, method, params)
    connection.send({ id, ok: true, result })
  } catch (error) {
    const code =
      (error?.isBridgeError === true || error?.name === 'BridgeError') && typeof error?.code === 'string'
        ? error.code
        : 'internal'
    const message = errorText(error)
    if (code === 'internal') {
      bridge.warn?.(error)
      bridge.log?.(`internal failure in ${method}: ${message}`)
    }
    connection.send({ id, ok: false, error: { code, message } })
  }
}

async function dispatch(bridge, connection, method, params) {
  const controller = bridge.ctx.sessionController
  if (!controller) throw new BridgeError('unavailable', 'sessionController is not composed in this Host')

  switch (method) {
    case 'ping':
      return { pong: true, time: Date.now(), uptimeMs: Date.now() - bridge.startedAt }

    case 'peers.list':
      return { items: listPeers(bridge).map(publicPeer) }

    case 'sessions.list': {
      const value = await controller.list({}, timeoutSignal(CALL_TIMEOUT_MS))
      return { items: (value?.items ?? []).map(publicSummary) }
    }

    // 单个会话的实时状态：客户端拿它决定「可发送 / 停止发送」，不要自己猜回合跑没跑完。
    // running=false 但队列里还有活的情况，controller 自己会置回 true。
    case 'sessions.state': {
      const id = typeof params.sessionId === 'string' ? params.sessionId : ''
      if (id.length === 0) throw new BridgeError('bad-request', '缺少 sessionId')
      const value = await controller.list({}, timeoutSignal(CALL_TIMEOUT_MS))
      const found = (value?.items ?? []).find((one) => (one?.sessionId ?? one?.id) === id)
      if (found === undefined) throw new BridgeError('not-found', `没有这个会话：${id}`)
      return { state: publicSummary(found) }
    }

    case 'sessions.create':
      return controller.create(
        {
          ...(typeof params.workspaceId === 'string' && params.workspaceId.length > 0
            ? { workspaceId: params.workspaceId }
            : {}),
          ...(typeof params.cwd === 'string' && params.cwd.length > 0 ? { cwd: params.cwd } : {}),
          ...(typeof params.agentPreset === 'string' && params.agentPreset.length > 0
            ? { agentPreset: params.agentPreset }
            : {}),
        },
        timeoutSignal(CALL_TIMEOUT_MS),
      )

    case 'sessions.prompt': {
      const text = typeof params.text === 'string' ? params.text : ''
      const images = parseImages(params.images)
      if (text.trim().length === 0 && images.length === 0) {
        throw new BridgeError('bad-request', 'text or at least one image is required')
      }
      requireSessionId(params)
      const content = []
      if (text.length > 0) content.push({ type: 'text', text })
      for (const image of images) content.push(image)
      // 客户端拿 requestId 对账：排队中的消息要能被撤回/插话，得先能认出它来。
      // 允许客户端自己铸（harness 的 SessionRequestId 就是客户端铸的），这样它发出去前就知道自己是谁。
      const requestId = typeof params.requestId === 'string' && params.requestId.length > 0
        ? params.requestId
        : randomUUID()
      const value = await controller.prompt(
        {
          requestId,
          sessionId: params.sessionId,
          mode: params.mode === 'steer' ? 'steer' : 'queue',
          content,
        },
        timeoutSignal(CALL_TIMEOUT_MS),
      )
      return { ...(value ?? {}), requestId }
    }

    /**
     * 取一张会话里出现过的图片。客户端只从记录里拿到元数据（attachmentId/尺寸），
     * 真要显示时按需取；`size` 选档：thumb 走 704px/320KB 重编码，full 走 2048px/1.6MB。
     */
    case 'sessions.image': {
      requireSessionId(params)
      const ref = imageRef(params.attachment ?? params, params.attachmentId ?? params.id)
      const size = params.size === 'full' ? 'full' : 'thumb'
      return readImage(bridge, params.sessionId, ref, size)
    }

    /** 改一条还在排队里的消息：编辑（只支持纯文本）/ 撤下 / 立刻插话。 */
    case 'sessions.queue': {
      requireSessionId(params)
      const itemId = typeof params.itemId === 'string' ? params.itemId : ''
      if (itemId.length === 0) throw new BridgeError('bad-request', 'itemId is required')
      const action = queueAction(params.action)
      const value = await controller.updateQueue({ sessionId: params.sessionId, itemId, action })
      return { accepted: value?.accepted === true, itemId, action: action.kind }
    }

    /**
     * 读会话工作区里的一个图片文件。
     * 手机端拿不到 PC 的硬盘：agent 把图写盘后回复里只留一个路径时，客户端靠这条口子取字节。
     * 读到的字节先落成附件引用再回传 —— 之后照常走 `sessions.image` 取缩略图/原图。
     */
    case 'sessions.file': {
      requireSessionId(params)
      const rawPath = typeof params.path === 'string' ? params.path : ''
      if (rawPath.length === 0) throw new BridgeError('bad-request', 'path is required')
      return readWorkspaceImage(bridge, params.sessionId, rawPath)
    }

    /** 信箱快照：手机重新进会话时先要一份当前排队内容，之后靠 inbox 事件增量维护。 */
    case 'sessions.inbox': {
      requireSessionId(params)
      const session = await sessionOf(bridge, params.sessionId)
      const projections = requireService(bridge, 'sessionProjections')
      const snapshot = projections.snapshot(session, ['inbox'])
      const state = snapshot?.values?.inbox ?? {}
      return {
        sessionId: params.sessionId,
        asOfSeq: typeof snapshot?.asOfSeq === 'number' ? snapshot.asOfSeq : undefined,
        nextTurn: (Array.isArray(state['next-turn']) ? state['next-turn'] : []).map(inboxItem),
        nextStep: (Array.isArray(state['next-step']) ? state['next-step'] : []).map(inboxItem),
      }
    }

    case 'sessions.cancel':
      requireSessionId(params)
      return controller.cancel({ sessionId: params.sessionId })

    case 'sessions.rename':
      requireSessionId(params)
      return controller.rename({ sessionId: params.sessionId, title: String(params.title ?? '') })

    case 'sessions.search': {
      try {
        return await controller.search({ query: String(params.query ?? '') }, timeoutSignal(CALL_TIMEOUT_MS))
      } catch (error) {
        // Deployments may disable the session-query index entirely; give that its own code.
        const detail = errorText(error)
        if (/session search is disabled|SessionQuery/i.test(detail)) {
          throw new BridgeError('search-disabled', `session search is unavailable on this host: ${detail}`)
        }
        throw error
      }
    }

    case 'sessions.tail':
      requireSessionId(params)
      return readTail(controller, params)

    case 'sessions.watch':
      requireSessionId(params)
      return startWatch(bridge, connection, params)

    case 'sessions.unwatch':
      requireSessionId(params)
      stopWatch(connection, params.sessionId)
      return { stopped: true }

    case 'workspaces.list': {
      const value = await readWorkspaces(requireService(bridge, 'workspaceController'))
      return {
        items: (value?.items ?? []).map(publicWorkspace),
        archivedSessionIds: value?.archivedSessionIds ?? [],
        pinnedSessionIds: value?.pinnedSessionIds ?? [],
      }
    }

    case 'workspaces.create': {
      const path = typeof params.path === 'string' ? params.path.trim() : ''
      if (path.length === 0) throw new BridgeError('bad-request', 'path is required')
      return requireService(bridge, 'workspaceController').create({ path }, timeoutSignal(CALL_TIMEOUT_MS))
    }

    case 'workspaces.rename': {
      requireWorkspaceId(params)
      const title = typeof params.title === 'string' ? params.title.trim() : ''
      if (title.length === 0) throw new BridgeError('bad-request', 'title is required')
      return requireService(bridge, 'workspaceController').rename(
        { workspaceId: params.workspaceId, title },
        timeoutSignal(CALL_TIMEOUT_MS),
      )
    }

    case 'workspaces.delete':
      requireWorkspaceId(params)
      return requireService(bridge, 'workspaceController').delete(
        { workspaceId: params.workspaceId },
        timeoutSignal(CALL_TIMEOUT_MS),
      )

    case 'permissions.catalog':
      return requireService(bridge, 'permissionPresets').catalog()

    case 'permissions.current': {
      requireSessionId(params)
      const presets = requireService(bridge, 'permissionPresets')
      const session = await sessionOf(bridge, params.sessionId)
      return { sessionId: params.sessionId, preset: presets.current(session) }
    }

    case 'permissions.set': {
      requireSessionId(params)
      const preset = typeof params.preset === 'string' ? params.preset.trim() : ''
      if (preset.length === 0) throw new BridgeError('bad-request', 'preset is required')
      const presets = requireService(bridge, 'permissionPresets')
      const session = await sessionOf(bridge, params.sessionId)
      try {
        presets.set(session, preset)
      } catch (error) {
        throw new BridgeError('bad-request', errorText(error))
      }
      return { sessionId: params.sessionId, preset: presets.current(session) }
    }

    case 'models.catalog':
      return controller.modelCatalog()

    case 'models.select':
      requireSessionId(params)
      return controller.selectModel({
        sessionId: params.sessionId,
        provider: String(params.provider ?? ''),
        model: String(params.model ?? ''),
        ...(typeof params.reasoningEffort === 'string' && params.reasoningEffort.length > 0
          ? { reasoningEffort: params.reasoningEffort }
          : {}),
      })

    default:
      throw new BridgeError('unknown-method', `unknown method: ${method}`)
  }
}

/**
 * Peer identity is advisory: an older CLI sends only `client`, and a handshake
 * must never fail because of it.
 */
function readDevice(raw, connection) {
  const source = raw !== null && typeof raw === 'object' ? raw : {}
  const text = (value) => (typeof value === 'string' && value.trim().length > 0 ? value.trim() : '')
  return {
    name: text(source.name) || connection.client || 'unknown',
    platform: text(source.platform),
    version: text(source.version),
    cwd: text(source.cwd),
  }
}

/** Live connections plus peers that disconnected within the linger window, most recent first. */
function listPeers(bridge) {
  const now = Date.now()
  const out = []
  for (const [id, connection] of bridge.peers ?? []) {
    const live = bridge.connections?.has(connection) === true
    if (!live && now - (connection.disconnectedAt ?? 0) > PEER_LINGER_MS) {
      bridge.peers.delete(id)
      continue
    }
    out.push({ connection, live })
  }
  out.sort((a, b) => b.connection.lastSeenAt - a.connection.lastSeenAt)
  return out
}

function publicPeer({ connection, live }) {
  const device = connection.device ?? {}
  return {
    name: device.name || connection.client || 'unknown',
    platform: device.platform || '',
    version: device.version || '',
    cwd: device.cwd || '',
    address: connection.peer,
    connectedAt: connection.connectedAt,
    lastSeenAt: connection.lastSeenAt,
    commands: connection.commands ?? 0,
    live,
  }
}

function localAddresses() {
  const out = []
  try {
    for (const [name, entries] of Object.entries(networkInterfaces())) {
      for (const entry of entries ?? []) {
        if (entry.family !== 'IPv4' || entry.internal) continue
        out.push({ name, address: entry.address })
      }
    }
  } catch {
    /* an address list is a convenience, never a reason to fail a request */
  }
  return out
}

/** Prefer a private LAN address: that is the one another device can actually reach. */
function firstLanAddress() {
  const all = localAddresses()
  const isPrivate = (address) =>
    /^10\./.test(address) || /^192\.168\./.test(address) || /^172\.(1[6-9]|2\d|3[01])\./.test(address)
  return (all.find((entry) => isPrivate(entry.address)) ?? all[0])?.address ?? '127.0.0.1'
}

/** The one line a user pastes into `dshctl` on the other device. */
function pairingCommand(ip, port, token) {
  return `dshctl add home ${ip}:${port} --token ${token}`
}

function statusPayload(bridge) {
  const addresses = localAddresses()
  const ip = firstLanAddress()
  const port = bridge.bound?.port ?? DEFAULT_PORT
  const peers = listPeers(bridge).map(publicPeer)
  const token = bridge.token ?? ''
  return {
    ok: true,
    server: 'devctl-dsh',
    version: VERSION,
    protocol: PROTOCOL,
    listenHost: bridge.bound?.host ?? DEFAULT_HOST,
    port,
    ip,
    addresses: addresses.map((entry) => entry.address),
    hostname: hostname(),
    token,
    command: pairingCommand(ip, port, token),
    qrPath: QR_PATH,
    startedAt: bridge.startedAt,
    uptimeMs: Date.now() - bridge.startedAt,
    peers,
    livePeers: peers.filter((peer) => peer.live).length,
    web: webPayload(bridge),
  }
}

/** Colours land in an SVG attribute, so only strict literal hex passes. */
function normaliseColor(value, fallback) {
  return typeof value === 'string' && /^#[0-9a-fA-F]{3,8}$/.test(value) ? value : fallback
}

/**
 * The one place the `web` block is shaped: the phone opens `url` verbatim when
 * `ready` is true, and reads `reason`/`seen` when it is not, so it never has to
 * guess a port or sweep loopback itself.
 */
function webPayload(bridge) {
  const web = bridge?.web
  if (!web) return null
  return {
    port: web.port ?? null,
    ready: !!web.ready,
    reason: web.reason ?? '',
    target: web.origin || bridge.webOrigin || null,
    url: web.url || null,
    source: web.reason === "host-service" ? "host" : web.reason === "configured" ? "config" : web.reason === "host-web-default" ? "default" : web.ready ? "discovered" : "none",
    seen: Array.isArray(web.seen) ? web.seen.slice(0, 8) : [],
  }
}

function sendJson(response, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.length),
    'cache-control': 'no-store',
  })
  response.end(body)
}

/**
 * These routes hand out the access token, so they have to clear the same
 * Host/Origin fence and browser cookie as `/api` — `connection` owns that
 * check and its `requestRejection` returns undefined, 401, or 403. Borrowing
 * it keeps this page exactly as strict as the surface it lives in. The
 * route only registers where that service exists; a Host composing the web
 * server without the connection carrier gets no settings page at all.
 */
/** 回环地址判定：127.0.0.0/8、::1、以及 IPv4-mapped 的 ::ffff:127.x。 */
function isLoopbackAddress(address) {
  if (typeof address !== 'string' || address.length === 0) return false
  const value = address.startsWith('::ffff:') ? address.slice(7) : address
  if (value === '::1' || value === 'localhost') return true
  return /^127\./.test(value)
}

/** Constant-time compare that never throws on a length mismatch. */
function tokenMatches(offered, expected) {
  const left = Buffer.from(String(offered ?? ''), 'utf8')
  const right = Buffer.from(String(expected ?? ''), 'utf8')
  return left.length > 0 && left.length === right.length && timingSafeEqual(left, right)
}

/**
 * Our own loopback discovery probe (see remote-web.js). The settings route sits
 * behind the Host browser-trust check, which a bare Node request can never pass —
 * so the probe presents the control token in `?probe=` and we wave it through.
 */
function isWebSelfProbe(request, bridge, statusPath) {
  if (!bridge?.token) return false
  let url
  try {
    url = new URL(request?.url ?? '/', 'http://127.0.0.1')
  } catch {
    return false
  }
  if (url.pathname !== statusPath) return false
  return tokenMatches(url.searchParams.get('probe') ?? '', bridge.token)
}

/**
 * DSH's own web server knows the port it listens on, so ask it instead of
 * sweeping the loopback range. The service shape is Host-private, so every
 * plausible accessor is tried and the keys are kept for diagnosis.
 */
function webServerPort(webServer) {
  if (!webServer || typeof webServer !== 'object') return 0
  const paths = [
    ['port'],
    ['address', 'port'],
    ['server', 'address', 'port'],
    ['httpServer', 'address', 'port'],
    ['httpServer', 'address'],
    ['url'],
    ['origin'],
    ['baseUrl'],
  ]
  for (const keys of paths) {
    let cur = webServer
    for (const key of keys) {
      if (cur == null) break
      cur = cur[key]
    }
    if (typeof cur === 'function') {
      try {
        cur = cur.call(webServer)
      } catch {
        continue
      }
    }
    const text = typeof cur === 'string' ? cur : cur && typeof cur === 'object' ? String(cur.port ?? '') : String(cur ?? '')
    const found = /(\d{2,5})/.exec(text)
    const port = found ? Number(found[1]) : 0
    if (Number.isInteger(port) && port > 0 && port <= 65535) return port
  }
  return 0
}

/** Hand the Host's own web origin to the LAN proxy as soon as we can read it. */
function publishWebServiceOrigin(webServer, bridge) {
  try {
    bridge.webServiceKeys = Object.keys(webServer ?? {}).slice(0, 24)
    const port = webServerPort(webServer)
    if (!port) return
    bridge.webOrigin = `http://127.0.0.1:${port}`
    bridge.web?.setTarget?.(bridge.webOrigin)
  } catch {
    // Reading a Host-private object must never break route installation.
  }
}

function rejectUntrustedRequest(connection, request, response) {
  let rejection
  try {
    rejection = connection.requestRejection(request) ?? undefined
  } catch (error) {
    rejection = 500
  }
  if (rejection === undefined) return false
  const text =
    rejection === 500
      ? 'devctl-dsh: the browser-trust check failed\n'
      : 'devctl-dsh: authentication required\n'
  response.writeHead(rejection, {
    'cache-control': 'no-store',
    'content-type': 'text/plain; charset=utf-8',
    'content-length': String(Buffer.byteLength(text)),
  })
  response.end(request.method === 'HEAD' ? undefined : text)
  return true
}

/**
 * The settings page is served by the Host web server, so it reads this plugin
 * through the same origin instead of reaching across the TCP port.
 */
function installSettingsRoutes(ctx, bridge) {
  if (typeof ctx?.inject !== 'function') return
  ctx.inject(['webServer', 'connection'], (host) => {
    if (typeof host?.webServer?.register !== 'function') return
    host.effect(
      () => {
        const disposers = []
        publishWebServiceOrigin(host.webServer, bridge)
        const add = (spec, label) => {
          try {
            disposers.push(host.webServer.register(spec, label))
          } catch (error) {
            bridge.warn?.(error)
          }
        }
        const guarded = (handler) => (request, response) => {
          if (isWebSelfProbe(request, bridge, STATUS_PATH)) {
            sendJson(response, 200, statusPayload(bridge))
            return
          }
          if (rejectUntrustedRequest(host.connection, request, response)) return
          handler(request, response)
        }
        add(
          {
            kind: 'exact',
            path: STATUS_PATH,
            handler: guarded((request, response) => {
              if (request.method !== 'GET' && request.method !== 'HEAD') {
                response.writeHead(405, { allow: 'GET' })
                response.end()
                return
              }
              try {
                sendJson(response, 200, statusPayload(bridge))
              } catch (error) {
                bridge.warn?.(error)
                sendJson(response, 500, { ok: false, error: errorText(error) })
              }
            }),
          },
          'devctl-dsh: settings status',
        )
        add(
          {
            kind: 'exact',
            path: QR_PATH,
            handler: guarded((request, response) => {
              if (request.method !== 'GET' && request.method !== 'HEAD') {
                response.writeHead(405, { allow: 'GET' })
                response.end()
                return
              }
              try {
                const url = new URL(request.url ?? QR_PATH, 'http://127.0.0.1')
                const status = statusPayload(bridge)
                const payload = url.searchParams.get('text') || status.command
                const body = Buffer.from(
                  qrSvg(payload, {
                    dark: normaliseColor(url.searchParams.get('dark'), '#000000'),
                    light: normaliseColor(url.searchParams.get('light'), '#ffffff'),
                    quiet: 2,
                    scale: 8,
                  }),
                  'utf8',
                )
                response.writeHead(200, {
                  'content-type': 'image/svg+xml; charset=utf-8',
                  'content-length': String(body.length),
                  'cache-control': 'no-store',
                })
                response.end(request.method === 'HEAD' ? undefined : body)
              } catch (error) {
                bridge.warn?.(error)
                response.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' })
                response.end('qr unavailable')
              }
            }),
          },
          'devctl-dsh: pairing QR',
        )
        return () => {
          for (const dispose of disposers) {
            try {
              dispose?.()
            } catch (error) {
              bridge.warn?.(error)
            }
          }
        }
      },
      'devctl-dsh: settings routes',
    )
  })
}

/** A short control call needs a cancel path; `AbortSignal.timeout` is not everywhere. */
function timeoutSignal(ms) {
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    return AbortSignal.timeout(ms)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  if (typeof timer.unref === 'function') timer.unref()
  return controller.signal
}

function requireSessionId(params) {
  if (typeof params.sessionId !== 'string' || params.sessionId.length === 0) {
    throw new BridgeError('bad-request', 'sessionId is required')
  }
}

function requireWorkspaceId(params) {
  if (typeof params.workspaceId !== 'string' || params.workspaceId.length === 0) {
    throw new BridgeError('bad-request', 'workspaceId is required')
  }
}

/** Prompt image parts carry base64 inline; the CLI sends `{mediaType, data, name?}`. */
function parseImages(raw) {
  if (raw === undefined || raw === null) return []
  if (!Array.isArray(raw)) throw new BridgeError('bad-request', 'images must be an array')
  const images = []
  for (const entry of raw) {
    if (entry === null || typeof entry !== 'object') {
      throw new BridgeError('bad-request', 'each image must be an object')
    }
    const mediaType = entry.mediaType
    if (typeof mediaType !== 'string' || !IMAGE_TYPES.has(mediaType)) {
      throw new BridgeError('bad-request', `unsupported image mediaType: ${String(mediaType)}`)
    }
    if (typeof entry.data !== 'string' || entry.data.length === 0) {
      throw new BridgeError('bad-request', 'image data is required')
    }
    images.push({
      type: 'image',
      mediaType,
      data: entry.data,
      ...(typeof entry.name === 'string' && entry.name.length > 0 ? { name: entry.name } : {}),
    })
  }
  return images
}

/**
 * 记录里的图片元数据 → 附件引用。客户端把 event 里的 `images[]` 原样回传即可；
 * attachmentId 是唯一权威字段，其余尺寸只用于回填引用（缓存/校验用）。
 */
function imageRef(input, fallbackId) {
  const source = input !== null && typeof input === 'object' ? input : {}
  const attachmentId = typeof source.attachmentId === 'string' && source.attachmentId.length > 0
    ? source.attachmentId
    : (typeof fallbackId === 'string' ? fallbackId : '')
  if (attachmentId.length === 0) throw new BridgeError('bad-request', 'attachmentId is required')
  const mediaType = typeof source.mediaType === 'string' && IMAGE_TYPES.has(source.mediaType)
    ? source.mediaType
    : 'image/png'
  const ref = {
    attachmentId,
    mediaType,
    bytes: Number.isFinite(source.bytes) && source.bytes >= 0 ? source.bytes : 0,
    width: Number.isFinite(source.width) && source.width >= 0 ? source.width : 0,
    height: Number.isFinite(source.height) && source.height >= 0 ? source.height : 0,
  }
  if (typeof source.name === 'string' && source.name.length > 0) ref.name = source.name
  return ref
}

/** 队列动作：只放行 Host 认识的那三种，别把客户端的手滑变成远端异常。 */
function queueAction(raw) {
  const action = raw !== null && typeof raw === 'object' ? raw : {}
  if (action.kind === 'remove') return { kind: 'remove' }
  if (action.kind === 'steer') return { kind: 'steer' }
  if (action.kind === 'edit') {
    const text = typeof action.text === 'string' ? action.text : ''
    if (text.trim().length === 0) throw new BridgeError('bad-request', 'edit requires non-empty text')
    return { kind: 'edit', content: [{ type: 'text', text }] }
  }
  throw new BridgeError('bad-request', `unsupported queue action: ${String(action.kind)}`)
}

/**
 * 图片字节出网。先让附件服务按目标档重编码（Host 侧归一化过的对象也可能有好几 MB），
 * 重编码不可用时退回 `sessionController.attachment`（它按会话日志授权、原样返回全尺寸）。
 */
async function readImage(bridge, sessionId, ref, size) {
  const store = requireService(bridge, 'attachments')
  const target = IMAGE_TARGETS[size]
  let data
  let meta = ref
  let variantId
  try {
    const variant = await store.readImageRequest(ref, target, timeoutSignal(CALL_TIMEOUT_MS))
    if (variant?.data !== undefined && variant.data !== null) {
      data = variant.data
      variantId = variant.variantId
      meta = {
        ...ref,
        mediaType: typeof variant.mediaType === 'string' ? variant.mediaType : ref.mediaType,
        width: Number.isFinite(variant.width) ? variant.width : ref.width,
        height: Number.isFinite(variant.height) ? variant.height : ref.height,
      }
    }
  } catch (error) {
    bridge.log?.(`sessions.image: re-encode failed, falling back to the raw attachment (${errorText(error)})`)
  }
  if (data === undefined) {
    const controller = bridge.ctx.sessionController
    const value = await controller.attachment({ sessionId, attachmentId: ref.attachmentId })
    const encoded = typeof value?.data === 'string' ? value.data : ''
    if (encoded.length === 0) throw new BridgeError('not-found', 'attachment bytes are unavailable')
    if (encoded.length > MAX_IMAGE_BASE64) {
      throw new BridgeError('image-too-large', `image is ${encoded.length} base64 chars; this build cannot preview it`)
    }
    return {
      attachmentId: ref.attachmentId,
      mediaType: typeof value?.attachment?.mediaType === 'string' ? value.attachment.mediaType : ref.mediaType,
      bytes: value?.attachment?.bytes ?? Math.floor(encoded.length * 3 / 4),
      width: value?.attachment?.width ?? ref.width,
      height: value?.attachment?.height ?? ref.height,
      size: 'raw',
      base64: encoded,
    }
  }
  const base64 = Buffer.from(data).toString('base64')
  if (base64.length > MAX_IMAGE_BASE64) {
    throw new BridgeError('image-too-large', `image is ${base64.length} base64 chars; this build cannot preview it`)
  }
  return {
    attachmentId: ref.attachmentId,
    mediaType: meta.mediaType,
    bytes: data.length,
    width: meta.width,
    height: meta.height,
    size,
    variantId,
    base64,
  }
}

/** `modelSelection` is a {lastUsed, next} projection; flatten it to "provider/model". */function modelSelectionText(projection) {
  if (typeof projection === 'string') return projection
  if (projection === null || typeof projection !== 'object') return undefined
  const chosen = projection.next ?? projection.lastUsed
  if (chosen === null || typeof chosen !== 'object') return undefined
  const provider = chosen.provider
  const model = chosen.model
  if (typeof provider === 'string' && typeof model === 'string') return `${provider}/${model}`
  return typeof model === 'string' ? model : undefined
}

/** Project a SessionSummary down to the fields a remote CLI renders. */
function publicSummary(summary) {
  const values = summary?.projections?.values ?? {}
  const title = values.title ?? values.sessionTitle
  const model = modelSelectionText(values.modelSelection) ?? values.model
  // `permissions` is a {currentValue} projection on current Hosts, a bare name on older ones.
  const permissions =
    typeof values.permissions === 'string' ? values.permissions : values.permissions?.currentValue
  return {
    sessionId: summary?.sessionId,
    running: summary?.running === true,
    blank: summary?.blank === true,
    agentAvailable: summary?.agentAvailable === true,
    updatedAt: summary?.updatedAt,
    cwd: summary?.cwd,
    origin: summary?.origin,
    parentSessionId: summary?.parentSessionId,
    ...(title === undefined ? {} : { title }),
    ...(model === undefined ? {} : { model }),
    ...(permissions === undefined ? {} : { permissions }),
  }
}

/** Project a WorkspaceView down to the fields a remote CLI renders. */
function publicWorkspace(view) {
  return {
    workspaceId: view?.workspaceId,
    path: view?.path,
    title: view?.title,
    sessionIds: Array.isArray(view?.sessionIds) ? view.sessionIds : [],
    createdAt: view?.createdAt,
    updatedAt: view?.updatedAt,
  }
}

/**
 * The workspace feed is the only way in: its first frame carries the full registry
 * baseline, so the list is read from that frame and the stream is released at once.
 */
async function readWorkspaces(controller) {
  const abort = new AbortController()
  const timer = setTimeout(() => abort.abort(), CALL_TIMEOUT_MS)
  try {
    for await (const frame of controller.follow(abort.signal)) {
      const value = frame?.value ?? {}
      return { items: value.items ?? [], archivedSessionIds: value.archivedSessionIds, pinnedSessionIds: value.pinnedSessionIds }
    }
    return {}
  } catch (error) {
    throw new BridgeError('workspace-failed', errorText(error))
  } finally {
    clearTimeout(timer)
    abort.abort()
  }
}

/** Reach a Host service that is optional for this plugin, with a sane error if absent. */
function requireService(bridge, name) {
  // `ctx.get` reads a service without declaring it in `inject`, so an absent
  // service costs one command instead of the whole plugin never activating.
  const service = typeof bridge.ctx?.get === 'function' ? bridge.ctx.get(name) : undefined
  if (!service) throw new BridgeError('unavailable', `${name} is not composed in this Host`)
  return service
}

/**
 * Session-object APIs (permission presets) need the live Session, not its id.
 * `resolveAgent` resumes a cold Session, which is the required side effect here.
 */
async function sessionOf(bridge, sessionId) {
  const controller = bridge.ctx.sessionController
  if (typeof controller.resolveAgent !== 'function') {
    throw new BridgeError('unavailable', 'sessionController.resolveAgent is unavailable in this Host')
  }
  let found
  try {
    found = await controller.resolveAgent(sessionId)
  } catch (error) {
    throw new BridgeError('session-unavailable', errorText(error))
  }
  if (found?.error) {
    throw new BridgeError(found.error.code ?? 'session-unavailable', errorText(found.error))
  }
  const session = found?.agent?.session
  if (!session) throw new BridgeError('session-unavailable', `no live Session for ${sessionId}`)
  return session
}

/** Open `follow`, keep only its opening snapshot, then release the stream. */
async function readTail(controller, params) {
  const sessionId = params.sessionId
  const limit = Number.isInteger(params.limit) && params.limit > 0 ? Math.min(params.limit, 500) : 40
  const controllerAbort = new AbortController()
  const timer = setTimeout(() => controllerAbort.abort(), TAIL_TIMEOUT_MS)
  try {
    const iterable = controller.follow(
      { address: { kind: 'session', sessionId }, maxMessages: limit },
      controllerAbort.signal,
    )
    for await (const frame of iterable) {
      if (frame?.type === 'snapshot') {
        return {
          sessionId,
          cursor: frame.cursor,
          hasMore: frame.hasMore === true,
          records: (frame.records ?? []).map(describeRecord),
        }
      }
      return { sessionId, cursor: null, hasMore: false, records: [] }
    }
    return { sessionId, cursor: null, hasMore: false, records: [] }
  } catch (error) {
    throw new BridgeError('follow-failed', errorText(error))
  } finally {
    clearTimeout(timer)
    controllerAbort.abort()
  }
}

function stopWatch(connection, sessionId) {
  const active = connection.watches.get(sessionId)
  if (active) {
    active.abort()
    connection.watches.delete(sessionId)
  }
}

/** Durably follow one Session and push every frame as an unsolicited event. */
function startWatch(bridge, connection, params) {
  const sessionId = params.sessionId
  stopWatch(connection, sessionId)
  const controllerAbort = new AbortController()
  connection.watches.set(sessionId, controllerAbort)
  connection.send({ evt: 'watch-start', data: { sessionId } })

  void (async () => {
    try {
      const iterable = bridge.ctx.sessionController.follow(
        { address: { kind: 'session', sessionId }, assistantStream: true },
        controllerAbort.signal,
      )
      for await (const frame of iterable) {
        if (controllerAbort.signal.aborted) break
        pushFollowFrame(connection, sessionId, frame)
      }
      if (!controllerAbort.signal.aborted) {
        connection.send({ evt: 'watch-end', data: { sessionId, reason: 'closed' } })
      }
    } catch (error) {
      if (!controllerAbort.signal.aborted) {
        connection.send({
          evt: 'watch-end',
          data: { sessionId, reason: 'error', message: errorText(error) },
        })
      }
    } finally {
      if (connection.watches.get(sessionId) === controllerAbort) connection.watches.delete(sessionId)
    }
  })()

  return { watching: sessionId }
}

function pushFollowFrame(connection, sessionId, frame) {
  if (!frame || typeof frame !== 'object') return
  switch (frame.type) {
    case 'snapshot':
      connection.send({
        evt: 'snapshot',
        data: {
          sessionId,
          cursor: frame.cursor,
          hasMore: frame.hasMore === true,
          records: (frame.records ?? []).map(describeRecord),
        },
      })
      return
    case 'event':
      connection.send({ evt: 'event', data: { sessionId, ...describeEvent(frame.event) } })
      return
    case 'assistant-stream': {
      const inner = frame.frame
      const chunk = inner?.chunk
      if (inner?.type !== 'chunk' || !chunk) return
      const where = { sessionId, turn: inner.turn, step: inner.step }
      if ((chunk.type === 'text-delta' || chunk.type === 'reasoning-delta') && typeof chunk.text === 'string') {
        connection.send({ evt: 'delta', data: { ...where, text: chunk.text, reasoning: chunk.type === 'reasoning-delta' } })
        return
      }
      // 工具参数是流式长出来的：先按 callId 报个空壳，参数边到边补，客户端才能显示「准备中」
      if (chunk.type === 'tool-call-delta') {
        connection.send({
          evt: 'tool-delta',
          data: { ...where, callId: chunk.id, index: chunk.index, name: chunk.name, text: chunk.argumentsDelta ?? '' },
        })
      }
      return
    }
    default:
      return
  }
}

function describeRecord(record) {
  if (record?.type === 'event') return describeEvent(record.event)
  return { kind: 'unknown' }
}

/** Flatten one durable Session event into the shape a terminal renders. */
function describeEvent(event) {
  const data = event?.data ?? {}
  const seq = event?.seq
  switch (event?.type) {
    case 'user/message':
      return {
        kind: 'user',
        seq,
        turn: data.turn,
        id: typeof data.id === 'string' ? data.id : undefined,
        rpcId: typeof data.source?.rpcId === 'string' ? data.source.rpcId : undefined,
        text: textOf(data.content),
        images: imagesOf(data.content),
      }
    case 'assistant/message':
      return {
        kind: 'assistant',
        seq,
        turn: data.turn,
        step: data.step,
        text: textOf(data.message?.content),
        reasoning: reasoningOf(data.message?.content),
        calls: callsOf(data.message?.content),
        images: imagesOf(data.message?.content),
        stream: typeof data.stream === 'string' ? data.stream : undefined,
        interrupted: data.interrupted === true,
      }
    case 'tool/call':
      return {
        kind: 'tool-call',
        seq,
        turn: data.turn,
        step: data.step,
        callId: data.callId,
        name: data.name,
        arguments: truncate(typeof data.arguments === 'string' ? data.arguments : ''),
      }
    case 'tool/result':
      return {
        kind: 'tool-result',
        seq,
        turn: data.turn,
        step: data.step,
        callId: data.message?.source?.callId ?? data.callId,
        text: truncate(textOf(data.message?.content)),
        images: imagesOf(data.message?.content),
        error: data.error ? { name: data.error.name, code: data.error.code, reason: data.error.reason } : undefined,
      }
    case 'agent/inbox/spliced':
      return {
        kind: 'inbox',
        seq,
        target: typeof data.target === 'string' ? data.target : '',
        start: typeof data.start === 'number' ? data.start : 0,
        removedCount: typeof data.removedCount === 'number' ? data.removedCount : 0,
        outcome: typeof data.outcome === 'string' ? data.outcome : undefined,
        inserted: Array.isArray(data.inserted) ? data.inserted.map(inboxItem) : [],
      }
    case 'turn/start':
      return { kind: 'turn-start', seq, turn: data.turn }
    case 'turn/end':
      return { kind: 'turn-end', seq, turn: data.turn, reason: turnReason(data.reason) }
    default:
      return { kind: 'event', seq, type: event?.type }
  }
}

function textOf(content) {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  const parts = []
  for (const block of content) {
    if (block && block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join('')
}

/**
 * 图片块 → 手机端要的元数据。只认已落库的附件引用（`agent/inbox/spliced` 与 user/message
 * 里的图片在进信箱前就已经过 admission 换成引用），内联 base64 不进帧——几 MB 的 data
 * 会让每一条记录都变成巨型帧，手机端自己也刚发过、本地渲染即可。
 */
function imagesOf(content) {
  if (!Array.isArray(content)) return []
  const out = []
  for (const block of content) {
    if (block === null || typeof block !== 'object' || block.type !== 'image') continue
    const ref = block.attachment
    if (ref === null || typeof ref !== 'object' || typeof ref.attachmentId !== 'string') continue
    const image = {
      attachmentId: ref.attachmentId,
      mediaType: typeof ref.mediaType === 'string' ? ref.mediaType : 'image/png',
      bytes: typeof ref.bytes === 'number' ? ref.bytes : 0,
      width: typeof ref.width === 'number' ? ref.width : 0,
      height: typeof ref.height === 'number' ? ref.height : 0,
    }
    if (typeof ref.name === 'string' && ref.name.length > 0) image.name = ref.name
    out.push(image)
  }
  return out
}

/** 一条排队中的用户消息 → 手机端队列坞站的一行（文本 + 图片元数据 + 对账键）。 */
/** 可显示的图片后缀 → media type。别的一律不读，避免"顺手读整个盘"。 */
const IMAGE_EXT = {
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.gif': 'image/gif',
}
/** 单文件上限：超过就让客户端自己想办法，别把桥堵死。 */
const MAX_WORKSPACE_FILE_BYTES = 12 * 1024 * 1024

/** 会话的工作目录（list 摘要里的 cwd）；问不到就返回空串。 */
async function sessionCwd(bridge, sessionId) {
  try {
    const value = await bridge.ctx.sessionController.list({}, timeoutSignal(CALL_TIMEOUT_MS))
    const found = (value?.items ?? []).find((one) => (one?.sessionId ?? one?.id) === sessionId)
    return typeof found?.cwd === 'string' ? found.cwd : ''
  } catch {
    return ''
  }
}

/** 读一个工作区图片 → 落库成附件引用（客户端随后用 sessions.image 取字节）。 */
async function readWorkspaceImage(bridge, sessionId, rawPath) {
  const cwd = await sessionCwd(bridge, sessionId)
  const abs = normalize(isAbsolute(rawPath) ? rawPath : resolve(cwd || '.', rawPath))
  if (cwd.length > 0) {
    const root = normalize(cwd)
    if (abs !== root && !abs.startsWith(root + sep)) {
      throw new BridgeError('forbidden', `超出会话工作区：${abs}`)
    }
  }
  const ext = extname(abs).toLowerCase()
  const mediaType = IMAGE_EXT[ext]
  if (mediaType === undefined) {
    throw new BridgeError('bad-request', `不是可显示的图片：${ext === '' ? '(无后缀)' : ext}`)
  }
  const info = await stat(abs).catch(() => null)
  if (info === null || !info.isFile()) throw new BridgeError('not-found', `没有这个文件：${abs}`)
  if (info.size > MAX_WORKSPACE_FILE_BYTES) {
    throw new BridgeError('image-too-large', `${info.size} bytes 超过上限`)
  }
  const data = await readFile(abs)
  const store = requireService(bridge, 'attachments')
  try {
    const ref = await store.saveImage({ mediaType, data, name: basename(abs) })
    return { path: abs, ...ref }
  } catch (error) {
    throw new BridgeError('unavailable', `图片落库失败：${errorText(error)}`)
  }
}

function inboxItem(message) {
  if (message === null || typeof message !== 'object') return { id: '', text: '', images: [] }
  const source = message.source ?? {}
  return {
    id: typeof message.id === 'string' ? message.id : '',
    source: typeof source.kind === 'string' ? source.kind : 'user',
    rpcId: typeof source.rpcId === 'string' ? source.rpcId : undefined,
    text: truncate(textOf(message.content), QUEUE_TEXT_CHARS),
    images: imagesOf(message.content),
  }
}

function truncate(value, limit = TRUNCATE_CHARS) {
  if (typeof value !== 'string') return value
  return value.length <= limit ? value : `${value.slice(0, limit)}…(+${value.length - limit} chars)`
}

/**
 * 回合收尾原因：error 分支里带着 LlmFailure 的原文（额度用尽、上下文超限、空响应、鉴权失败
 * 都在这），只留一个 kind 手机端就只能干看着「什么都没发生」。
 */
function turnReason(reason) {
  if (!reason || typeof reason !== 'object') return undefined
  const out = { kind: reason.kind }
  if (reason.kind === 'error' && reason.error) {
    out.code = reason.error.code
    out.message = truncate(typeof reason.error.message === 'string' ? reason.error.message : '', 600)
    if (typeof reason.error.status === 'number') out.status = reason.error.status
  }
  if (reason.kind === 'aborted') out.cause = reason.reason?.kind ?? reason.cause
  return out
}

/** 思考块拼回一段文本：历史重放要用，否则刷新/重连后思考内容就没了。 */
function reasoningOf(content) {
  if (!Array.isArray(content)) return ''
  const parts = []
  for (const block of content) {
    if (block && block.type === 'reasoning' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join('')
}

/** assistant 消息里带的工具调用，客户端拿它重建工具行（null 表示这一帧没有）。 */
function callsOf(content) {
  if (!Array.isArray(content)) return []
  const out = []
  for (const block of content) {
    if (block && block.type === 'tool-call') {
      out.push({
        callId: block.id,
        name: block.name,
        arguments: truncate(typeof block.arguments === 'string' ? block.arguments : ''),
      })
    }
  }
  return out
}
