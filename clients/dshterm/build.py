#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DSH 终端（设备上跑 DeepSeek Harness）：WebView 展示 UI + 原生只负责"跑"。

用法：
    python3 build.py            # 出包 out/dshterm.apk

跟 dshconsole 一样是零依赖构建：javac → d8 → 手写二进制 manifest → zip → apksigner。
UI 全在 assets/term.html（改 UI 不用碰 Java）；Java 侧只做一件事：把命令丢进 proot 跑，
再把输出流回 JS（同时镜像到 logcat，tag=DshTerm，方便命令行侧自测）。
"""
import io
import os
import subprocess
import sys
import zipfile

from lxml import etree
from pyaxml import AXML

BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, "src")
ASSETS = os.path.join(BASE, "assets")
LIBS = os.path.join(BASE, "libs", "arm64-v8a")
BUILD = os.path.join(BASE, "build")
OUT = os.path.join(BASE, "out")
TOOLS = os.path.join(BASE, "tools")
ANDROID_JAR = os.path.join(TOOLS, "android.jar")
D8_JAR = os.path.join(TOOLS, "d8.jar")
APKSIGNER_JAR = os.path.join(TOOLS, "apksigner.jar")
KEYSTORE = os.path.join(TOOLS, "debug.keystore")
PKG = "com.minis.dshterm"
MIN_API = "24"
TARGET_API = "28"          # 要在私有目录里 exec（rootfs 里的 ELF），必须 ≤28


def run(cmd):
    print("$ " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def build_manifest():
    tree = etree.parse(os.path.join(BASE, "AndroidManifest.xml"))
    for node in tree.iter():
        if node.tag.endswith("uses-sdk"):
            node.set("{http://schemas.android.com/apk/res/android}targetSdkVersion", TARGET_API)
    axml = AXML()
    axml.from_xml(tree.getroot())
    data = axml.pack()
    dst = os.path.join(BUILD, "AndroidManifest.xml")
    io.open(dst, "wb").write(data)
    print("[manifest] %d bytes" % len(data))
    return dst


def java_sources():
    out = []
    for root, _dirs, files in os.walk(SRC):
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(root, f))
    return sorted(out)


def compile_java():
    classes = os.path.join(BUILD, "classes")
    os.makedirs(classes, exist_ok=True)
    run(["javac", "-source", "8", "-target", "8", "-encoding", "utf-8",
         "-bootclasspath", ANDROID_JAR, "-classpath", ANDROID_JAR,
         "-d", classes] + java_sources())
    return classes


def dex(classes):
    outdir = os.path.join(BUILD, "dex")
    os.makedirs(outdir, exist_ok=True)
    files = []
    for root, _dirs, names in os.walk(classes):
        for n in names:
            if n.endswith(".class"):
                files.append(os.path.join(root, n))
    run(["java", "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--min-api", MIN_API, "--output", outdir] + files)
    return os.path.join(outdir, "classes.dex")


def package_apk(manifest, dexfile):
    unsigned = os.path.join(BUILD, "unsigned.apk")
    if os.path.exists(unsigned):
        os.remove(unsigned)
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(manifest, "AndroidManifest.xml")
        z.write(dexfile, "classes.dex")
        for name in sorted(os.listdir(LIBS)):            # proot + 依赖 + glibc 加载器
            z.write(os.path.join(LIBS, name), "lib/arm64-v8a/" + name)
            print("[lib] %s" % name)
        for root, _dirs, files in os.walk(ASSETS):       # 网页 UI
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, ASSETS)
                z.write(full, "assets/" + rel.replace(os.sep, "/"))
                print("[asset] %s" % rel)
    print("[zip] %d bytes" % os.path.getsize(unsigned))
    return unsigned


def sign(unsigned):
    os.makedirs(OUT, exist_ok=True)
    apk = os.path.join(OUT, "dshterm.apk")
    if os.path.exists(apk):
        os.remove(apk)
    run(["java", "-jar", APKSIGNER_JAR, "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "dsh", "--min-sdk-version", MIN_API,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--out", apk, unsigned])
    print("[apk] %s  (%d bytes)" % (apk, os.path.getsize(apk)))
    return apk


def main():
    os.makedirs(BUILD, exist_ok=True)
    manifest = build_manifest()
    dexfile = dex(compile_java())
    sign(package_apk(manifest, dexfile))


if __name__ == "__main__":
    main()
