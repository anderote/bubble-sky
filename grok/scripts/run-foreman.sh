#!/usr/bin/env bash
# Run the COLONY FOREMAN brain (design block #18b) against the mod-side bridge.
#
# Best path: run this on the Minecraft server host, where the bridge is bound to
# localhost by default. If the bridge is exposed on the LAN, set
# BUBBLESKY_BRIDGE_URL=http://<server-ip>:25580 (and a matching token).
#
# Env is loaded from grok/.env by foreman.js itself (ANTHROPIC_API_KEY,
# FOREMAN_MODEL, bridge vars); this launcher just fills the bridge token from the
# server config if it isn't already set, mirroring scripts/run-warlord.sh.
#
#   scripts/run-foreman.sh                 # main loop
#   scripts/run-foreman.sh --once --dry    # one cycle, print orders, do not post
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
export FOREMAN_MODEL="${FOREMAN_MODEL:-claude-opus-4-8}"

cd "$GROK_DIR"
exec node foreman.js "$@"
