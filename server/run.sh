#!/usr/bin/env bash
# Launch the bubble-sky Fabric 1.21.6 dev server.
# Java 21 is pinned to a hermetic Temurin install under ~/.jdks (see server/README.md).
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.11+10/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Java 21 not found at $JAVA_HOME — see server/README.md for setup." >&2
  exit 1
fi
export JAVA_HOME

cd "$(dirname "$0")"
exec "$JAVA_HOME/bin/java" -Xmx2G -Xms2G -jar fabric-server-launch.jar nogui
