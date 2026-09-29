// 本地自测：假装 127.0.0.1:3081 是 DSH 网页服务，验证发现 / 鉴权 / 跳转 / 代理 / health
// 关键用例：上游按 DSH 的 same-origin 规则拒掉跨源请求（403 forbidden），
// 验证代理会把 Origin / Referer / Sec-Fetch-Site 改写成本机同源。
import { createServer, request as httpRequest } from 'node:http'
import { installRemoteWeb } from './remote-web.js'

const LOCAL = 'http://127.0.0.1:3081'
const seen = [] // 诊断用

const up = createServer((req, res) => {
  if (req.url.startsWith('/devctl-dsh/status')) {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end('{"ok":true,"version":"1.2.1"}')
    return
  }
  // DSH 网页服务的同源守卫：只要来源不是它自己，一律 403
  if ((req.headers.origin && req.headers.origin !== LOCAL) || (req.headers.referer && !req.headers.referer.startsWith(LOCAL))) {
    res.writeHead(403, { 'content-type': 'text/plain' })
    res.end('forbidden')
    return
  }
  if (req.headers['sec-fetch-site'] === 'cross-site') {
    res.writeHead(403, { 'content-type': 'text/plain' })
    res.end('forbidden')
    return
  }
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify({ origin: req.headers.origin ?? '', referer: req.headers.referer ?? '', site: req.headers['sec-fetch-site'] ?? '' }))
})
await new Promise((r) => up.listen(3081, '127.0.0.1', r))

const win = installRemoteWeb({
  port: 7790,
  token: 'tok-abc',
  statusPath: '/devctl-dsh/status',
  log: (m) => console.log('[log]', m),
  warn: (e) => console.log('[warn]', String(e)),
})
await new Promise((r) => win.health().then((i) => { console.log('[info]', JSON.stringify(i)); r() }))

const base = 'http://127.0.0.1:7790'
let failed = 0
const check = (label, got, want) => {
  const ok = got === want
  if (!ok) failed += 1
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: got ${got} want ${want}`)
}
const call = (path, headers = {}, method = 'GET') =>
  new Promise((resolve) => {
    const req = httpRequest({ host: '127.0.0.1', port: 7790, path, method, headers }, (res) => {
      let body = ''
      res.on('data', (c) => { body += c })
      res.on('end', () => resolve({ status: res.statusCode ?? 0, location: res.headers.location ?? '', body }))
    })
    req.end()
  })

check('无 token', (await call('/')).status, 401)
check('错 token', (await call('/page?token=nope')).status, 401)
check('带 token 跳转', (await call('/?token=tok-abc')).status, 302)
check('cookie 通行', (await call('/page', { cookie: 'devctl_web=tok-abc' })).status, 200)
check('health', (await call('/__devctl-web/health?token=tok-abc')).status, 200)

// 手机端真实情形：页面上带的是局域网来源
const lan = 'http://192.168.1.9:7790'
const cookie = { cookie: 'devctl_web=tok-abc' }
const head = async (label, headers, method = 'GET') => {
  const res = await call('/api/list', { ...cookie, ...headers }, method)
  check(`${label} 状态`, res.status, 200)
  return JSON.parse(res.body)
}
const a = await head('POST 跨源', { origin: lan }, 'POST')
check('上游看到的 origin', a.origin, LOCAL)
const b = await head('GET lan referer', { referer: `${lan}/settings` })
check('上游看到的 referer', b.referer.slice(0, LOCAL.length), LOCAL)
const c = await head('sec-fetch-site', { 'sec-fetch-site': 'cross-site' })
check('上游看到的 sec-fetch-site', c.site, 'same-origin')

await win.dispose()
up.close()
console.log(failed === 0 ? 'ALL PASS' : `${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
