#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/infra/compose.yml"
TOKEN_DIR="$ROOT_DIR/.local/grafana-mcp"
TOKEN_FILE="$TOKEN_DIR/service-account-token"
BINARY_DIR="$TOKEN_DIR/bin"
BINARY_FILE="$BINARY_DIR/mcp-grafana"
ACCOUNT_NAME=rulepilot-mcp
MCP_VERSION=1.1.0

compose() {
	if [ -f "$ROOT_DIR/.env" ]; then
		docker compose --env-file "$ROOT_DIR/.env" -f "$COMPOSE_FILE" "$@"
	else
		docker compose -f "$COMPOSE_FILE" "$@"
	fi
}

fail() {
	echo "Grafana MCP setup: $*" >&2
	exit 1
}

require_tool() {
	command -v "$1" >/dev/null 2>&1 || fail "$1 is required."
}

sha256_file() {
	if command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$1" | awk '{print $1}'
	elif command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{print $1}'
	else
		fail "shasum or sha256sum is required to verify the Grafana MCP release."
	fi
}

install_grafana_mcp() {
	if [ -x "$BINARY_FILE" ]; then
		installed_version=$("$BINARY_FILE" -version 2>/dev/null || true)
		case "$installed_version" in
			"$MCP_VERSION"|"v$MCP_VERSION") return ;;
		esac
	fi

	if command -v brew >/dev/null 2>&1; then
		brew_installation=$(brew list --versions mcp-grafana 2>/dev/null || true)
		if [ "$brew_installation" = "mcp-grafana $MCP_VERSION" ]; then
			brew_prefix=$(brew --prefix mcp-grafana 2>/dev/null || true)
			brew_binary="$brew_prefix/bin/mcp-grafana"
			if [ -n "$brew_prefix" ] && [ -x "$brew_binary" ]; then
				echo "Homebrew Grafana MCP v$MCP_VERSION is already installed."
				return
			fi
		fi

		formula_version=$(brew info --json=v2 mcp-grafana 2>/dev/null |
			jq -r '.formulae[0].versions.stable // empty')
		if [ "$formula_version" = "$MCP_VERSION" ]; then
			echo "Installing pinned Grafana MCP v$MCP_VERSION through Homebrew."
			if ! HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 brew install mcp-grafana; then
				echo "Homebrew install failed; trying the pinned upstream release asset." >&2
			fi

			brew_installation=$(brew list --versions mcp-grafana 2>/dev/null || true)
			brew_prefix=$(brew --prefix mcp-grafana 2>/dev/null || true)
			brew_binary="$brew_prefix/bin/mcp-grafana"
			if [ "$brew_installation" = "mcp-grafana $MCP_VERSION" ] &&
				[ -n "$brew_prefix" ] && [ -x "$brew_binary" ]; then
				echo "Installed Homebrew Grafana MCP v$MCP_VERSION."
				return
			fi
		fi
	fi

	platform=$(uname -s)
	architecture=$(uname -m)
	case "$platform/$architecture" in
		Darwin/arm64)
			asset=mcp-grafana_Darwin_arm64.tar.gz
			expected_checksum=96ccc022d1618a9e9a853f4b765dbaa3f86edeb1b489c1fca7fc150710c9df72
			;;
		Darwin/x86_64)
			asset=mcp-grafana_Darwin_x86_64.tar.gz
			expected_checksum=060a71a78d13e9e9f7181b1fa3b3b56c8ed80936a5d254cabafdc2f5e866e715
			;;
		Linux/arm64|Linux/aarch64)
			asset=mcp-grafana_Linux_arm64.tar.gz
			expected_checksum=23074b93313a7ae2ee7770b4cb5b4859f2acf1830e56f39e0cf49ce48a49e8ae
			;;
		Linux/x86_64|Linux/amd64)
			asset=mcp-grafana_Linux_x86_64.tar.gz
			expected_checksum=8468b1e159412eb1ab738786cf0d2755a1ea0a44103ca8c0040849a227746e07
			;;
		*) fail "unsupported platform for Grafana MCP: $platform/$architecture" ;;
	esac

	download_dir=$(mktemp -d) || fail "could not create a temporary download directory."
	trap 'rm -rf -- "$download_dir"' EXIT HUP INT TERM
	archive="$download_dir/$asset"
	download_url="https://github.com/grafana/mcp-grafana/releases/download/v$MCP_VERSION/$asset"

	echo "Downloading pinned Grafana MCP release: v$MCP_VERSION ($platform/$architecture)"
	curl --fail --location --silent --show-error \
		--retry 3 --retry-delay 1 --connect-timeout 10 \
		--output "$archive" "$download_url" ||
		fail "could not download the pinned Grafana MCP release."

	actual_checksum=$(sha256_file "$archive")
	[ "$actual_checksum" = "$expected_checksum" ] ||
		fail "Grafana MCP archive checksum did not match the pinned release."

	tar -xzf "$archive" -C "$download_dir"
	[ -f "$download_dir/mcp-grafana" ] ||
		fail "Grafana MCP archive did not contain the expected binary."
	chmod 700 "$download_dir/mcp-grafana"
	downloaded_version=$("$download_dir/mcp-grafana" -version 2>/dev/null || true)
	case "$downloaded_version" in
		"$MCP_VERSION"|"v$MCP_VERSION") ;;
		*) fail "downloaded Grafana MCP reported unexpected version: $downloaded_version" ;;
	esac

	mkdir -p "$BINARY_DIR"
	temporary_binary="$BINARY_DIR/.mcp-grafana.$$"
	cp "$download_dir/mcp-grafana" "$temporary_binary"
	chmod 700 "$temporary_binary"
	mv "$temporary_binary" "$BINARY_FILE"
	rm -rf -- "$download_dir"
	trap - EXIT HUP INT TERM
	echo "Installed verified Grafana MCP v$MCP_VERSION in ignored .local state."
}

curl_config_escape() {
	printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

viewer_request() {
	token=$1
	path=$2
	escaped_header=$(curl_config_escape "Authorization: Bearer $token")
	printf 'header = "%s"\n' "$escaped_header" |
		curl --config - --fail --silent --show-error "$grafana_url$path"
}

admin_request() {
	method=$1
	path=$2
	payload=${3:-}
	if [ -n "$payload" ]; then
		printf 'user = "%s:%s"\n' "$escaped_admin_user" "$escaped_admin_password" |
			curl --config - --fail --silent --show-error \
				--request "$method" \
				--header 'Content-Type: application/json' \
				--data "$payload" \
				"$grafana_url$path"
	else
		printf 'user = "%s:%s"\n' "$escaped_admin_user" "$escaped_admin_password" |
			curl --config - --fail --silent --show-error \
				--request "$method" \
				"$grafana_url$path"
	fi
}

require_tool docker
require_tool curl
require_tool jq
require_tool sed
require_tool tar
require_tool awk
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."

grafana_container=$(compose ps -q grafana)
if [ -z "$grafana_container" ] || [ "$(docker inspect --format '{{.State.Running}}' "$grafana_container" 2>/dev/null || true)" != "true" ]; then
	fail 'local Grafana is not running. Run `make compose-up` first.'
fi

grafana_address=$(compose port grafana 3000)
[ -n "$grafana_address" ] || fail "could not resolve the local Grafana port."
grafana_url="http://$grafana_address"
curl --fail --silent --show-error "$grafana_url/api/health" >/dev/null ||
	fail 'local Grafana is not healthy. Run `make compose-up` and retry.'

umask 077
mkdir -p "$TOKEN_DIR"
install_grafana_mcp

existing_token_valid=false
if [ -s "$TOKEN_FILE" ]; then
	existing_token=$(tr -d '\r\n' < "$TOKEN_FILE")
	if [ -n "$existing_token" ]; then
		if viewer=$(viewer_request "$existing_token" /api/user 2>/dev/null); then
			viewer_name=$(printf '%s' "$viewer" | jq -r '.name // empty')
			viewer_uid=$(printf '%s' "$viewer" | jq -r '.uid // empty')
			viewer_admin=$(printf '%s' "$viewer" | jq -r '.isGrafanaAdmin // false')
			[ "$viewer_name" = "$ACCOUNT_NAME" ] ||
				fail "the stored credential belongs to $viewer_name instead of $ACCOUNT_NAME."
			case "$viewer_uid" in
				service-account:*) ;;
				*) fail "the stored credential is not a Grafana service-account token." ;;
			esac
			[ "$viewer_admin" = "false" ] ||
				fail "the stored credential has Grafana administrator access."
			existing_token_valid=true
		fi
	fi
	if [ "$existing_token_valid" = "false" ]; then
		echo "Stored Grafana MCP credential is stale; issuing a new Viewer token." >&2
	fi
fi

compose_config=$(compose config --format json)
admin_user=$(printf '%s' "$compose_config" | jq -er '.services.grafana.environment.GF_SECURITY_ADMIN_USER') ||
	fail "Grafana admin user is missing from Compose configuration."
admin_password=$(printf '%s' "$compose_config" | jq -er '.services.grafana.environment.GF_SECURITY_ADMIN_PASSWORD') ||
	fail "Grafana admin password is missing from Compose configuration."

case "$admin_user$admin_password" in
	*'
'*) fail "Grafana admin credentials must not contain newlines." ;;
esac

escaped_admin_user=$(curl_config_escape "$admin_user")
escaped_admin_password=$(curl_config_escape "$admin_password")

accounts=$(admin_request GET "/api/serviceaccounts/search?query=$ACCOUNT_NAME&perpage=100&page=1") ||
	fail "could not list Grafana service accounts; verify the local admin credentials in .env."
matching_count=$(printf '%s' "$accounts" | jq --arg name "$ACCOUNT_NAME" '[.serviceAccounts[]? | select(.name == $name)] | length')

if [ "$matching_count" -gt 1 ]; then
	fail "multiple service accounts named $ACCOUNT_NAME exist; resolve the ambiguity in Grafana."
fi

if [ "$matching_count" -eq 0 ]; then
	[ "$existing_token_valid" = "false" ] ||
		fail "the valid token's Viewer service account could not be confirmed through the admin API."
	account_payload=$(jq -nc --arg name "$ACCOUNT_NAME" '{name:$name,"role":"Viewer","isDisabled":false}')
	account=$(admin_request POST /api/serviceaccounts "$account_payload") ||
		fail "could not create the $ACCOUNT_NAME Viewer service account."
	account_id=$(printf '%s' "$account" | jq -er '.id') ||
		fail "Grafana did not return the new service-account id."
	echo "Created local Grafana Viewer service account: $ACCOUNT_NAME"
else
	account=$(printf '%s' "$accounts" | jq -c --arg name "$ACCOUNT_NAME" '.serviceAccounts[] | select(.name == $name)')
	account_id=$(printf '%s' "$account" | jq -er '.id') ||
		fail "Grafana did not return the existing service-account id."
	account_role=$(printf '%s' "$account" | jq -r '.role // empty')
	account_disabled=$(printf '%s' "$account" | jq -r '.isDisabled // false')
	[ "$account_role" = "Viewer" ] || fail "$ACCOUNT_NAME has role $account_role instead of Viewer."
	[ "$account_disabled" = "false" ] || fail "$ACCOUNT_NAME is disabled."
fi

if [ "$existing_token_valid" = "true" ]; then
	chmod 600 "$TOKEN_FILE"
	echo "Grafana MCP Viewer credential is already valid."
	exit 0
fi

token_name="codex-local-$(date -u +%Y%m%dT%H%M%SZ)-$$"
token_payload=$(jq -nc --arg name "$token_name" '{name:$name}')
token_response=$(admin_request POST "/api/serviceaccounts/$account_id/tokens" "$token_payload") ||
	fail "could not issue a Viewer token for $ACCOUNT_NAME."
new_token=$(printf '%s' "$token_response" | jq -er '.key') ||
	fail "Grafana did not return the new service-account token."

temporary_token="$TOKEN_DIR/.service-account-token.$$"
trap 'rm -f "$temporary_token"' EXIT HUP INT TERM
printf '%s\n' "$new_token" > "$temporary_token"
chmod 600 "$temporary_token"
mv "$temporary_token" "$TOKEN_FILE"
trap - EXIT HUP INT TERM

echo "Saved the local Viewer credential to ignored .local state (mode 600)."
echo 'Grafana MCP setup is ready; run `make mcp-grafana-smoke` to verify the protocol path.'
