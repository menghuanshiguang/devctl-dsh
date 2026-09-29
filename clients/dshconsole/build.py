#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在 Android(aarch64) 上无 Gradle 构建 APK:
    javac(JDK17) -> d8 -> 手写二进制 manifest(pyaxml) -> zip -> apksigner
"""
import io
import os
import subprocess
import sys
import zipfile

from lxml import etree
from pyaxml import AXML

BASE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(BASE, "tools")
ANDROID_JAR = os.path.join(TOOLS, "android.jar")
D8_JAR = os.path.join(TOOLS, "d8.jar")
APKSIGNER_JAR = os.path.join(TOOLS, "apksigner.jar")
KEYSTORE = os.path.join(TOOLS, "debug.keystore")
SRC = os.path.join(BASE, "src")
SHARED = os.path.join(SRC, "shared")        # UI / 协议 / 渲染：两套共用，谁都不许带口味
FLAVOR_SRC = os.path.join(SRC, "flavor")    # 核心差异：Core/Cores/两个实现/本地运行时
BUILD = os.path.join(BASE, "build")
OUT = os.path.join(BASE, "out")
MIN_API = "24"


def run(cmd, **kw):
    print("$ " + " ".join(cmd))
    sys.stdout.flush()
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        sys.exit("!! 失败: " + cmd[0])
    return r


ANDROID_NS = "http://schemas.android.com/apk/res/android"
# 两个 app 共存：名字要一眼分清（远端连着局域网那台 / 本地把这台手机当 host）
LABELS = {"remote": "DSH 远端版", "local": "DSH 本地版"}
PKGS = {"remote": "com.minis.dshconsole", "local": "com.minis.dshconsole.local"}
FLAVOR = "remote"          # remote = 指向局域网那台；local = harness 跑在 app 里
for i, a in enumerate(sys.argv):
    if a == "--flavor" and i + 1 < len(sys.argv):
        FLAVOR = sys.argv[i + 1]

def write_flavor():
    """按 flavor 生成 Flavor.java：源码里不手改，两套 app 的差异就在这几个常量上。"""
    local = FLAVOR == "local"
    src = ("package com.minis.dshconsole;\n\n"
           "/** 由 build.py --flavor 生成，别手改。 */\n"
           "final class Flavor {\n"
           "    static final boolean LOCAL = %s;\n"
           "    static final String NAME = \"%s\";\n"
           "    private Flavor() {\n    }\n}\n" % ("true" if local else "false", FLAVOR))
    path = os.path.join(FLAVOR_SRC, "com", "minis", "dshconsole", "Flavor.java")
    io.open(path, "w", encoding="utf-8").write(src)
    print("[flavor] %s -> %s" % (FLAVOR, path))

def build_manifest():
    tree = etree.parse(os.path.join(BASE, "AndroidManifest.xml"))
    # 两套 app 并存：包名和名字都带 flavor 后缀（本地版叫「DSH 本地版」）
    if FLAVOR == "local":
        # 换包名是为了两套 app 并存；但 Activity 在清单里写的是相对名（.MainActivity），
        # 换包名后会被解析成 com.minis.dshconsole.local.MainActivity（不存在）→ 一启动就崩。
        # 所以这里把相对名补成绝对名（Java 包名不变，只动清单）。
        for node in tree.iter():
            for attr in ("name", "targetActivity"):
                key = "{%s}%s" % (ANDROID_NS, attr)
                v = node.get(key)
                if v is not None and v.startswith("."):
                    node.set(key, "com.minis.dshconsole" + v)
        tree.getroot().set("package", PKGS[FLAVOR])
    for node in tree.iter():
        key = "{%s}label" % ANDROID_NS
        if node.get(key) == "DSH 控制台":
            node.set(key, LABELS[FLAVOR])
    axml = AXML()
    axml.from_xml(tree.getroot())
    data = axml.pack()
    dst = os.path.join(BUILD, "AndroidManifest.xml")
    with open(dst, "wb") as f:
        f.write(data)
    print("[manifest] %d bytes binary AXML" % len(data))
    open(os.path.join(BUILD, "manifest.bin"), "wb").write(data)
    return dst


# shared 里只允许通过 Core/LocalEnv 这两个接口碰口味，别的都算越界（避免"一份 UI 两处维护"）
FORBIDDEN_IN_SHARED = ("Flavor.LOCAL", "new LocalRuntime", "import com.minis.dshconsole.Flavor")

def guard_shared():
    bad = []
    for root, _dirs, files in os.walk(SHARED):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            text = io.open(path, encoding="utf-8").read()
            for token in FORBIDDEN_IN_SHARED:
                if token in text:
                    bad.append("%s 出现 %s" % (os.path.relpath(path, BASE), token))
    if bad:
        sys.exit("!! shared 里出现了口味专用符号，请改成走 Cores.get()/LocalEnv：\n   " + "\n   ".join(bad))
    print("[guard] shared 干净（只认 Core / LocalEnv）")

def java_sources():
    out = []
    for base in (SHARED, FLAVOR_SRC):
        for root, _dirs, files in os.walk(base):
            for f in files:
                if f.endswith(".java"):
                    out.append(os.path.join(root, f))
    return sorted(out)


def compile_java():
    classes = os.path.join(BUILD, "classes")
    if os.path.isdir(classes):
        subprocess.run(["rm", "-rf", classes])
    os.makedirs(classes, exist_ok=True)
    run(["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-nowarn",
         "-Xlint:none", "-classpath", ANDROID_JAR, "-d", classes] + java_sources())
    files = []
    for root, _dirs, fs in os.walk(classes):
        for f in fs:
            if f.endswith(".class"):
                files.append(os.path.join(root, f))
    print("[javac] %d classes" % len(files))
    return files


def dex(files):
    outdir = os.path.join(BUILD, "dex")
    if os.path.isdir(outdir):
        subprocess.run(["rm", "-rf", outdir])
    os.makedirs(outdir, exist_ok=True)
    run(["java", "-Xmx1500m", "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--min-api", MIN_API, "--release", "--lib", ANDROID_JAR,
         "--output", outdir] + files)
    return os.path.join(outdir, "classes.dex")


def package_apk(manifest, dexfile):
    unsigned = os.path.join(BUILD, "unsigned.apk")
    if os.path.exists(unsigned):
        os.remove(unsigned)
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(manifest, "AndroidManifest.xml")
        z.write(dexfile, "classes.dex")
        # 本地模式要用的原生件（proot + 它依赖的两个库）：必须以 lib/<abi>/ 进包，
        # 系统才会把它们解到 nativeLibraryDir —— 只有那里允许 exec。
        libdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "libs", "arm64-v8a")
        if FLAVOR == "local" and os.path.isdir(libdir):   # proot 只有本地版用得上
            for name in sorted(os.listdir(libdir)):
                z.write(os.path.join(libdir, name), "lib/arm64-v8a/" + name)
                print("[lib] %s" % name)
    print("[zip] %d bytes" % os.path.getsize(unsigned))
    return unsigned


def sign(unsigned):
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    apk = os.path.join(OUT, "dshconsole-%s.apk" % FLAVOR)
    if os.path.exists(apk):
        os.remove(apk)
    run(["java", "-jar", APKSIGNER_JAR, "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "dsh", "--min-sdk-version", MIN_API,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--out", apk, unsigned])
    run(["java", "-jar", APKSIGNER_JAR, "verify", "--print-certs", apk])
    print("[apk] %s  (%d bytes)" % (apk, os.path.getsize(apk)))
    return apk


def build_one(flavor):
    """构建一个口味：生成 Flavor.java → 守门 shared → 清单 → javac → d8 → 打包 → 签名。"""
    global FLAVOR
    FLAVOR = flavor
    os.makedirs(BUILD, exist_ok=True)
    print("\n===== 构建 %s（%s / %s）=====" % (flavor, PKGS[flavor], LABELS[flavor]))
    write_flavor()
    guard_shared()
    manifest = build_manifest()
    classes = compile_java()
    dexfile = dex(classes)
    unsigned = package_apk(manifest, dexfile)
    return sign(unsigned)

def main():
    flavors = ["remote", "local"] if FLAVOR == "all" else [FLAVOR]
    built = [build_one(f) for f in flavors]
    print("\n产物：")
    for apk in built:
        print("  %s" % apk)

if __name__ == "__main__":
    main()