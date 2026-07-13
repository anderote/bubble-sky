#!/usr/bin/env bash
# Run disembodied Codex against the mod-side AgentBridge.
#
# Best path: run this on the Minecraft server host, where the bridge is bound to
# localhost by default. If the bridge is deliberately exposed on the LAN, set
# BUBBLESKY_BRIDGE_URL=http://<server-ip>:25580 and BUBBLESKY_BRIDGE_TOKEN.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${SERVER_DIR:-$ROOT/server}"
BRIDGE_CONFIG="${BRIDGE_CONFIG:-$SERVER_DIR/config/bubblesky-bridge.json}"

if [ -z "${BUBBLESKY_BRIDGE_TOKEN:-}" ] && [ -f "$BRIDGE_CONFIG" ]; then
  BUBBLESKY_BRIDGE_TOKEN="$(
    node -e "const fs=require('fs'); const p=process.argv[1]; console.log(JSON.parse(fs.readFileSync(p,'utf8')).token || '')" "$BRIDGE_CONFIG"
  )"
  export BUBBLESKY_BRIDGE_TOKEN
fi

export BUBBLESKY_BRIDGE_URL="${BUBBLESKY_BRIDGE_URL:-http://127.0.0.1:25580}"
export CODEX_BOT_USERNAME="${CODEX_BOT_USERNAME:-Codex}"
export CODEX_BOT_ALIASES="${CODEX_BOT_ALIASES:-codex}"

if [ -z "${BUBBLESKY_BRIDGE_TOKEN:-}" ]; then
  echo "BUBBLESKY_BRIDGE_TOKEN is required (or provide $BRIDGE_CONFIG)." >&2
  exit 1
fi

cd "$ROOT"
exec node ./mcp/codex-bridge-godmode.mjs
