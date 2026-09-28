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
import { homedir, hostname } from 'node:os'
import { dirname, join } from 'node:path'

const VERSION = '1.1.0'
const PROTOCOL = 1
/** Prompt image parts accept exactly these media types. */
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
/** Where the control token is persisted, relative to `$DSH_HOME`. */
const STATE_NAME = 'devctl-dsh.json'
/** Pre-rename state file, read once so already-paired clients keep working. */
const LEGACY_STATE_NAME = 'dsh-remote.json'
const MAX_LINE_BYTES = 4 * 1024 * 1024
const DEFAULT_HOST = '0.0.0.0'
const DEFAULT_PORT = 7788
/** Every short control call is bounded; `follow` is the only unbounded read. */
const CALL_TIMEOUT_MS = 30_000
const TAIL_TIMEOUT_MS = 20_000
const TRUNCATE_CHARS = 4_000

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

  const stateDir = process.env.DSH_HOME && process.env.DSH_HOME.length > 0 ? process.env.DSH_HOME : join(homedir(), '.dsh')
  const stateFile = join(stateDir, STATE_NAME)
  const legacyStateFile = join(stateDir, LEGACY_STATE_NAME)
  const token = resolveToken(settings, stateFile, legacyStateFile)

  const connections = new Set()
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
    stateFile,
    startedAt,
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
    })
  })

  ctx.effect(() => {
    server.on('error', warn)
    server.listen(port, host, () => {
      const bound = server.address()
      const actualPort = bound && typeof bound === 'object' ? bound.port : port
      writeState(stateFile, { token, host, port: actualPort, version: VERSION, startedAt })
      log(`listening on ${host}:${actualPort}; control token in ${stateFile}`)
    })
    return () =>
      new Promise((resolve) => {
        for (const connection of connections) connection.dispose()
        connections.clear()
        server.close(() => resolve())
      })
  }, 'devctl-dsh.listen')
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
      connection.send({ id, ok: false, error: { code: 'unauthorized', message: 'token rejected' } })
      connection.dispose()
      return
    }
    connection.authenticated = true
    connection.client = typeof params.client === 'string' ? params.client : 'unknown'
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

    case 'sessions.list': {
      const value = await controller.list({}, timeoutSignal(CALL_TIMEOUT_MS))
      return { items: (value?.items ?? []).map(publicSummary) }
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
      return controller.prompt(
        {
          requestId: randomUUID(),
          sessionId: params.sessionId,
          mode: params.mode === 'steer' ? 'steer' : 'queue',
          content,
        },
        timeoutSignal(CALL_TIMEOUT_MS),
      )
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

/** `modelSelection` is a {lastUsed, next} projection; flatten it to "provider/model". */
function modelSelectionText(projection) {
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
      if (inner?.type === 'chunk' && inner.chunk?.type === 'text-delta' && typeof inner.chunk.text === 'string') {
        connection.send({ evt: 'delta', data: { sessionId, text: inner.chunk.text } })
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
      return { kind: 'user', seq, text: textOf(data.content) }
    case 'assistant/message':
      return {
        kind: 'assistant',
        seq,
        turn: data.turn,
        step: data.step,
        text: textOf(data.message?.content),
        interrupted: data.interrupted === true,
      }
    case 'tool/call':
      return { kind: 'tool-call', seq, name: data.name, arguments: truncate(data.arguments) }
    case 'tool/result':
      return {
        kind: 'tool-result',
        seq,
        text: truncate(textOf(data.message?.content)),
        error: data.error ? { name: data.error.name, code: data.error.code, reason: data.error.reason } : undefined,
      }
    case 'turn/start':
      return { kind: 'turn-start', seq, turn: data.turn }
    case 'turn/end':
      return { kind: 'turn-end', seq, turn: data.turn, reason: data.reason?.kind }
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

function truncate(value, limit = TRUNCATE_CHARS) {
  if (typeof value !== 'string') return value
  return value.length <= limit ? value : `${value.slice(0, limit)}…(+${value.length - limit} chars)`
}
