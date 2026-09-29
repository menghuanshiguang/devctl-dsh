#!/usr/bin/env sh
# 取本地版要用的原生件（proot + 它依赖的两个库），放进 libs/arm64-v8a/。
#
# 为什么要从 Termux 的仓库拿：这些是**为 Android 编译**的 aarch64 二进制，
# 而且必须以 lib/<abi>/libXXX.so 的形式进 APK —— 系统只把那里解出来的文件算作可执行，
# 放在 app 私有目录里的可执行文件在 targetSdk>=29 时会被拒绝 exec。
#
# 用法：sh fetch-libs.sh        （需要有 curl、ar、tar）
set -e
BASE=$(cd "$(dirname "$0")" && pwd)
OUT="$BASE/arm64-v8a"
MIRROR="https://packages.termux.dev/apt/termux-main"
mkdir -p "$OUT" /tmp/dshlibs
cd /tmp/dshlibs

# 1) proot / libtalloc / libandroid-shmem 三个包
PKGS="pool/main/p/proot/proot_5.1.107.95_aarch64.deb
pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb
pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"

for p in $PKGS; do
  f=$(basename "$p")
  [ -f "$f" ] || curl -sL -o "$f" "$MIRROR/$p"
  rm -rf x && mkdir x && cd x
  ar x "../$f" 2>/dev/null || true
  tar xf data.tar.xz 2>/dev/null || true
  cd ..
done

# 2) 摆成 Android 认的命名：libproot.so / libtalloc.so.2 / libandroid-shmem.so
cp x/data/data/com.termux/files/usr/bin/proot                "$OUT/libproot.so"
cp x/data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3   "$OUT/libtalloc.so.2"
cp x/data/data/com.termux/files/usr/lib/libandroid-shmem.so  "$OUT/libandroid-shmem.so"
chmod 644 "$OUT"/*
ls -l "$OUT"
echo "好了：现在 python3 build.py --flavor local 会把它们打进 APK 的 lib/arm64-v8a/"
