#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM_DIR="$ROOT_DIR/mindcraft/upstream"
PROFILE="${MINDCRAFT_PROFILE:-$ROOT_DIR/mindcraft/profiles/codex-claude.json}"
export MINDCRAFT_PROFILE="$PROFILE"

if [ ! -d "$UPSTREAM_DIR/node_modules" ]; then
  "$ROOT_DIR/mindcraft/bootstrap.sh"
fi

if [ ! -f "$UPSTREAM_DIR/keys.json" ]; then
  cat >&2 <<'EOF'
Missing mindcraft/upstream/keys.json.

Create it from mindcraft/upstream/keys.example.json and set ANTHROPIC_API_KEY
for the default codex profile.
EOF
  exit 1
fi

if command -v fnm >/dev/null 2>&1; then
  eval "$(fnm env --use-on-cd)"
  cd "$ROOT_DIR"
  fnm install
  fnm use
fi

export SETTINGS_JSON
SETTINGS_JSON="$(node -e '
const settings = {
  minecraft_version: process.env.MINECRAFT_VERSION || "1.21.6",
  host: process.env.MINECRAFT_HOST || "127.0.0.1",
  port: Number(process.env.MINECRAFT_PORT || 25565),
  auth: "offline",
  auto_open_ui: false,
  base_profile: "assistant",
  profiles: [process.env.MINDCRAFT_PROFILE],
  init_message: "Say hello and report your current position.",
  only_chat_with: [],
  chat_ingame: true,
  render_bot_view: false,
  allow_insecure_coding: false,
  allow_vision: false,
  narrate_behavior: true,
  chat_bot_messages: true,
  spawn_timeout: 45
};
process.stdout.write(JSON.stringify(settings));
')"

cd "$UPSTREAM_DIR"
exec node main.js --profiles "$PROFILE"
