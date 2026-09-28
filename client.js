/**
 * devctl-dsh settings section.
 *
 * The Host half owns the TCP control port; this page renders what that port is
 * doing and how to pair a second device with it. Reads go through the Host web
 * server on the same origin, so nothing here touches the control port itself.
 */
window.__ModuleLoader__.load({
  id: 'devctl-dsh',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })

    const react = require('react')
    const h = react.createElement
    const { useCallback, useEffect, useState } = react

    const NS = 'devctl-dsh'
    const STATUS_URL = '/devctl-dsh/status'
    const POLL_MS = 5000

    const ZH = {
      title: 'devctl 远程控制',
      subtitle: '从手机或其他设备用 dshctl 驱动这台 DSH',
      listening: '监听地址',
      lan: '局域网地址',
      token: '访问令牌',
      reveal: '显示',
      hide: '隐藏',
      copy: '复制',
      copied: '已复制',
      pairing: '配对二维码',
      pairingHint: '在另一台设备上装好 dshctl 后扫这个码，或直接运行下面的命令',
      devices: '已连接设备',
      noDevices: '还没有设备连接过',
      colName: '设备',
      colAddress: '地址',
      colLast: '最后活动',
      colCommands: '命令',
      colState: '状态',
      online: '在线',
      offline: '已断开',
      refresh: '刷新',
      unavailable: '读不到状态',
      uptime: '已运行',
      version: '版本',
      second: '秒',
      minute: '分钟',
      hour: '小时',
      day: '天',
    }

    const EN = {
      title: 'devctl remote control',
      subtitle: 'Drive this DSH from another device with the dshctl CLI',
      listening: 'Listening on',
      lan: 'LAN address',
      token: 'Access token',
      reveal: 'Show',
      hide: 'Hide',
      copy: 'Copy',
      copied: 'Copied',
      pairing: 'Pairing QR code',
      pairingHint: 'Scan this on the other device once dshctl is installed, or run the command below',
      devices: 'Connected devices',
      noDevices: 'No device has connected yet',
      colName: 'Device',
      colAddress: 'Address',
      colLast: 'Last seen',
      colCommands: 'Commands',
      colState: 'State',
      online: 'online',
      offline: 'disconnected',
      refresh: 'Refresh',
      unavailable: 'Status unavailable',
      uptime: 'Uptime',
      version: 'Version',
      second: 's',
      minute: 'm',
      hour: 'h',
      day: 'd',
    }

    const S = {
      page: {
        display: 'flex',
        flexDirection: 'column',
        gap: '14px',
        fontSize: '13px',
        color: 'var(--dsw-alias-label-primary)',
      },
      card: {
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
        padding: '14px 16px',
        border: '1px solid var(--dsw-alias-border-l1)',
        background: 'var(--dsw-alias-bg-layer-1)',
        borderRadius: '10px',
      },
      heading: { fontSize: '13px', fontWeight: 600 },
      dim: { color: 'var(--dsw-alias-label-secondary)' },
      row: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' },
      label: { color: 'var(--dsw-alias-label-secondary)', minWidth: '76px' },
      code: {
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: '12px',
        padding: '2px 7px',
        border: '1px solid var(--dsw-alias-border-l1)',
        background: 'var(--dsw-alias-bg-layer-2)',
        borderRadius: '6px',
        wordBreak: 'break-all',
      },
      button: {
        appearance: 'none',
        font: 'inherit',
        fontSize: '12px',
        padding: '3px 10px',
        color: 'var(--dsw-alias-label-primary)',
        border: '1px solid var(--dsw-alias-border-l2)',
        background: 'transparent',
        borderRadius: '6px',
        cursor: 'pointer',
      },
      dot: (live) => ({
        flex: '0 0 auto',
        width: '8px',
        height: '8px',
        borderRadius: '999px',
        background: live ? 'var(--dsw-alias-state-success-primary)' : 'var(--dsw-alias-state-idle-primary)',
      }),
      // A QR code needs real contrast, so the plate stays literal even in dark themes.
      qrPlate: {
        alignSelf: 'flex-start',
        padding: '12px',
        background: '#ffffff',
        borderRadius: '12px',
        lineHeight: 0,
      },
      qr: { display: 'block', width: '208px', height: '208px' },
      table: { width: '100%', borderCollapse: 'collapse', fontSize: '12px' },
      th: {
        padding: '4px 10px 4px 0',
        textAlign: 'left',
        fontWeight: 500,
        color: 'var(--dsw-alias-label-secondary)',
        borderBottom: '1px solid var(--dsw-alias-border-l1)',
      },
      td: {
        padding: '6px 10px 6px 0',
        borderBottom: '1px solid var(--dsw-alias-border-l1)',
        verticalAlign: 'middle',
      },
      error: { color: 'var(--dsw-alias-state-error-primary)' },
    }

    function makeTranslate(ctx) {
      let bound = null
      try {
        bound = ctx.locale.bind(NS)
      } catch {
        bound = null
      }
      return (key) => {
        // The bundled dictionaries are authoritative: the locale service may
        // answer from the host's own namespace or fall back to another
        // language, and this page must follow the language the user picked.
        const local = pickDictionary(ctx)[key]
        if (typeof local === 'string') return local
        try {
          if (typeof bound === 'function') {
            const value = bound(key)
            if (typeof value === 'string' && value.length > 0 && value !== key) return value
          } else if (bound && typeof bound === 'object' && typeof bound[key] === 'string') {
            return bound[key]
          }
        } catch {
          /* an unusable locale binding must never take down the page */
        }
        return key
      }
    }

    /** The locale service reports `{active, locales, revision}`; older shapes are a bare id. */
    function activeLocaleId(ctx) {
      try {
        const current = ctx.locale.getLocale()
        if (typeof current === 'string') return current
        if (current && typeof current.active === 'string') return current.active
      } catch {
        /* fall through to the default language */
      }
      return ''
    }

    /** Read the active language and choose the dictionary that matches it. */
    function pickDictionary(ctx) {
      return activeLocaleId(ctx).toLowerCase().startsWith('en') ? EN : ZH
    }

    function formatUptime(ms, tr) {
      const total = Math.max(0, Math.floor(ms / 1000))
      const days = Math.floor(total / 86400)
      const hours = Math.floor((total % 86400) / 3600)
      const minutes = Math.floor((total % 3600) / 60)
      const seconds = total % 60
      if (days > 0) return `${days}${tr('day')} ${hours}${tr('hour')}`
      if (hours > 0) return `${hours}${tr('hour')} ${minutes}${tr('minute')}`
      if (minutes > 0) return `${minutes}${tr('minute')} ${seconds}${tr('second')}`
      return `${seconds}${tr('second')}`
    }

    function clockOf(value) {
      if (!value) return '-'
      try {
        return new Date(value).toLocaleTimeString()
      } catch {
        return '-'
      }
    }

    function maskToken(token) {
      if (typeof token !== 'string' || token.length === 0) return ''
      if (token.length <= 8) return '\u2022'.repeat(token.length)
      return `${token.slice(0, 4)}${'\u2022'.repeat(12)}${token.slice(-4)}`
    }

    function makePanel(ctx) {
      const tr = makeTranslate(ctx)

      return function DevctlSettingsSection() {
        const [status, setStatus] = useState(null)
        const [error, setError] = useState(null)
        const [showToken, setShowToken] = useState(false)
        const [copied, setCopied] = useState(null)

        const load = useCallback(async () => {
          try {
            const response = await fetch(STATUS_URL, { cache: 'no-store', credentials: 'same-origin' })
            if (!response.ok) throw new Error(`HTTP ${response.status}`)
            const payload = await response.json()
            setStatus(payload)
            setError(null)
          } catch (cause) {
            setError(cause && cause.message ? cause.message : String(cause))
          }
        }, [])

        useEffect(() => {
          let alive = true
          const tick = () => {
            if (alive) load()
          }
          tick()
          const timer = setInterval(tick, POLL_MS)
          return () => {
            alive = false
            clearInterval(timer)
          }
        }, [load])

        const copy = useCallback(async (value, key) => {
          try {
            await navigator.clipboard.writeText(value)
            setCopied(key)
            setTimeout(() => setCopied((current) => (current === key ? null : current)), 1500)
          } catch {
            setCopied(null)
          }
        }, [])

        if (!status) {
          return h(
            'div',
            { style: S.page },
            h('div', { style: S.heading }, tr('title')),
            h('div', { style: S.dim }, error ? `${tr('unavailable')}: ${error}` : '\u2026'),
          )
        }

        const endpoint = `${status.ip}:${status.port}`
        const peers = Array.isArray(status.peers) ? status.peers : []
        const qrSrc = `${status.qrPath || '/devctl-dsh/qr.svg'}?v=${status.uptimeMs || 0}`

        const copyButton = (value, key) =>
          h(
            'button',
            {
              type: 'button',
              style: S.button,
              onClick: () => copy(value, key),
            },
            copied === key ? tr('copied') : tr('copy'),
          )

        return h(
          'div',
          { style: S.page },

          h(
            'div',
            { style: { display: 'flex', flexDirection: 'column', gap: '3px' } },
            h('div', { style: S.heading }, tr('title')),
            h('div', { style: S.dim }, tr('subtitle')),
          ),

          h(
            'div',
            { style: S.card },
            h(
              'div',
              { style: S.row },
              h('span', { style: S.dot(true) }),
              h('span', null, tr('listening')),
              h('code', { style: S.code }, `${status.listenHost}:${status.port}`),
            ),
            h(
              'div',
              { style: S.row },
              h('span', { style: S.label }, tr('lan')),
              h('code', { style: S.code }, endpoint),
              copyButton(status.ip, 'ip'),
              status.version
                ? h('span', { style: S.dim }, `${tr('version')} ${status.version} \u00b7 ${tr('uptime')} ${formatUptime(status.uptimeMs || 0, tr)}`)
                : null,
            ),
            h(
              'div',
              { style: S.row },
              h('span', { style: S.label }, tr('token')),
              h('code', { style: S.code }, showToken ? status.token : maskToken(status.token)),
              h(
                'button',
                { type: 'button', style: S.button, onClick: () => setShowToken((value) => !value) },
                showToken ? tr('hide') : tr('reveal'),
              ),
              copyButton(status.token, 'token'),
            ),
          ),

          h(
            'div',
            { style: S.card },
            h('div', { style: S.heading }, tr('pairing')),
            h('div', { style: S.dim }, tr('pairingHint')),
            h('div', { style: S.qrPlate }, h('img', { src: qrSrc, style: S.qr, alt: tr('pairing') })),
            h(
              'div',
              { style: S.row },
              h('code', { style: S.code }, status.command),
              copyButton(status.command, 'command'),
            ),
          ),

          h(
            'div',
            { style: S.card },
            h(
              'div',
              { style: S.row },
              h('div', { style: S.heading }, tr('devices')),
              h('span', { style: S.dim }, `${status.livePeers}/${peers.length}`),
              h(
                'button',
                { type: 'button', style: { ...S.button, marginLeft: 'auto' }, onClick: () => load() },
                tr('refresh'),
              ),
            ),
            error ? h('div', { style: S.error }, error) : null,
            peers.length === 0
              ? h('div', { style: S.dim }, tr('noDevices'))
              : h(
                  'table',
                  { style: S.table },
                  h(
                    'thead',
                    null,
                    h(
                      'tr',
                      null,
                      h('th', { style: S.th }, tr('colName')),
                      h('th', { style: S.th }, tr('colAddress')),
                      h('th', { style: S.th }, tr('colLast')),
                      h('th', { style: S.th }, tr('colCommands')),
                      h('th', { style: S.th }, tr('colState')),
                    ),
                  ),
                  h(
                    'tbody',
                    null,
                    peers.map((peer, index) =>
                      h(
                        'tr',
                        { key: `${peer.address}-${peer.connectedAt}-${index}` },
                        h(
                          'td',
                          { style: S.td },
                          h(
                            'div',
                            { style: S.row },
                            h('span', { style: S.dot(peer.live) }),
                            h('span', null, peer.name),
                          ),
                          peer.platform || peer.version
                            ? h('div', { style: S.dim }, [peer.platform, peer.version].filter(Boolean).join(' '))
                            : null,
                        ),
                        h('td', { style: S.td }, peer.address),
                        h('td', { style: S.td }, clockOf(peer.lastSeenAt)),
                        h('td', { style: S.td }, String(peer.commands ?? 0)),
                        h('td', { style: S.td }, peer.live ? tr('online') : tr('offline')),
                      ),
                    ),
                  ),
                ),
          ),
        )
      }
    }

    const name = 'devctl-dsh'
    const inject = ['slots', 'locale']

    function apply(ctx) {
      // The locale service keys dictionaries by its own ids: `zh` and `en`.
      ctx.effect(() => ctx.locale.register(NS, 'zh', ZH), 'devctl-dsh: locale zh')
      ctx.effect(() => ctx.locale.register(NS, 'en', EN), 'devctl-dsh: locale en')
      ctx.slots.inject('settings.section', () =>
        ctx.slots.register(
          { name: 'settings.section', id: 'devctl-dsh', order: 35, label: 'devctl' },
          makePanel(ctx),
        ),
      )
    }

    exports.name = name
    exports.inject = inject
    exports.apply = apply
    return module.exports
  },
})
