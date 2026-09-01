#!/usr/bin/env bash

set -Eeuo pipefail

readonly LEASE_STALE_SECONDS=150
readonly WATCHDOG_DEADLINE_SECONDS=2100
readonly WATCHDOG_POLL_SECONDS=5
readonly WATCHDOG_READY_ATTEMPTS=100
readonly WATCHDOG_READY_POLL_SECONDS=0.05
readonly ROLLBACK_READY_TIMEOUT_SECONDS=360
readonly ROLLBACK_READY_POLL_SECONDS=2
readonly QUALIFIED_MAIN_REMOTE=https://github.com/ZhenFan1996/rulepilot.git
readonly QUALIFIED_MAIN_VERIFY_ATTEMPTS=3
readonly QUALIFIED_MAIN_VERIFY_RETRY_SECONDS=1
readonly EXPECTED_RECOMMENDATION_PROVIDER=qwen
readonly EXPECTED_RECOMMENDATION_MODEL=qwen3.7-plus

fail() {
	printf '%s\n' "$*" >&2
	exit 1
}

require_release_id() {
	[[ "$1" =~ ^[0-9a-f]{40}-[0-9]+(-[0-9]+)?$ ]] || fail "Invalid immutable release id"
}

require_candidate_release_id() {
	[[ "$1" =~ ^[0-9a-f]{40}-[0-9]+-[0-9]+$ ]] || fail "Invalid candidate release id"
}

require_current_qualified_main() {
	local release_id=$1
	local candidate_sha remote_line remote_sha remote_ref extra attempt
	require_candidate_release_id "$release_id"
	candidate_sha=${release_id%%-*}
	for ((attempt = 1; attempt <= QUALIFIED_MAIN_VERIFY_ATTEMPTS; attempt++)); do
		if remote_line=$(GIT_TERMINAL_PROMPT=0 timeout 20s git ls-remote \
			--exit-code "$QUALIFIED_MAIN_REMOTE" refs/heads/main); then
			break
		fi
		if (( attempt == QUALIFIED_MAIN_VERIFY_ATTEMPTS )); then
			fail "Could not verify the current qualified main revision after ${QUALIFIED_MAIN_VERIFY_ATTEMPTS} attempts"
		fi
		printf 'Qualified main revision lookup failed on attempt %s/%s; retrying.\n' \
			"$attempt" "$QUALIFIED_MAIN_VERIFY_ATTEMPTS" >&2
		sleep "$QUALIFIED_MAIN_VERIFY_RETRY_SECONDS"
	done
	read -r remote_sha remote_ref extra <<< "$remote_line"
	[[ -z "${extra:-}" && "$remote_sha" =~ ^[0-9a-f]{40}$ && "$remote_ref" == refs/heads/main ]] \
		|| fail "Current qualified main revision response is invalid"
	[[ "$candidate_sha" == "$remote_sha" ]] \
		|| fail "Candidate release is no longer the current qualified main revision"
}

resolve_application_root() {
	local root
	root=$(readlink -f "$1")
	[[ -d "$root" ]] || fail "Production application root is unavailable"
	printf '%s\n' "$root"
}

guard_directory() {
	printf '%s/deployment-guards/%s\n' "$1" "$2"
}

active_transaction_file() {
	printf '%s/deployment-guards/active-transaction\n' "$1"
}

transaction_terminal() {
	local state_dir=$1
	[[ -f "$state_dir/committed" || -f "$state_dir/rolled-back" || -f "$state_dir/unchanged" ]]
}

staged_bgg_credential() {
	local release_id=$1
	require_candidate_release_id "$release_id"
	printf '/tmp/rulepilot-bgg-token-%s\n' "$release_id"
}

environment_snapshot() {
	printf '%s/environment.snapshot\n' "$1"
}

require_environment_snapshot() {
	local state_dir=$1
	local snapshot permissions
	snapshot=$(environment_snapshot "$state_dir")
	[[ -f "$snapshot" && ! -L "$snapshot" ]] \
		|| fail "Rollback environment snapshot is unavailable"
	permissions=$(stat -c '%a' "$snapshot")
	[[ "$permissions" == 600 ]] || fail "Rollback environment snapshot permissions are invalid"
	printf '%s\n' "$snapshot"
}

snapshot_environment() {
	local application_root=$1
	local state_dir=$2
	local source snapshot temporary
	source="$application_root/.env"
	snapshot=$(environment_snapshot "$state_dir")
	[[ -f "$source" && ! -L "$source" ]] || fail "Production environment file is unavailable"
	temporary=$(mktemp "${snapshot}.tmp.XXXXXX")
	if ! install -m 0600 "$source" "$temporary" || ! mv -f "$temporary" "$snapshot"; then
		rm -f -- "$temporary"
		fail "Could not create the rollback environment snapshot"
	fi
}

restore_environment() {
	local application_root=$1
	local state_dir=$2
	local target snapshot temporary
	target="$application_root/.env"
	snapshot=$(require_environment_snapshot "$state_dir")
	temporary=$(mktemp "${target}.rollback.XXXXXX")
	if ! install -m 0600 "$snapshot" "$temporary" || ! mv -f "$temporary" "$target"; then
		rm -f -- "$temporary"
		fail "Could not restore the rollback environment snapshot"
	fi
}

discard_transaction_secrets() {
	local state_dir=$1
	local release_id=$2
	# One rm invocation attempts every bounded release-owned secret even if one path cannot be removed.
	rm -f -- \
		"$(environment_snapshot "$state_dir")" \
		"$(staged_bgg_credential "$release_id")" \
		"$state_dir/watchdog-failed"
}

compose_container() {
	local release_dir=$1
	local service=$2
	(
		cd "$release_dir"
		docker compose --env-file .env \
			-f infra/compose.yml \
			-f infra/compose.deployment.yml \
			-f infra/compose.production.yml ps -q "$service"
	)
}

compose_loopback_endpoint() {
	local release_dir=$1
	local service=$2
	local container_port=$3
	local endpoint published_port
	[[ "$container_port" =~ ^[1-9][0-9]{0,4}$ && "$container_port" -le 65535 ]] \
		|| fail "Invalid rollback container port"
	if ! endpoint=$(
		cd "$release_dir"
		docker compose --env-file .env \
			-f infra/compose.yml \
			-f infra/compose.deployment.yml \
			-f infra/compose.production.yml port "$service" "$container_port"
	); then
		fail "Rollback service published endpoint is unavailable"
	fi
	# Compose is authoritative after it has evaluated every layered file and environment expression. Accept exactly
	# one numeric port on a literal loopback address so rollback probes can never follow an external or ambiguous
	# endpoint supplied by mutable environment text.
	[[ -n "$endpoint" && "$endpoint" != *$'\n'* ]] \
		|| fail "Rollback service must publish exactly one loopback endpoint"
	if [[ "$endpoint" =~ ^127\.0\.0\.1:([1-9][0-9]{0,4})$ ]]; then
		published_port=${BASH_REMATCH[1]}
	elif [[ "$endpoint" =~ ^\[::1\]:([1-9][0-9]{0,4})$ ]]; then
		published_port=${BASH_REMATCH[1]}
	else
		fail "Rollback service must publish exactly one loopback endpoint"
	fi
	[[ "$published_port" -le 65535 ]] \
		|| fail "Rollback service published port is invalid"
	printf '%s\n' "$endpoint"
}

wait_for_http() {
	local label=$1
	local url=$2
	local deadline=$3
	local http_status
	shift 3
	while :; do
		if http_status=$(curl -sS --max-time 6 --output /dev/null --write-out '%{http_code}' \
			"$@" "$url" 2>/dev/null) && [[ "$http_status" == 200 ]]; then
			return 0
		fi
		(( $(date +%s) < deadline )) || break
		sleep "$ROLLBACK_READY_POLL_SECONDS"
	done
	fail "Immutable rollback ${label} did not become ready before the recovery deadline"
}

verify_candidate_publication_boundary() (
	set -Eeuo pipefail
	local active_release=$1
	local release_id=$2
	local candidate_sha api_container preflight_dir auth_config
	local username password first_bgg_id
	local curl_status http_status request_label body_path headers_path

	candidate_sha=${release_id%%-*}
	api_container=$(compose_container "$active_release" api)
	[[ -n "$api_container" ]] || fail "Candidate API container is unavailable for publication verification"
	preflight_dir=$(mktemp -d "/tmp/rulepilot-candidate-boundary.${release_id}.XXXXXX")
	auth_config="$preflight_dir/model-auth.curl"
	cleanup_candidate_boundary() {
		if [[ "$preflight_dir" == "/tmp/rulepilot-candidate-boundary.${release_id}."* ]]; then
			rm -rf -- "$preflight_dir"
		fi
	}
	trap cleanup_candidate_boundary EXIT

	read_environment_value() {
		local key=$1 line value first_character last_character
		while IFS= read -r line || [[ -n "$line" ]]; do
			[[ "${line%%=*}" == "$key" ]] || continue
			value=${line#*=}
			if (( ${#value} >= 2 )); then
				first_character=${value:0:1}
				last_character=${value: -1}
				if [[ "$first_character" == "$last_character"
					&& ( "$first_character" == '"' || "$first_character" == "'" ) ]]; then
					value=${value:1:${#value}-2}
				fi
			fi
			printf '%s' "$value"
			return 0
		done < "$active_release/.env"
		return 1
	}

	escape_curl_config_value() {
		local value=$1
		[[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] \
			|| fail "Candidate publication credential contains an unsupported line break"
		value=${value//\\/\\\\}
		value=${value//\"/\\\"}
		printf '%s' "$value"
	}

	username=$(read_environment_value RULEPILOT_USER_USERNAME) \
		|| fail "Candidate publication username is unavailable"
	password=$(read_environment_value RULEPILOT_USER_PASSWORD) \
		|| fail "Candidate publication password is unavailable"
	[[ -n "$username" && "$username" != *:* && -n "$password" ]] \
		|| fail "Candidate publication configuration is incomplete"
	umask 077
	printf 'user = "%s:%s"\n' \
		"$(escape_curl_config_value "$username")" \
		"$(escape_curl_config_value "$password")" > "$auth_config"

	candidate_https_get() {
		request_label=$1
		local request_path=$2
		body_path=$3
		headers_path=$4
		shift 4
		if http_status=$(curl \
			--silent \
			--show-error \
			--connect-timeout 3 \
			--max-time 6 \
			--max-filesize 1048576 \
			--max-redirs 0 \
			--proto '=https' \
			--noproxy '*' \
			--resolve 'rulepilot.cn:443:127.0.0.1' \
			--dump-header "$headers_path" \
			--output "$body_path" \
			--write-out '%{http_code}' \
			"$@" \
			"https://rulepilot.cn${request_path}"); then
			:
		else
			curl_status=$?
			case "$curl_status" in
				5|6|7|16|18|28|35|52|55|56|80|92)
					printf 'Candidate publication boundary temporarily could not reach %s (curl exit %s).\n' \
						"$request_label" "$curl_status" >&2
					exit 75
					;;
				*)
					fail "Candidate publication boundary transport rejected ${request_label} (curl exit ${curl_status})"
					;;
			esac
		fi
		case "$http_status" in
			200)
				return 0
				;;
			502|503|504)
				printf 'Candidate publication boundary temporarily received HTTP %s from %s.\n' \
					"$http_status" "$request_label" >&2
				exit 75
				;;
			*)
				fail "Candidate publication boundary received HTTP ${http_status} from ${request_label}"
				;;
		esac
	}

	assert_json_contract() {
		local contract=$1
		local payload=$2
		local expected_id=${3:-}
		docker exec -i \
			-e "RULEPILOT_BOUNDARY_CONTRACT=$contract" \
			-e "RULEPILOT_EXPECTED_RELEASE_ID=$release_id" \
			-e "RULEPILOT_EXPECTED_COMMIT_SHA=$candidate_sha" \
			-e "RULEPILOT_EXPECTED_PROVIDER=$EXPECTED_RECOMMENDATION_PROVIDER" \
			-e "RULEPILOT_EXPECTED_MODEL=$EXPECTED_RECOMMENDATION_MODEL" \
			-e "RULEPILOT_EXPECTED_BGG_ID=$expected_id" \
			"$api_container" python3 -c '
import json
import os
import sys

contract = os.environ["RULEPILOT_BOUNDARY_CONTRACT"]

def reject(reason):
    print(f"Candidate publication boundary rejected {contract}: {reason}", file=sys.stderr)
    raise SystemExit(1)

try:
    payload = json.load(sys.stdin)
except (UnicodeDecodeError, json.JSONDecodeError):
    reject("invalid JSON")

if contract == "release":
    if not isinstance(payload, dict):
        reject("response is not an object")
    if payload.get("releaseId") != os.environ["RULEPILOT_EXPECTED_RELEASE_ID"]:
        reject("release identity mismatch")
    if payload.get("commitSha") != os.environ["RULEPILOT_EXPECTED_COMMIT_SHA"]:
        reject("commit identity mismatch")
elif contract == "model":
    recommendation = payload.get("recommendationModel") if isinstance(payload, dict) else None
    if not isinstance(recommendation, dict):
        reject("recommendation model is absent")
    if recommendation.get("provider") != os.environ["RULEPILOT_EXPECTED_PROVIDER"]:
        reject("recommendation provider mismatch")
    if recommendation.get("model") != os.environ["RULEPILOT_EXPECTED_MODEL"]:
        reject("recommendation model mismatch")
elif contract == "csrf":
    if not isinstance(payload, dict):
        reject("response is not an object")
    if not isinstance(payload.get("token"), str) or not payload["token"]:
        reject("token is absent")
    if not isinstance(payload.get("headerName"), str) or not payload["headerName"]:
        reject("header name is absent")
elif contract == "recommendations":
    if not isinstance(payload, list) or not payload or not isinstance(payload[0], dict):
        reject("catalog has no first game")
    game = payload[0]
    bgg_id = game.get("bggId")
    if type(bgg_id) is not int or bgg_id <= 0:
        reject("first game id is invalid")
    if not isinstance(game.get("name"), str) or not game["name"]:
        reject("first game name is absent")
    print(bgg_id)
elif contract == "detail":
    if not isinstance(payload, dict):
        reject("response is not an object")
    expected_bgg_id = int(os.environ["RULEPILOT_EXPECTED_BGG_ID"])
    if payload.get("bggId") != expected_bgg_id:
        reject("game identity mismatch")
    if not isinstance(payload.get("description"), str):
        reject("description is invalid")
    if type(payload.get("descriptionTranslated")) is not bool:
        reject("translation marker is invalid")
    if not isinstance(payload.get("categories"), list):
        reject("categories are invalid")
    if not isinstance(payload.get("mechanics"), list):
        reject("mechanics are invalid")
else:
    reject("unknown contract")
' < "$payload"
	}

	candidate_https_get release /api/public/release \
		"$preflight_dir/release.body" "$preflight_dir/release.headers"
	assert_json_contract release "$preflight_dir/release.body"
	tr -d '\r' < "$preflight_dir/release.headers" | awk '
tolower($0) ~ /^cache-control:/ {
    value = substr($0, index($0, ":") + 1)
    directive_count = split(value, directives, ",")
    for (index_value = 1; index_value <= directive_count; index_value++) {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", directives[index_value])
        if (tolower(directives[index_value]) == "no-store") found = 1
    }
}
END { exit(found ? 0 : 1) }
' \
		|| fail "Candidate release identity is cacheable"

	candidate_https_get model /api/v1/model-configuration \
		"$preflight_dir/model.body" "$preflight_dir/model.headers" \
		--config "$auth_config" --header 'Accept: application/json'
	assert_json_contract model "$preflight_dir/model.body"

	candidate_https_get home / "$preflight_dir/home.body" "$preflight_dir/home.headers"
	[[ -s "$preflight_dir/home.body" ]] || fail "Candidate home page is empty"
	tr -d '\r' < "$preflight_dir/home.headers" \
		| grep -Eiq '^content-type:[[:space:]]*text/html([;[:space:]]|$)' \
		|| fail "Candidate home page is not HTML"

	candidate_https_get csrf /api/auth/csrf \
		"$preflight_dir/csrf.body" "$preflight_dir/csrf.headers"
	assert_json_contract csrf "$preflight_dir/csrf.body"

	candidate_https_get recommendations /api/v1/bgg/recommendations \
		"$preflight_dir/recommendations.body" "$preflight_dir/recommendations.headers"
	first_bgg_id=$(assert_json_contract recommendations "$preflight_dir/recommendations.body")
	[[ "$first_bgg_id" =~ ^[1-9][0-9]*$ ]] \
		|| fail "Candidate recommendation catalog returned an invalid game id"

	candidate_https_get detail \
		"/api/v1/bgg/games/${first_bgg_id}?locale=zh-CN" \
		"$preflight_dir/detail.body" "$preflight_dir/detail.headers"
	assert_json_contract detail "$preflight_dir/detail.body" "$first_bgg_id"
	printf 'Candidate publication boundary verified release %s through the production gateway.\n' \
		"$release_id"
)

require_running_image() {
	local release_dir=$1
	local service=$2
	local expected_image=$3
	local container running actual_image
	container=$(compose_container "$release_dir" "$service")
	[[ -n "$container" ]] || fail "Rollback service container is unavailable"
	running=$(docker inspect --format '{{.State.Running}}' "$container")
	actual_image=$(docker inspect --format '{{.Image}}' "$container")
	[[ "$running" == true && "$actual_image" == "$expected_image" ]] \
		|| fail "Rollback service is not running its checkpoint image"
}

wait_for_worker() {
	local release_dir=$1
	local expected_image=$2
	local deadline=$3
	local container running actual_image worker_health
	while :; do
		container=$(compose_container "$release_dir" worker)
		if [[ -n "$container" ]]; then
			running=$(docker inspect --format '{{.State.Running}}' "$container")
			actual_image=$(docker inspect --format '{{.Image}}' "$container")
			worker_health=$(docker inspect --format \
				'{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
				"$container")
			if [[ "$running" == true && "$actual_image" == "$expected_image" && "$worker_health" == healthy ]]; then
				return 0
			fi
		fi
		(( $(date +%s) < deadline )) || break
		sleep "$ROLLBACK_READY_POLL_SECONDS"
	done
	fail "Immutable rollback worker did not become ready before the recovery deadline"
}

atomic_write() {
	local target=$1
	local value=$2
	local temporary
	temporary=$(mktemp "${target}.tmp.XXXXXX")
	chmod 600 "$temporary"
	printf '%s\n' "$value" > "$temporary"
	mv -f "$temporary" "$target"
}

require_active_transaction() {
	local application_root=$1
	local release_id=$2
	local ownership_file owner
	require_candidate_release_id "$release_id"
	ownership_file=$(active_transaction_file "$application_root")
	[[ -f "$ownership_file" && ! -L "$ownership_file" ]] \
		|| fail "Production release transaction ownership is unavailable"
	owner=$(<"$ownership_file")
	require_candidate_release_id "$owner"
	[[ "$owner" == "$release_id" ]] \
		|| fail "Another production release transaction owns the runtime"
}

claim_active_transaction_held() {
	local application_root=$1
	local release_id=$2
	local guards_root ownership_file owner owner_state candidate_state candidate_id
	local nonterminal_id='' nonterminal_count=0
	require_candidate_release_id "$release_id"
	guards_root="$application_root/deployment-guards"
	ownership_file=$(active_transaction_file "$application_root")
	install -d -m 0700 "$guards_root"
	if [[ -e "$ownership_file" ]]; then
		[[ -f "$ownership_file" && ! -L "$ownership_file" ]] \
			|| fail "Production release transaction ownership is invalid"
		owner=$(<"$ownership_file")
		require_candidate_release_id "$owner"
		owner_state=$(guard_directory "$application_root" "$owner")
		[[ -d "$owner_state" && ! -L "$owner_state" \
			&& -f "$owner_state/previous-release" && ! -L "$owner_state/previous-release" ]] \
			|| fail "Production release transaction ownership is invalid"
	fi
	# The ownership file was introduced after the first immutable guard. Scan legacy guard state as a one-way
	# compatibility boundary. Prove there is at most one unfinished owner before changing either the runtime or the
	# ownership coordinate; inconsistent multiple transactions must remain a fail-closed operator incident.
	for candidate_state in "$guards_root"/*; do
		[[ -d "$candidate_state" && -f "$candidate_state/previous-release" ]] || continue
		candidate_id=${candidate_state##*/}
		require_candidate_release_id "$candidate_id"
		if ! transaction_terminal "$candidate_state"; then
			nonterminal_id=$candidate_id
			nonterminal_count=$((nonterminal_count + 1))
		fi
	done
	(( nonterminal_count <= 1 )) \
		|| fail "Multiple non-terminal production release transactions require operator recovery"
	if [[ -n "${owner:-}" ]]; then
		if transaction_terminal "$owner_state"; then
			discard_transaction_secrets "$owner_state" "$owner"
			release_active_transaction_held "$application_root" "$owner"
		elif [[ "$nonterminal_id" != "$owner" ]]; then
			fail "Production release transaction ownership does not match guard state"
		fi
	fi
	if [[ -n "$nonterminal_id" ]]; then
		# A legacy guard might predate the explicit ownership file. Publish that exact owner while still holding the
		# production mutation lock, then let this current, reviewed guard perform the recovery.
		if [[ ! -e "$ownership_file" ]]; then
			atomic_write "$ownership_file" "$nonterminal_id"
		fi
		recover_stale_transaction_held "$application_root" "$nonterminal_id"
	fi
	[[ ! -e "$ownership_file" ]] \
		|| fail "Previous production release transaction did not release ownership"
	atomic_write "$ownership_file" "$release_id"
}

recover_stale_transaction_held() {
	local application_root=$1
	local release_id=$2
	local state_dir previous_release_id armed_release now lease_epoch recovery_reason
	require_candidate_release_id "$release_id"
	state_dir=$(guard_directory "$application_root" "$release_id")
	[[ -d "$state_dir" && ! -L "$state_dir" \
		&& -f "$state_dir/previous-release" && ! -L "$state_dir/previous-release" ]] \
		|| fail "Previous production release transaction checkpoint is invalid"
	transaction_terminal "$state_dir" \
		&& fail "Terminal production release transaction cannot be recovered as active"
	[[ -f "$state_dir/armed" && ! -L "$state_dir/armed" ]] \
		|| fail "Another production release transaction is still active; its unarmed checkpoint requires the watchdog"
	armed_release=$(<"$state_dir/armed")
	[[ "$armed_release" == "$release_id" ]] \
		|| fail "Previous production release transaction arm marker is invalid"
	[[ -f "$state_dir/lease" && ! -L "$state_dir/lease" ]] \
		|| fail "Another production release transaction has no recoverable lease"
	now=$(date +%s)
	lease_epoch=$(stat -c %Y "$state_dir/lease")
	[[ "$lease_epoch" =~ ^[0-9]+$ && "$now" =~ ^[0-9]+$ && "$now" -ge "$lease_epoch" ]] \
		|| fail "Previous production release transaction lease is invalid"
	# A persisted watchdog failure explains why takeover is needed, but never overrides a fresh lease. The lease is
	# the authoritative concurrency boundary for a runner that may still be making progress under another SSH call.
	if (( now - lease_epoch < LEASE_STALE_SECONDS )); then
		fail "Another production release transaction still has a fresh lease"
	fi
	previous_release_id=$(<"$state_dir/previous-release")
	require_release_id "$previous_release_id"
	recovery_reason='stale lease'
	if [[ -f "$state_dir/watchdog-failed" && ! -L "$state_dir/watchdog-failed" ]]; then
		recovery_reason='stale lease after watchdog failure'
	fi
	printf 'Recovering previous production transaction %s (%s).\n' \
		"$release_id" "$recovery_reason" >&2
	# checkpoint stdout is a machine-readable previous-release id consumed by the workflow. Keep the recovered
	# transaction's human diagnostic on stderr so takeover cannot corrupt that control-plane coordinate.
	rollback_held "$application_root" "$release_id" "$previous_release_id" >&2
	transaction_terminal "$state_dir" \
		|| fail "Previous production release transaction did not reach a terminal state"
	[[ ! -e "$(active_transaction_file "$application_root")" ]] \
		|| fail "Previous production release transaction did not release ownership"
}

release_active_transaction_held() {
	local application_root=$1
	local release_id=$2
	local ownership_file owner
	ownership_file=$(active_transaction_file "$application_root")
	[[ -e "$ownership_file" ]] || return 0
	[[ -f "$ownership_file" && ! -L "$ownership_file" ]] \
		|| fail "Production release transaction ownership is invalid"
	owner=$(<"$ownership_file")
	require_candidate_release_id "$owner"
	[[ "$owner" == "$release_id" ]] || return 0
	rm -f -- "$ownership_file"
}

checkpoint() (
	local application_root release_id releases_root current_release previous_release_id
	local api_container worker_container frontend_container api_image worker_image frontend_image
	local start_status state_dir
	local checkpoint_claimed=false
	local watchdog_start_attempted=false
	local checkpoint_published=false
	cleanup_unpublished_checkpoint() {
		local status=$?
		trap - EXIT
		if [[ "$checkpoint_claimed" == true \
			&& "$checkpoint_published" != true && "$watchdog_start_attempted" != true ]]; then
			if [[ -n "${state_dir:-}" && -d "$state_dir" ]]; then
				discard_transaction_secrets "$state_dir" "$release_id" || true
				# This candidate was never published to a watchdog. Remove every bounded transaction marker so a
				# failed snapshot/tag/write cannot masquerade as a legacy non-terminal transaction forever.
				rm -f -- \
					"$state_dir/previous-release" \
					"$state_dir/armed" \
					"$state_dir/lease" \
					"$state_dir/watchdog.pid" \
					"$state_dir/watchdog-generation" \
					"$state_dir/watchdog-ready" \
					"$state_dir/committed" \
					"$state_dir/rolled-back" \
					"$state_dir/unchanged" \
					"$state_dir/watchdog-failed" || true
			fi
			if [[ "$checkpoint_claimed" == true ]]; then
				release_active_transaction_held "$application_root" "$release_id" || true
			fi
		fi
		exit "$status"
	}
	trap cleanup_unpublished_checkpoint EXIT
	application_root=$(resolve_application_root "$1")
	release_id=$2
	require_candidate_release_id "$release_id"
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	# workflow_run events and their reruns can arrive out of order. Recheck the public repository while holding the
	# production mutation lock so a previously qualified but now stale commit can never move the runtime backward.
	require_current_qualified_main "$release_id"
	state_dir=$(guard_directory "$application_root" "$release_id")
	# Recover at most one abandoned armed transaction before reading the active release. A successful takeover can
	# move current back to that transaction's checkpoint, which is the only correct baseline for this new checkpoint.
	claim_active_transaction_held "$application_root" "$release_id"
	checkpoint_claimed=true
	releases_root=$(readlink -f "$application_root/releases")
	[[ -L "$application_root/current" ]] || fail "Current production release is not an immutable symlink"
	current_release=$(readlink -f "$application_root/current")
	previous_release_id=${current_release##*/}
	require_release_id "$previous_release_id"
	[[ "$previous_release_id" != "$release_id" ]] \
		|| fail "Candidate release must not overwrite its own rollback checkpoint"
	[[ "${current_release%/*}" == "$releases_root" && -f "$current_release/.env" ]] \
		|| fail "Current production release is outside the immutable releases root"

	api_container=$(compose_container "$current_release" api)
	worker_container=$(compose_container "$current_release" worker)
	frontend_container=$(compose_container "$current_release" frontend)
	[[ -n "$api_container" && -n "$worker_container" && -n "$frontend_container" ]] \
		|| fail "All active application containers are required for an exact rollback checkpoint"
	api_image=$(docker inspect --format '{{.Image}}' "$api_container")
	worker_image=$(docker inspect --format '{{.Image}}' "$worker_container")
	frontend_image=$(docker inspect --format '{{.Image}}' "$frontend_container")
	[[ "$api_image" == "$worker_image" ]] \
		|| fail "The active API and worker do not share one immutable backend image"
	docker image inspect "$api_image" >/dev/null
	docker image inspect "$frontend_image" >/dev/null
	docker tag "$api_image" "rulepilot-backend:${previous_release_id}"
	docker tag "$frontend_image" "rulepilot-frontend:${previous_release_id}"

	install -d -m 0700 "$state_dir"
	atomic_write "$state_dir/previous-release" "$previous_release_id"
	rm -f \
		"$state_dir/armed" \
		"$state_dir/committed" \
		"$state_dir/rolled-back" \
		"$state_dir/unchanged" \
		"$state_dir/watchdog-ready" \
		"$state_dir/watchdog-failed"
	rm -f -- "$(staged_bgg_credential "$release_id")"
	snapshot_environment "$application_root" "$state_dir"
	# Starting the watchdog is part of checkpoint publication. Once this child returns, a lost SSH response still has
	# an independent owner that will either observe arm or remove the unmodified secret checkpoint at its deadline.
	watchdog_start_attempted=true
	# The detached watchdog must not inherit this process's flock-holding descriptor. Otherwise it would keep the
	# deployment lock forever and deadlock activation as well as its own deadline recovery.
	if bash "$0" start "$application_root" "$release_id" "$previous_release_id" 9>&-; then
		checkpoint_published=true
		trap - EXIT
		printf '%s\n' "$previous_release_id"
		return 0
	else
		start_status=$?
	fi
	printf 'Could not start the rollback watchdog; closing the unarmed checkpoint.\n' >&2
	if ! finalize_unchanged_held "$application_root" "$release_id" "$previous_release_id"; then
		printf 'Could not close the unarmed checkpoint after watchdog startup failed.\n' >&2
	else
		checkpoint_published=true
	fi
	return "$start_status"
)

require_checkpoint() {
	local application_root=$1
	local release_id=$2
	local previous_release_id=$3
	local state_dir recorded_previous
	require_candidate_release_id "$release_id"
	require_release_id "$previous_release_id"
	state_dir=$(guard_directory "$application_root" "$release_id")
	[[ -d "$state_dir" && -f "$state_dir/previous-release" ]] \
		|| fail "Immutable rollback checkpoint is unavailable"
	recorded_previous=$(<"$state_dir/previous-release")
	[[ "$recorded_previous" == "$previous_release_id" ]] \
		|| fail "Rollback checkpoint does not match the requested previous release"
	if ! transaction_terminal "$state_dir"; then
		require_environment_snapshot "$state_dir" >/dev/null
	fi
	printf '%s\n' "$state_dir"
}

heartbeat() {
	local application_root release_id previous_release_id state_dir releases_root active_release
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	transaction_terminal "$state_dir" && return 0
	require_active_transaction "$application_root" "$release_id"
	require_live_watchdog "$state_dir"
	if [[ -f "$state_dir/armed" ]]; then
		releases_root=$(readlink -f "$application_root/releases")
		active_release=$(readlink -f "$application_root/current" 2>/dev/null || true)
		[[ "$active_release" == "$releases_root/$release_id" ]] \
			|| fail "Guarded candidate release is not active"
	fi
	touch "$state_dir/lease"
}

arm() {
	local application_root release_id previous_release_id state_dir
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	transaction_terminal "$state_dir" && fail "Terminal release checkpoint cannot be re-armed"
	require_active_transaction "$application_root" "$release_id"
	require_live_watchdog "$state_dir"
	touch "$state_dir/lease"
	atomic_write "$state_dir/armed" "$release_id"
}

assert_activation_held() {
	local application_root release_id previous_release_id state_dir releases_root active_release
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	transaction_terminal "$state_dir" \
		&& fail "Terminal release checkpoint cannot be activated"
	require_active_transaction "$application_root" "$release_id"
	require_live_watchdog "$state_dir"
	[[ -f "$state_dir/armed" ]] || fail "Release checkpoint is not armed for activation"
	releases_root=$(readlink -f "$application_root/releases")
	active_release=$(readlink -f "$application_root/current" 2>/dev/null || true)
	[[ "$active_release" == "$releases_root/$previous_release_id" ]] \
		|| fail "Production moved away from the guarded rollback checkpoint before activation"
	# The caller already owns deployment.lock. Refresh only after every fail-closed ownership and state assertion passes.
	touch "$state_dir/lease"
}

finalize_unchanged_held() {
	local application_root release_id previous_release_id state_dir
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	if transaction_terminal "$state_dir"; then
		discard_transaction_secrets "$state_dir" "$release_id"
		release_active_transaction_held "$application_root" "$release_id"
		return 0
	fi
	require_active_transaction "$application_root" "$release_id"
	if [[ -f "$state_dir/armed" ]]; then
		printf 'Armed release checkpoint requires rollback, not unchanged finalization.\n' >&2
		return 2
	fi
	atomic_write "$state_dir/unchanged" "$previous_release_id"
	discard_transaction_secrets "$state_dir" "$release_id"
	release_active_transaction_held "$application_root" "$release_id"
	printf 'Closed unarmed production checkpoint %s without a runtime mutation.\n' "$release_id"
}

finalize_unchanged() {
	local application_root release_id previous_release_id
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	finalize_unchanged_held "$application_root" "$release_id" "$previous_release_id"
}

rollback_held() {
	local application_root release_id previous_release_id state_dir releases_root
	local failed_release previous_release active_release backend_image frontend_image backend_image_id frontend_image_id
	local backend_endpoint frontend_endpoint rollback_ready_deadline
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	if transaction_terminal "$state_dir"; then
		discard_transaction_secrets "$state_dir" "$release_id"
		release_active_transaction_held "$application_root" "$release_id"
		return 0
	fi
	require_active_transaction "$application_root" "$release_id"
	releases_root=$(readlink -f "$application_root/releases")
	failed_release=$(readlink -f "$releases_root/$release_id" 2>/dev/null || true)
	previous_release=$(readlink -f "$releases_root/$previous_release_id")
	active_release=$(readlink -f "$application_root/current" 2>/dev/null || true)
	[[ "${previous_release%/*}" == "$releases_root" && -d "$previous_release" && -f "$previous_release/.env" ]] \
		|| fail "Rollback target is outside the immutable releases root"
	if [[ -n "$failed_release" && "${failed_release%/*}" != "$releases_root" ]]; then
		fail "Failed release is outside the immutable releases root"
	fi
	if [[ "$active_release" != "$failed_release" && "$active_release" != "$previous_release" ]]; then
		fail "Refusing to roll back an unrelated active release"
	fi
	backend_image="rulepilot-backend:${previous_release_id}"
	frontend_image="rulepilot-frontend:${previous_release_id}"
	# Prove both immutable rollback images exist before changing the shared runtime environment. A transient image
	# inspection failure must leave the still-running candidate and its configuration together for the next retry.
	docker image inspect "$backend_image" >/dev/null
	docker image inspect "$frontend_image" >/dev/null
	backend_image_id=$(docker image inspect --format '{{.Id}}' "$backend_image")
	frontend_image_id=$(docker image inspect --format '{{.Id}}' "$frontend_image")
	# Every release links to the shared root environment. Restore its exact checkpoint before Compose evaluates the
	# previous release so rollback covers runtime configuration as well as immutable images.
	restore_environment "$application_root" "$state_dir"
	# Compatibility is owned by this reviewed guard, not by the previous release's launcher. The first deployment of
	# immutable frontend rollback necessarily targets a release whose Compose file still names the local frontend
	# alias, so point both aliases at their pinned image IDs before one explicit no-build topology switch.
	docker tag "$backend_image" rulepilot-backend:local
	docker tag "$frontend_image" rulepilot-frontend:local
	(
		cd "$previous_release"
		RULEPILOT_RELEASE_ID="$previous_release_id" \
		RULEPILOT_BACKEND_IMAGE="$backend_image" \
		RULEPILOT_FRONTEND_IMAGE="$frontend_image" \
		docker compose --env-file .env \
			-f infra/compose.yml \
			-f infra/compose.deployment.yml \
			-f infra/compose.production.yml \
			up -d --no-build --no-deps api worker frontend gateway
	)
	backend_endpoint=$(compose_loopback_endpoint "$previous_release" api 8080)
	frontend_endpoint=$(compose_loopback_endpoint "$previous_release" frontend 80)
	rollback_ready_deadline=$(($(date +%s) + ROLLBACK_READY_TIMEOUT_SECONDS))
	wait_for_http api "http://${backend_endpoint}/actuator/health" "$rollback_ready_deadline"
	wait_for_http frontend "http://${frontend_endpoint}/" "$rollback_ready_deadline"
	wait_for_worker "$previous_release" "$backend_image_id" "$rollback_ready_deadline"
	# Verify the restored route through Caddy on loopback. This proves the complete application path without letting
	# an external DNS, CDN, firewall, or filing edge redefine whether the immutable host rollback succeeded.
	wait_for_http gateway "https://rulepilot.cn/api/auth/csrf" "$rollback_ready_deadline" \
		--proto '=https' --noproxy '*' --resolve 'rulepilot.cn:443:127.0.0.1'
	# A service that became ready earlier in the recovery window can still restart while a sibling catches up. Verify
	# the complete immutable topology once more immediately before making the rollback terminal and visible.
	require_running_image "$previous_release" api "$backend_image_id"
	require_running_image "$previous_release" worker "$backend_image_id"
	require_running_image "$previous_release" frontend "$frontend_image_id"
	ln -sfn "$previous_release" "$application_root/current"
	active_release=$(readlink -f "$application_root/current")
	[[ "$active_release" == "$previous_release" ]] \
		|| fail "Rollback release pointer did not return to the immutable checkpoint"
	atomic_write "$state_dir/rolled-back" "$previous_release_id"
	discard_transaction_secrets "$state_dir" "$release_id"
	release_active_transaction_held "$application_root" "$release_id"
	printf 'Rolled production back from %s to %s\n' "$release_id" "$previous_release_id"
}

rollback() {
	local application_root release_id previous_release_id
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	rollback_held "$application_root" "$release_id" "$previous_release_id"
}

rollback_if_stale() (
	local application_root release_id previous_release_id state_dir now lease_epoch
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	if transaction_terminal "$state_dir"; then
		discard_transaction_secrets "$state_dir" "$release_id"
		release_active_transaction_held "$application_root" "$release_id"
		return 0
	fi
	require_active_transaction "$application_root" "$release_id"
	[[ -f "$state_dir/armed" && -f "$state_dir/lease" ]] || return 0
	# The candidate can legitimately spend longer than one lease interval building while it owns the deployment
	# lock. Re-read the lease only after acquiring that same lock: an activation heartbeat or public commit that won
	# the race must disarm this stale observation instead of being rolled back immediately afterward.
	now=$(date +%s)
	lease_epoch=$(stat -c %Y "$state_dir/lease")
	if (( now - lease_epoch < LEASE_STALE_SECONDS )); then
		return 0
	fi
	rollback_held "$application_root" "$release_id" "$previous_release_id"
)

finalize_deadline() {
	local application_root release_id previous_release_id state_dir
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	if transaction_terminal "$state_dir"; then
		discard_transaction_secrets "$state_dir" "$release_id"
		release_active_transaction_held "$application_root" "$release_id"
		return 0
	fi
	require_active_transaction "$application_root" "$release_id"
	if [[ -f "$state_dir/armed" ]]; then
		rollback_held "$application_root" "$release_id" "$previous_release_id"
		return 0
	fi
	finalize_unchanged_held "$application_root" "$release_id" "$previous_release_id"
}

commit_release() {
	local application_root release_id previous_release_id state_dir releases_root active_release
	local backend_image frontend_image backend_image_id frontend_image_id worker_container worker_health
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	if [[ -f "$state_dir/committed" ]]; then
		discard_transaction_secrets "$state_dir" "$release_id"
		release_active_transaction_held "$application_root" "$release_id"
		return 0
	fi
	touch "$state_dir/lease"
	[[ ! -f "$state_dir/rolled-back" && ! -f "$state_dir/unchanged" ]] \
		|| fail "A closed release checkpoint cannot be committed"
	require_active_transaction "$application_root" "$release_id"
	releases_root=$(readlink -f "$application_root/releases")
	active_release=$(readlink -f "$application_root/current")
	[[ "$active_release" == "$releases_root/$release_id" ]] \
		|| fail "Only the exact active candidate release can be committed"
	backend_image="rulepilot-backend:${release_id}"
	frontend_image="rulepilot-frontend:${release_id}"
	backend_image_id=$(docker image inspect --format '{{.Id}}' "$backend_image")
	frontend_image_id=$(docker image inspect --format '{{.Id}}' "$frontend_image")
	# Publication eligibility belongs to this transaction owner. The candidate must prove the complete production
	# route through Caddy on host loopback, with the real public SNI and deterministic player-safe contracts, before
	# a terminal commit can make rollback ineligible. External observers remain useful evidence but cannot redefine
	# candidate correctness when their own DNS, TLS, or egress path is unavailable.
	verify_candidate_publication_boundary "$active_release" "$release_id"
	# A container can restart while the route contract is being checked. Revalidate every immutable runtime identity
	# and worker readiness immediately before publishing the terminal marker.
	require_running_image "$active_release" api "$backend_image_id"
	require_running_image "$active_release" worker "$backend_image_id"
	require_running_image "$active_release" frontend "$frontend_image_id"
	worker_container=$(compose_container "$active_release" worker)
	worker_health=$(docker inspect --format \
		'{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
		"$worker_container")
	[[ "$worker_health" == healthy ]] || fail "Worker readiness was lost before release commit"
	atomic_write "$state_dir/committed" "$release_id"
	discard_transaction_secrets "$state_dir" "$release_id"
	release_active_transaction_held "$application_root" "$release_id"
	printf 'Committed public production release %s\n' "$release_id"
}

record_watchdog_failure() {
	local state_dir=$1
	local action=$2
	local status=$3
	atomic_write "$state_dir/watchdog-failed" "${action}:exit-${status}"
}

watchdog_generation_matches() {
	local state_dir=$1
	local expected_generation=$2
	local actual_generation
	[[ -z "$expected_generation" ]] && return 0
	[[ -f "$state_dir/watchdog-generation" ]] || return 1
	actual_generation=$(<"$state_dir/watchdog-generation")
	[[ "$actual_generation" == "$expected_generation" ]]
}

watchdog_process_matches() {
	local process_id=$1
	local expected_generation=$2
	local argument
	[[ "$process_id" =~ ^[1-9][0-9]*$ ]] && kill -0 "$process_id" 2>/dev/null || return 1
	[[ -r "/proc/$process_id/cmdline" ]] || return 1
	while IFS= read -r -d '' argument; do
		[[ "$argument" == "$expected_generation" ]] && return 0
	done < "/proc/$process_id/cmdline"
	return 1
}

watchdog_ready_matches() {
	local state_dir=$1
	local expected_generation=$2
	local ready_generation
	[[ -n "$expected_generation" && -f "$state_dir/watchdog-ready" ]] || return 1
	ready_generation=$(<"$state_dir/watchdog-ready")
	[[ "$ready_generation" == "$expected_generation" ]]
}

require_live_watchdog() {
	local state_dir=$1
	local process_id generation
	[[ -f "$state_dir/watchdog.pid" && -f "$state_dir/watchdog-generation" ]] \
		|| fail "Rollback watchdog identity is unavailable"
	process_id=$(<"$state_dir/watchdog.pid")
	generation=$(<"$state_dir/watchdog-generation")
	watchdog_ready_matches "$state_dir" "$generation" \
		|| fail "Rollback watchdog did not publish readiness"
	watchdog_process_matches "$process_id" "$generation" \
		|| fail "Rollback watchdog is not live for this release generation"
}

watchdog() {
	local application_root release_id previous_release_id generation state_dir started now lease_epoch action_status
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	generation=${4:-}
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	watchdog_generation_matches "$state_dir" "$generation" || return 0
	require_active_transaction "$application_root" "$release_id"
	atomic_write "$state_dir/watchdog-ready" "$generation"
	started=$(date +%s)
	while :; do
		watchdog_generation_matches "$state_dir" "$generation" || return 0
		if transaction_terminal "$state_dir"; then
			if bash "$0" finalize-deadline "$application_root" "$release_id" "$previous_release_id"; then
				return 0
			fi
			printf 'Release guard terminal ownership cleanup failed; retrying before the watchdog deadline.\n' >&2
		fi
		now=$(date +%s)
		if (( now - started >= WATCHDOG_DEADLINE_SECONDS )); then
			# Run the deadline decision in a child so fail()/set -e cannot terminate this watchdog before the exit status
			# is recorded. The child acquires the deployment lock and rechecks terminal/armed state before acting.
			if bash "$0" finalize-deadline "$application_root" "$release_id" "$previous_release_id"; then
				return 0
			else
				action_status=$?
			fi
			printf 'Release guard deadline recovery failed for release %s with exit %s.\n' \
				"$release_id" "$action_status" >&2
			if ! record_watchdog_failure "$state_dir" deadline-recovery "$action_status"; then
				printf 'Release guard could not persist its terminal failure marker.\n' >&2
			fi
			return "$action_status"
		fi
		if [[ -f "$state_dir/armed" && -f "$state_dir/lease" ]]; then
			lease_epoch=$(stat -c %Y "$state_dir/lease")
			if (( now - lease_epoch >= LEASE_STALE_SECONDS )); then
				# rollback_held uses explicit exit-on-boundary failures. Isolate it so one transient Docker or readiness
				# failure is logged and retried instead of permanently killing the independent watchdog.
				if bash "$0" rollback-if-stale "$application_root" "$release_id" "$previous_release_id"; then
					if transaction_terminal "$state_dir"; then
						if bash "$0" finalize-deadline "$application_root" "$release_id" "$previous_release_id"; then
							return 0
						fi
						printf 'Release guard terminal ownership cleanup failed; retrying before the watchdog deadline.\n' >&2
					fi
				else
					action_status=$?
					printf 'Release guard rollback attempt failed for release %s with exit %s; retrying before the deadline.\n' \
						"$release_id" "$action_status" >&2
				fi
			fi
		fi
		sleep "$WATCHDOG_POLL_SECONDS"
	done
}

start_watchdog() {
	local application_root release_id previous_release_id state_dir log_file existing_pid generation process_id attempt
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	if [[ -f "$state_dir/watchdog.pid" && -f "$state_dir/watchdog-generation" ]]; then
		existing_pid=$(<"$state_dir/watchdog.pid")
		generation=$(<"$state_dir/watchdog-generation")
		for ((attempt = 1; attempt <= WATCHDOG_READY_ATTEMPTS; attempt++)); do
			if watchdog_ready_matches "$state_dir" "$generation" \
				&& watchdog_process_matches "$existing_pid" "$generation"; then
				return 0
			fi
			watchdog_process_matches "$existing_pid" "$generation" || break
			sleep "$WATCHDOG_READY_POLL_SECONDS"
		done
	fi
	log_file="$state_dir/watchdog.log"
	generation="$(date +%s%N)-$$-$RANDOM"
	rm -f -- "$state_dir/watchdog-ready"
	atomic_write "$state_dir/watchdog-generation" "$generation"
	nohup bash "$0" watchdog "$application_root" "$release_id" "$previous_release_id" \
		"$generation" \
		>>"$log_file" 2>&1 </dev/null &
	process_id=$!
	atomic_write "$state_dir/watchdog.pid" "$process_id"
	for ((attempt = 1; attempt <= WATCHDOG_READY_ATTEMPTS; attempt++)); do
		if watchdog_ready_matches "$state_dir" "$generation" \
			&& watchdog_process_matches "$process_id" "$generation"; then
			return 0
		fi
		watchdog_process_matches "$process_id" "$generation" || break
		sleep "$WATCHDOG_READY_POLL_SECONDS"
	done
	if watchdog_process_matches "$process_id" "$generation"; then
		kill "$process_id" 2>/dev/null || true
	fi
	if watchdog_generation_matches "$state_dir" "$generation"; then
		rm -f -- \
			"$state_dir/watchdog.pid" \
			"$state_dir/watchdog-generation" \
			"$state_dir/watchdog-ready"
	fi
	fail "Rollback watchdog did not become ready"
}

case "${1:-}" in
	checkpoint)
		[[ $# -eq 3 ]] || fail "Usage: $0 checkpoint APPLICATION_ROOT RELEASE_ID"
		checkpoint "$2" "$3"
		;;
	start)
		[[ $# -eq 4 ]] || fail "Usage: $0 start APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		start_watchdog "$2" "$3" "$4"
		;;
	arm)
		[[ $# -eq 4 ]] || fail "Usage: $0 arm APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		arm "$2" "$3" "$4"
		;;
	assert-activation-held)
		[[ $# -eq 4 ]] || fail "Usage: $0 assert-activation-held APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		assert_activation_held "$2" "$3" "$4"
		;;
	heartbeat)
		[[ $# -eq 4 ]] || fail "Usage: $0 heartbeat APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		heartbeat "$2" "$3" "$4"
		;;
	commit)
		[[ $# -eq 4 ]] || fail "Usage: $0 commit APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		commit_release "$2" "$3" "$4"
		;;
	rollback)
		[[ $# -eq 4 ]] || fail "Usage: $0 rollback APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		rollback "$2" "$3" "$4"
		;;
	rollback-if-stale)
		[[ $# -eq 4 ]] || fail "Usage: $0 rollback-if-stale APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		rollback_if_stale "$2" "$3" "$4"
		;;
	rollback-held)
		[[ $# -eq 4 ]] || fail "Usage: $0 rollback-held APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		rollback_held "$2" "$3" "$4"
		;;
	finalize-unchanged)
		[[ $# -eq 4 ]] || fail "Usage: $0 finalize-unchanged APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		finalize_unchanged "$2" "$3" "$4"
		;;
	finalize-deadline)
		[[ $# -eq 4 ]] || fail "Usage: $0 finalize-deadline APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		finalize_deadline "$2" "$3" "$4"
		;;
	watchdog)
		[[ $# -eq 4 || $# -eq 5 ]] || fail "Usage: $0 watchdog APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID [GENERATION]"
		watchdog "$2" "$3" "$4" "${5:-}"
		;;
	*)
		fail "Usage: $0 {checkpoint|start|arm|assert-activation-held|heartbeat|commit|rollback|rollback-if-stale|rollback-held|finalize-unchanged|finalize-deadline|watchdog} ..."
		;;
esac
