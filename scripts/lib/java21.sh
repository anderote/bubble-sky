#!/usr/bin/env bash
# Source this file to discover a real Java 21 installation without hard-coding one person's Mac.

bubblesky_java21() {
  local candidate=""
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    candidate="$JAVA_HOME"
  elif [ -x /usr/libexec/java_home ]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
  if [ -z "$candidate" ] && command -v brew >/dev/null 2>&1; then
    candidate="$(brew --prefix openjdk@21 2>/dev/null || true)"
  fi
  if [ -z "$candidate" ] && [ -d "$HOME/.jdks" ]; then
    candidate="$(find "$HOME/.jdks" -mindepth 1 -maxdepth 1 -type d -name '*21*' -print | sort | tail -1)"
  fi
  if [ -z "$candidate" ] || [ ! -x "$candidate/bin/java" ]; then
    echo "Java 21 was not found. Install it with 'brew install openjdk@21', or set JAVA_HOME." >&2
    return 1
  fi
  if ! "$candidate/bin/java" -version 2>&1 | head -1 | grep -Eq '"21([."]|$)'; then
    echo "JAVA_HOME does not point to Java 21: $candidate" >&2
    return 1
  fi
  JAVA_HOME="$candidate"
  export JAVA_HOME
}

bubblesky_java21
