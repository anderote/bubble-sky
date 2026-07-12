#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.codex-runtime/codexbot"
HOST="${MINECRAFT_HOST:-192.168.86.188}"
PORT="${MINECRAFT_PORT:-25565}"
NAME="${CODEX_BOT_USERNAME:-CodexBot}"

usage() {
  cat <<'EOF'
Usage:
  ./mcp/codex-bot.sh start
  ./mcp/codex-bot.sh stop
  ./mcp/codex-bot.sh status
  ./mcp/codex-bot.sh logs
EOF
}

command="${1:-}"
pid_file="$RUNTIME_DIR/${NAME}.pid"
log_file="$RUNTIME_DIR/${NAME}.log"

case "$command" in
  start)
    mkdir -p "$RUNTIME_DIR"
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      echo "$NAME already running as pid $(cat "$pid_file")"
      exit 0
    fi

    if command -v fnm >/dev/null 2>&1; then
      eval "$(fnm env --use-on-cd)"
      cd "$ROOT_DIR"
      fnm install >/dev/null
      fnm use >/dev/null
    fi
    NODE_BIN="$(command -v node)"

    cd "$ROOT_DIR"
    env \
      MINECRAFT_HOST="$HOST" \
      MINECRAFT_PORT="$PORT" \
      CODEX_BOT_USERNAME="$NAME" \
      nohup "$NODE_BIN" ./mcp/codex-command-bot.mjs >"$log_file" 2>&1 &
    echo $! >"$pid_file"
    sleep 0.5
    if kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      echo "started $NAME pid $(cat "$pid_file") log=$log_file"
    else
      echo "failed to start $NAME; see $log_file"
      exit 1
    fi
    ;;
  stop)
    if [ ! -f "$pid_file" ]; then
      echo "$NAME is not running"
      exit 0
    fi
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid"
      echo "stopped $NAME pid $pid"
    else
      echo "$NAME pid $pid was not running"
    fi
    rm -f "$pid_file"
    ;;
  status)
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      echo "$NAME running pid $(cat "$pid_file")"
    else
      echo "$NAME not running"
    fi
    ;;
  logs)
    tail -n 80 "$log_file"
    ;;
  *)
    usage
    exit 1
    ;;
esac
