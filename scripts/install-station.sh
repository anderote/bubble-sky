#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${BUBBLE_STATION_CONFIG:-$HOME/.config/bubble-sky/station.json}"
LAUNCH_AGENTS="$HOME/Library/LaunchAgents"
NODE="$(command -v node)"

mkdir -p "$(dirname "$CONFIG")" "$LAUNCH_AGENTS" "$HOME/Library/Logs/BubbleSky"
if [ ! -f "$CONFIG" ]; then
  cp "$ROOT/control/station.example.json" "$CONFIG"
  chmod 600 "$CONFIG"
  echo "Created $CONFIG. Set sharedToken, nodeId, providers, peers, and Minecraft names, then run this installer again." >&2
  exit 2
fi

write_plist() {
  local label="$1" program="$2" output="$3"
  local plist="$LAUNCH_AGENTS/$label.plist"
  /usr/bin/python3 - "$plist" "$label" "$NODE" "$program" "$CONFIG" "$output" <<'PY'
import plistlib, sys
file, label, node, program, config, output = sys.argv[1:]
value = {
  "Label": label,
  "ProgramArguments": [node, program, config],
  "RunAtLoad": True,
  "KeepAlive": True,
  "WorkingDirectory": program.rsplit("/", 2)[0],
  "StandardOutPath": output,
  "StandardErrorPath": output,
  "ThrottleInterval": 10,
}
with open(file, "wb") as handle: plistlib.dump(value, handle)
PY
  launchctl bootout "gui/$UID/$label" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$UID" "$plist"
}

write_plist "dev.bubblesky.station" "$ROOT/control/station.mjs" "$HOME/Library/Logs/BubbleSky/station.log"
write_plist "dev.bubblesky.minecraft-chat" "$ROOT/control/minecraft-dev-gateway.mjs" "$HOME/Library/Logs/BubbleSky/minecraft-chat.log"
write_plist "dev.bubblesky.release-watcher" "$ROOT/control/release-watcher.mjs" "$HOME/Library/Logs/BubbleSky/release-watcher.log"

echo "Bubble Sky Station installed. Pairing code: tail -f '$HOME/Library/Logs/BubbleSky/station.log'"
