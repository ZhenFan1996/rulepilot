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

compose_with_timeout() {
	command_timeout_seconds=$1
	shift
	if [ -f "$ROOT_DIR/.env" ]; then
		timeout -k 2s "${command_timeout_seconds}s" docker compose --env-file "$ROOT_DIR/.env" \
			-f "$BASE_FILE" \
			-f "$DEPLOYMENT_FILE" \
			-f "$PRODUCTION_FILE" "$@"
	else
		timeout -k 2s "${command_timeout_seconds}s" docker compose \
			-f "$BASE_FILE" \
			-f "$DEPLOYMENT_FILE" \
			-f "$PRODUCTION_FILE" "$@"
	fi
}

docker_with_timeout() {
	command_timeout_seconds=$1
	shift
	timeout -k 2s "${command_timeout_seconds}s" docker "$@"
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

stateful_query_timeout() {
	now=$(date +%s)
	remaining_seconds=$((stateful_ready_deadline - now))
	if [ "$remaining_seconds" -le 0 ]; then
		return 1
	fi
	if [ "$remaining_seconds" -lt "$stateful_query_timeout_seconds" ]; then
		printf '%s\n' "$remaining_seconds"
	else
		printf '%s\n' "$stateful_query_timeout_seconds"
	fi
}

bounded_stateful_compose() {
	query_timeout=$(stateful_query_timeout) || return 124
	compose_with_timeout "$query_timeout" "$@"
}

bounded_stateful_docker() {
	query_timeout=$(stateful_query_timeout) || return 124
	docker_with_timeout "$query_timeout" "$@"
}

wait_for_stateful_dependencies() {
	ready_timeout_seconds=${PRODUCTION_INFRASTRUCTURE_READY_TIMEOUT_SECONDS:-300}
	stable_duration_seconds=60
	required_successful_healthchecks=12
	stateful_query_timeout_seconds=${PRODUCTION_DOCKER_QUERY_TIMEOUT_SECONDS:-10}
	observation_interval_seconds=${PRODUCTION_INFRASTRUCTURE_OBSERVATION_INTERVAL_SECONDS:-3}
	for setting in \
		"PRODUCTION_INFRASTRUCTURE_READY_TIMEOUT_SECONDS:$ready_timeout_seconds" \
		"PRODUCTION_DOCKER_QUERY_TIMEOUT_SECONDS:$stateful_query_timeout_seconds" \
		"PRODUCTION_INFRASTRUCTURE_OBSERVATION_INTERVAL_SECONDS:$observation_interval_seconds"; do
		setting_name=${setting%%:*}
		setting_value=${setting#*:}
		case "$setting_value" in
			''|*[!0-9]*|0)
				printf '%s must be a positive integer.\n' "$setting_name"
				return 1
				;;
		esac
	done
	if ! command -v timeout >/dev/null 2>&1; then
		echo "timeout is required to bound production Docker queries."
		return 1
	fi

	started_at=$(date +%s)
	stateful_ready_deadline=$((started_at + ready_timeout_seconds))
	postgres_last_probe=
	redis_last_probe=
	rabbitmq_last_probe=
	minio_last_probe=
	postgres_successful_healthchecks=0
	redis_successful_healthchecks=0
	rabbitmq_successful_healthchecks=0
	minio_successful_healthchecks=0
	stable_since=
	last_observation="no completed healthchecks observed"

	for service in postgres redis rabbitmq minio; do
		if ! expected_hash_line=$(bounded_stateful_compose config --hash "$service" 2>/dev/null); then
			printf 'Could not resolve the declared %s configuration within the bounded Docker query window.\n' "$service"
			return 1
		fi
		expected_config_hash=${expected_hash_line#* }
		if [ -z "$expected_config_hash" ] || [ "$expected_config_hash" = "$expected_hash_line" ]; then
			printf 'Could not resolve the declared %s configuration hash.\n' "$service"
			return 1
		fi
		if ! expected_image_name=$(bounded_stateful_compose config --images "$service" 2>/dev/null); then
			printf 'Could not resolve the declared %s image within the bounded Docker query window.\n' "$service"
			return 1
		fi
		expected_image_name=$(printf '%s\n' "$expected_image_name" | sed -n '1p')
		if [ -z "$expected_image_name" ] \
			|| ! expected_image_id=$(bounded_stateful_docker image inspect --format '{{.Id}}' "$expected_image_name" 2>/dev/null); then
			printf 'The declared %s image is not available after the stateful start.\n' "$service"
			return 1
		fi
		if ! container=$(bounded_stateful_compose ps -q "$service" 2>/dev/null) || [ -z "$container" ]; then
			printf 'The %s container is missing after the stateful start.\n' "$service"
			return 1
		fi
		case "$service" in
			postgres)
				postgres_expected_config_hash=$expected_config_hash
				postgres_expected_image_id=$expected_image_id
				postgres_container=$container
				;;
			redis)
				redis_expected_config_hash=$expected_config_hash
				redis_expected_image_id=$expected_image_id
				redis_container=$container
				;;
			rabbitmq)
				rabbitmq_expected_config_hash=$expected_config_hash
				rabbitmq_expected_image_id=$expected_image_id
				rabbitmq_container=$container
				;;
			minio)
				minio_expected_config_hash=$expected_config_hash
				minio_expected_image_id=$expected_image_id
				minio_container=$container
				;;
		esac
	done

	while [ "$(date +%s)" -lt "$stateful_ready_deadline" ]; do
		observation_valid=true
		unsettled_services=
		for service in postgres redis rabbitmq minio; do
			case "$service" in
				postgres)
					container=$postgres_container
					expected_config_hash=$postgres_expected_config_hash
					expected_image_id=$postgres_expected_image_id
					previous_probe=$postgres_last_probe
					previous_successes=$postgres_successful_healthchecks
					;;
				redis)
					container=$redis_container
					expected_config_hash=$redis_expected_config_hash
					expected_image_id=$redis_expected_image_id
					previous_probe=$redis_last_probe
					previous_successes=$redis_successful_healthchecks
					;;
				rabbitmq)
					container=$rabbitmq_container
					expected_config_hash=$rabbitmq_expected_config_hash
					expected_image_id=$rabbitmq_expected_image_id
					previous_probe=$rabbitmq_last_probe
					previous_successes=$rabbitmq_successful_healthchecks
					;;
				minio)
					container=$minio_container
					expected_config_hash=$minio_expected_config_hash
					expected_image_id=$minio_expected_image_id
					previous_probe=$minio_last_probe
					previous_successes=$minio_successful_healthchecks
					;;
			esac
			if [ -z "$stable_since" ]; then
				previous_probe=
				previous_successes=0
			fi

			if ! container_observation=$(bounded_stateful_docker inspect --format \
				'container|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}|{{.Image}}|{{index .Config.Labels "com.docker.compose.config-hash"}}{{"\n"}}{{if .State.Health}}{{range .State.Health.Log}}probe|{{.End}}|{{.ExitCode}}{{"\n"}}{{end}}{{end}}' \
				"$container" 2>/dev/null); then
				observation_valid=false
				unsettled_services="${unsettled_services}${unsettled_services:+, }${service}(docker-query-unavailable)"
				continue
			fi
			container_header=$(printf '%s\n' "$container_observation" | sed -n '1p')
			IFS='|' read -r record_type container_state container_health container_image container_config_hash <<EOF
$container_header
EOF
			if [ "$record_type" != container ]; then
				observation_valid=false
				unsettled_services="${unsettled_services}${unsettled_services:+, }${service}(invalid-inspection)"
				continue
			fi
			if [ "$container_config_hash" != "$expected_config_hash" ]; then
				printf 'Stateful dependency %s has configuration drift; use the explicit stateful maintenance path before deploying.\n' "$service"
				return 1
			fi
			if [ "$container_image" != "$expected_image_id" ]; then
				printf 'Stateful dependency %s has image drift; use the explicit stateful maintenance path before deploying.\n' "$service"
				return 1
			fi

			probe_summary=$(printf '%s\n' "$container_observation" | awk -F '|' -v previous="$previous_probe" '
				$1 == "probe" {
					latest_end = $2
					latest_exit = $3
					if (previous != "" && $2 == previous) {
						previous_found = 1
					}
					if (previous != "" && $2 > previous) {
						if ($3 == "0") {
							new_successes += 1
						} else {
							new_failure = 1
						}
					}
				}
				END { printf "%s|%s|%d|%d|%d\n", latest_end, latest_exit, new_successes, new_failure, previous_found }
			')
			IFS='|' read -r latest_probe latest_probe_exit new_successes new_failure previous_probe_found <<EOF
$probe_summary
EOF
			updated_successes=$((previous_successes + new_successes))
			case "$service" in
				postgres)
					postgres_last_probe=$latest_probe
					postgres_successful_healthchecks=$updated_successes
					;;
				redis)
					redis_last_probe=$latest_probe
					redis_successful_healthchecks=$updated_successes
					;;
				rabbitmq)
					rabbitmq_last_probe=$latest_probe
					rabbitmq_successful_healthchecks=$updated_successes
					;;
				minio)
					minio_last_probe=$latest_probe
					minio_successful_healthchecks=$updated_successes
					;;
			esac

			if [ "$container_state" != running ] || [ "$container_health" != healthy ]; then
				observation_valid=false
				unsettled_services="${unsettled_services}${unsettled_services:+, }${service}(${container_state}/${container_health})"
			elif [ -n "$previous_probe" ] && [ "$previous_probe_found" != 1 ]; then
				observation_valid=false
				unsettled_services="${unsettled_services}${unsettled_services:+, }${service}(healthcheck-history-gap)"
			elif [ -z "$latest_probe" ] || [ "$latest_probe_exit" != 0 ] || [ "$new_failure" != 0 ]; then
				observation_valid=false
				unsettled_services="${unsettled_services}${unsettled_services:+, }${service}(healthcheck-exit-${latest_probe_exit:-missing})"
			fi
		done

		if [ "$observation_valid" = true ]; then
			now=$(date +%s)
			if [ -z "$stable_since" ]; then
				stable_since=$now
			fi
			stable_elapsed_seconds=$((now - stable_since))
			last_observation="successful healthchecks postgres=${postgres_successful_healthchecks}, redis=${redis_successful_healthchecks}, rabbitmq=${rabbitmq_successful_healthchecks}, minio=${minio_successful_healthchecks}; stable ${stable_elapsed_seconds}/${stable_duration_seconds}s"
			if [ "$stable_elapsed_seconds" -ge "$stable_duration_seconds" ] \
				&& [ "$postgres_successful_healthchecks" -ge "$required_successful_healthchecks" ] \
				&& [ "$redis_successful_healthchecks" -ge "$required_successful_healthchecks" ] \
				&& [ "$rabbitmq_successful_healthchecks" -ge "$required_successful_healthchecks" ] \
				&& [ "$minio_successful_healthchecks" -ge "$required_successful_healthchecks" ]; then
				for service in postgres redis rabbitmq minio; do
					case "$service" in
						postgres) expected_container=$postgres_container ;;
						redis) expected_container=$redis_container ;;
						rabbitmq) expected_container=$rabbitmq_container ;;
						minio) expected_container=$minio_container ;;
					esac
					if ! current_container=$(bounded_stateful_compose ps -q "$service" 2>/dev/null) \
						|| [ "$current_container" != "$expected_container" ]; then
						echo "Stateful dependency container identity changed during the readiness gate."
						return 1
					fi
				done
				printf 'Production stateful dependencies completed at least %s new successful healthchecks and remained healthy for %s second(s).\n' \
					"$required_successful_healthchecks" "$stable_duration_seconds"
				return 0
			fi
		else
			postgres_successful_healthchecks=0
			redis_successful_healthchecks=0
			rabbitmq_successful_healthchecks=0
			minio_successful_healthchecks=0
			stable_since=
			last_observation=$unsettled_services
		fi
		printf 'Waiting for production stateful dependencies: %s\n' "$last_observation"
		now=$(date +%s)
		remaining_seconds=$((stateful_ready_deadline - now))
		if [ "$remaining_seconds" -le 0 ]; then
			break
		fi
		if [ "$remaining_seconds" -lt "$observation_interval_seconds" ]; then
			sleep "$remaining_seconds"
		else
			sleep "$observation_interval_seconds"
		fi
	done

	printf 'Production stateful dependencies did not prove stable health within %s second(s): %s\n' \
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
		infrastructure_start_timeout_seconds=${PRODUCTION_INFRASTRUCTURE_START_TIMEOUT_SECONDS:-300}
		case "$infrastructure_start_timeout_seconds" in
			''|*[!0-9]*|0)
				echo "PRODUCTION_INFRASTRUCTURE_START_TIMEOUT_SECONDS must be a positive integer."
				exit 1
				;;
		esac
		if ! command -v timeout >/dev/null 2>&1; then
			echo "timeout is required to bound production Docker commands."
			exit 1
		fi
		# rsync can preserve a developer's restrictive target/ umask. Docker must be
		# able to traverse it or it silently reuses a stale application layer.
		chmod -R a+rX "$ROOT_DIR/backend/target"
		# Keep metrics dashboards off the constrained host; configure_tracing_backend starts only the bounded Tempo
		# service when the deployment has explicitly enabled exact-release traces.
		# Compose treats an existing `unhealthy` dependency as terminal even when a later healthcheck would recover.
		# Preserve existing stateful containers after image/build pressure. Configuration or image drift fails closed
		# below and must be applied through a separately reviewed stateful maintenance operation.
		if compose_with_timeout "$infrastructure_start_timeout_seconds" \
			up -d --build --no-deps --no-recreate postgres redis rabbitmq minio; then
			:
		else
			infrastructure_start_status=$?
			case "$infrastructure_start_status" in
				124|137)
					printf 'Production stateful dependency start exceeded %s second(s).\n' \
						"$infrastructure_start_timeout_seconds"
					;;
				*)
					printf 'Production stateful dependency start failed with exit status %s.\n' \
						"$infrastructure_start_status"
					;;
			esac
			exit 1
		fi
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
