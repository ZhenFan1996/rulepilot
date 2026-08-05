#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
	cat <<'EOF'
Usage: RULEPILOT_ADMIN_USERNAME=... RULEPILOT_ADMIN_PASSWORD=... \
  manage-public-lesson-candidate.sh --base-url URL (--plan-id UUID | --lesson-title TITLE) \
  --operation stage|apply

Stages a public lesson candidate without replacing the active lesson, or applies the
latest deterministic recommendation in a separate invocation.
EOF
}

base_url=
plan_id=
lesson_title=
operation=
timeout_seconds=1800

while [ "$#" -gt 0 ]; do
	case "$1" in
		--base-url) base_url=${2:-}; shift 2 ;;
		--plan-id) plan_id=${2:-}; shift 2 ;;
		--lesson-title) lesson_title=${2:-}; shift 2 ;;
		--operation) operation=${2:-}; shift 2 ;;
		--timeout-seconds) timeout_seconds=${2:-}; shift 2 ;;
		--help|-h) usage; exit 0 ;;
		*) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
done

if [ -z "$base_url" ] || ! [[ "$base_url" =~ ^https?://(127\.0\.0\.1|localhost)(:[0-9]+)?/?$ ]]; then
	echo "--base-url must be a loopback RulePilot URL" >&2
	exit 2
fi
if { [ -n "$plan_id" ] && [ -n "$lesson_title" ]; } || { [ -z "$plan_id" ] && [ -z "$lesson_title" ]; }; then
	echo "exactly one of --plan-id or --lesson-title is required" >&2
	exit 2
fi
if { [ -n "$plan_id" ] && ! [[ "$plan_id" =~ ^[0-9a-fA-F-]{36}$ ]]; } \
		|| ! [[ "$operation" =~ ^(stage|apply)$ ]]; then
	usage >&2
	exit 2
fi
if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
	echo "--timeout-seconds must be a positive integer" >&2
	exit 2
fi
if [ -z "${RULEPILOT_ADMIN_USERNAME:-}" ] || [ -z "${RULEPILOT_ADMIN_PASSWORD:-}" ]; then
	echo "RULEPILOT_ADMIN_USERNAME and RULEPILOT_ADMIN_PASSWORD are required" >&2
	exit 2
fi
for command_name in curl jq mktemp; do
	command -v "$command_name" >/dev/null 2>&1 || { echo "$command_name is required" >&2; exit 2; }
done

base_url=${base_url%/}
candidate_work_dir=$(mktemp -d)
cookie_jar="$candidate_work_dir/cookies.txt"
csrf_header=
csrf_token=
trap 'rm -rf "$candidate_work_dir"' EXIT

get_json() {
	curl --fail-with-body --silent --show-error \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		"$base_url$1"
}

refresh_csrf() {
	local response
	response=$(get_json "/api/auth/csrf")
	csrf_header=$(jq -er '.headerName' <<<"$response")
	csrf_token=$(jq -er '.token' <<<"$response")
}

post_json() {
	curl --fail-with-body --silent --show-error \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		--request POST --header "$csrf_header: $csrf_token" \
		--header "Content-Type: application/json" \
		"$base_url$1"
}

comparison_summary() {
	local run_state=${1:-STAGED}
	jq --arg runState "$run_state" '{
		candidateRunState: $runState,
		active: {
			lessonId: .active.lesson.id,
			status: .active.lesson.status,
			generatorVersion: .active.lesson.generatorVersion,
			sectionCount: (.active.lesson.sections | length),
			stepCount: ([.active.lesson.sections[].steps[]] | length),
			qualityScore: .active.quality.score
		},
		candidate: {
			lessonId: .candidate.lesson.id,
			status: .candidate.lesson.status,
			generatorVersion: .candidate.lesson.generatorVersion,
			sectionCount: (.candidate.lesson.sections | length),
			stepCount: ([.candidate.lesson.sections[].steps[]] | length),
			qualityScore: .candidate.quality.score
		},
		recommendation,
		reasons
	}'
}

refresh_csrf
curl --fail-with-body --silent --show-error --output /dev/null \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "Content-Type: application/x-www-form-urlencoded" \
	--header "$csrf_header: $csrf_token" \
	--data-urlencode "username=$RULEPILOT_ADMIN_USERNAME" \
	--data-urlencode "password=$RULEPILOT_ADMIN_PASSWORD" \
	"$base_url/api/auth/login"

session=$(get_json "/api/auth/session")
if ! jq -e --arg username "$RULEPILOT_ADMIN_USERNAME" \
	'.username == $username and (.roles | index("ADMIN")) != null' >/dev/null <<<"$session"; then
	echo "Authenticated session is not the configured administrator" >&2
	exit 1
fi

if [ -n "$lesson_title" ]; then
	catalog=$(get_json "/api/public/lessons?limit=60")
	match_count=$(jq -er --arg title "$lesson_title" '[.[] | select(.rulebookTitle == $title)] | length' <<<"$catalog")
	if [ "$match_count" != "1" ]; then
		echo "--lesson-title must match exactly one public lesson; matched $match_count" >&2
		exit 1
	fi
	plan_id=$(jq -er --arg title "$lesson_title" \
		'.[] | select(.rulebookTitle == $title) | .teachingPlanId' <<<"$catalog")
fi

candidate_path="/api/admin/public-lessons/$plan_id/candidates"
if [ "$operation" = "apply" ]; then
	comparison=$(get_json "$candidate_path/latest")
	comparison_summary STAGED <<<"$comparison" >&2
	refresh_csrf
	post_json "$candidate_path/latest/apply-recommendation"
	exit 0
fi

refresh_csrf
launch=$(post_json "$candidate_path")
run_id=$(jq -er '.assistantRunId' <<<"$launch")
deadline=$((SECONDS + timeout_seconds))
state=RECEIVED
while [ "$SECONDS" -lt "$deadline" ]; do
	run=$(get_json "/api/v1/assistant-runs/$run_id")
	state=$(jq -er '.run.state' <<<"$run")
	case "$state" in
		COMPLETED) break ;;
		FAILED|DEGRADED|INSUFFICIENT_EVIDENCE)
			jq -r '.activities[]?
				| select(.outcome == "REJECTED")
				| "CANDIDATE_ACTIVITY operation=\(.operation) outcome=\(.outcome) summary=\((.summary // "") | gsub("[\\r\\n]"; " ") | .[0:300])"' \
				<<<"$run" >&2
			break
			;;
	esac
	sleep 2
done
if ! [[ "$state" =~ ^(COMPLETED|FAILED|DEGRADED|INSUFFICIENT_EVIDENCE)$ ]]; then
	echo "Public lesson candidate timed out after ${timeout_seconds}s" >&2
	exit 1
fi

comparison=$(get_json "$candidate_path/latest")
comparison_summary "$state" <<<"$comparison"
