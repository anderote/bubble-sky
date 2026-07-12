#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MINDCRAFT_DIR="$ROOT_DIR/mindcraft"

if command -v fnm >/dev/null 2>&1; then
  eval "$(fnm env --use-on-cd)"
  cd "$ROOT_DIR"
  fnm install
  fnm use
fi

cd "$MINDCRAFT_DIR"

if [ -d upstream/.git ]; then
  git -C upstream pull --ff-only
else
  git clone https://github.com/mindcraft-bots/mindcraft.git upstream
fi

cd upstream
rm -f package-lock.json
npm pkg set \
  dependencies.minecraft-data=3.97.0 \
  dependencies.mineflayer=4.33.0 \
  dependencies.prismarine-viewer=1.33.0
npm install
