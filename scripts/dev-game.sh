#!/usr/bin/env bash
# One friendly command for the local modded dev game:
#   ./scripts/dev-game.sh                         # build, test, start, sync client, launch
#   PRISM_INSTANCE=bubble-sky-codex ./scripts/dev-game.sh
#   PRISM_OFFLINE_NAME=LocalTester ./scripts/dev-game.sh  # test-only Prism identity
#   ./scripts/dev-game.sh stop                    # stop only the local dev server
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODDED="$ROOT/server-modded"
ACTION="${1:-start}"

if [ "$ACTION" = "stop" ]; then
  if [ -f "$MODDED/.server.pid" ] && kill -0 "$(cat "$MODDED/.server.pid")" 2>/dev/null; then
    if [ -p "$MODDED/console.in" ]; then
      echo stop > "$MODDED/console.in"
      echo "Stopping the Bubble Sky dev server cleanly…"
    else
      echo "The dev server is running without its console pipe; stop it from its terminal." >&2
      exit 1
    fi
  else
    echo "The Bubble Sky dev server is already stopped."
  fi
  exit 0
fi
if [ "$ACTION" != "start" ]; then
  echo "Usage: ./scripts/dev-game.sh [start|stop]" >&2
  exit 2
fi

PRISM_ROOT="${PRISM_ROOT:-$HOME/Library/Application Support/PrismLauncher/instances}"
PRISM_INSTANCE="${PRISM_INSTANCE:-bubble-sky-1.21.6}"
CLIENT="$PRISM_ROOT/$PRISM_INSTANCE/.minecraft"
if [ ! -d "$CLIENT/mods" ]; then
  echo "Prism instance '$PRISM_INSTANCE' was not found at:" >&2
  echo "  $CLIENT" >&2
  echo "Set it explicitly, for example:" >&2
  echo "  PRISM_INSTANCE=bubble-sky-codex ./scripts/dev-game.sh" >&2
  if [ -d "$PRISM_ROOT" ]; then
    echo "Available instances:" >&2
    find "$PRISM_ROOT" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort | sed 's/^/  - /' >&2
  fi
  exit 1
fi

"$ROOT/scripts/deploy-modded.sh"

JAR="$(find "$ROOT/mods/towerdefense/build/libs" -maxdepth 1 -type f \
  -name 'towerdefense-*.jar' ! -name '*-sources.jar' -print | sort | tail -1)"
if [ -z "$JAR" ]; then
  echo "The Tower Defense build completed without a playable jar." >&2
  exit 1
fi
find "$CLIENT/mods" -maxdepth 1 -type f -name 'towerdefense-*.jar' -delete
cp "$JAR" "$CLIENT/mods/"

SERVER_JAR="$MODDED/mods/$(basename "$JAR")"
BUILD_SUM="$(shasum -a 256 "$JAR" | awk '{print $1}')"
SERVER_SUM="$(shasum -a 256 "$SERVER_JAR" | awk '{print $1}')"
CLIENT_SUM="$(shasum -a 256 "$CLIENT/mods/$(basename "$JAR")" | awk '{print $1}')"
if [ "$BUILD_SUM" != "$SERVER_SUM" ] || [ "$BUILD_SUM" != "$CLIENT_SUM" ]; then
  echo "Jar checksum mismatch; Minecraft was not launched." >&2
  exit 1
fi

PRISM_APP="${PRISM_APP:-$HOME/Applications/Prism Launcher.app}"
if [ ! -d "$PRISM_APP" ]; then
  PRISM_APP="/Applications/Prism Launcher.app"
fi
if [ ! -d "$PRISM_APP" ]; then
  echo "Server is ready and the client jar is synced, but Prism Launcher was not found." >&2
  echo "Open '$PRISM_INSTANCE' manually and join 127.0.0.1:25566." >&2
  exit 0
fi

echo "Exact client/server jar verified: ${BUILD_SUM:0:12}…"
echo "Launching '$PRISM_INSTANCE' and joining the local dev server at 127.0.0.1:25566."
# Start a fresh launcher process so the launch arguments are honored even when Prism's main
# window was already open.
LAUNCH_ARGS=(--launch "$PRISM_INSTANCE" --server 127.0.0.1:25566 --show-window)
if [ -n "${PRISM_OFFLINE_NAME:-}" ]; then
  LAUNCH_ARGS+=(--offline "$PRISM_OFFLINE_NAME")
fi
open -n -a "$PRISM_APP" --args "${LAUNCH_ARGS[@]}"
