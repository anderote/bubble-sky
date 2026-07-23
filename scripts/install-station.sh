#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${BUBBLE_STATION_CONFIG:-$HOME/.config/bubble-sky/station.json}"
LAUNCH_AGENTS="$HOME/Library/LaunchAgents"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "Bubble Sky Station background services currently require macOS." >&2
  exit 1
fi
if ! NODE="$(command -v node)" || [ -z "$NODE" ]; then
  echo "Node.js 22+ is required. Install it, then rerun this command." >&2
  exit 1
fi

mkdir -p "$(dirname "$CONFIG")" "$LAUNCH_AGENTS" "$HOME/Library/Logs/BubbleSky"
if [ ! -f "$CONFIG" ]; then
  echo "No Station config found at $CONFIG." >&2
  echo "Run the friendly setup first: $ROOT/scripts/station.mjs setup andrew" >&2
  exit 2
fi
chmod 600 "$CONFIG"

"$NODE" "$ROOT/scripts/station.mjs" doctor --config "$CONFIG"

write_plist() {
  local label="$1" program="$2" output="$3"
  local plist="$LAUNCH_AGENTS/$label.plist"
  /usr/bin/python3 - "$plist" "$label" "$NODE" "$program" "$CONFIG" "$output" "$PATH" <<'PY'
import plistlib, sys
file, label, node, program, config, output, shell_path = sys.argv[1:]
value = {
  "Label": label,
  "ProgramArguments": [node, program, config],
  "RunAtLoad": True,
  "KeepAlive": True,
  "WorkingDirectory": program.rsplit("/", 2)[0],
  "StandardOutPath": output,
  "StandardErrorPath": output,
  "ThrottleInterval": 10,
  "EnvironmentVariables": {"PATH": shell_path},
}
with open(file, "wb") as handle: plistlib.dump(value, handle)
PY
  launchctl bootout "gui/$UID/$label" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$UID" "$plist"
}

write_plist "dev.bubblesky.station" "$ROOT/control/station.mjs" "$HOME/Library/Logs/BubbleSky/station.log"
write_plist "dev.bubblesky.minecraft-chat" "$ROOT/control/minecraft-dev-gateway.mjs" "$HOME/Library/Logs/BubbleSky/minecraft-chat.log"
write_plist "dev.bubblesky.release-watcher" "$ROOT/control/release-watcher.mjs" "$HOME/Library/Logs/BubbleSky/release-watcher.log"

echo
echo "Bubble Sky Station installed and restarted."
echo "Status:       $ROOT/scripts/station.mjs status"
echo "Pair in game: $ROOT/scripts/station.mjs pair-code"
echo "Live logs:    $ROOT/scripts/station.mjs logs station"
