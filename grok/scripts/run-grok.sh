#!/usr/bin/env bash
# Run GROK — our personal in-world assistant (assistant.js) — over the mod-side bridge.
#
# Grok listens to human players in chat and acts with godmode via the bridge. It
# IGNORES the swarm agents (Warlord/Foreman) and other bots (see BOTNAMES in
# assistant.js) so it never reacts to their taunts/orders (no feedback loop).
#
# Env is loaded from grok/.env by assistant.js itself (XAI_API_KEY, ANTHROPIC_API_KEY,
# GROK_ARCHITECT_*); this launcher sets the transport + bridge vars + allowlist,
# filling the bridge token from the server config if not already set.
#
#   scripts/run-grok.sh          # start Grok (bridge transport)
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

export GROK_TRANSPORT="${GROK_TRANSPORT:-bridge}"
export BUBBLESKY_BRIDGE_URL="${BUBBLESKY_BRIDGE_URL:-http://127.0.0.1:25580}"
# Respond to all human players plus the explicit allowlist; bots/agents are filtered
# by BOTNAMES inside assistant.js (Grok/Warlord/Foreman/Codex/… are ignored).
export GROK_ALLOW="${GROK_ALLOW:-*,viscousvermin9}"

cd "$GROK_DIR"
exec node assistant.js "$@"
