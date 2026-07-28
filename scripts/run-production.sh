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

wait_for_api() {
	if ! command -v curl >/dev/null 2>&1; then
		echo "curl is required to verify the production API."
		exit 1
	fi

	attempt=1
	while [ "$attempt" -le 36 ]; do
		if curl -fsS "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health" >/dev/null 2>&1; then
			echo "Production API is ready."
			return
		fi
		attempt=$((attempt + 1))
		sleep 5
	done

	echo "Production API did not become ready."
	exit 1
}

wait_for_frontend() {
	frontend_address=${RULEPILOT_HTTP_PORT:-80}
	attempt=1
	while [ "$attempt" -le 18 ]; do
		if curl -fsS "http://127.0.0.1:${frontend_address}/" >/dev/null 2>&1; then
			echo "Production frontend is ready."
			return
		fi
		attempt=$((attempt + 1))
		sleep 5
	done

	echo "Production frontend did not become ready."
	exit 1
}

case "${1:-config}" in
	config)
		compose config --quiet
		echo "Production deployment configuration is valid."
		;;
	up)
		# rsync can preserve a developer's restrictive target/ umask. Docker must be
		# able to traverse it or it silently reuses a stale application layer.
		chmod -R a+rX "$ROOT_DIR/backend/target"
		compose up -d --build --wait postgres redis rabbitmq minio prometheus tempo grafana
		compose up -d --build --no-deps api
		wait_for_api
		compose up -d --build --no-deps frontend gateway
		wait_for_frontend
		compose up -d --no-deps worker
		echo "Production API, frontend, gateway, and worker are running."
		;;
	down)
		compose down
		;;
	*)
		echo "Usage: $0 [config|up|down]"
		exit 2
		;;
esac
