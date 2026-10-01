#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 smali 还原 DeepSeek 客户端完整的 Material3 配色。

R8 混淆后的真实结构：
    rz7 : 原始色值表   Color 由 const 整数 + Ly08;->k(R,G,B,A) / m(R,G,B) 构造
    mx2 : 暗色语义调色板（引用 rz7）
    ix2 : 亮色语义调色板（引用 rz7）
    rx2 : darkColorScheme  按 Material3 ColorScheme 形参顺序读 mx2
    ema : lightColorScheme 按同一顺序读 ix2
"""
import re
import sys

SMALI = "/var/minis/shared/deepseek-2.6.1-re/apktool/smali"

M3_ORDER = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground",
    "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint",
    "inverseSurface", "inverseOnSurface",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim",
    "surfaceBright", "surfaceDim",
    "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    "surfaceContainerLow", "surfaceContainerLowest",
    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
]

CONST_RE = re.compile(r'^\s*const(?:/4|/16|/high16)?\s+v(\d+),\s*(\S+)\s*$', re.M)
INVOKE_RE = re.compile(r'invoke-static\s*\{([v0-9,\s]+)\},\s*Ly08;->([km])\(([^)]*)\)J')
MOVERES_RE = re.compile(r'^\s*move-result-wide\s+v(\d+)\s*$', re.M)
SPUT_RE = re.compile(r'^\s*sput-wide\s+v(\d+),\s*L(\w+);->(\w+):J', re.M)
SGET_RE = re.compile(r'^\s*sget-wide\s+v(\d+),\s*L(\w+);->(\w+):J', re.M)


def clinit(path):
    src = open(path, encoding='utf-8', errors='replace').read()
    m = re.search(r'\.method static constructor <clinit>\(\)V(.*?)\.end method', src, re.S)
    return m.group(1) if m else ""


def to_int(tok):
    t = tok.strip().rstrip('sSlL')          # 去掉可能的字面量后缀
    neg = t.startswith('-')
    t = t.lstrip('-+')
    try:
        v = int(t, 16) if t.startswith('0x') else int(t)
    except ValueError:
        return None
    if neg:
        v = -v
    return v & 0xFFFFFFFF


def parse_raw(path):
    """rz7 -> {field: argb}"""
    regs, moved, pending = {}, {}, None
    out, dead = {}, 0
    for line in clinit(path).splitlines():
        mc = CONST_RE.match(line)
        if mc:
            v = to_int(mc.group(2))
            if v is not None:
                regs[mc.group(1)] = v
            continue
        mi = INVOKE_RE.search(line)
        if mi:
            args = [a.strip().lstrip('v') for a in mi.group(1).split(',')]
            vals = [regs.get(a) for a in args]
            if any(v is None for v in vals):
                pending = None
            elif len(vals) == 4:
                r, g, b, a = [v & 0xFF for v in vals]
                pending = (a << 24) | (r << 16) | (g << 8) | b
            else:
                r, g, b = [v & 0xFF for v in vals]
                pending = 0xFF000000 | (r << 16) | (g << 8) | b
            continue
        mm = MOVERES_RE.match(line)
        if mm:
            if pending is None:
                dead += 1
            moved[mm.group(1)] = pending
            continue
        ms = SPUT_RE.match(line)
        if ms:
            if ms.group(2) == "rz7":
                v = moved.get(ms.group(1))
                if v is not None:
                    out[ms.group(3)] = v
            continue
    return out, dead


def parse_alias(path):
    """mx2 / ix2 -> {palette_field: rz7_field}"""
    regs, out = {}, {}
    for line in clinit(path).splitlines():
        ms = SGET_RE.match(line)
        if ms and ms.group(2) == "rz7":
            regs[ms.group(1)] = ms.group(3)
            continue
        mp = SPUT_RE.match(line)
        if mp:
            v = regs.get(mp.group(1))
            if v is not None:
                out[mp.group(3)] = v
    return out


def parse_scheme(path, palette_class):
    """rx2 / ema -> [palette_field] 按 ColorScheme 形参顺序"""
    src = open(path, encoding='utf-8', errors='replace').read()
    idx = src.rfind('Lqx2;-><init>')
    seg = src[max(0, idx - 30000):idx]
    return [m.group(3) for m in SGET_RE.finditer(seg) if m.group(2) == palette_class]


def main():
    raw, dead = parse_raw(f"{SMALI}/rz7.smali")
    print("rz7 原始色值 %d 个（未使用中间值 %d 个）" % (len(raw), dead))
    for k in sorted(raw):
        v = raw[k]
        print("   rz7.%-2s = #%08X  rgb(%3d,%3d,%3d) a=%d" % (k, v, v >> 16 & 255, v >> 8 & 255, v & 255, v >> 24 & 255))

    result = {}
    for tag, scheme, pal in (("dark", "rx2", "mx2"), ("light", "ema", "ix2")):
        alias = parse_alias(f"{SMALI}/{pal}.smali")
        order = parse_scheme(f"{SMALI}/{scheme}.smali", pal)
        print("\n===== %s  ( %s <- %s <- rz7 )  调色板 %d 项 / 形参 %d 项 =====" % (tag, scheme, pal, len(alias), len(order)))
        for i, pf in enumerate(order):
            name = M3_ORDER[i] if i < len(M3_ORDER) else "arg%d" % i
            rf = alias.get(pf)
            v = raw.get(rf) if rf else None
            if v is None:
                print("  %-28s = ??          (%s <- %s)" % (name, pf, rf))
            else:
                print("  %-28s = #%08X   (%s <- %s <- rz7.%s)" % (name, v, pf, rf, rf))
                result.setdefault(tag, {})[name] = "#%08X" % v
    import json
    open("/tmp/ds_palette.json", "w").write(json.dumps(result, indent=2))
    print("\n已写出 /tmp/ds_palette.json")


main()
