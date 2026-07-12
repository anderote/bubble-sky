#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.codex-runtime"
LOG_DIR="$RUNTIME_DIR/logs"
SESSION="${CODEX_COMMAND_SESSION:-bubble-codex-chat}"
HOST="${MINECRAFT_HOST:-192.168.86.188}"
PORT="${MINECRAFT_PORT:-25565}"
USERNAME="${CODEX_BOT_USERNAME:-codex}"
RESTART_MIN_SECONDS="${CODEX_BOT_RESTART_MIN_SECONDS:-3}"
RESTART_MAX_SECONDS="${CODEX_BOT_RESTART_MAX_SECONDS:-60}"
RESTART_HEALTHY_SECONDS="${CODEX_BOT_RESTART_HEALTHY_SECONDS:-120}"

usage() {
  cat <<'EOF'
Usage:
  ./mcp/codex-command.sh start
  ./mcp/codex-command.sh stop
  ./mcp/codex-command.sh status
  ./mcp/codex-command.sh logs

Environment:
  MINECRAFT_HOST=192.168.86.188
  MINECRAFT_PORT=25565
  CODEX_BOT_USERNAME=codex
  CODEX_COMMAND_SESSION=bubble-codex-chat
  CODEX_BOT_RESTART_MIN_SECONDS=3
  CODEX_BOT_RESTART_MAX_SECONDS=60
  CODEX_BOT_RESTART_HEALTHY_SECONDS=120
EOF
}

node_bin() {
  if command -v fnm >/dev/null 2>&1; then
    eval "$(fnm env --use-on-cd)"
    cd "$ROOT_DIR"
    fnm install >/dev/null 2>&1
    fnm use >/dev/null 2>&1
  fi
  command -v node
}

start() {
  if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "$SESSION already running"
    return
  fi

  mkdir -p "$LOG_DIR"
  local node
  node="$(node_bin)"
  tmux new-session -d -s "$SESSION" "attempts=0; while true; do started=\$(date +%s); cd '$ROOT_DIR' && MINECRAFT_HOST='$HOST' MINECRAFT_PORT='$PORT' CODEX_BOT_USERNAME='$USERNAME' '$node' ./mcp/codex-command-bot.mjs; code=\$?; ended=\$(date +%s); runtime=\$((ended - started)); if [ \$runtime -ge $RESTART_HEALTHY_SECONDS ]; then attempts=0; else attempts=\$((attempts + 1)); fi; exponent=\$((attempts - 1)); if [ \$exponent -lt 0 ]; then exponent=0; fi; if [ \$exponent -gt 8 ]; then exponent=8; fi; delay=\$(($RESTART_MIN_SECONDS << exponent)); if [ \$delay -gt $RESTART_MAX_SECONDS ]; then delay=$RESTART_MAX_SECONDS; fi; echo '$SESSION exited' \$code 'after' \$runtime's; restarting in' \$delay's'; sleep \$delay; done"
  tmux pipe-pane -o -t "$SESSION" "cat >> '$LOG_DIR/${SESSION}.log'"
  echo "started $SESSION"
}

case "${1:-}" in
  start)
    start
    ;;
  stop)
    tmux kill-session -t "$SESSION" 2>/dev/null || true
    echo "stopped $SESSION"
    ;;
  status)
    if tmux has-session -t "$SESSION" 2>/dev/null; then
      echo "$SESSION running"
    else
      echo "$SESSION not running"
    fi
    ;;
  logs)
    tmux capture-pane -pt "$SESSION" -S -120
    ;;
  *)
    usage
    exit 1
    ;;
esac
