#!/usr/bin/env sh

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORKSPACE_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -f "$REPO_ROOT/.env" ]; then
  set -a
  . "$REPO_ROOT/.env"
  set +a
fi

java_major_from_home() {
  _home="$1"
  if [ -z "$_home" ] || [ ! -x "$_home/bin/java" ]; then
    echo ""
    return
  fi

  "$_home/bin/java" -version 2>&1 \
    | awk -F '[\".]' '/version/ {print $2}' \
    | head -1
}

find_java21_home() {
  _found=""

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    _found=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  fi

  if [ -n "$_found" ] && [ -x "$_found/bin/java" ]; then
    echo "$_found"
    return
  fi

  for _candidate in \
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" \
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  do
    if [ -x "$_candidate/bin/java" ]; then
      echo "$_candidate"
      return
    fi
  done

  for _candidate in /Library/Java/JavaVirtualMachines/*21*.jdk/Contents/Home; do
    if [ -x "$_candidate/bin/java" ]; then
      echo "$_candidate"
      return
    fi
  done

  if command -v jenv >/dev/null 2>&1; then
    _found=$(jenv prefix 21 2>/dev/null || true)
    if [ -n "$_found" ] && [ -x "$_found/bin/java" ]; then
      echo "$_found"
      return
    fi
  fi

  echo ""
}

# Resolve JAVA_HOME in a portable way for fresh clones.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  JAVA_HOME=$(find_java21_home)

  if [ -z "${JAVA_HOME:-}" ] && command -v java >/dev/null 2>&1; then
    JAVA_BIN=$(command -v java)
    JAVA_HOME=$(CDPATH= cd -- "$(dirname -- "$JAVA_BIN")/.." && pwd)
  fi
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ERROR: Java 21+ not found." >&2
  echo "Set JAVA_HOME or install Java 21, then retry." >&2
  exit 1
fi

JAVA_MAJOR=$(java_major_from_home "$JAVA_HOME")
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
  JAVA21_HOME=$(find_java21_home)
  if [ -n "$JAVA21_HOME" ]; then
    JAVA_HOME="$JAVA21_HOME"
    JAVA_MAJOR=$(java_major_from_home "$JAVA_HOME")
  fi

  if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "ERROR: JAVA_HOME points to Java ${JAVA_MAJOR:-unknown}. Java 21+ is required." >&2
    echo "Current JAVA_HOME: $JAVA_HOME" >&2
    echo "Install Java 21 (macOS: brew install --cask temurin@21) and retry." >&2
    exit 1
  fi
fi

export JAVA_HOME
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$REPO_ROOT/.gradle-user-home}"
export TMPDIR="${TMPDIR:-$REPO_ROOT/.tmp}"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$GRADLE_USER_HOME" "$TMPDIR"
