#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 devctl-dsh 的 index.js 打补丁：放行 reasoning-delta，让客户端能显示思考过程。

用法（在 index.js 旁边执行）：
    python3 fix-thinking.py                # 改 ./index.js，自动备份 index.js.bak
    python3 fix-thinking.py 路径/index.js
幂等：已经打过就什么都不做。改完重启 dsh host 即生效。
"""
import io
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "index.js"
src = io.open(path, encoding="utf-8").read()

if "reasoning" in src and "reasoning-delta" in src:
    print("[=] 已经打过补丁，什么都没做")
    sys.exit(0)

# 方案 A：两个精确替换（最安全）
A = "inner.chunk?.type === 'text-delta'"
A2 = "inner.chunk?.type === 'reasoning-delta'"
B = "{ evt: 'delta', data: { sessionId, text: inner.chunk.text } }"
B2 = ("{ evt: 'delta', data: { sessionId, text: inner.chunk.text, "
      "reasoning: inner.chunk.type === 'reasoning-delta' } }")

out = None
if A in src and B in src:
    out = src.replace(A, "(" + A + " || " + A2 + ")").replace(B, B2)
    how = "精确替换"
else:
    # 方案 B：整块替换 assistant-stream 的转发逻辑（不挑 chunk 名字，任何带 text 的都转发）
    pat = re.compile(
        r"(case\s+'assistant-stream':\s*\{)(.*?)(\n\s*return\b)", re.S | re.M)
    m = pat.search(src)
    if m:
        body = (
            "\n      const inner = frame.frame\n"
            "      const ck = inner?.type === 'chunk' ? inner.chunk : null\n"
            "      if (ck && typeof ck.text === 'string' && ck.text.length > 0) {\n"
            "        connection.send({ evt: 'delta', data: { sessionId, text: ck.text,\n"
            "          reasoning: ck.type !== 'text-delta' } })\n"
            "      }\n"
        )
        out = src[:m.start(2)] + body + src[m.end(2):]
        how = "整块替换（兼容任意 thinking chunk 名字）"

if out is None:
    print("[x] 没找到预期结构，index.js 未改动。请把下面这段发给我，我给准确替换：\n")
    i = src.find("assistant-stream")
    print(src[max(0, i - 400): i + 900] if i >= 0 else src[:1200])
    sys.exit(1)

io.open(path + ".bak", "w", encoding="utf-8").write(src)
io.open(path, "w", encoding="utf-8").write(out)
print("[√] 已打补丁（%s）：%s   备份 -> %s.bak" % (how, path, path))
print("[!] 重启 dsh host 后生效；dshconsole 会把思考渲染成折叠的 ✦ 思考 块")
