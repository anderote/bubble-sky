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
RESTART_JITTER_SECONDS="${CODEX_BOT_RESTART_JITTER_SECONDS:-3}"

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
  CODEX_BOT_RESTART_JITTER_SECONDS=3
EOF
}

last_meaningful_line() {
  tmux capture-pane -pt "$SESSION" -S -160 2>/dev/null |
    awk 'NF { line=$0 } END { if (line) print line }'
}

saved_visibility() {
  local state_path="${CODEX_CHAT_VISIBILITY_STATE:-$RUNTIME_DIR/chat-visibility.json}"
  if [ -f "$state_path" ]; then
    node -e 'const fs=require("fs"); const file=process.argv[1]; try { const state=JSON.parse(fs.readFileSync(file,"utf8")); if (state.visibility) console.log(state.visibility); } catch {}' "$state_path"
  fi
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

start() {
  if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "$SESSION already running"
    return
  fi

  mkdir -p "$LOG_DIR"
  local node
  node="$(node_bin)"
  tmux new-session -d -s "$SESSION" "attempts=0; while true; do started=\$(date +%s); cd '$ROOT_DIR' && MINECRAFT_HOST='$HOST' MINECRAFT_PORT='$PORT' CODEX_BOT_USERNAME='$USERNAME' '$node' ./mcp/codex-command-bot.mjs; code=\$?; ended=\$(date +%s); runtime=\$((ended - started)); if [ \$runtime -ge $RESTART_HEALTHY_SECONDS ]; then attempts=0; else attempts=\$((attempts + 1)); fi; exponent=\$((attempts - 1)); if [ \$exponent -lt 0 ]; then exponent=0; fi; if [ \$exponent -gt 8 ]; then exponent=8; fi; delay=\$(($RESTART_MIN_SECONDS << exponent)); if [ \$delay -gt $RESTART_MAX_SECONDS ]; then delay=$RESTART_MAX_SECONDS; fi; jitter=0; if [ $RESTART_JITTER_SECONDS -gt 0 ]; then jitter=\$((RANDOM % ($RESTART_JITTER_SECONDS + 1))); fi; wait_time=\$((delay + jitter)); echo '$SESSION exited' \$code 'after' \$runtime's; restarting in' \$wait_time's'; sleep \$wait_time; done"
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
      last_line="$(last_meaningful_line || true)"
      if [ -n "${last_line:-}" ]; then
        echo "last: $last_line"
      fi
    else
      echo "$SESSION not running"
    fi
    visibility="$(saved_visibility || true)"
    if [ -n "${visibility:-}" ]; then
      echo "saved visibility: $visibility"
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
