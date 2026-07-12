#!/usr/bin/env bash
set -euo pipefail

if command -v fnm >/dev/null 2>&1; then
  eval "$(fnm env --use-on-cd)"
  fnm use >/dev/null
fi

exec npx -y github:yuniko-software/minecraft-mcp-server \
  --host "${MINECRAFT_HOST:-localhost}" \
  --port "${MINECRAFT_PORT:-25565}" \
  --username "${MCP_MINECRAFT_USERNAME:-ClaudeBot}" \
  "$@"
