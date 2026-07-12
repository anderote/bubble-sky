#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.codex-runtime/swarm"
HOST="${MINECRAFT_HOST:-192.168.86.188}"
PORT="${MINECRAFT_PORT:-25565}"
COUNT="${CODEX_SWARM_COUNT:-3}"
TASK="${CODEX_SWARM_TASK:-standby}"
PREFIX="${CODEX_SWARM_PREFIX:-CodexSwarm}"
ORIGIN="${CODEX_SWARM_ORIGIN:-}"

usage() {
  cat <<'EOF'
Usage:
  ./mcp/codex-swarm.sh start [count] [task]
  ./mcp/codex-swarm.sh stop
  ./mcp/codex-swarm.sh status
  ./mcp/codex-swarm.sh logs [bot-number]

Environment:
  MINECRAFT_HOST=192.168.86.188
  MINECRAFT_PORT=25565
  CODEX_SWARM_COUNT=3
  CODEX_SWARM_TASK=standby|survey|flatten|castle
  CODEX_SWARM_PREFIX=CodexSwarm
  CODEX_SWARM_ORIGIN=x,y,z
EOF
}

command="${1:-}"
case "$command" in
  start)
    COUNT="${2:-$COUNT}"
    TASK="${3:-$TASK}"
    mkdir -p "$RUNTIME_DIR/logs" "$RUNTIME_DIR/pids"
    if command -v fnm >/dev/null 2>&1; then
      eval "$(fnm env --use-on-cd)"
      cd "$ROOT_DIR"
      fnm install >/dev/null
      fnm use >/dev/null
    fi
    NODE_BIN="$(command -v node)"

    for index in $(seq 0 $((COUNT - 1))); do
      bot_number=$((index + 1))
      name="${PREFIX}${bot_number}"
      pid_file="$RUNTIME_DIR/pids/${name}.pid"
      log_file="$RUNTIME_DIR/logs/${name}.log"

      if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "$name already running as pid $(cat "$pid_file")"
        continue
      fi

      cd "$ROOT_DIR"
      env \
        MINECRAFT_HOST="$HOST" \
        MINECRAFT_PORT="$PORT" \
        CODEX_BOT_USERNAME="$name" \
        CODEX_SWARM_INDEX="$index" \
        CODEX_SWARM_COUNT="$COUNT" \
        CODEX_SWARM_TASK="$TASK" \
        CODEX_SWARM_ORIGIN="$ORIGIN" \
        nohup "$NODE_BIN" ./mcp/codex-swarm-worker.mjs >"$log_file" 2>&1 &
      echo $! >"$pid_file"
      sleep 0.5
      if kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        echo "started $name pid $(cat "$pid_file") task=$TASK log=$log_file"
      else
        echo "failed to start $name; see $log_file"
      fi
    done
    ;;
  stop)
    if [ ! -d "$RUNTIME_DIR/pids" ]; then
      echo "no swarm runtime found"
      exit 0
    fi
    for pid_file in "$RUNTIME_DIR"/pids/*.pid; do
      [ -e "$pid_file" ] || continue
      pid="$(cat "$pid_file")"
      name="$(basename "$pid_file" .pid)"
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid"
        echo "stopped $name pid $pid"
      else
        echo "$name pid $pid was not running"
      fi
      rm -f "$pid_file"
    done
    ;;
  status)
    if [ ! -d "$RUNTIME_DIR/pids" ]; then
      echo "no swarm runtime found"
      exit 0
    fi
    for pid_file in "$RUNTIME_DIR"/pids/*.pid; do
      [ -e "$pid_file" ] || continue
      pid="$(cat "$pid_file")"
      name="$(basename "$pid_file" .pid)"
      if kill -0 "$pid" 2>/dev/null; then
        echo "$name running pid $pid"
      else
        echo "$name stale pid $pid"
      fi
    done
    ;;
  logs)
    bot="${2:-}"
    if [ -n "$bot" ]; then
      tail -n 80 "$RUNTIME_DIR/logs/${PREFIX}${bot}.log"
    else
      tail -n 30 "$RUNTIME_DIR"/logs/*.log
    fi
    ;;
  *)
    usage
    exit 1
    ;;
esac
