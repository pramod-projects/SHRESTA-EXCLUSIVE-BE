#!/usr/bin/env bash

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "$REPO_DIR/.." && pwd)"
BE_DIR="$WORKSPACE_DIR/SHRESTA-EXCLUSIVE-BE"
FE_DIR="$WORKSPACE_DIR/SHRESTA-EXCLUSIVE-WEB-FE"
RUN_DIR="$REPO_DIR/.run"
LOG_DIR="$REPO_DIR/.logs"
LOCK_DIR="$RUN_DIR/stack-control.lock"
ACTIVE_ENV_FILE="$RUN_DIR/active-environment"
DEV_COMPOSE_MARKER="$RUN_DIR/dev-compose-owned"
UAT_DEPENDENCY_MARKER="$RUN_DIR/uat-dependencies-owned"

FE_PORT="3010"
BE_PORT="8090"
BE_HEALTH_URL="http://127.0.0.1:$BE_PORT/api/v1/platform/health"
FE_HEALTH_URL="http://127.0.0.1:$FE_PORT/api/health"
DEV_DEPENDENCY_PORTS=(5442 6389 9010)
COMPOSE_COMMAND=()

mkdir -p "$RUN_DIR" "$LOG_DIR"

release_lock() {
  rm -rf "$LOCK_DIR" 2>/dev/null || true
}

acquire_lock() {
  local waited=0
  local lock_pid=""
  while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    if [[ -f "$LOCK_DIR/pid" ]]; then
      lock_pid="$(cat "$LOCK_DIR/pid")"
      if [[ -n "$lock_pid" ]] && ! kill -0 "$lock_pid" >/dev/null 2>&1; then
        echo "Removing stale stack lock (pid=$lock_pid)."
        rm -rf "$LOCK_DIR"
        continue
      fi
    fi

    sleep 1
    waited=$((waited + 1))
    if (( waited >= 60 )); then
      echo "Another stack operation is in progress. If this is stale, remove: $LOCK_DIR"
      exit 1
    fi
  done

  echo "$$" >"$LOCK_DIR/pid"
  trap release_lock EXIT
}

pid_file() {
  echo "$RUN_DIR/$1.pid"
}

log_file() {
  echo "$LOG_DIR/$1.log"
}

uppercase() {
  printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
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

require_commands() {
  local command missing=0
  for command in curl lsof nohup pgrep; do
    if ! command -v "$command" >/dev/null 2>&1; then
      echo "ERROR: Required command not found: $command" >&2
      missing=1
    fi
  done
  (( missing == 0 ))
}

resolve_compose_command() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE_COMMAND=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_COMMAND=(docker-compose)
  else
    return 1
  fi
}

all_dev_dependency_ports_ready() {
  local port
  for port in "${DEV_DEPENDENCY_PORTS[@]}"; do
    [[ -n "$(listening_pid "$port")" ]] || return 1
  done
}

wait_for_dev_dependencies() {
  local attempt
  echo "[dev-dependencies] waiting for PostgreSQL, Redis, and MinIO"
  for ((attempt = 1; attempt <= 90; attempt++)); do
    if all_dev_dependency_ports_ready; then
      echo "[dev-dependencies] ready"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: DEV dependencies did not become ready within 90s." >&2
  return 1
}

start_dev_dependencies() {
  local compose_containers=""

  if resolve_compose_command; then
    compose_containers="$(cd "$BE_DIR" && "${COMPOSE_COMMAND[@]}" --env-file .env.dev -f docker-compose.dev.yml ps -q 2>/dev/null || true)"
  fi

  if [[ -n "$compose_containers" ]] || ! all_dev_dependency_ports_ready; then
    if (( ${#COMPOSE_COMMAND[@]} == 0 )); then
      echo "ERROR: DEV dependencies are unavailable and Docker Compose is not installed." >&2
      return 1
    fi
    echo "[dev-dependencies] starting with ${COMPOSE_COMMAND[*]}..."
    (cd "$BE_DIR" && "${COMPOSE_COMMAND[@]}" --env-file .env.dev -f docker-compose.dev.yml up -d)
    touch "$DEV_COMPOSE_MARKER"
  else
    echo "[dev-dependencies] using externally managed services"
  fi

  wait_for_dev_dependencies
}

stop_dev_dependencies() {
  if [[ ! -f "$DEV_COMPOSE_MARKER" ]]; then
    return 0
  fi
  if ! resolve_compose_command; then
    echo "ERROR: Cannot stop controller-owned DEV dependencies because Docker Compose is unavailable." >&2
    return 1
  fi
  echo "[dev-dependencies] stopping (data volumes preserved)..."
  (cd "$BE_DIR" && "${COMPOSE_COMMAND[@]}" --env-file .env.dev -f docker-compose.dev.yml down)
  rm -f "$DEV_COMPOSE_MARKER"
  echo "[dev-dependencies] stopped"
}

status_dev_dependencies() {
  if all_dev_dependency_ports_ready; then
    if [[ -f "$DEV_COMPOSE_MARKER" ]]; then
      echo "[dev-dependencies] UP (compose-owned)"
    else
      echo "[dev-dependencies] UP (external)"
    fi
  else
    echo "[dev-dependencies] DOWN"
  fi
}

wait_for_http() {
  local service="$1"
  local url="$2"
  local expected_environment="$3"
  local attempts="${4:-90}"
  local attempt response

  echo "[$service] waiting for ${expected_environment} readiness: $url"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    response="$(curl --connect-timeout 2 --max-time 3 --fail --silent "$url" 2>/dev/null || true)"
    if grep -q "\"environmentMode\":\"$expected_environment\"" <<<"$response"; then
      echo "[$service] ready"
      return 0
    fi
    sleep 1
  done

  echo "ERROR: [$service] did not become ready within ${attempts}s: $url" >&2
  return 1
}

rollback_startup() {
  echo "Startup failed; stopping the partial stack." >&2
  stop_service "cloudflared-be"
  stop_matching_processes "cloudflared-be" "cloudflared tunnel.*run shresta-(uat|prod)-api"
  stop_service "fe" "$FE_PORT"
  stop_service "be" "$BE_PORT"
  "$BE_DIR/scripts/uat-dependencies" stop
  stop_dev_dependencies
  rm -f "$ACTIVE_ENV_FILE"
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
  local port="${2:-}"
  local pid listener_pid
  pid="$(current_pid "$service" || true)"

  if [[ -n "$port" ]] && { [[ -z "${pid:-}" ]] || ! is_running "$pid"; }; then
    pid="$(listening_pid "$port")"
  fi

  if [[ -z "${pid:-}" ]]; then
    echo "[$service] not running"
    rm -f "$(pid_file "$service")"
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

  if [[ -n "$port" ]]; then
    listener_pid="$(listening_pid "$port")"
    if [[ -n "${listener_pid:-}" ]] && is_running "$listener_pid"; then
      echo "[$service] stopping listener on port $port (pid=$listener_pid)..."
      kill "$listener_pid" >/dev/null 2>&1 || true
      for _ in {1..20}; do
        if ! is_running "$listener_pid"; then
          break
        fi
        sleep 0.3
      done
      if is_running "$listener_pid"; then
        kill -9 "$listener_pid" >/dev/null 2>&1 || true
      fi
    fi
  fi

  rm -f "$(pid_file "$service")"
  echo "[$service] stopped"
}

stop_matching_processes() {
  local label="$1"
  local pattern="$2"
  local pids
  pids="$(pgrep -f "$pattern" || true)"
  if [[ -z "$pids" ]]; then
    return 0
  fi

  echo "[$label] stopping untracked processes: $(printf '%s' "$pids" | tr '\n' ' ')"
  while IFS= read -r pid; do
    [[ -n "$pid" ]] && kill "$pid" >/dev/null 2>&1 || true
  done <<<"$pids"
}

status_service() {
  local service="$1"
  local port="${2:-}"
  local process_pattern="${3:-}"
  local pid
  pid="$(current_pid "$service" || true)"

  if [[ -n "${pid:-}" ]] && is_running "$pid"; then
    echo "[$service] UP (pid=$pid, log=$(log_file "$service"))"
    return 0
  fi

  if [[ -n "$port" ]]; then
    pid="$(listening_pid "$port")"
    if [[ -n "${pid:-}" ]] && is_running "$pid"; then
      echo "[$service] UP (untracked pid=$pid, port=$port)"
      return 0
    fi
  fi

  if [[ -n "$process_pattern" ]]; then
    pid="$(matching_pid "$process_pattern")"
    if [[ -n "${pid:-}" ]] && is_running "$pid"; then
      echo "[$service] UP (untracked pid=$pid)"
      return 0
    fi
  fi

  echo "[$service] DOWN"
}

normalize_environment() {
  local environment="${1:-dev}"
  environment="$(printf '%s' "$environment" | tr '[:upper:]' '[:lower:]')"

  case "$environment" in
    dev|uat|prod)
      printf '%s\n' "$environment"
      ;;
    *)
      echo "ERROR: Unsupported environment '$1'. Expected: dev, uat, or prod." >&2
      return 1
      ;;
  esac
}

validate_environment_files() {
  local environment="$1"
  local missing=0
  local env_file

  for env_file in "$BE_DIR/.env.$environment" "$FE_DIR/.env.$environment"; do
    if [[ ! -f "$env_file" ]]; then
      echo "ERROR: Required environment file not found: $env_file" >&2
      missing=1
    fi
  done

  if (( missing != 0 )); then
    echo "Create each missing file from its matching .env.$environment.example template." >&2
    return 1
  fi
}

any_stack_service_running() {
  local service pid
  for service in be fe cloudflared-be; do
    pid="$(current_pid "$service" || true)"
    if [[ -n "${pid:-}" ]] && is_running "$pid"; then
      return 0
    fi
  done
  [[ -n "$(listening_pid "$BE_PORT")" || -n "$(listening_pid "$FE_PORT")" \
    || -f "$DEV_COMPOSE_MARKER" || -f "$UAT_DEPENDENCY_MARKER" ]]
}

validate_active_environment() {
  local requested_environment="$1"
  local active_environment=""

  if [[ -f "$ACTIVE_ENV_FILE" ]]; then
    active_environment="$(cat "$ACTIVE_ENV_FILE")"
  fi

  if [[ -n "$active_environment" && "$active_environment" != "$requested_environment" ]] && any_stack_service_running; then
    echo "ERROR: The stack is already running in $(uppercase "$active_environment")." >&2
    echo "       Run ./down before starting $(uppercase "$requested_environment")." >&2
    return 1
  fi

  if [[ -z "$active_environment" ]] && any_stack_service_running; then
    echo "ERROR: Running stack processes have no recorded environment." >&2
    echo "       Run ./down, then retry ./up $requested_environment." >&2
    return 1
  fi
}

up() {
  local environment="$1"
  local public_api_url=""

  if [[ ! -d "$BE_DIR" || ! -d "$FE_DIR" ]]; then
    echo "Expected sibling repos under: $WORKSPACE_DIR"
    echo "Required: SHRESTA-EXCLUSIVE-BE and SHRESTA-EXCLUSIVE-WEB-FE"
    exit 1
  fi

  validate_environment_files "$environment"
  validate_active_environment "$environment"
  require_commands

  if [[ "$environment" == "dev" ]]; then
    stop_service "cloudflared-be"
    stop_matching_processes "cloudflared-be" "cloudflared tunnel.*run shresta-(uat|prod)-api"
    "$BE_DIR/scripts/uat-dependencies" stop
    start_dev_dependencies
  else
    "$BE_DIR/scripts/be-cloudflared" "$environment" --check
    case "$environment" in
      uat)
        public_api_url="https://uat-api.shrestaexclusive.com/api/v1/platform/health"
        "$BE_DIR/scripts/uat-dependencies" start
        ;;
      prod) public_api_url="https://api.shrestaexclusive.com/api/v1/platform/health" ;;
    esac
  fi

  local fe_pid
  fe_pid="$(current_pid "fe" || true)"
  if [[ -z "${fe_pid:-}" ]] || ! is_running "$fe_pid"; then
    fe_pid="$(listening_pid "$FE_PORT")"
  fi

  if [[ -z "${fe_pid:-}" ]]; then
    echo "[fe] running clean $(uppercase "$environment") build before startup..."
    rm -rf "$FE_DIR/.next"
    "$BE_DIR/scripts/fe-stack" "$environment" build
  else
    echo "[fe] already running (pid=$fe_pid); skipping build"
  fi

  if ! start_service "be" "$BE_DIR" "./scripts/be-$environment" "$BE_PORT" "shresta-be-0.0.1-SNAPSHOT.jar|./scripts/be-$environment"; then
    rollback_startup
    return 1
  fi
  if ! wait_for_http "be" "$BE_HEALTH_URL" "$(uppercase "$environment")"; then
    rollback_startup
    return 1
  fi

  if ! start_service "fe" "$BE_DIR" "./scripts/fe-stack $environment start" "$FE_PORT" "next start -p $FE_PORT|next-server"; then
    rollback_startup
    return 1
  fi
  if ! wait_for_http "fe" "$FE_HEALTH_URL" "$(uppercase "$environment")"; then
    rollback_startup
    return 1
  fi

  if [[ "$environment" != "dev" ]]; then
    if ! start_service "cloudflared-be" "$BE_DIR" "./scripts/be-cloudflared $environment" "" "cloudflared tunnel.*run shresta-${environment}-api"; then
      rollback_startup
      return 1
    fi
    if ! wait_for_http "cloudflared-be" "$public_api_url" "$(uppercase "$environment")"; then
      rollback_startup
      return 1
    fi
  fi

  printf '%s\n' "$environment" >"$ACTIVE_ENV_FILE"
  echo "All $(uppercase "$environment") services requested to start."
  status
}

down() {
  stop_service "cloudflared-be"
  stop_matching_processes "cloudflared-be" "cloudflared tunnel.*run shresta-(uat|prod)-api"
  stop_service "fe" "$FE_PORT"
  stop_service "be" "$BE_PORT"
  "$BE_DIR/scripts/uat-dependencies" stop
  stop_dev_dependencies
  rm -f "$ACTIVE_ENV_FILE"

  echo "All services requested to stop."
  status
}

status() {
  local active_environment=""
  if [[ -f "$ACTIVE_ENV_FILE" ]]; then
    active_environment="$(cat "$ACTIVE_ENV_FILE")"
    echo "[environment] $(printf '%s' "$active_environment" | tr '[:lower:]' '[:upper:]')"
  else
    echo "[environment] not recorded"
  fi
  if [[ "$active_environment" == "dev" ]] || [[ -f "$DEV_COMPOSE_MARKER" ]]; then
    status_dev_dependencies
  fi
  if [[ "$active_environment" == "uat" ]] || [[ -f "$UAT_DEPENDENCY_MARKER" ]]; then
    "$BE_DIR/scripts/uat-dependencies" status
  fi
  status_service "be" "$BE_PORT"
  status_service "fe" "$FE_PORT"
  status_service "cloudflared-be" "" "cloudflared tunnel.*run shresta-(uat|prod)-api"
}

logs() {
  local service="${1:-}"
  case "$service" in
    be|fe|cloudflared-be) ;;
    *)
      echo "Usage: $0 logs <be|fe|cloudflared-be>" >&2
      exit 1
      ;;
  esac

  if [[ ! -f "$(log_file "$service")" ]]; then
    echo "ERROR: No log exists for $service. Start the service first." >&2
    exit 1
  fi
  tail -n 120 -f "$(log_file "$service")"
}

usage() {
  cat <<'EOF'
Usage:
  ./up [dev|uat|prod]
  ./down
  ./status
  ./stack-control.sh logs <be|fe|cloudflared-be>

Notes:
- Services run via nohup and continue after terminal closes or screen locks.
- DEV is used when ./up is called without an environment.
- DEV uses localhost only and does not start Cloudflare.
- UAT and PROD start their named Cloudflare tunnel directly to the backend port.
- Run ./down before switching environments.
- Services stop only when you run ./down, machine shuts down, or process is killed.
EOF
}

main() {
  local action="${1:-}"
  case "$action" in
    UP|up)
      if (( $# > 2 )); then
        echo "ERROR: Too many arguments. Usage: ./up [dev|uat|prod]" >&2
        exit 1
      fi
      local environment
      environment="$(normalize_environment "${2:-dev}")"
      acquire_lock
      up "$environment"
      ;;
    DOWN|down)
      if (( $# != 1 )); then
        echo "ERROR: Usage: ./down" >&2
        exit 1
      fi
      acquire_lock
      down
      ;;
    STATUS|status)
      if (( $# != 1 )); then
        echo "ERROR: Usage: ./status" >&2
        exit 1
      fi
      status
      ;;
    logs)
      if (( $# != 2 )); then
        echo "Usage: $0 logs <be|fe|cloudflared-be>" >&2
        exit 1
      fi
      logs "${2:-}"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
