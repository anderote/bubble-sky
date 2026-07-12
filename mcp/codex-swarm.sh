#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.codex-runtime/swarm"
LOG_DIR="$RUNTIME_DIR/logs"
HOST="${MINECRAFT_HOST:-192.168.86.188}"
PORT="${MINECRAFT_PORT:-25565}"
COUNT="${CODEX_SWARM_COUNT:-4}"
PREFIX="${CODEX_SWARM_PREFIX:-CodexDrone}"
BOSS="${CODEX_SWARM_BOSS:-CodexBoss}"
SESSION_PREFIX="${CODEX_SWARM_SESSION_PREFIX:-bubble-swarm}"
ANNOUNCE="${CODEX_SWARM_ANNOUNCE_ON_JOIN:-0}"
BUILD_MODE="${CODEX_SWARM_BUILD_MODE:-command}"
REPORT_INTERVAL_MS="${CODEX_SWARM_REPORT_INTERVAL_MS:-60000}"
DRONE_REPORT_EVERY_JOBS="${CODEX_DRONE_REPORT_EVERY_JOBS:-0}"
COMMAND_DELAY_MS="${CODEX_SWARM_COMMAND_DELAY_MS:-100}"
GLOBAL_COMMAND_DELAY_MS="${CODEX_SWARM_GLOBAL_COMMAND_DELAY_MS:-650}"
BATCH_SIZE="${CODEX_SWARM_BATCH_SIZE:-32}"
JOB_CHUNK_SIZE="${CODEX_SWARM_JOB_CHUNK_SIZE:-32}"
VERIFY_COMMANDS="${CODEX_SWARM_VERIFY_COMMANDS:-0}"
RESTART_MIN_SECONDS="${CODEX_SWARM_RESTART_MIN_SECONDS:-3}"
RESTART_MAX_SECONDS="${CODEX_SWARM_RESTART_MAX_SECONDS:-60}"
RESTART_HEALTHY_SECONDS="${CODEX_SWARM_RESTART_HEALTHY_SECONDS:-120}"
RESTART_JITTER_SECONDS="${CODEX_SWARM_RESTART_JITTER_SECONDS:-3}"

usage() {
  cat <<'EOF'
Usage:
  ./mcp/codex-swarm.sh start [count]
  ./mcp/codex-swarm.sh stop
  ./mcp/codex-swarm.sh status
  ./mcp/codex-swarm.sh validate [state.json]
  ./mcp/codex-swarm.sh logs [boss|drone-number]
  ./mcp/codex-swarm.sh logfiles

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
  CODEX_SWARM_COMMAND_DELAY_MS=100
  CODEX_SWARM_GLOBAL_COMMAND_DELAY_MS=650
  CODEX_SWARM_BATCH_SIZE=32
  CODEX_SWARM_JOB_CHUNK_SIZE=32
  CODEX_SWARM_VERIFY_COMMANDS=0
  CODEX_SWARM_REPORT_INTERVAL_MS=60000
  CODEX_DRONE_REPORT_EVERY_JOBS=0
  CODEX_SWARM_RESTART_MIN_SECONDS=3
  CODEX_SWARM_RESTART_MAX_SECONDS=60
  CODEX_SWARM_RESTART_HEALTHY_SECONDS=120
  CODEX_SWARM_RESTART_JITTER_SECONDS=3
EOF
}

node_bin() {
  if command -v fnm >/dev/null 2>&1; then
    cd "$ROOT_DIR"
    eval "$(fnm env)"
    fnm install >/dev/null 2>&1
    fnm use >/dev/null 2>&1
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

  mkdir -p "$LOG_DIR"
  tmux new-session -d -s "$session" "attempts=0; while true; do started=\$(date +%s); $command; code=\$?; ended=\$(date +%s); runtime=\$((ended - started)); if [ \$runtime -ge $RESTART_HEALTHY_SECONDS ]; then attempts=0; else attempts=\$((attempts + 1)); fi; exponent=\$((attempts - 1)); if [ \$exponent -lt 0 ]; then exponent=0; fi; if [ \$exponent -gt 8 ]; then exponent=8; fi; delay=\$(($RESTART_MIN_SECONDS << exponent)); if [ \$delay -gt $RESTART_MAX_SECONDS ]; then delay=$RESTART_MAX_SECONDS; fi; jitter=0; if [ $RESTART_JITTER_SECONDS -gt 0 ]; then jitter=\$((RANDOM % ($RESTART_JITTER_SECONDS + 1))); fi; wait_time=\$((delay + jitter)); echo '$session exited' \$code 'after' \$runtime's; restarting in' \$wait_time's'; sleep \$wait_time; done"
  tmux pipe-pane -o -t "$session" "cat >> '$LOG_DIR/${name}.log'"
  echo "started $session"
}

command="${1:-}"
case "$command" in
  start)
    COUNT="${2:-$COUNT}"
    mkdir -p "$RUNTIME_DIR"
    NODE_BIN="$(node_bin)"
    if [ -f "$ROOT_DIR/mcp/package.json" ]; then
      npm install --prefix "$ROOT_DIR/mcp" >/dev/null
    fi

    if [ -f "$ROOT_DIR/mcp/codex-swarm-planner.mjs" ]; then
      start_session boss "cd '$ROOT_DIR' && MINECRAFT_HOST='$HOST' MINECRAFT_PORT='$PORT' CODEX_SWARM_COUNT='$COUNT' CODEX_SWARM_PREFIX='$PREFIX' CODEX_SWARM_BOSS='$BOSS' CODEX_SWARM_RUNTIME='$RUNTIME_DIR' CODEX_SWARM_JOB_CHUNK_SIZE='$JOB_CHUNK_SIZE' CODEX_SWARM_ANNOUNCE_ON_JOIN='$ANNOUNCE' CODEX_SWARM_REPORT_INTERVAL_MS='$REPORT_INTERVAL_MS' '$NODE_BIN' ./mcp/codex-swarm-planner.mjs"
    fi

    for index in $(seq 1 "$COUNT"); do
      name="${PREFIX}${index}"
      start_session "drone-${index}" "cd '$ROOT_DIR' && MINECRAFT_HOST='$HOST' MINECRAFT_PORT='$PORT' CODEX_BOT_USERNAME='$name' CODEX_SWARM_RUNTIME='$RUNTIME_DIR' CODEX_SWARM_BUILD_MODE='$BUILD_MODE' CODEX_SWARM_COMMAND_DELAY_MS='$COMMAND_DELAY_MS' CODEX_SWARM_GLOBAL_COMMAND_DELAY_MS='$GLOBAL_COMMAND_DELAY_MS' CODEX_SWARM_BATCH_SIZE='$BATCH_SIZE' CODEX_SWARM_VERIFY_COMMANDS='$VERIFY_COMMANDS' CODEX_SWARM_ANNOUNCE_ON_JOIN='$ANNOUNCE' CODEX_DRONE_REPORT_EVERY_JOBS='$DRONE_REPORT_EVERY_JOBS' '$NODE_BIN' ./mcp/codex-swarm-worker.mjs"
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
      NODE_BIN="$(node_bin)"
      CODEX_SWARM_RUNTIME="$RUNTIME_DIR" "$NODE_BIN" "$ROOT_DIR/mcp/codex-swarm-status.mjs"
    fi
    ;;
  validate)
    NODE_BIN="$(node_bin)"
    CODEX_SWARM_RUNTIME="$RUNTIME_DIR" MINECRAFT_VERSION="${MINECRAFT_VERSION:-1.21.6}" "$NODE_BIN" "$ROOT_DIR/mcp/codex-swarm-validate.mjs" "${2:-}"
    ;;
  logs)
    target="${2:-boss}"
    if [ "$target" = "boss" ]; then
      tmux capture-pane -pt "$(tmux_session boss)" -S -120
    else
      tmux capture-pane -pt "$(tmux_session drone-"$target")" -S -120
    fi
    ;;
  logfiles)
    mkdir -p "$LOG_DIR"
    echo "$LOG_DIR"
    ls -la "$LOG_DIR"
    ;;
  *)
    usage
    exit 1
    ;;
esac
