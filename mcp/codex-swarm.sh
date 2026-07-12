#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.codex-runtime/swarm"
HOST="${MINECRAFT_HOST:-192.168.86.188}"
PORT="${MINECRAFT_PORT:-25565}"
COUNT="${CODEX_SWARM_COUNT:-4}"
PREFIX="${CODEX_SWARM_PREFIX:-CodexDrone}"
BOSS="${CODEX_SWARM_BOSS:-CodexBoss}"
SESSION_PREFIX="${CODEX_SWARM_SESSION_PREFIX:-bubble-swarm}"
ANNOUNCE="${CODEX_SWARM_ANNOUNCE_ON_JOIN:-0}"
BUILD_MODE="${CODEX_SWARM_BUILD_MODE:-command}"

usage() {
  cat <<'EOF'
Usage:
  ./mcp/codex-swarm.sh start [count]
  ./mcp/codex-swarm.sh stop
  ./mcp/codex-swarm.sh status
  ./mcp/codex-swarm.sh logs [boss|drone-number]

In game:
  @swarm build cabin
  @swarm build watchtower
  @swarm status
  @swarm cancel

Environment:
  MINECRAFT_HOST=192.168.86.188
  MINECRAFT_PORT=25565
  CODEX_SWARM_COUNT=4
  CODEX_SWARM_PREFIX=CodexDrone
  CODEX_SWARM_BOSS=CodexBoss
  CODEX_SWARM_BUILD_MODE=command
EOF
}

node_bin() {
  if command -v fnm >/dev/null 2>&1; then
    eval "$(fnm env --use-on-cd)"
    cd "$ROOT_DIR"
    fnm install >/dev/null
    fnm use >/dev/null
  fi
  command -v node
}

tmux_session() {
  local name="$1"
  echo "${SESSION_PREFIX}-${name}"
}

start_session() {
  local name="$1"
  local command="$2"
  local session
  session="$(tmux_session "$name")"

  if tmux has-session -t "$session" 2>/dev/null; then
    echo "$session already running"
    return
  fi

  tmux new-session -d -s "$session" "while true; do $command; code=\$?; echo '$session exited' \$code '- restarting in 3s'; sleep 3; done"
  echo "started $session"
}

command="${1:-}"
case "$command" in
  start)
    COUNT="${2:-$COUNT}"
    mkdir -p "$RUNTIME_DIR"
    NODE_BIN="$(node_bin)"

    start_session boss "cd '$ROOT_DIR' && MINECRAFT_HOST='$HOST' MINECRAFT_PORT='$PORT' CODEX_SWARM_COUNT='$COUNT' CODEX_SWARM_PREFIX='$PREFIX' CODEX_SWARM_BOSS='$BOSS' CODEX_SWARM_RUNTIME='$RUNTIME_DIR' CODEX_SWARM_ANNOUNCE_ON_JOIN='$ANNOUNCE' '$NODE_BIN' ./mcp/codex-swarm-planner.mjs"

    for index in $(seq 1 "$COUNT"); do
      name="${PREFIX}${index}"
      start_session "drone-${index}" "cd '$ROOT_DIR' && MINECRAFT_HOST='$HOST' MINECRAFT_PORT='$PORT' CODEX_BOT_USERNAME='$name' CODEX_SWARM_RUNTIME='$RUNTIME_DIR' CODEX_SWARM_BUILD_MODE='$BUILD_MODE' CODEX_SWARM_ANNOUNCE_ON_JOIN='$ANNOUNCE' '$NODE_BIN' ./mcp/codex-swarm-worker.mjs"
      sleep 0.25
    done
    ;;
  stop)
    for session in $(tmux list-sessions -F '#S' 2>/dev/null | grep "^${SESSION_PREFIX}-" || true); do
      tmux kill-session -t "$session"
      echo "stopped $session"
    done
    ;;
  status)
    if ! tmux list-sessions -F '#S' 2>/dev/null | grep "^${SESSION_PREFIX}-"; then
      echo "no swarm sessions running"
    fi
    if [ -f "$RUNTIME_DIR/state.json" ]; then
      sed -n '1,80p' "$RUNTIME_DIR/state.json"
    fi
    ;;
  logs)
    target="${2:-boss}"
    if [ "$target" = "boss" ]; then
      tmux capture-pane -pt "$(tmux_session boss)" -S -120
    else
      tmux capture-pane -pt "$(tmux_session drone-"$target")" -S -120
    fi
    ;;
  *)
    usage
    exit 1
    ;;
esac
