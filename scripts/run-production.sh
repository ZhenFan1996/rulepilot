#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BASE_FILE="$ROOT_DIR/infra/compose.yml"
DEPLOYMENT_FILE="$ROOT_DIR/infra/compose.deployment.yml"
PRODUCTION_FILE="$ROOT_DIR/infra/compose.production.yml"

compose() {
	if [ -f "$ROOT_DIR/.env" ]; then
		docker compose --env-file "$ROOT_DIR/.env" \
			-f "$BASE_FILE" \
			-f "$DEPLOYMENT_FILE" \
			-f "$PRODUCTION_FILE" "$@"
	else
		docker compose \
			-f "$BASE_FILE" \
			-f "$DEPLOYMENT_FILE" \
			-f "$PRODUCTION_FILE" "$@"
	fi
}

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
	echo "Docker Compose v2 is required."
	exit 1
fi

case "${1:-config}" in
	config)
		compose config --quiet
		echo "Production deployment configuration is valid."
		;;
	up)
		compose up -d --build --wait
		;;
	down)
		compose down
		;;
	*)
		echo "Usage: $0 [config|up|down]"
		exit 2
		;;
esac
