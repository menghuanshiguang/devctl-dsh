package com.minis.dshconsole;

import android.webkit.WebView;
import org.json.JSONObject;

/**
 * DSH 原版网页里的「设置」是一个 SPA 内部浮层：没有 URL 能直达，只能进去点。
 * 这段脚本做三件事，然后把浮层撑成手机上的全屏页面：
 *   1. 点侧栏的设置钮（aria-label/title/text 为「设置」的那个 button）；
 *   2. 点对应分区（显示名/id/序号依次兜底，归一化后精确 → 前缀匹配）；
 *   3. 给浮层打上 data-dsfs 标记，用一段 CSS 把分区列表变成顶部横向胶囊条、
 *      内容区占满剩余高度，顺手藏掉第三方小挂件（dsh-whale）。
 *
 * 选择器不写死哈希类名：DSH 用 CSS Modules，类名形如 xxxxx_navList，
 * 运行时按「类名 token 以 _navList / -navList 结尾」来找，再用 MutationObserver 反复矫正，
 * 所以网页重渲染、切分区、旋转屏幕都不会散架。点完之后回头再点同一分区是幂等的。
 */
final class DsWeb {

    private DsWeb() {
    }

    /**
     * 打开（或重新打开）指定分区的全屏设置页。
     * 一级页把显示名、分区 id、列表序号一起传下来，哪条能对上用哪条
     * （网页分区名和 host 给的名字不一定一致，单靠文字匹配容易停在默认分区）。
     */
    static void apply(WebView web, String label, String id, int index) {
        if (web == null) {
            return;
        }
        String js = script()
                .replace("__LABEL__", quote(label))
                .replace("__SECTION_ID__", quote(id))
                .replace("__SECTION_INDEX__", String.valueOf(index));
        web.evaluateJavascript(js, null);
    }

    /** 只按显示名切分区（没有序号信息时的老调用点）。 */
    static void apply(WebView web, String label) {
        apply(web, label, "", -1);
    }

    /** 现在停在哪个分区：拿它判断「有没有真的跳过去」，跳不动就提示用户在顶部胶囊里点。 */
    static void probe(WebView web, android.webkit.ValueCallback<String> cb) {
        if (web == null || cb == null) {
            return;
        }
        web.evaluateJavascript("window.__dsSettingsPage?window.__dsSettingsPage.header():''", cb);
    }

    private static String quote(String s) {
        try {
            return JSONObject.quote(s == null ? "" : s);
        } catch (Throwable error) {
            return "" + '"' + '"';
        }
    }

    /** 退回 DSH 原版浮层（去掉全屏样式与标记）。 */
    static void restore(WebView web) {
        if (web == null) {
            return;
        }
        web.evaluateJavascript("window.__dsSettingsPage&&window.__dsSettingsPage.restore()", null);
    }

    /** 小鲸鱼之类的第三方挂件盖在设置页上很难看，直接藏掉。 */
    static String hideWidgets() {
        return "var s=document.getElementById('ds-widget-hide');if(!s){s=document.createElement('style');"
                + "s.id='ds-widget-hide';s.appendChild(document.createTextNode('[class*=dshwv-]{display:none!important;}'));"
                + "(document.head||document.documentElement).appendChild(s);}";
    }

    private static String script() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** JS 一行一条，方便以后改；写的时候别用反斜杠，省得跟 Java 转义打架。 */
    private static final String[] LINES = {
        "(function () {",
        "  'use strict';",
        "  var LABEL = __LABEL__;",
        "  var SECTION_ID = __SECTION_ID__;",
        "  var SECTION_INDEX = __SECTION_INDEX__;",
        "  if (window.__dsSettingsPage) { window.__dsSettingsPage.setTarget(LABEL, SECTION_ID, SECTION_INDEX); return; }",
        "  var STYLE_ID = 'ds-settings-page-style';",
        "  var state = { label: LABEL, id: SECTION_ID, index: SECTION_INDEX, active: null, clicks: 0, done: false, hit: '' };",
        "  /** 归一化：去掉空格和常见标点、压成小写 —— 「Agent 预设」和「agent预设」要算同一个。 */",
        "  function norm(t) {",
        "    return (t || '').replace(/[\\s\\u3000()\\uff08\\uff09\\u3010\\u3011\\u00b7\\-_,.\\uff0c\\u3001\\/]/g, '').toLowerCase();",
        "  }",
        "  /** 一级页传下来的东西凑成候选：显示名优先，再试 id，最后试去掉「设置」尾巴的名字。 */",
        "  function candidates() {",
        "    var list = [state.label, state.id], out = [], i, v, tail;",
        "    for (i = 0; i < list.length; i++) {",
        "      v = norm(list[i]);",
        "      if (!v) continue;",
        "      if (out.indexOf(v) < 0) out.push(v);",
        "      tail = v.replace(/\\u8bbe\\u7f6e$/, '');",
        "      if (tail && out.indexOf(tail) < 0) out.push(tail);",
        "    }",
        "    return out;",
        "  }",
        "  function log(m) { try { console.log('[ds]' + m); } catch (e) {} }",
        "  /** 分区是否真的切过去了：只认标题区（header）里的同名标题，比看高亮类名靠谱。 */",
        "  function showsSection(overlay, want) {",
        "    var zones = [sub(overlay, 'header'), sub(overlay, 'content')], z, zone, nodes, i, t;",
        "    for (z = 0; z < zones.length; z++) {",
        "      zone = zones[z];",
        "      if (!zone) continue;",
        "      nodes = zone.querySelectorAll('h1,h2,h3,h4,div,span,p');",
        "      for (i = 0; i < nodes.length; i++) {",
        "        if (nodes[i].children.length) continue;",
        "        t = norm(nodes[i].textContent);",
        "        if (t === want) return true;",
        "      }",
        "    }",
        "    return false;",
        "  }",
        "  function headerTitle(overlay) {",
        "    var h = sub(overlay, 'header'), nodes, i, t, best = '', bestSize = 0, size;",
        "    if (!h) return '';",
        "    nodes = h.querySelectorAll('h1,h2,h3,h4,h5,div,span');",
        "    for (i = 0; i < nodes.length; i++) {",
        "      if (nodes[i].children.length) continue;",
        "      t = (nodes[i].textContent || '').replace(/ /g, '');",
        "      if (!t || t === '关闭' || t.length > 16) continue;",
        "      size = parseFloat(getComputedStyle(nodes[i]).fontSize) || 0;",
        "      if (size > bestSize) { bestSize = size; best = t; }",
        "    }",
        "    return best;",
        "  }",
        "  function txt(el) { return el && el.nodeType === 1 ? (el.textContent || '').replace(/\\s+/g, '') : ''; }",
        "  function tail(el, name) {",
        "    var cls = el && el.className, parts, i;",
        "    if (typeof cls !== 'string' || !cls) return false;",
        "    parts = cls.split(/\\s+/);",
        "    for (i = 0; i < parts.length; i++) {",
        "      if (parts[i] === name || parts[i].slice(-(name.length + 1)) === '_' + name) return true;",
        "    }",
        "    return false;",
        "  }",
        "  function sub(root, name) {",
        "    var all = root.querySelectorAll('*'), i;",
        "    for (i = 0; i < all.length; i++) if (tail(all[i], name)) return all[i];",
        "    return null;",
        "  }",
        "  function subAll(root, name) {",
        "    var out = [], all = root.querySelectorAll('*'), i;",
        "    for (i = 0; i < all.length; i++) if (tail(all[i], name)) out.push(all[i]);",
        "    return out;",
        "  }",
        "  function findByText(t, sel) {",
        "    var nodes = document.querySelectorAll(sel), i;",
        "    for (i = 0; i < nodes.length; i++) if (txt(nodes[i]) === t) return nodes[i];",
        "    return null;",
        "  }",
        "  function findOverlay() {",
        "    var cell = findByText('通用设置', 'button,div,span,a'), el, cs, r;",
        "    if (!cell) return null;",
        "    el = cell;",
        "    while (el && el !== document.body) {",
        "      cs = getComputedStyle(el);",
        "      r = el.getBoundingClientRect();",
        "      if (cs.position === 'fixed' && r.width >= window.innerWidth - 4) return el;",
        "      el = el.parentElement;",
        "    }",
        "    return null;",
        "  }",
        "  function findOpenButton() {",
        "    var btns = document.querySelectorAll('button,[role=\"button\"],a'), i, b, al;",
        "    for (i = 0; i < btns.length; i++) {",
        "      b = btns[i];",
        "      al = b.getAttribute('aria-label') || b.title || '';",
        "      if (al === '设置' || txt(b) === '设置') return b;",
        "    }",
        "    return null;",
        "  }",
        "  function ensureStyle() {",
        "    if (document.getElementById(STYLE_ID)) return;",
        "    var css = [",
        "      '[data-dsfs=overlay]{position:fixed!important;inset:0!important;display:block!important;padding:0!important;margin:0!important;overflow:hidden!important;background:#fff!important;}',",
        "      '[data-dsfs=mask]{display:none!important;}',",
        "      '[data-dsfs=panel]{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;margin:0!important;border:0!important;border-radius:0!important;box-shadow:none!important;transform:none!important;display:flex!important;flex-direction:column!important;overflow:hidden!important;}',",
        "      '[data-dsfs=nav]{flex:0 0 auto!important;display:block!important;width:100%!important;max-width:none!important;height:auto!important;padding:8px 12px!important;box-sizing:border-box!important;border:0!important;overflow:visible!important;}',",
        "      '[data-dsfs=navtitle]{display:none!important;}',",
        "      '[data-dsfs=navlist]{display:flex!important;flex-direction:row!important;flex-wrap:nowrap!important;gap:8px!important;width:100%!important;max-width:none!important;height:auto!important;overflow-x:auto!important;overflow-y:hidden!important;-webkit-overflow-scrolling:touch!important;}',",
        "      '[data-dsfs=navcell]{flex:0 0 auto!important;width:auto!important;height:34px!important;white-space:nowrap!important;padding:0 14px!important;}',",
        "      '[data-dsfs=content]{flex:1 1 auto!important;display:flex!important;flex-direction:column!important;width:100%!important;max-width:none!important;min-width:0!important;height:auto!important;overflow:hidden!important;}',",
        "      '[data-dsfs=header]{flex:0 0 auto!important;width:100%!important;box-sizing:border-box!important;}',",
        "      '[data-dsfs=options]{flex:1 1 auto!important;width:100%!important;max-width:none!important;min-width:0!important;box-sizing:border-box!important;overflow-y:auto!important;-webkit-overflow-scrolling:touch!important;}',",
        "      '[class^=dshwv-],[class*=\" dshwv-\"]{display:none!important;}'",
        "    ].join('');",
        "    var s = document.createElement('style');",
        "    s.id = STYLE_ID;",
        "    s.appendChild(document.createTextNode(css));",
        "    (document.head || document.documentElement).appendChild(s);",
        "  }",
        "  function mark(el, tag) { if (el && el.nodeType === 1) el.setAttribute('data-dsfs', tag); }",
        "  function apply() {",
        "    var overlay = findOverlay(), mask, panel, nav, cells, i;",
        "    if (!overlay) return false;",
        "    ensureStyle();",
        "    mark(overlay, 'overlay');",
        "    mask = sub(overlay, 'mask');",
        "    if (mask) mark(mask, 'mask');",
        "    panel = sub(overlay, 'panel') || overlay;",
        "    mark(panel, 'panel');",
        "    mark(sub(panel, 'navTitle'), 'navtitle');",
        "    mark(sub(panel, 'navList'), 'navlist');",
        "    cells = subAll(panel, 'navCell');",
        "    for (i = 0; i < cells.length; i++) mark(cells[i], 'navcell');",
        "    nav = sub(panel, 'nav');",
        "    if (nav) mark(nav, 'nav');",
        "    mark(sub(panel, 'content'), 'content');",
        "    mark(sub(panel, 'header'), 'header');",
        "    mark(sub(panel, 'options'), 'options');",
        "    return true;",
        "  }",
        "  function isActive(el) {",
        "    var cls = typeof el.className === 'string' ? el.className : '';",
        "    var aria = el.getAttribute('aria-current') || el.getAttribute('aria-selected') || '';",
        "    if (aria === 'true' || aria === 'page') return true;",
        "    return /(^|[ _-])active([ _-]|$)/i.test(cls);",
        "  }",
        "  function select() {",
        "    var overlay, wants, w, cells, exact = null, loose = null, i, t, target, names = [], via = '';",
        "    if (!state.label && !state.id) return true;",
        "    if (state.done) return true;",
        "    overlay = findOverlay();",
        "    if (!overlay) return false;",
        "    wants = candidates();",
        "    cells = subAll(overlay, 'navCell');",
        "    if (!cells.length) cells = overlay.querySelectorAll('button,[role=\"button\"]');",
        "    for (i = 0; i < cells.length; i++) {",
        "      t = txt(cells[i]);",
        "      if (!t || t.length > 14) continue;",
        "      names.push(t);",
        "    }",
        "    for (w = 0; w < wants.length && !exact; w++) {",
        "      for (i = 0; i < cells.length; i++) {",
        "        t = txt(cells[i]);",
        "        if (!t || t.length > 14) continue;",
        "        if (norm(t) === wants[w]) { exact = cells[i]; via = 'exact:' + wants[w]; break; }",
        "        if (!loose && (norm(t).indexOf(wants[w]) === 0 || wants[w].indexOf(norm(t)) === 0)) {",
        "          loose = cells[i];",
        "          via = 'prefix:' + wants[w];",
        "        }",
        "      }",
        "    }",
        "    if (!state.logged) { state.logged = true; log('cells=' + names.join('|') + ' want=' + wants.join('/')); }",
        "    var cur = headerTitle(overlay);",
        "    if (cur !== state.curTitle) { state.curTitle = cur; log('header=' + cur); }",
        "    for (w = 0; w < wants.length; w++) {",
        "      if (showsSection(overlay, wants[w])) { state.done = true; state.hit = cur; log('done ' + wants[w]); return true; }",
        "    }",
        "    target = exact || loose;",
        "    if (!target && state.index >= 0 && state.index < cells.length) {",
        "      target = cells[state.index];                 // 兜底：一级列表的序号和网页分区顺序一致",
        "      via = 'index:' + state.index;",
        "    }",
        "    if (!target) { log('no cell for ' + wants.join('/')); return false; }",
        "    if (isActive(target)) { state.done = true; log('already active ' + want); return true; }",
        "    if (state.clicks >= 3) { state.done = true; log('give up ' + want + ' cur=' + cur); return true; }",
        "    if (Date.now() - (state.lastClick || 0) < 700) return true;",
        "    state.lastClick = Date.now();",
        "    state.clicks += 1;",
        "    log('click #' + state.clicks + ' ' + via + ' hit=' + txt(target) + ' cur=' + cur);",
        "    try { target.scrollIntoView({ inline: 'center', block: 'nearest' }); } catch (e) {}",
        "    target.click();",
        "    return true;",
        "  }",
        "  function run() {",
        "    var btn, ok;",
        "    if (!document.body) return;",
        "    ok = apply();",
        "    if (!ok) {",
        "      btn = findOpenButton();",
        "      if (btn) { btn.click(); ok = apply(); if (!state.openLogged && ok) { state.openLogged = true; log('panel opened'); } }",
        "    }",
        "    if (ok && !state.firstLogged) { state.firstLogged = true; log('overlay ok'); }",
        "    select();",
        "  }",
        "  var queued = false;",
        "  function schedule() {",
        "    if (queued) return;",
        "    queued = true;",
        "    setTimeout(function () { queued = false; run(); }, 120);",
        "  }",
        "  function boot() {",
        "    var delays = [0, 60, 180, 420, 900, 1600, 2600, 4000], i;",
        "    run();",
        "    for (i = 0; i < delays.length; i++) setTimeout(run, delays[i]);",
        "    try {",
        "      new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });",
        "    } catch (e) {}",
        "    window.addEventListener('resize', schedule);",
        "    window.addEventListener('orientationchange', schedule);",
        "    setTimeout(function () { window.scrollTo(0, 0); }, 300);",
        "  }",
        "  function restore() {",
        "    var s = document.getElementById(STYLE_ID), marked, i;",
        "    if (s && s.parentNode) s.parentNode.removeChild(s);",
        "    marked = document.querySelectorAll('[data-dsfs]');",
        "    for (i = 0; i < marked.length; i++) marked[i].removeAttribute('data-dsfs');",
        "    state.clicks = 99;",
        "  }",
        "  window.__dsSettingsPage = {",
        "    run: run,",
        "    apply: apply,",
        "    select: select,",
        "    restore: restore,",
        "    setLabel: function (l) { window.__dsSettingsPage.setTarget(l, '', -1); },",
        "    setTarget: function (l, sid, idx) {",
        "      state.label = l || '';",
        "      state.id = sid || '';",
        "      state.index = (typeof idx === 'number') ? idx : -1;",
        "      state.active = null;",
        "      state.target = null;",
        "      state.clicks = 0;",
        "      state.done = false;",
        "      state.logged = false;",
        "      state.hit = '';",
        "      run();",
        "    },",
        "    /** 现在停在哪个分区（给外面做「没跳过去」的提示用）。 */",
        "    header: function () { var o = findOverlay(); return o ? headerTitle(o) : ''; }",
        "  };",
        "  if (document.readyState === 'complete' || document.readyState === 'interactive') boot();",
        "  else document.addEventListener('DOMContentLoaded', boot);",
        "})();",
    };
}
