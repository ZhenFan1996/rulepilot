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

validate_tracing_export() {
	case "${PRODUCTION_TRACING_EXPORT_OTLP_ENABLED:-false}" in
		false)
			;;
		true)
			if [ -z "${PRODUCTION_TRACING_OTLP_ENDPOINT:-}" ]; then
				echo "PRODUCTION_TRACING_OTLP_ENDPOINT is required when production OTLP tracing is enabled."
				exit 1
			fi
			;;
		*)
			echo "PRODUCTION_TRACING_EXPORT_OTLP_ENABLED must be true or false."
			exit 1
			;;
	esac
}

wait_for_api() {
	if ! command -v curl >/dev/null 2>&1; then
		echo "curl is required to verify the production API."
		exit 1
	fi

	ready_timeout_seconds=${PRODUCTION_API_READY_TIMEOUT_SECONDS:-300}
	case "$ready_timeout_seconds" in
		''|*[!0-9]*|0)
			echo "PRODUCTION_API_READY_TIMEOUT_SECONDS must be a positive integer."
			exit 1
			;;
	esac
	started_at=$(date +%s)
	deadline=$((started_at + ready_timeout_seconds))
	while [ "$(date +%s)" -lt "$deadline" ]; do
		if curl -fsS "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health" >/dev/null 2>&1; then
			ready_elapsed_seconds=$(($(date +%s) - started_at))
			printf 'Production API is ready after %s second(s).\n' "$ready_elapsed_seconds"
			return
		fi
		sleep 5
	done

	printf 'Production API did not become ready within %s second(s).\n' "$ready_timeout_seconds"
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
		validate_tracing_export
		compose config --quiet
		echo "Production deployment configuration is valid."
		;;
	up)
		validate_tracing_export
		# rsync can preserve a developer's restrictive target/ umask. Docker must be
		# able to traverse it or it silently reuses a stale application layer.
		chmod -R a+rX "$ROOT_DIR/backend/target"
		# Prometheus, Tempo, and Grafana are useful on demand but compete with
		# player-facing traffic on the production host's small CPU and memory budget.
		compose up -d --build --wait postgres redis rabbitmq minio
		compose stop prometheus tempo grafana >/dev/null 2>&1 || true
		case "${RULEPILOT_PREBUILT_BACKEND_IMAGE:-false}" in
			true)
				compose up -d --no-build --no-deps api
				;;
			false)
				compose up -d --build --no-deps api
				;;
			*)
				echo "RULEPILOT_PREBUILT_BACKEND_IMAGE must be true or false."
				exit 1
				;;
		esac
		wait_for_api
		compose up -d --build --no-deps frontend gateway
		wait_for_frontend
		compose up -d --no-deps worker
		echo "Production API, frontend, gateway, and worker are running."
		;;
	diagnose)
		echo "Production filesystem usage:"
		df -h "$ROOT_DIR" || true
		echo "Production Docker usage:"
		docker system df || true
		echo "Production container status:"
		compose ps --all || true
		echo "PostgreSQL runtime state:"
		postgres_container=$(compose ps -q postgres 2>/dev/null || true)
		if [ -n "$postgres_container" ]; then
			docker inspect --format '{{json .State}}' "$postgres_container" || true
		fi
		echo "Recent PostgreSQL logs:"
		compose logs --no-color --tail=120 postgres || true
		;;
	down)
		compose down
		;;
	*)
		echo "Usage: $0 [config|up|diagnose|down]"
		exit 2
		;;
esac
