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

require_tooling() {
	if ! command -v docker >/dev/null 2>&1; then
		echo "Docker is required. Install Docker Desktop or Docker Engine with Compose v2."
		exit 1
	fi

	if ! docker compose version >/dev/null 2>&1; then
		echo "Docker Compose v2 is required."
		exit 1
	fi
}

verify_config() {
	compose config --quiet
	echo "Compose configuration is valid."
}

require_volume_mount() {
	service=$1
	destination=$2
	container_id=$(compose ps -q "$service")

	if [ -z "$container_id" ]; then
		echo "FAIL $service container is not running."
		exit 1
	fi

	if ! docker inspect --format '{{range .Mounts}}{{println .Destination}}{{end}}' "$container_id" | grep -Fx "$destination" >/dev/null; then
		echo "FAIL $service has no persistent mount at $destination."
		exit 1
	fi

	echo "PASS $service persistent mount: $destination"
}

verify_running_services() {
	postgres_version=$(compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT extversion FROM pg_extension WHERE extname = '\''vector'\''"')
	if [ -z "$postgres_version" ]; then
		echo "FAIL PostgreSQL vector extension is not enabled."
		exit 1
	fi
	echo "PASS PostgreSQL vector extension: $postgres_version"

	redis_response=$(compose exec -T redis redis-cli ping)
	if [ "$redis_response" != "PONG" ]; then
		echo "FAIL Redis ping returned: $redis_response"
		exit 1
	fi
	echo "PASS Redis ping"

	compose exec -T rabbitmq rabbitmq-diagnostics -q ping >/dev/null
	echo "PASS RabbitMQ diagnostics"

	compose exec -T minio mc ready local >/dev/null
	echo "PASS MinIO readiness"

	compose exec -T prometheus wget -qO- http://localhost:9090/-/ready >/dev/null
	echo "PASS Prometheus readiness"

	compose exec -T prometheus wget -qO- http://tempo:3200/ready >/dev/null
	echo "PASS Tempo readiness"

	compose exec -T prometheus wget -qO- http://grafana:3000/api/health >/dev/null
	echo "PASS Grafana readiness"

	require_volume_mount postgres /var/lib/postgresql/data
	require_volume_mount redis /data
	require_volume_mount rabbitmq /var/lib/rabbitmq
	require_volume_mount minio /data
	require_volume_mount prometheus /prometheus
	require_volume_mount tempo /var/tempo
	require_volume_mount grafana /var/lib/grafana
}

require_tooling

case "${1:-config}" in
	config)
		verify_config
		;;
	up)
		verify_config
		compose up -d --wait
		verify_running_services
		;;
	down)
		verify_config
		compose down
		;;
	*)
		echo "Usage: $0 [config|up|down]"
		exit 2
		;;
esac
