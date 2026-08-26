#!/usr/bin/env bash

set -Eeuo pipefail

readonly LEASE_STALE_SECONDS=150
readonly WATCHDOG_DEADLINE_SECONDS=2100

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

resolve_application_root() {
	local root
	root=$(readlink -f "$1")
	[[ -d "$root" ]] || fail "Production application root is unavailable"
	printf '%s\n' "$root"
}

guard_directory() {
	printf '%s/deployment-guards/%s\n' "$1" "$2"
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

managed_port() {
	local env_file=$1
	local key=$2
	local fallback=$3
	local value
	value=$(sed -n "s/^${key}=//p" "$env_file" | head -n 1)
	value=${value:-$fallback}
	case "$value" in
		\"*\") value=${value#\"}; value=${value%\"} ;;
		\'*\') value=${value#\'}; value=${value%\'} ;;
	esac
	[[ "$value" =~ ^[1-9][0-9]{0,4}$ && "$value" -le 65535 ]] \
		|| fail "Invalid managed production port"
	printf '%s\n' "$value"
}

wait_for_http() {
	local url=$1
	local attempts=$2
	local attempt=1
	while (( attempt <= attempts )); do
		if curl -fsS "$url" >/dev/null 2>&1; then
			return 0
		fi
		attempt=$((attempt + 1))
		sleep 2
	done
	fail "Immutable rollback service did not become ready"
}

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
	local attempts=$3
	local attempt=1 container running actual_image worker_health
	while (( attempt <= attempts )); do
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
		attempt=$((attempt + 1))
		sleep 2
	done
	fail "Immutable rollback worker did not become ready"
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

checkpoint() {
	local application_root release_id releases_root current_release previous_release_id
	local api_container worker_container frontend_container api_image worker_image frontend_image
	application_root=$(resolve_application_root "$1")
	release_id=$2
	require_candidate_release_id "$release_id"
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

	local state_dir
	state_dir=$(guard_directory "$application_root" "$release_id")
	install -d -m 0700 "$state_dir"
	atomic_write "$state_dir/previous-release" "$previous_release_id"
	rm -f "$state_dir/armed" "$state_dir/committed" "$state_dir/rolled-back"
	printf '%s\n' "$previous_release_id"
}

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
	printf '%s\n' "$state_dir"
}

heartbeat() {
	local application_root release_id previous_release_id state_dir
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	[[ ! -f "$state_dir/committed" && ! -f "$state_dir/rolled-back" ]] || return 0
	touch "$state_dir/lease"
}

arm() {
	local application_root release_id previous_release_id state_dir
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	[[ ! -f "$state_dir/committed" ]] || fail "Committed release cannot be re-armed"
	touch "$state_dir/lease"
	atomic_write "$state_dir/armed" "$release_id"
}

rollback_held() {
	local application_root release_id previous_release_id state_dir releases_root
	local failed_release previous_release active_release backend_image frontend_image backend_image_id frontend_image_id
	local backend_port frontend_port
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	[[ ! -f "$state_dir/committed" ]] || return 0
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
	docker image inspect "$backend_image" >/dev/null
	docker image inspect "$frontend_image" >/dev/null
	backend_image_id=$(docker image inspect --format '{{.Id}}' "$backend_image")
	frontend_image_id=$(docker image inspect --format '{{.Id}}' "$frontend_image")
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
	backend_port=$(managed_port "$previous_release/.env" BACKEND_PORT 8080)
	frontend_port=$(managed_port "$previous_release/.env" RULEPILOT_HTTP_PORT 80)
	wait_for_http "http://127.0.0.1:${backend_port}/actuator/health" 60
	wait_for_http "http://127.0.0.1:${frontend_port}/" 45
	wait_for_worker "$previous_release" "$backend_image_id" 60
	# A service that became ready earlier in the recovery window can still restart while a sibling catches up. Verify
	# the complete immutable topology once more immediately before making the rollback terminal and visible.
	require_running_image "$previous_release" api "$backend_image_id"
	require_running_image "$previous_release" worker "$backend_image_id"
	require_running_image "$previous_release" frontend "$frontend_image_id"
	ln -sfn "$previous_release" "$application_root/current"
	atomic_write "$state_dir/rolled-back" "$previous_release_id"
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
	[[ ! -f "$state_dir/committed" && ! -f "$state_dir/rolled-back" ]] || return 0
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

commit_release() {
	local application_root release_id previous_release_id state_dir releases_root active_release
	local backend_image frontend_image backend_image_id frontend_image_id worker_container worker_health
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	touch "$state_dir/lease"
	exec 9>"$application_root/deployment.lock"
	flock -x 9
	[[ ! -f "$state_dir/rolled-back" ]] || fail "A rolled-back release cannot be committed"
	releases_root=$(readlink -f "$application_root/releases")
	active_release=$(readlink -f "$application_root/current")
	[[ "$active_release" == "$releases_root/$release_id" ]] \
		|| fail "Only the exact publicly verified active release can be committed"
	backend_image="rulepilot-backend:${release_id}"
	frontend_image="rulepilot-frontend:${release_id}"
	backend_image_id=$(docker image inspect --format '{{.Id}}' "$backend_image")
	frontend_image_id=$(docker image inspect --format '{{.Id}}' "$frontend_image")
	require_running_image "$active_release" api "$backend_image_id"
	require_running_image "$active_release" worker "$backend_image_id"
	require_running_image "$active_release" frontend "$frontend_image_id"
	worker_container=$(compose_container "$active_release" worker)
	worker_health=$(docker inspect --format \
		'{{if .State.Health}}{{.State.Health.Status}}{{else}}not-configured{{end}}' \
		"$worker_container")
	[[ "$worker_health" == healthy ]] || fail "Worker readiness was lost before release commit"
	atomic_write "$state_dir/committed" "$release_id"
	printf 'Committed public production release %s\n' "$release_id"
}

watchdog() {
	local application_root release_id previous_release_id state_dir started now lease_epoch
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	started=$(date +%s)
	while :; do
		[[ ! -f "$state_dir/committed" && ! -f "$state_dir/rolled-back" ]] || return 0
		now=$(date +%s)
		if [[ -f "$state_dir/armed" && -f "$state_dir/lease" ]]; then
			lease_epoch=$(stat -c %Y "$state_dir/lease")
			if (( now - lease_epoch >= LEASE_STALE_SECONDS )); then
				rollback_if_stale "$application_root" "$release_id" "$previous_release_id"
				[[ ! -f "$state_dir/committed" && ! -f "$state_dir/rolled-back" ]] || return 0
			fi
		fi
		if (( now - started >= WATCHDOG_DEADLINE_SECONDS )); then
			if [[ -f "$state_dir/armed" ]]; then
				rollback "$application_root" "$release_id" "$previous_release_id"
			fi
			return 0
		fi
		sleep 5
	done
}

start_watchdog() {
	local application_root release_id previous_release_id state_dir log_file existing_pid
	application_root=$(resolve_application_root "$1")
	release_id=$2
	previous_release_id=$3
	state_dir=$(require_checkpoint "$application_root" "$release_id" "$previous_release_id")
	if [[ -f "$state_dir/watchdog.pid" ]]; then
		existing_pid=$(<"$state_dir/watchdog.pid")
		if [[ "$existing_pid" =~ ^[1-9][0-9]*$ ]] && kill -0 "$existing_pid" 2>/dev/null; then
			return 0
		fi
	fi
	log_file="$state_dir/watchdog.log"
	nohup "$0" watchdog "$application_root" "$release_id" "$previous_release_id" \
		>>"$log_file" 2>&1 </dev/null &
	atomic_write "$state_dir/watchdog.pid" "$!"
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
	watchdog)
		[[ $# -eq 4 ]] || fail "Usage: $0 watchdog APPLICATION_ROOT RELEASE_ID PREVIOUS_RELEASE_ID"
		watchdog "$2" "$3" "$4"
		;;
	*)
		fail "Usage: $0 {checkpoint|start|arm|heartbeat|commit|rollback|rollback-if-stale|rollback-held|watchdog} ..."
		;;
esac
