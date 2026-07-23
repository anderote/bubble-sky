#!/usr/bin/env bash
# deploy-modded.sh — build the towerdefense mod and (re)start a MODDED Fabric
# 1.21.6 server on :25566 with the in-JVM agent bridge on :25580.
#
# This server is separate from the vanilla server/ (:25565): it has its OWN
# mods/, world/, config/ and server.properties, but SYMLINKS the heavy,
# read-only launch infra (server.jar, launcher jar, libraries/, versions/) from
# server/ so we don't duplicate ~60MB. The main server/ dir is never modified.
#
# Idempotent: safe to re-run. It rebuilds the mod, syncs the jar, and restarts
# the modded server backgrounded (FIFO stdin + a log file). Bounded waits only.
#
# Carpet (fake-player embodiment) is intentionally NOT installed here — the
# godmode bridge does not need it for this phase (see BRIDGE.md).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/java21.sh
source "$ROOT/scripts/lib/java21.sh"
MAIN="$ROOT/server"
MODDED="$ROOT/server-modded"
MODPROJ="$ROOT/mods/towerdefense"
FABRIC_API="fabric-api-0.128.2+1.21.6.jar"

PORT="${MODDED_PORT:-25566}"
BRIDGE_PORT="${BUBBLESKY_BRIDGE_PORT:-25580}"
export BUBBLESKY_BRIDGE_PORT="$BRIDGE_PORT"
# A stable token for local dev; agents read the same value from this env var.
export BUBBLESKY_BRIDGE_TOKEN="${BUBBLESKY_BRIDGE_TOKEN:-bubblesky-dev-token}"

LOG="$MODDED/logs/modded-live.log"
FIFO="$MODDED/console.in"
SERVER_PID="$MODDED/.server.pid"
HOLDER_PID="$MODDED/.console-holder.pid"
SCREEN_SESSION="bubble-sky-modded"

log() { echo "[deploy-modded] $*"; }

# ---- 1. build the mod ----
log "building towerdefense mod…"
( cd "$MODPROJ" && ./gradlew build --console=plain -q )
JAR="$(ls -t "$MODPROJ"/build/libs/towerdefense-*.jar | grep -v sources | head -1)"
[ -n "$JAR" ] || { echo "no built jar found" >&2; exit 1; }
log "built $(basename "$JAR")"

# ---- 2. scaffold server-modded/ ----
mkdir -p "$MODDED/mods" "$MODDED/logs" "$MODDED/config"

# Symlink heavy read-only launch infra from the main server (never modified).
for item in server.jar fabric-server-launch.jar libraries versions; do
  if [ ! -e "$MODDED/$item" ]; then
    ln -s "$MAIN/$item" "$MODDED/$item"
    log "linked $item -> server/$item"
  fi
done

# Own launcher props / eula / server.properties (created once, then preserved).
[ -f "$MODDED/fabric-server-launcher.properties" ] || echo "serverJar=server.jar" > "$MODDED/fabric-server-launcher.properties"
echo "eula=true" > "$MODDED/eula.txt"
if [ ! -f "$MODDED/server.properties" ]; then
  cat > "$MODDED/server.properties" <<EOF
server-port=$PORT
query.port=$PORT
online-mode=false
gamemode=creative
difficulty=peaceful
level-name=world
motd=bubble-sky MODDED dev server (agent bridge)
spawn-protection=0
allow-flight=true
enable-command-block=true
op-permission-level=4
max-players=10
view-distance=10
simulation-distance=10
EOF
  log "wrote server.properties (port $PORT)"
fi

# Persist bridge config so the token/port survive restarts.
cat > "$MODDED/config/bubblesky-bridge.json" <<EOF
{
  "enabled": true,
  "port": $BRIDGE_PORT,
  "token": "$BUBBLESKY_BRIDGE_TOKEN"
}
EOF

# ---- 3. sync mods ----
rm -f "$MODDED"/mods/towerdefense-*.jar
cp "$JAR" "$MODDED/mods/"
if [ ! -f "$MODDED/mods/$FABRIC_API" ]; then
  cp "$MAIN/mods/$FABRIC_API" "$MODDED/mods/$FABRIC_API"
  log "copied $FABRIC_API"
fi
log "synced mods: $(ls "$MODDED"/mods | tr '\n' ' ')"

# ---- 4. stop any running modded server ----
stop_server() {
  local live_pid
  live_pid="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
  if [ -z "$live_pid" ] && [ -f "$SERVER_PID" ] && kill -0 "$(cat "$SERVER_PID")" 2>/dev/null; then
    live_pid="$(cat "$SERVER_PID")"
  fi
  if [ -n "$live_pid" ]; then
    log "stopping running modded server (pid $live_pid)…"
    [ -p "$FIFO" ] && echo "stop" > "$FIFO" || true
    for _ in $(seq 1 30); do
      kill -0 "$live_pid" 2>/dev/null || break
      sleep 1
    done
    kill "$live_pid" 2>/dev/null || true
  fi
  screen -S "$SCREEN_SESSION" -X quit >/dev/null 2>&1 || true
  if [ -f "$HOLDER_PID" ]; then kill "$(cat "$HOLDER_PID")" 2>/dev/null || true; fi
  rm -f "$SERVER_PID" "$HOLDER_PID"
}
stop_server

# ---- 5. start backgrounded with a FIFO console ----
rm -f "$FIFO"; mkfifo "$FIFO"
: > "$LOG"
if command -v screen >/dev/null 2>&1; then
  # A detached screen survives terminals and agent command runners closing. The FIFO stays
  # available for friendly console commands and graceful shutdowns.
  screen -dmS "$SCREEN_SESSION" /bin/bash -c '
    cd "$1"
    ( sleep 2147483647 > "$2" ) &
    echo $! > "$3"
    exec "$4" -Xmx2G -Xms2G -jar fabric-server-launch.jar nogui < "$2" >> "$5" 2>&1
  ' _ "$MODDED" "$FIFO" "$HOLDER_PID" "$JAVA_HOME/bin/java" "$LOG"
  log "modded server starting in detached session '$SCREEN_SESSION', log: $LOG"
else
  # Holder keeps the write end of the FIFO open so the server's stdin never EOFs.
  ( sleep 2147483647 > "$FIFO" ) &
  echo $! > "$HOLDER_PID"
  ( cd "$MODDED" && exec "$JAVA_HOME/bin/java" -Xmx2G -Xms2G -jar fabric-server-launch.jar nogui ) < "$FIFO" >> "$LOG" 2>&1 &
  echo $! > "$SERVER_PID"
  log "modded server starting (pid $(cat "$SERVER_PID")), log: $LOG"
fi

# ---- 6. bounded wait for readiness ----
READY=0
for _ in $(seq 1 150); do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then READY=1; break; fi
  if [ -f "$SERVER_PID" ] && ! kill -0 "$(cat "$SERVER_PID")" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$READY" = 1 ]; then
  LIVE_PID="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
  [ -n "$LIVE_PID" ] && echo "$LIVE_PID" > "$SERVER_PID"
  log "READY on :$PORT — bridge on http://127.0.0.1:$BRIDGE_PORT (X-Bridge-Token: $BUBBLESKY_BRIDGE_TOKEN)"
else
  log "server not ready within timeout; tail of log:" && tail -20 "$LOG" >&2
  exit 1
fi
