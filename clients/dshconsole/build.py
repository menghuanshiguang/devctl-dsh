#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在 Android(aarch64) 上无 Gradle 构建 APK:
    javac(JDK17) -> d8 -> 手写二进制 manifest(pyaxml) -> zip -> apksigner
"""
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


def build_manifest():
    tree = etree.parse(os.path.join(BASE, "AndroidManifest.xml"))
    axml = AXML()
    axml.from_xml(tree.getroot())
    data = axml.pack()
    dst = os.path.join(BUILD, "AndroidManifest.xml")
    with open(dst, "wb") as f:
        f.write(data)
    print("[manifest] %d bytes binary AXML" % len(data))
    open(os.path.join(BUILD, "manifest.bin"), "wb").write(data)
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
    print("[zip] %d bytes" % os.path.getsize(unsigned))
    return unsigned


def sign(unsigned):
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    apk = os.path.join(OUT, "dshconsole.apk")
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


def main():
    os.makedirs(BUILD, exist_ok=True)
    manifest = build_manifest()
    classes = compile_java()
    dexfile = dex(classes)
    unsigned = package_apk(manifest, dexfile)
    sign(unsigned)


if __name__ == "__main__":
    main()
