// 本地自测：假装 127.0.0.1:3081 是 DSH 网页服务，验证发现 / 鉴权 / 跳转 / 代理 / health
import { createServer } from 'node:http'
import { installRemoteWeb } from './remote-web.js'

const up = createServer((req, res) => {
  if (req.url.startsWith('/devctl-dsh/status')) {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end('{"ok":true,"version":"1.2.1"}')
    return
  }
  res.writeHead(200, { 'content-type': 'text/html' })
  res.end('<h1>DSH web</h1>')
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
const show = async (label, url, headers = {}) => {
  const res = await fetch(url, { headers, redirect: 'manual' })
  const body = await res.text()
  console.log(`${label}: ${res.status} ${JSON.stringify(res.headers.get('location') || '')} ${body.slice(0, 60)}`)
}
await show('无 token   ', `${base}/`)
await show('错 token   ', `${base}/page?token=nope`)
await show('带 token   ', `${base}/?token=tok-abc`)
await show('cookie 通行', `${base}/page`, { cookie: 'devctl_web=tok-abc' })
await show('health     ', `${base}/__devctl-web/health?token=tok-abc`)
await show('POST 转发  ', `${base}/api?token=tok-abc`, { cookie: 'devctl_web=tok-abc' })

await win.dispose()
up.close()
process.exit(0)
