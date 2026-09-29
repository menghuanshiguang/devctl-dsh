#!/usr/bin/env sh
# 在手机上（最好是一个 glibc 环境，比如 Termux 的 proot Ubuntu）把 DSH harness 跑起来，
# 让 App 的「本地模式」有东西可连。用法：sh start-local.sh [插件目录]
set -e

PLUGIN_DIR=${1:-$(cd "$(dirname "$0")/../../.." && pwd)}
# 本地专用补丁：只绑回环 + 回环免令牌（App 端就不用填令牌了）
LOCAL_PATCH=${LOCAL_PATCH:-$(cd "$(dirname "$0")" && pwd)/cordis.local.patch.yml}
ROOT=${ROOT:-$HOME/dsh-local}
DSH_HOME=${DSH_HOME:-$HOME/.dsh}
VERSION=${VERSION:-0.2.0-rc.1}
PORT=${PORT:-7788}

echo "==> 1/4 安装 harness CLI（@deepseek-ai/dsh@$VERSION）"
mkdir -p "$ROOT" "$DSH_HOME"
cd "$ROOT"
[ -d node_modules ] || npm i --no-audit --no-fund "$(printf '@deepseek-ai/dsh@%s' "$VERSION")"
CLI="$ROOT/node_modules/.bin/dsh"

echo "==> 2/4 把 devctl-dsh 插件装进 web profile（$PLUGIN_DIR）"
if [ -f "$PLUGIN_DIR/package.json" ]; then
  DSH_HOME="$DSH_HOME" "$CLI" plugin --profile web add "$PLUGIN_DIR" || \
    echo "   （老版本没有 plugin 子命令？那就手动把插件加进 profile 的 patch 列表）"
else
  echo "   ！找不到插件目录，跳过：$PLUGIN_DIR"
fi

echo "==> 3/4 启动 dsh web（绑回环 + 免令牌，端口 $PORT）"
PATCH=""
[ -f "$LOCAL_PATCH" ] && PATCH="--patch $LOCAL_PATCH"
# 前台跑，Ctrl-C 停；要看后台就把 nohup 那行打开
# nohup env DSH_HOME="$DSH_HOME" "$CLI" web $PATCH > "$ROOT/dsh-web.log" 2>&1 &
DSH_HOME="$DSH_HOME" "$CLI" web $PATCH &
DSH_PID=$!
sleep 6

echo "==> 4/4 读出插件状态（端口 / 令牌）"
STATE="$DSH_HOME/devctl-dsh.json"
if [ -f "$STATE" ]; then
  # 只用 node 解一下 JSON，避免依赖 jq
  node -e '
    const fs = require("fs")
    const s = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
    console.log("")

    console.log("  把这行填进 App → 设置 → 运行模式 → 本地 → 本机地址/令牌：")
    console.log("    " + (s.host || "127.0.0.1") + " | " + (s.port || "") + " | " + (s.token || ""))
  ' "$STATE"
else
  echo "   ！还没生成 $STATE —— 看日志确认插件的 host/port 配置"
fi

echo ""
echo "harness pid=$DSH_PID（Ctrl-C 结束）"
wait $DSH_PID
