#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
BASE_FILE="$ROOT_DIR/infra/compose.yml"
DEPLOYMENT_FILE="$ROOT_DIR/infra/compose.deployment.yml"
PRODUCTION_FILE="$ROOT_DIR/infra/compose.production.yml"

read_managed_production_setting() {
	case "$1" in
		PRODUCTION_TRACING_EXPORT_OTLP_ENABLED|PRODUCTION_TRACING_OTLP_ENDPOINT|PRODUCTION_TRACING_SAMPLING_PROBABILITY|TEMPO_PORT)
			;;
		*)
			return 1
			;;
	esac
	value=$(sed -n "s/^$1=//p" "$ROOT_DIR/.env" | head -n 1)
	case "$value" in
		\"*\") value=${value#\"}; value=${value%\"} ;;
		\'*\') value=${value#\'}; value=${value%\'} ;;
	esac
	printf '%s' "$value"
}

# Docker Compose reads the deployment-owned .env directly, while this launcher also needs the three allow-listed
# tracing controls to decide whether Tempo should run. Never source the file: it contains credentials and is data.
if [ -f "$ROOT_DIR/.env" ]; then
	if [ -z "${PRODUCTION_TRACING_EXPORT_OTLP_ENABLED+x}" ]; then
		managed_value=$(read_managed_production_setting PRODUCTION_TRACING_EXPORT_OTLP_ENABLED)
		if [ -n "$managed_value" ]; then
			PRODUCTION_TRACING_EXPORT_OTLP_ENABLED=$managed_value
			export PRODUCTION_TRACING_EXPORT_OTLP_ENABLED
		fi
	fi
	if [ -z "${PRODUCTION_TRACING_OTLP_ENDPOINT+x}" ]; then
		managed_value=$(read_managed_production_setting PRODUCTION_TRACING_OTLP_ENDPOINT)
		if [ -n "$managed_value" ]; then
			PRODUCTION_TRACING_OTLP_ENDPOINT=$managed_value
			export PRODUCTION_TRACING_OTLP_ENDPOINT
		fi
	fi
	if [ -z "${PRODUCTION_TRACING_SAMPLING_PROBABILITY+x}" ]; then
		managed_value=$(read_managed_production_setting PRODUCTION_TRACING_SAMPLING_PROBABILITY)
		if [ -n "$managed_value" ]; then
			PRODUCTION_TRACING_SAMPLING_PROBABILITY=$managed_value
			export PRODUCTION_TRACING_SAMPLING_PROBABILITY
		fi
	fi
	if [ -z "${TEMPO_PORT+x}" ]; then
		managed_value=$(read_managed_production_setting TEMPO_PORT)
		if [ -n "$managed_value" ]; then
			TEMPO_PORT=$managed_value
			export TEMPO_PORT
		fi
	fi
	unset managed_value
fi

# Release directories are immutable and named by the deployment workflow. Exporting that directory name makes
# every backend span queryable by the exact release without persisting another mutable deployment coordinate.
RULEPILOT_RELEASE_ID=${RULEPILOT_RELEASE_ID:-${ROOT_DIR##*/}}
export RULEPILOT_RELEASE_ID

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
	if [ "${TEMPO_PORT:-3200}" != 3200 ]; then
		echo "Production Tempo must use the fixed loopback port 3200."
		exit 1
	fi
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
	frontend_ready_attempts=${PRODUCTION_FRONTEND_READY_ATTEMPTS:-18}
	case "$frontend_ready_attempts" in
		''|*[!0-9]*|0)
			echo "PRODUCTION_FRONTEND_READY_ATTEMPTS must be a positive integer."
			exit 1
			;;
	esac
	attempt=1
	while [ "$attempt" -le "$frontend_ready_attempts" ]; do
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

wait_for_worker() {
	worker_ready_attempts=${PRODUCTION_WORKER_READY_ATTEMPTS:-60}
	case "$worker_ready_attempts" in
		''|*[!0-9]*|0)
			echo "PRODUCTION_WORKER_READY_ATTEMPTS must be a positive integer."
			exit 1
			;;
	esac
	expected_worker_image=$(docker image inspect --format '{{.Id}}' \
		"${RULEPILOT_BACKEND_IMAGE:-rulepilot-backend:local}")
	attempt=1
	while [ "$attempt" -le "$worker_ready_attempts" ]; do
		worker_container=$(compose ps -q worker 2>/dev/null || true)
		if [ -n "$worker_container" ]; then
			worker_state=$(docker inspect --format '{{.State.Status}}' "$worker_container")
			worker_health=$(docker inspect --format \
				'{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
				"$worker_container")
			worker_image=$(docker inspect --format '{{.Image}}' "$worker_container")
			if [ "$worker_state" = running ] \
				&& [ "$worker_health" = healthy ] \
				&& [ "$worker_image" = "$expected_worker_image" ]; then
				echo "Production worker is ready on the exact backend image."
				return
			fi
			if [ "$worker_state" = exited ] || [ "$worker_state" = dead ]; then
				echo "Production worker stopped before becoming ready."
				exit 1
			fi
		fi
		attempt=$((attempt + 1))
		sleep 2
	done

	echo "Production worker did not become ready."
	exit 1
}

wait_for_stateful_dependencies() {
	ready_timeout_seconds=${PRODUCTION_INFRASTRUCTURE_READY_TIMEOUT_SECONDS:-180}
	case "$ready_timeout_seconds" in
		''|*[!0-9]*|0)
			echo "PRODUCTION_INFRASTRUCTURE_READY_TIMEOUT_SECONDS must be a positive integer."
			return 1
			;;
	esac
	started_at=$(date +%s)
	deadline=$((started_at + ready_timeout_seconds))
	stable_samples=0
	last_observation="no containers observed"
	while [ "$(date +%s)" -lt "$deadline" ]; do
		all_healthy=true
		unsettled_services=
		for service in postgres redis rabbitmq minio; do
			container=$(compose ps -q "$service" 2>/dev/null || true)
			container_state=missing
			container_health=missing
			if [ -n "$container" ]; then
				container_state=$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null || true)
				container_health=$(docker inspect --format \
					'{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
					"$container" 2>/dev/null || true)
			fi
			if [ "$container_state" != running ] || [ "$container_health" != healthy ]; then
				all_healthy=false
				unsettled_services="${unsettled_services}${unsettled_services:+, }${service}(${container_state}/${container_health})"
			fi
		done
		if [ "$all_healthy" = true ]; then
			stable_samples=$((stable_samples + 1))
			last_observation="all healthy; stability sample ${stable_samples}/12"
			if [ "$stable_samples" -ge 12 ]; then
				echo "Production stateful dependencies are healthy for 12 consecutive samples."
				return 0
			fi
		else
			stable_samples=0
			last_observation=$unsettled_services
		fi
		printf 'Waiting for production stateful dependencies: %s\n' "$last_observation"
		sleep 5
	done

	printf 'Production stateful dependencies did not remain healthy within %s second(s): %s\n' \
		"$ready_timeout_seconds" "$last_observation"
	return 1
}

wait_for_tempo() {
	tempo_address=${TEMPO_PORT:-3200}
	attempt=1
	while [ "$attempt" -le 20 ]; do
		if curl -fsS "http://127.0.0.1:${tempo_address}/ready" >/dev/null 2>&1; then
			echo "Production Tempo is ready for exact-release traces."
			return
		fi
		attempt=$((attempt + 1))
		sleep 1
	done

	echo "Production Tempo did not become ready."
	exit 1
}

configure_tracing_backend() {
	# Metrics dashboards remain opt-in on the small host. Tempo alone is the durable evidence needed by the
	# deployment and single-user production journeys.
	compose stop prometheus grafana >/dev/null 2>&1 || true
	if [ "${PRODUCTION_TRACING_EXPORT_OTLP_ENABLED:-false}" = true ]; then
		compose up -d --no-deps tempo
		wait_for_tempo
	else
		compose stop tempo >/dev/null 2>&1 || true
	fi
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
		# Keep metrics dashboards off the constrained host; configure_tracing_backend starts only the bounded Tempo
		# service when the deployment has explicitly enabled exact-release traces.
		# Compose treats an existing `unhealthy` dependency as terminal even when a later healthcheck would recover.
		# Start the exact stateful topology first, then require one full health-failure window of stable samples after
		# image and build pressure ends.
		compose up -d --build --no-deps postgres redis rabbitmq minio
		wait_for_stateful_dependencies
		configure_tracing_backend
		prebuilt_backend=${RULEPILOT_PREBUILT_BACKEND_IMAGE:-false}
		prebuilt_frontend=${RULEPILOT_PREBUILT_FRONTEND_IMAGE:-false}
		case "$prebuilt_backend" in
			true|false) ;;
			*)
				echo "RULEPILOT_PREBUILT_BACKEND_IMAGE must be true or false."
				exit 1
				;;
		esac
		case "$prebuilt_frontend" in
			true|false) ;;
			*)
				echo "RULEPILOT_PREBUILT_FRONTEND_IMAGE must be true or false."
				exit 1
				;;
		esac
		if [ "$prebuilt_backend" = true ] && [ "$prebuilt_frontend" = true ]; then
			# Rollback is one immutable topology switch. Starting every application container from pinned images in
			# one Compose operation avoids a mixed frontend/backend release and does not depend on retained build data.
			compose up -d --no-build --no-deps api worker frontend gateway
			wait_for_api
			wait_for_frontend
			wait_for_worker
			echo "Production API, frontend, gateway, and worker are running from immutable images."
			exit 0
		fi
		case "$prebuilt_backend" in
			true)
				compose up -d --no-build --no-deps api
				;;
			false)
				compose up -d --build --no-deps api
				;;
		esac
		wait_for_api
		case "$prebuilt_frontend" in
			true)
				compose up -d --no-build --no-deps frontend gateway
				;;
			false)
				compose up -d --build --no-deps frontend gateway
				;;
		esac
		wait_for_frontend
		compose up -d --no-deps worker
		wait_for_worker
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
		# Services selected through a profile are omitted by a plain `compose down`. Activate the profile only for
		# teardown so Tempo and any operator-started dashboards cannot retain the production network or host memory.
		compose --profile observability down
		;;
	*)
		echo "Usage: $0 [config|up|diagnose|down]"
		exit 2
		;;
esac
