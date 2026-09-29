import io, os
src = '/var/minis/workspace/dshconsole/tools/ds-settings-page.js'
out = '/var/minis/workspace/dshconsole/src/com/minis/dshconsole/DsWeb.java'
js = io.open(src, encoding='utf-8').read().split('\n')
lines = []
for ln in js:
    if ln.strip() == '':
        continue
    e = ln.replace('\\', '\\\\').replace('"', '\\"')
    lines.append('        "%s",' % e)
head = '''package com.minis.dshconsole;

import android.webkit.WebView;
import org.json.JSONObject;

/**
 * DSH 原版网页里的「设置」是一个 SPA 内部浮层：没有 URL 能直达，只能进去点。
 * 这段脚本做三件事，然后把浮层撑成手机上的全屏页面：
 *   1. 点侧栏的设置钮（aria-label/title/text 为「设置」的那个 button）；
 *   2. 点对应分区（按文字精确匹配 → 再去掉空格/前缀匹配）；
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

    /** 打开（或重新打开）指定分区的全屏设置页；label 为空就停在默认分区。 */
    static void apply(WebView web, String label) {
        if (web == null) {
            return;
        }
        String q;
        try {
            q = JSONObject.quote(label == null ? "" : label);
        } catch (Throwable error) {
            q = "\\"\\"";
        }
        web.evaluateJavascript(script().replace("__LABEL__", q), null);
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
            sb.append(line).append('\\n');
        }
        return sb.toString();
    }

    /** JS 一行一条，方便以后改；写的时候别用反斜杠，省得跟 Java 转义打架。 */
    private static final String[] LINES = {
'''
tail = '''    };
}
'''
os.makedirs(os.path.dirname(out), exist_ok=True)
io.open(out, 'w', encoding='utf-8').write(head + '\n'.join(lines) + '\n' + tail)
print('wrote', out, os.path.getsize(out))
