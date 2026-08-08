#!/usr/bin/env bash

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "$REPO_DIR/.." && pwd)"
BE_DIR="$WORKSPACE_DIR/SHRESTA-EXCLUSIVE-BE"
FE_DIR="$WORKSPACE_DIR/SHRESTA-EXCLUSIVE-WEB-FE"
RUN_DIR="$REPO_DIR/.run"
LOG_DIR="$REPO_DIR/.logs"

FE_PORT="3010"
BE_PORT="8090"
CLOUDFLARED_PROXY_PORT="3310"

mkdir -p "$RUN_DIR" "$LOG_DIR"

pid_file() {
  echo "$RUN_DIR/$1.pid"
}

log_file() {
  echo "$LOG_DIR/$1.log"
}

is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

listening_pid() {
  local port="$1"
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

matching_pid() {
  local pattern="$1"
  pgrep -f "$pattern" | head -n 1 || true
}

current_pid() {
  local service="$1"
  local file
  file="$(pid_file "$service")"
  if [[ -f "$file" ]]; then
    cat "$file"
  fi
}

start_service() {
  local service="$1"
  local workdir="$2"
  local command="$3"
  local port="${4:-}"
  local process_pattern="${5:-}"
  local pid
  pid="$(current_pid "$service" || true)"

  if [[ -n "${pid:-}" ]] && is_running "$pid"; then
    echo "[$service] already running (pid=$pid)"
    return 0
  fi

  if [[ -n "$port" ]]; then
    pid="$(listening_pid "$port")"
    if [[ -n "${pid:-}" ]] && is_running "$pid"; then
      echo "$pid" >"$(pid_file "$service")"
      echo "[$service] already running on port $port (pid=$pid); adopted"
      return 0
    fi
  fi

  if [[ -n "$process_pattern" ]]; then
    pid="$(matching_pid "$process_pattern")"
    if [[ -n "${pid:-}" ]] && is_running "$pid"; then
      echo "$pid" >"$(pid_file "$service")"
      echo "[$service] matching process already running (pid=$pid); adopted"
      return 0
    fi
  fi

  rm -f "$(pid_file "$service")"

  echo "[$service] starting..."
  nohup bash -lc "cd \"$workdir\" && $command" >"$(log_file "$service")" 2>&1 &
  pid=$!
  echo "$pid" >"$(pid_file "$service")"

  sleep 1

  if is_running "$pid"; then
    echo "[$service] started (pid=$pid)"
  else
    # Recovery path: if startup exited because another valid process already
    # owns the service, adopt it instead of failing hard.
    if [[ -n "$port" ]]; then
      pid="$(listening_pid "$port")"
      if [[ -n "${pid:-}" ]] && is_running "$pid"; then
        echo "$pid" >"$(pid_file "$service")"
        echo "[$service] already running on port $port (pid=$pid); adopted"
        return 0
      fi
    fi

    if [[ -n "$process_pattern" ]]; then
      pid="$(matching_pid "$process_pattern")"
      if [[ -n "${pid:-}" ]] && is_running "$pid"; then
        echo "$pid" >"$(pid_file "$service")"
        echo "[$service] matching process already running (pid=$pid); adopted"
        return 0
      fi
    fi

    echo "[$service] failed to start. Recent logs:"
    tail -n 40 "$(log_file "$service")" || true
    return 1
  fi
}

stop_service() {
  local service="$1"
  local pid
  pid="$(current_pid "$service" || true)"

  if [[ -z "${pid:-}" ]]; then
    echo "[$service] not running"
    return 0
  fi

  if ! is_running "$pid"; then
    echo "[$service] stale pid file removed"
    rm -f "$(pid_file "$service")"
    return 0
  fi

  echo "[$service] stopping (pid=$pid)..."
  kill "$pid" >/dev/null 2>&1 || true

  for _ in {1..20}; do
    if ! is_running "$pid"; then
      break
    fi
    sleep 0.3
  done

  if is_running "$pid"; then
    echo "[$service] force stopping (pid=$pid)..."
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi

  rm -f "$(pid_file "$service")"
  echo "[$service] stopped"
}

status_service() {
  local service="$1"
  local pid
  pid="$(current_pid "$service" || true)"

  if [[ -n "${pid:-}" ]] && is_running "$pid"; then
    echo "[$service] UP (pid=$pid, log=$(log_file "$service"))"
  else
    echo "[$service] DOWN"
  fi
}

up() {
  if [[ ! -d "$BE_DIR" || ! -d "$FE_DIR" ]]; then
    echo "Expected sibling repos under: $WORKSPACE_DIR"
    echo "Required: SHRESTA-EXCLUSIVE-BE and SHRESTA-EXCLUSIVE-WEB-FE"
    exit 1
  fi

  if [[ ! -d "$FE_DIR/.next" ]]; then
    echo "[fe] .next build output not found; running npm run build once..."
    (cd "$FE_DIR" && npm run build)
  fi

  start_service "be" "$BE_DIR" "./scripts/be-uat" "$BE_PORT" "shresta-be-0.0.1-SNAPSHOT.jar|./scripts/be-uat"
  start_service "fe" "$FE_DIR" "npm run start" "$FE_PORT" "next start -p $FE_PORT|next-server"
  start_service "cloudflared-proxy" "$BE_DIR" "node ./scripts/cloudflared-proxy.mjs" "$CLOUDFLARED_PROXY_PORT" "cloudflared-proxy.mjs"
  start_service "cloudflared" "$WORKSPACE_DIR" "cloudflared tunnel --url http://127.0.0.1:$CLOUDFLARED_PROXY_PORT --no-autoupdate" "" "cloudflared tunnel --url http://127.0.0.1:$CLOUDFLARED_PROXY_PORT --no-autoupdate"

  echo "All services requested to start."
  status
}

down() {
  stop_service "cloudflared"
  stop_service "cloudflared-proxy"
  stop_service "fe"
  stop_service "be"

  echo "All services requested to stop."
  status
}

status() {
  status_service "be"
  status_service "fe"
  status_service "cloudflared-proxy"
  status_service "cloudflared"
}

logs() {
  local service="${1:-}"
  if [[ -z "$service" ]]; then
    echo "Usage: $0 logs <be|fe|cloudflared-proxy|cloudflared>"
    exit 1
  fi

  tail -n 120 -f "$(log_file "$service")"
}

usage() {
  cat <<'EOF'
Usage:
  ./up
  ./down
  ./status
  ./stack-control.sh logs <be|fe|cloudflared-proxy|cloudflared>

Notes:
- Services run via nohup and continue after terminal closes or screen locks.
- Services stop only when you run DOWN, machine shuts down, or process is killed.
EOF
}

main() {
  local action="${1:-}"
  case "$action" in
    UP|up)
      up
      ;;
    DOWN|down)
      down
      ;;
    STATUS|status)
      status
      ;;
    logs)
      logs "${2:-}"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
