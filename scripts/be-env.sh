#!/usr/bin/env sh

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORKSPACE_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -f "$REPO_ROOT/.env" ]; then
  set -a
  . "$REPO_ROOT/.env"
  set +a
fi

export JAVA_HOME="$WORKSPACE_ROOT/.tools/jdks/jdk-21.0.11+10/Contents/Home"
export GRADLE_USER_HOME="$WORKSPACE_ROOT/.tools/gradle-user-home"
export TMPDIR="$WORKSPACE_ROOT/.tools/tmp"
export PATH="$JAVA_HOME/bin:$WORKSPACE_ROOT/.tools/gradle/gradle-8.10/bin:$PATH"

mkdir -p "$GRADLE_USER_HOME" "$TMPDIR"
