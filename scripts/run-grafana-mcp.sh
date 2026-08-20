#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/infra/compose.yml"
TOKEN_FILE="$ROOT_DIR/.local/grafana-mcp/service-account-token"
BINARY_FILE="$ROOT_DIR/.local/grafana-mcp/bin/mcp-grafana"
MCP_VERSION=1.1.0

fail() {
	echo "Grafana MCP: $*" >&2
	exit 1
}

command -v docker >/dev/null 2>&1 || fail "Docker is required."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."

if [ ! -s "$TOKEN_FILE" ]; then
	fail 'local Viewer credential is missing. Run `make mcp-grafana-setup` first.'
fi

server_binary=
if [ -x "$BINARY_FILE" ]; then
	installed_version=$("$BINARY_FILE" -version 2>/dev/null || true)
	case "$installed_version" in
		"$MCP_VERSION"|"v$MCP_VERSION") server_binary=$BINARY_FILE ;;
	esac
fi

if [ -z "$server_binary" ] && command -v brew >/dev/null 2>&1; then
	brew_installation=$(brew list --versions mcp-grafana 2>/dev/null || true)
	if [ "$brew_installation" = "mcp-grafana $MCP_VERSION" ]; then
		brew_prefix=$(brew --prefix mcp-grafana 2>/dev/null || true)
		if [ -n "$brew_prefix" ] && [ -x "$brew_prefix/bin/mcp-grafana" ]; then
			server_binary="$brew_prefix/bin/mcp-grafana"
		fi
	fi
fi

[ -n "$server_binary" ] ||
	fail "Grafana MCP v$MCP_VERSION is missing. Run `make mcp-grafana-setup` first."

if [ -f "$ROOT_DIR/.env" ]; then
	grafana_container=$(docker compose --env-file "$ROOT_DIR/.env" -f "$COMPOSE_FILE" ps -q grafana)
else
	grafana_container=$(docker compose -f "$COMPOSE_FILE" ps -q grafana)
fi

if [ -z "$grafana_container" ] || [ "$(docker inspect --format '{{.State.Running}}' "$grafana_container" 2>/dev/null || true)" != "true" ]; then
	fail 'local Grafana is not running. Run `make compose-up` first.'
fi

if [ -f "$ROOT_DIR/.env" ]; then
	grafana_address=$(docker compose --env-file "$ROOT_DIR/.env" -f "$COMPOSE_FILE" port grafana 3000)
else
	grafana_address=$(docker compose -f "$COMPOSE_FILE" port grafana 3000)
fi

[ -n "$grafana_address" ] || fail "could not resolve the local Grafana port."
export GRAFANA_URL="http://$grafana_address"
export GRAFANA_SERVICE_ACCOUNT_TOKEN_FILE="$TOKEN_FILE"

exec "$server_binary" \
	-t stdio \
	--disable-write \
	--disable-proxied \
	--enabled-tools search,datasource,prometheus,dashboard,navigation \
	--grafana-timeout 10s
