#!/usr/bin/env bash
set -Eeuo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
set -a
if [[ -f "$root_dir/.env" ]]; then
  source "$root_dir/.env"
fi
set +a

ports=("${BACKEND_PORT:-8080}" "${FRONTEND_PORT:-5173}")
declare -a owned_pids=()

for port in "${ports[@]}"; do
  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1)
    if [[ "$cwd" == "$root_dir" || "$cwd" == "$root_dir/"* ]]; then
      owned_pids+=("$pid")
      echo "Stopping RulePilot process $pid on port $port..."
      kill -TERM "$pid" 2>/dev/null || true
    else
      echo "Port $port belongs to another project; leaving process $pid untouched." >&2
      ps -p "$pid" -o pid=,command= >&2 || true
    fi
  done < <(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
done

if ((${#owned_pids[@]} == 0)); then
  echo "No RulePilot development server is using ports ${ports[*]}."
  exit 0
fi

for attempt in {1..50}; do
  running=false
  for pid in "${owned_pids[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      running=true
      break
    fi
  done
  [[ "$running" == false ]] && break
  sleep 0.1
done

for pid in "${owned_pids[@]}"; do
  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
done

echo "RulePilot development ports are available again."
