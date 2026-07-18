#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/infra/compose.yml"

compose() {
	if [ -f "$ROOT_DIR/.env" ]; then
		docker compose --env-file "$ROOT_DIR/.env" -f "$COMPOSE_FILE" "$@"
	else
		docker compose -f "$COMPOSE_FILE" "$@"
	fi
}

started_here=true
if [ -n "$(compose ps -q 2>/dev/null)" ]; then
	started_here=false
fi

cleanup() {
	if [ "$started_here" = true ]; then
		sh "$ROOT_DIR/scripts/verify-compose.sh" down
	fi
}

trap cleanup EXIT INT TERM

sh "$ROOT_DIR/scripts/verify-compose.sh" up
sh "$ROOT_DIR/scripts/verify-answer-rate-limit.sh"
echo "Infrastructure integration smoke tests passed."
