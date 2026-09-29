/**
 * The phone-visible window onto the Host's own web UI.
 *
 * `dsh web` normally listens on 127.0.0.1 with a launch token that only the
 * machine's browser has. Phones cannot use either. This module starts a second
 * listener on the LAN that is authenticated by the *devctl* token the paired
 * device already holds, and proxies everything to the Host web server over
 * loopback, where no launch token is needed.
 *
 * Why a proxy instead of a bespoke settings API: the settings page is a Web
 * client, and every plugin contributes its own section through the client
 * bundle graph. Rendering those faithfully on a phone means loading that page,
 * not re-implementing each plugin's panel.
 */
import { createServer as createHttpServer, request as httpRequest } from 'node:http'
import { connect as tcpConnect } from 'node:net'
import { networkInterfaces } from 'node:os'
import { timingSafeEqual } from 'node:crypto'

/** Default LAN port. Set `web.port` to 0 in the plugin config to switch this off. */
export const DEFAULT_WEB_PORT = 7790
/** Loopback paths this module answers itself, so they never reach the Host web server. */
const HEALTH_PATH = '/__devctl-web/health'
/** Cookie that carries the devctl token after the first `?token=` hit. */
const COOKIE = 'devctl_web'
/** Plausible ports for the local Host web server, probed in order before a wider sweep. */
const COMMON_PORTS = [3081, 3080, 3000, 3001, 5173, 4173, 8000, 8080, 8888, 9000, 7860, 1234]

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])

/** Constant-time compare that never throws on length mismatch. */
function sameToken(a, b) {
  const left = Buffer.from(String(a ?? ''), 'utf8')
  const right = Buffer.from(String(b ?? ''), 'utf8')
  if (left.length !== right.length) return false
  return timingSafeEqual(left, right)
}

/**
 * Ask one loopback port for our own settings endpoint: a self-identifying probe.
 * The plugin's own route sits behind DSH's browser-trust check, so the probe
 * carries the control token in `?probe=` and the route waves it through.
 */
function probePort(port, statusPath, probeToken) {
  const path = probeToken ? `${statusPath}?probe=${encodeURIComponent(probeToken)}` : statusPath
  return new Promise((resolve) => {
    const req = httpRequest(
      { host: '127.0.0.1', port, path, method: 'GET', headers: { accept: 'application/json' } },
      (res) => {
        let size = 0
        res.on('data', (chunk) => {
          size += chunk.length
        })
        res.on('end', () => resolve({ ok: res.statusCode === 200 && size > 0, status: res.statusCode ?? 0 }))
      },
    )
    req.setTimeout(1200, () => req.destroy())
    req.on('error', () => resolve({ ok: false, status: 0 }))
    req.end()
  })
}

/**
 * Last resort before admitting defeat: does *anything* speak HTTP on the port
 * `dsh web` uses by default? The Host web server may answer its own login page
 * where our marker route is unreachable, and a reachable target beats a 503.
 */
function probeHttp(port) {
  return new Promise((resolve) => {
    const req = httpRequest(
      { host: '127.0.0.1', port, path: '/', method: 'GET', headers: { accept: 'text/html' } },
      (res) => {
        res.resume()
        res.on('end', () => resolve(res.statusCode ?? 0))
      },
    )
    req.setTimeout(1200, () => req.destroy())
    req.on('error', () => resolve(0))
    req.end()
  })
}

/**
 * `dsh web` binds this port unless told otherwise, so it is the one guess worth
 * making when every self-identifying probe comes back empty.
 */
export const DEFAULT_HOST_WEB_PORT = 3081

/**
 * Find the loopback port the Host web server listens on. Never returns null —
 * `origin` is '' when nothing answered, and `seen` lists every port that spoke
 * HTTP at all (401/404 included) so the phone can show why it failed.
 */
export async function discoverHostWeb(statusPath, probeToken) {
  const tried = new Set()
  const seen = []
  const ports = [...COMMON_PORTS]
  for (let p = 3000; p <= 3100; p += 1) ports.push(p)
  for (const port of ports) {
    if (tried.has(port)) continue
    tried.add(port)
    const hit = await probePort(port, statusPath, probeToken)
    if (hit.status > 0 && hit.status !== 404) seen.push({ port, status: hit.status })
    if (hit.ok) return { port, origin: `http://127.0.0.1:${port}`, seen }
  }
  return { port: 0, origin: '', seen }
}

/** Pull one header out of the raw header list (Node gives us no case-insensitive map there). */
function rawHeader(rawHeaders, name) {
  const lower = name.toLowerCase()
  for (let i = 0; i < rawHeaders.length; i += 2) {
    if (String(rawHeaders[i]).toLowerCase() === lower) return String(rawHeaders[i + 1] ?? '')
  }
  return ''
}

/** Where the caller's devctl token came from, if anywhere. */
function tokenFrom(request) {
  const raw = request.url ?? '/'
  let url
  try {
    url = new URL(raw, 'http://127.0.0.1')
  } catch {
    return { token: '', url: null, fromQuery: false }
  }
  const query = url.searchParams.get('token') ?? url.searchParams.get('t') ?? ''
  if (query) return { token: query, url, fromQuery: true }
  const auth = rawHeader(request.rawHeaders ?? [], 'authorization')
  if (auth.startsWith('Bearer ')) return { token: auth.slice(7), url, fromQuery: false }
  const cookie = rawHeader(request.rawHeaders ?? [], 'cookie')
  const match = /(?:^|;\s*)devctl_web=([^;]+)/.exec(cookie)
  if (match) return { token: decodeURIComponent(match[1]), url, fromQuery: false }
  return { token: '', url, fromQuery: false }
}

/** Same-origin URL without the token parameter, for the post-auth redirect. */
function urlWithoutToken(url) {
  const clean = new URL(url.toString())
  clean.searchParams.delete('token')
  clean.searchParams.delete('t')
  return `${clean.pathname}${clean.search}`
}

/**
 * Start the LAN listener. Returns `{ dispose, info }`, or null when disabled.
 * `statusPath` is this plugin's own settings endpoint: probing loopback for it
 * identifies the Host web server without guessing.
 */
export function installRemoteWeb({
  port = DEFAULT_WEB_PORT,
  token,
  target = '',
  statusPath,
  log = () => {},
  warn = () => {},
} = {}) {
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return null
  const info = { port, origin: target, ready: false, reason: target ? 'configured' : 'searching', url: '', seen: [] }
  let searching = null

  const ensureOrigin = () => {
    if (info.origin) {
      info.ready = true
      info.reason = info.reason === 'searching' ? 'ok' : info.reason
      return Promise.resolve(info.origin)
    }
    if (!searching) {
      searching = discoverHostWeb(statusPath, token)
        .then(async (found) => {
          info.seen = found.seen ?? []
          if (found.origin) {
            info.origin = found.origin
            info.ready = true
            info.reason = 'discovered'
            log(`web window target ${found.origin} (port ${found.port})`)
            return info.origin
          }
          const status = await probeHttp(DEFAULT_HOST_WEB_PORT)
          if (status > 0) {
            info.origin = `http://127.0.0.1:${DEFAULT_HOST_WEB_PORT}`
            info.ready = true
            info.reason = 'host-web-default'
            log(`web window target ${info.origin} (dsh web default port, HTTP ${status})`)
            return info.origin
          }
          info.ready = false
          info.reason = 'host-web-not-found'
          return ''
        })
        .catch((error) => {
          warn(error)
          info.reason = 'discovery-failed'
          return ''
        })
        .finally(() => {
          searching = null
          syncUrl()
        })
    }
    return searching
  }

  /**
   * The one URL the phone should open: the LAN listener plus the control token.
   * Exposed over the authenticated devctl channel, so it leaks nothing new.
   */
  const syncUrl = () => {
    const address = addresses()[0] ?? ''
    info.url = info.ready && address ? `http://${address}:${info.port}/?token=${encodeURIComponent(token ?? '')}` : ''
  }

  /** The Host web server knows its own port; prefer it over any port sweep. */
  const setTarget = (origin) => {
    if (typeof origin !== 'string' || origin.length === 0) return false
    info.origin = origin
    info.ready = true
    info.reason = 'host-service'
    searching = null
    syncUrl()
    log(`web window target ${origin} (from the Host web server)`)
    return true
  }

  const addresses = () => {
    const out = []
    try {
      const nets = networkInterfaces()
      for (const name of Object.keys(nets)) {
        for (const net of nets[name] ?? []) {
          if (net.family === 'IPv4' && !net.internal) out.push(net.address)
        }
      }
    } catch {
      // Some restricted runtimes refuse to enumerate interfaces; the phone
      // already knows the host it dialled, so this list is a convenience only.
    }
    return out
  }

  const deny = (response, text, code) => {
    const body = Buffer.from(text, 'utf8')
    response.writeHead(code, {
      'content-type': 'text/plain; charset=utf-8',
      'content-length': String(body.length),
      'cache-control': 'no-store',
    })
    response.end(body)
  }

  const server = createHttpServer((request, response) => {
    const { token: presented, url, fromQuery } = tokenFrom(request)
    if (!sameToken(presented, token)) {
      deny(response, 'devctl-dsh: 需要访问令牌（?token=…）\n', 401)
      return
    }
    if (request.url === HEALTH_PATH || url?.pathname === HEALTH_PATH) {
      const payload = JSON.stringify({
        ok: info.ready,
        port: info.port,
        origin: info.origin,
        reason: info.reason,
        url: info.url,
        seen: info.seen,
        addresses: addresses(),
      })
      const body = Buffer.from(payload, 'utf8')
      response.writeHead(200, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': String(body.length),
        'cache-control': 'no-store',
      })
      response.end(body)
      return
    }
    if (fromQuery && url) {
      const body = Buffer.from('', 'utf8')
      response.writeHead(302, {
        location: urlWithoutToken(url),
        'set-cookie': `${COOKIE}=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Lax`,
        'content-length': String(body.length),
        'cache-control': 'no-store',
      })
      response.end(body)
      return
    }
    ensureOrigin().then((origin) => {
      if (!origin) {
        deny(response, 'devctl-dsh: 本机没找到 DSH 网页服务（dsh web 没在跑？）\n', 503)
        return
      }
      let base
      try {
        base = new URL(origin)
      } catch {
        deny(response, 'devctl-dsh: web.target 不是合法 URL\n', 500)
        return
      }
      const headers = { ...request.headers, host: base.host }
      for (const key of Object.keys(headers)) {
        if (HOP_BY_HOP.has(key.toLowerCase())) delete headers[key]
      }
      const upstream = httpRequest(
        { host: base.hostname, port: base.port || 80, method: request.method, path: request.url, headers },
        (up) => {
          const out = { ...up.headers }
          for (const key of Object.keys(out)) {
            if (HOP_BY_HOP.has(key.toLowerCase())) delete out[key]
          }
          response.writeHead(up.statusCode ?? 502, out)
          up.pipe(response)
        },
      )
      upstream.on('error', (error) => {
        warn(error)
        if (!response.headersSent) deny(response, 'devctl-dsh: 无法连上 DSH 网页服务\n', 502)
        else response.destroy()
      })
      request.pipe(upstream)
    })
  })

  // Web clients keep a websocket open; pipe it through so the page stays live.
  server.on('upgrade', (request, socket, head) => {
    const { token: presented } = tokenFrom(request)
    if (!sameToken(presented, token)) {
      socket.destroy()
      return
    }
    ensureOrigin().then((origin) => {
      if (!origin) {
        socket.destroy()
        return
      }
      let base
      try {
        base = new URL(origin)
      } catch {
        socket.destroy()
        return
      }
      const upstream = tcpConnect(Number(base.port || 80), base.hostname, () => {
        let head_text = `${request.method} ${request.url} HTTP/1.1\r\n`
        for (let i = 0; i < request.rawHeaders.length; i += 2) {
          head_text += `${request.rawHeaders[i]}: ${request.rawHeaders[i + 1]}\r\n`
        }
        head_text += '\r\n'
        upstream.write(head_text)
        if (head && head.length > 0) upstream.write(head)
        upstream.pipe(socket)
        socket.pipe(upstream)
      })
      upstream.on('error', () => socket.destroy())
      socket.on('error', () => upstream.destroy())
    })
  })

  server.on('error', (error) => {
    warn(error)
    info.reason = 'listen-failed'
  })
  server.listen(port, '0.0.0.0', () => {
    const boundPort = server.address()?.port ?? port
    info.port = boundPort
    log(`web window on 0.0.0.0:${boundPort}; open http://<lan-ip>:${boundPort}/?token=<control token>`)
    ensureOrigin()
  })

  return {
    info,
    setTarget,
    health: () => ensureOrigin().then(() => info),
    dispose: () =>
      new Promise((resolve) => {
        try {
          server.close(() => resolve())
        } catch {
          resolve()
        }
      }),
  }
}
