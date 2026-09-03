#!/usr/bin/env bash
set -Eeuo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
mode=${1:-combined}

if [[ "$mode" != "combined" && "$mode" != "split" ]]; then
  echo "Usage: scripts/run-dev.sh [combined|split]" >&2
  exit 2
fi

set -a
if [[ -f "$root_dir/.env" ]]; then
  source "$root_dir/.env"
fi
set +a

# Older local .env files predate account-scoped startup model access. Keep an explicitly configured
# allow-list authoritative, but make the documented local player and demo administrator usable when
# the setting is absent. This applies only to the local development launcher; deployment keeps its
# own narrower operator-controlled default.
if [[ -z "${RULEPILOT_MODELS_STARTUP_ALLOWED_USERS+x}" ]]; then
  export RULEPILOT_MODELS_STARTUP_ALLOWED_USERS="${RULEPILOT_USER_USERNAME:-player},${RULEPILOT_ADMIN_USERNAME:-admin}"
  echo "Local startup model access defaulted to the configured player and demo administrator accounts."
fi

backend_port=${BACKEND_PORT:-8080}
frontend_port=${FRONTEND_PORT:-5173}
export SERVER_PORT=${SERVER_PORT:-$backend_port}
export VITE_API_PROXY_TARGET=${VITE_API_PROXY_TARGET:-http://127.0.0.1:$backend_port}

check_port() {
  local port=$1
  local label=$2
  local owner
  owner=$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)
  if [[ -z "$owner" ]]; then
    return
  fi

  echo "$label cannot start because port $port is already in use:" >&2
  ps -p "$owner" -o pid=,command= >&2 || true
  echo "Run 'make dev-stop' if this is a RulePilot process left by an earlier run." >&2
  exit 1
}

check_port "$backend_port" "Backend"
check_port "$frontend_port" "Frontend"

if [[ "$mode" == "split" ]]; then
  (cd "$root_dir/backend" && ./mvnw -q -DskipTests package)
fi

declare -a child_names=()
declare -a child_pids=()
declare -a child_groups=()
cleanup_started=false

# Job control gives every background service its own process group. Maven/npm
# descendants then receive the same shutdown signal as their launcher.
set -m

start_service() {
  local name=$1
  shift
  "$@" &
  local pid=$!
  local group
  group=$(ps -o pgid= -p "$pid" | tr -d ' ')
  child_names+=("$name")
  child_pids+=("$pid")
  child_groups+=("${group:-$pid}")
}

group_is_alive() {
  kill -0 -- "-${1}" 2>/dev/null
}

signal_service() {
  local signal=$1
  local index=$2
  local group=${child_groups[$index]}
  local pid=${child_pids[$index]}
  if [[ -n "$group" ]] && group_is_alive "$group"; then
    kill "-$signal" -- "-$group" 2>/dev/null || true
  else
    kill "-$signal" "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ "$cleanup_started" == true ]]; then
    return
  fi
  cleanup_started=true
  trap - EXIT INT TERM

  if ((${#child_pids[@]} == 0)); then
    return
  fi

  echo
  echo "Stopping RulePilot development services..."
  local index
  for index in "${!child_pids[@]}"; do
    signal_service TERM "$index"
  done

  local attempt
  for attempt in {1..50}; do
    local running=false
    for index in "${!child_groups[@]}"; do
      if group_is_alive "${child_groups[$index]}"; then
        running=true
        break
      fi
    done
    if [[ "$running" == false ]]; then
      break
    fi
    sleep 0.1
  done

  for index in "${!child_pids[@]}"; do
    if group_is_alive "${child_groups[$index]}"; then
      signal_service KILL "$index"
    fi
    wait "${child_pids[$index]}" 2>/dev/null || true
  done
  echo "Development services stopped; ports $backend_port and $frontend_port are available."
}

trap 'exit 130' INT
trap 'exit 143' TERM
trap cleanup EXIT

if [[ "$mode" == "combined" ]]; then
  start_service backend bash -c 'cd "$1" && exec ./mvnw spring-boot:run' _ "$root_dir/backend"
else
  start_service api bash -c 'cd "$1" && exec java -jar target/rulepilot-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=api' _ "$root_dir/backend"
  start_service worker bash -c 'cd "$1" && exec java -jar target/rulepilot-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=worker' _ "$root_dir/backend"
fi
start_service frontend bash -c 'cd "$1" && exec npm run dev -- --host 127.0.0.1 --port "$2" --strictPort' _ "$root_dir/frontend" "$frontend_port"

echo "RulePilot is starting: backend http://127.0.0.1:$backend_port, frontend http://127.0.0.1:$frontend_port"
echo "Press Ctrl+C once to stop every development service."

while true; do
  for index in "${!child_pids[@]}"; do
    if ! kill -0 "${child_pids[$index]}" 2>/dev/null; then
      set +e
      wait "${child_pids[$index]}"
      exit_code=$?
      set -e
      echo "${child_names[$index]} exited with status $exit_code; stopping the remaining services." >&2
      exit "$exit_code"
    fi
  done
  sleep 0.5
done
