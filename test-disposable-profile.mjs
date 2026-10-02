#!/usr/bin/env node
// 一次性 Profile 验收：install → 冷启动 → 控制端口鉴权 → 卸载。
// 用法：node test-disposable-profile.mjs <DSH CLI 的 lib/bin.js> [包根目录]
// 输出一行 JSON 证据（dshVersion / install / composition / coldStart / controlHello / uninstall）。
import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { mkdtemp, mkdir, rm, writeFile, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { connect, createServer } from 'node:net'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)
const cli = resolve(process.argv[2] ?? '')
const packageRoot = resolve(process.argv[3] ?? '.')
if (!process.argv[2]) throw new Error('pass the fixed DSH CLI path as argv[2]')
const pluginSpec = process.env.DSH_TEST_PLUGIN_SPEC ?? `file:${packageRoot.replace(/\\/g, '/')}`

const root = await mkdtemp(join(tmpdir(), 'devctl-dsh-e2e-'))
const env = Object.fromEntries(Object.entries(process.env)
  .filter(([key]) => !/(TOKEN|SECRET|PASSWORD|API_KEY|AUTH|CREDENTIAL)/i.test(key)))
Object.assign(env, {
  DSH_HOME: resolve(root, 'home'),
  DSH_AGENTS_HOME: resolve(root, 'agents'),
  DSH_TELEMETRY_DISABLED: '1',
  npm_config_cache: resolve(root, 'npm-cache'),
  npm_config_userconfig: join(root, '.npmrc'),
  npm_config_registry: 'https://registry.npmjs.org/',
  CI: 'true',
})

let child
let stage = 'prepare'
let tail = ''
async function run(args) {
  try {
    const result = await execFileAsync(process.execPath, [cli, ...args], {
      cwd: root, env, timeout: 420_000, maxBuffer: 6 * 1024 * 1024,
    })
    return result.stdout
  } catch (error) {
    tail = `${error.stdout ?? ''}${error.stderr ?? ''}`.slice(-4000)
    throw new Error(`official DSH CLI operation failed at ${stage}`)
  }
}

async function unusedPort() {
  const server = createServer()
  await new Promise((done, reject) => server.once('error', reject).listen(0, '127.0.0.1', done))
  const port = server.address().port
  await new Promise((done, reject) => server.close(error => error ? reject(error) : done()))
  return port
}

// cordis.patch.yml 默认把控制端口钉在 0.0.0.0:7788（开发机常被真实实例占着）。
// 一次性 Profile 的层文件里插一条 id 定向覆盖：port=0 让 OS 挑空闲口（状态文件记录实际口）、
// web.port=0 关掉 LAN 窗口。`dsh web` 子命令不接 --patch，所以走 Profile 层。
const overlayEntries = [
  '- id: devctl-dsh',
  '  config:',
  '    port: 0',
  '    web:',
  '      port: 0',
].join('\n')

function readControlState(stateFile, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs
  const attempt = async () => {
    try {
      const state = JSON.parse(await readFile(stateFile, 'utf8'))
      if (Number.isInteger(state.port) && state.port > 0 && typeof state.token === 'string') return state
    } catch { /* not written yet */ }
    if (Date.now() >= deadline) throw new Error('control-state-timeout')
    await new Promise(done => setTimeout(done, 500))
    return attempt()
  }
  return attempt()
}

function rpc(port, token, requests, timeoutMs = 20_000) {
  return new Promise((resolve, reject) => {
    const socket = connect({ port, host: '127.0.0.1' })
    const replies = []
    let buffer = ''
    const timer = setTimeout(() => { socket.destroy(); reject(new Error('control-timeout')) }, timeoutMs)
    const finish = (error, value) => {
      clearTimeout(timer)
      socket.destroy()
      error ? reject(error) : resolve(value)
    }
    socket.setNoDelay(true)
    socket.on('error', error => finish(error))
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      let index = buffer.indexOf('\n')
      while (index >= 0) {
        const line = buffer.slice(0, index)
        buffer = buffer.slice(index + 1)
        if (line.trim()) {
          try { replies.push(JSON.parse(line)) } catch { return finish(new Error('control-bad-reply')) }
        }
        index = buffer.indexOf('\n')
      }
      if (replies.length >= requests.length + 1) finish(null, replies)
    })
    socket.on('connect', () => {
      const hello = { id: 1, method: 'hello', params: { token, client: 'disposable-profile-probe', device: 'acceptance' } }
      const rest = requests.map((request, offset) => ({ id: offset + 2, ...request }))
      socket.write(`${[hello, ...rest].map(frame => JSON.stringify(frame)).join('\n')}\n`)
    })
  })
}

try {
  await mkdir(env.DSH_HOME, { recursive: true })
  await writeFile(env.npm_config_userconfig, '', { mode: 0o600 })

  stage = 'initial-profile'
  const baselineConfig = await run(['--profile', 'web', '--dump-config'])

  stage = 'install'
  await run(['plugin', '--profile', 'web', 'add', '--ignore-scripts', '--config.auto-install-peers=false', pluginSpec])
  const installedConfig = await run(['--profile', 'web', '--dump-config'])
  assert.ok(installedConfig.includes('devctl-dsh'), 'installed profile must contain the devctl-dsh entry')

  stage = 'config-overlay'
  const profilePatchPath = join(env.DSH_HOME, 'profiles', 'web', 'cordis.patch.yml')
  const originalPatch = await readFile(profilePatchPath, 'utf8')
  if (/\[\s*\]/.test(originalPatch)) {
    await writeFile(profilePatchPath, originalPatch.replace(/\[\s*\]/, `\n${overlayEntries}\n`))
  } else if (originalPatch.trimEnd().endsWith(']')) {
    throw new Error('unsupported non-empty flow-style profile patch file')
  } else {
    await writeFile(profilePatchPath, `${originalPatch.replace(/\s+$/, '')}\n${overlayEntries}\n`)
  }

  stage = 'cold-start'
  const port = await unusedPort()
  child = spawn(process.execPath, [cli, 'web', '--no-open', '--port', String(port)], {
    cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'],
  })
  const launchUrl = await new Promise((done, reject) => {
    let buffer = ''
    const timer = setTimeout(() => reject(new Error(`startup-timeout: ${buffer.slice(-2000)}`)), 120_000)
    const append = (chunk) => {
      buffer = `${buffer}${chunk.toString()}`.slice(-65_536)
      const match = /dsh web:\s*(http:\/\/[^\s]+)/i.exec(buffer)
        ?? /(http:\/\/(?:localhost|127\.0\.0\.1):\d+\/[^\s]*)/i.exec(buffer)
      if (!match) return
      clearTimeout(timer)
      done(match[1])
    }
    child.stdout.on('data', append)
    child.stderr.on('data', append)
    child.once('error', () => { clearTimeout(timer); reject(new Error('startup-spawn-failed')) })
    child.once('exit', code => { clearTimeout(timer); reject(new Error(`startup-exited-${code}: ${buffer.slice(-2000)}`)) })
  })

  stage = 'authenticated-host-probe'
  const login = await fetch(launchUrl, { redirect: 'manual', signal: AbortSignal.timeout(15_000) })
  assert.ok([302, 303].includes(login.status), `launch URL must redirect to set a cookie, got ${login.status}`)
  const cookie = login.headers.getSetCookie().map(value => value.split(';')[0]).join('; ')
  assert.ok(cookie)
  const base = new URL(launchUrl).origin
  let hostReady = false
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const response = await fetch(`${base}/`, { headers: { cookie }, signal: AbortSignal.timeout(10_000) })
    if (response.status === 200) { hostReady = true; break }
    await new Promise(done => setTimeout(done, 500))
  }
  assert.equal(hostReady, true, 'host web UI must answer 200 on the disposable profile')

  stage = 'control-port-hello'
  const state = await readControlState(join(env.DSH_HOME, 'devctl-dsh.json'))
  assert.notEqual(state.port, 7788, 'overlay must rebind the control port away from the fixed 7788')
  const [hello, pong] = await rpc(state.port, state.token, [{ method: 'ping' }])
  assert.equal(hello.ok, true, `hello must authenticate: ${JSON.stringify(hello.error ?? null)}`)
  assert.equal(hello.result?.server, 'devctl-dsh')
  assert.equal(pong.ok, true, 'ping must answer after hello')

  stage = 'stop-host'
  child.kill('SIGTERM')
  await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(new Error('shutdown-timeout')), 15_000)
    child.once('exit', () => { clearTimeout(timer); done() })
  })
  child = null
  // 卸载前撤掉覆盖，最终 dump-config 才能和基线逐字对比。
  await writeFile(profilePatchPath, originalPatch)

  stage = 'uninstall'
  await run(['plugin', '--profile', 'web', 'remove', 'devctl-dsh'])
  const removedConfig = await run(['--profile', 'web', '--dump-config'])
  assert.equal(removedConfig.includes('devctl-dsh'), false, 'uninstall must drop the devctl-dsh entry')
  assert.equal(removedConfig, baselineConfig, 'uninstall must restore the exact pre-install Profile composition')

  console.log(JSON.stringify({
    status: 'passed',
    dshVersion: (await run(['--version'])).trim(),
    install: true,
    composition: true,
    coldStart: true,
    authenticatedHostHttp: 200,
    controlHello: true,
    controlPing: true,
    controlPort: state.port,
    uninstall: true,
    disposableProfile: true,
  }))
} catch (error) {
  console.error(JSON.stringify({
    status: 'failed',
    stage,
    reason: String(error.message ?? error).slice(0, 500),
    tail: tail || undefined,
  }))
  process.exitCode = 1
} finally {
  if (child && child.exitCode === null) {
    child.kill('SIGKILL')
    await new Promise(done => child.once('exit', done))
  }
  await rm(root, { recursive: true, force: true })
}
