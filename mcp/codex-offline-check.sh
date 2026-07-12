#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

node_bin() {
  if command -v fnm >/dev/null 2>&1; then
    cd "$ROOT_DIR"
    eval "$(fnm env)"
    fnm install >/dev/null 2>&1
    fnm use >/dev/null 2>&1
  fi
  command -v node
}

NODE_BIN="$(node_bin)"

cd "$ROOT_DIR"

bash -n ./mcp/codex-command.sh
bash -n ./mcp/codex-swarm.sh

"$NODE_BIN" --check ./mcp/codex-command-bot.mjs
"$NODE_BIN" --check ./mcp/codex-swarm-worker.mjs
"$NODE_BIN" --check ./mcp/codex-swarm-status.mjs
"$NODE_BIN" --check ./mcp/codex-swarm-validate.mjs
CODEX_COMMAND_SELFTEST=1 "$NODE_BIN" ./mcp/codex-command-bot.mjs

./mcp/codex-swarm.sh validate
./mcp/codex-swarm.sh status >/dev/null

echo "codex offline checks passed"
