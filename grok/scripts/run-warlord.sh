#!/usr/bin/env bash
# Run the ENEMY AI WARLORD brain (design block #17b) against the mod-side bridge.
#
# Best path: run this on the Minecraft server host, where the bridge is bound to
# localhost by default. If the bridge is exposed on the LAN, set
# BUBBLESKY_BRIDGE_URL=http://<server-ip>:25580 (and a matching token).
#
# Env is loaded from grok/.env by warlord.js itself (ANTHROPIC_API_KEY,
# WARLORD_MODEL, bridge vars); this launcher just fills the bridge token from the
# server config if it isn't already set, mirroring scripts/run-codex-bridge.sh.
#
#   scripts/run-warlord.sh                 # main loop
#   scripts/run-warlord.sh --once --dry    # one cycle, print plan, do not post
set -euo pipefail

GROK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$GROK_DIR/.." && pwd)"
BRIDGE_CONFIG="${BRIDGE_CONFIG:-$ROOT/server/config/bubblesky-bridge.json}"

if [ -z "${BUBBLESKY_BRIDGE_TOKEN:-}" ] && [ -f "$BRIDGE_CONFIG" ]; then
  BUBBLESKY_BRIDGE_TOKEN="$(
    node -e "const fs=require('fs'); console.log(JSON.parse(fs.readFileSync(process.argv[1],'utf8')).token || '')" "$BRIDGE_CONFIG"
  )"
  export BUBBLESKY_BRIDGE_TOKEN
fi

export BUBBLESKY_BRIDGE_URL="${BUBBLESKY_BRIDGE_URL:-http://127.0.0.1:25580}"
export WARLORD_MODEL="${WARLORD_MODEL:-claude-opus-4-8}"

cd "$GROK_DIR"
exec node warlord.js "$@"
