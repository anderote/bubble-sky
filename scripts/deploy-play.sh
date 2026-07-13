#!/usr/bin/env bash
# deploy-play.sh — build the towerdefense mod and (re)start the PLAY server
# (server/ on :25565, agent bridge on :25580) with the freshly built jar.
#
# This is the server players actually connect to (LAN 25565). It already owns its
# server.properties (survival/easy) and config/bubblesky-bridge.json (real token),
# so — unlike deploy-modded.sh — this script NEVER rewrites those; it only rebuilds
# the mod, swaps the jar, optionally wipes the world for a fresh map, and restarts
# backgrounded with a FIFO console + log. Bounded waits only.
#
#   FRESH_MAP=1 scripts/deploy-play.sh   # move world aside, start a brand-new map
#   scripts/deploy-play.sh               # keep the existing world
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.11+10/Contents/Home}"
export JAVA_HOME
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Java 21 not found at $JAVA_HOME" >&2; exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRV="$ROOT/server"
MODPROJ="$ROOT/mods/towerdefense"
PORT=25565

LOG="$SRV/server-live.log"
FIFO="$SRV/console.in"
SERVER_PID="$SRV/.server.pid"
HOLDER_PID="$SRV/.console-holder.pid"
FRESH_MAP="${FRESH_MAP:-0}"

log() { echo "[deploy-play] $*"; }

# ---- 1. build the mod ----
log "building towerdefense mod…"
( cd "$MODPROJ" && ./gradlew build --console=plain -q )
JAR="$(ls -t "$MODPROJ"/build/libs/towerdefense-*.jar | grep -v sources | head -1)"
[ -n "$JAR" ] || { echo "no built jar found" >&2; exit 1; }
log "built $(basename "$JAR")"

# ---- 2. stop the running play server (graceful, then firm) ----
LIVE_PID="$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
if [ -n "$LIVE_PID" ]; then
  log "stopping live server (pid $LIVE_PID) via console 'stop'…"
  [ -p "$FIFO" ] && (echo stop > "$FIFO" 2>/dev/null || true) || true
  for _ in $(seq 1 30); do
    kill -0 "$LIVE_PID" 2>/dev/null || break
    sleep 1
  done
  kill "$LIVE_PID" 2>/dev/null || true
  for _ in $(seq 1 10); do kill -0 "$LIVE_PID" 2>/dev/null || break; sleep 1; done
fi
# Retire any stale FIFO holder from a previous launch.
[ -f "$HOLDER_PID" ] && kill "$(cat "$HOLDER_PID")" 2>/dev/null || true
rm -f "$SERVER_PID" "$HOLDER_PID"

# ---- 3. optional fresh map ----
if [ "$FRESH_MAP" = 1 ] && [ -d "$SRV/world" ]; then
  TS="$(date +%s)"
  mv "$SRV/world" "$SRV/world.discarded-$TS"
  log "fresh map: moved world -> world.discarded-$TS"
fi

# ---- 4. swap the jar ----
rm -f "$SRV"/mods/towerdefense-*.jar
cp "$JAR" "$SRV/mods/"
log "synced jar: $(basename "$JAR")"

# ---- 5. start backgrounded with a FIFO console ----
rm -f "$FIFO"; mkfifo "$FIFO"
# Holder keeps the FIFO's write end open so the server's stdin never EOFs.
( sleep 2147483647 > "$FIFO" ) &
echo $! > "$HOLDER_PID"

: > "$LOG"
( cd "$SRV" && exec "$JAVA_HOME/bin/java" -Xmx2G -Xms2G -jar fabric-server-launch.jar nogui ) < "$FIFO" >> "$LOG" 2>&1 &
echo $! > "$SERVER_PID"
log "server starting (pid $(cat "$SERVER_PID")), log: $LOG"

# ---- 6. bounded wait for readiness ----
READY=0
for _ in $(seq 1 150); do
  if grep -qE 'Done \([0-9]' "$LOG" 2>/dev/null; then READY=1; break; fi
  if ! kill -0 "$(cat "$SERVER_PID")" 2>/dev/null; then break; fi
  sleep 1
done
if [ "$READY" = 1 ]; then
  BR="$(grep -m1 'bridge.*listening' "$LOG" 2>/dev/null || true)"
  log "READY on :$PORT  ${BR:+(bridge up)}"
else
  log "server NOT ready within timeout; tail of log:" && tail -25 "$LOG" >&2
  exit 1
fi
