#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
	cat <<'EOF'
Usage: RULEPILOT_SMOKE_PASSWORD=... smoke-production-ordinary-user.sh \
  --base-url URL --pdf FILE [--username USER] [--timeout-seconds SECONDS]

Runs the authenticated upload -> processing -> teaching plan -> illustrated lesson
journey and removes the synthetic document before exiting.
EOF
}

base_url=
pdf_file=
username=player
timeout_seconds=1500
expected_title="lantern relay"
uploaded_title="Lantern Relay rulebook EN v4 12pages"

while [ "$#" -gt 0 ]; do
	case "$1" in
		--base-url)
			base_url=${2:-}
			shift 2
			;;
		--pdf)
			pdf_file=${2:-}
			shift 2
			;;
		--username)
			username=${2:-}
			shift 2
			;;
		--timeout-seconds)
			timeout_seconds=${2:-}
			shift 2
			;;
		--help|-h)
			usage
			exit 0
			;;
		*)
			echo "Unknown argument: $1" >&2
			usage >&2
			exit 2
			;;
	esac
done

if [ -z "$base_url" ] || [ -z "$pdf_file" ] || [ ! -f "$pdf_file" ]; then
	usage >&2
	exit 2
fi
if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
	echo "--timeout-seconds must be a positive integer" >&2
	exit 2
fi
if [ -z "${RULEPILOT_SMOKE_PASSWORD:-}" ]; then
	echo "RULEPILOT_SMOKE_PASSWORD is required" >&2
	exit 2
fi
for command_name in curl jq mktemp; do
	if ! command -v "$command_name" >/dev/null 2>&1; then
		echo "$command_name is required" >&2
		exit 2
	fi
done

base_url=${base_url%/}
work_dir=$(mktemp -d)
cookie_jar="$work_dir/cookies.txt"
document_id=
preparation_run_id=
lesson_run_id=
csrf_header=
csrf_token=

get_json() {
	curl --fail-with-body --silent --show-error \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		"$base_url$1"
}

log_stage() {
	printf 'SMOKE_STAGE %s\n' "$1" >&2
}

log_run_timing() {
	local phase=$1
	local response=$2
	jq -r --arg phase "$phase" '
        "SMOKE_TIMING phase=\($phase) kind=run createdAt=\(.run.createdAt // "unknown") completedAt=\(.run.completedAt // "unknown")",
        (.steps[]? | "SMOKE_TIMING phase=\($phase) kind=step sequence=\(.sequence) from=\(.fromState) to=\(.toState) occurredAt=\(.occurredAt)"),
        (.activities[]? | "SMOKE_TIMING phase=\($phase) kind=activity sequence=\(.sequence) type=\(.type) operation=\(.operation) outcome=\(.outcome) latencyMs=\(.latencyMs // 0) inputTokens=\(.estimatedInputTokens // 0) outputTokens=\(.estimatedOutputTokens // 0) occurredAt=\(.occurredAt // "unknown")"),
        (if .budget then "SMOKE_TIMING phase=\($phase) kind=budget usedModelCalls=\(.budget.usedModelCalls // 0) usedToolCalls=\(.budget.usedToolCalls // 0) usedTokens=\(.budget.usedTokens // 0)" else empty end)
    ' <<<"$response" >&2
}

verify_preparation_critical_path() {
	local response=$1
	if jq -e '.activities[]? | select(.operation | startswith("inspectRulebookVisualBatch"))' \
		>/dev/null <<<"$response"; then
		echo "SMOKE_WARNING Text-rulebook preparation performed visual catalog work before publishing the plan" >&2
	fi
	if ! jq -e '.activities[]? | select(.operation == "deferSelectedVisualPageCatalog" and .outcome == "SUCCEEDED")' \
		>/dev/null <<<"$response"; then
		echo "SMOKE_WARNING Text-rulebook preparation did not report the deferred visual-catalog boundary" >&2
	fi
}

verify_lesson_critical_path() {
	local response=$1
	local section_count=$2
	local metrics first_section_seconds total_seconds used_model_calls correction_calls model_call_limit
	metrics=$(jq -er '
        def epoch: sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;
        (.run.createdAt | epoch) as $started
        | (.run.completedAt | epoch) as $completed
        | ([.activities[]?
            | select(.operation | startswith("publishTeachingSection|"))
            | select(.outcome == "SUCCEEDED")
            | .occurredAt | epoch] | min) as $firstPublished
        | {
            firstSectionSeconds: ($firstPublished - $started),
            totalSeconds: ($completed - $started),
            usedModelCalls: (.budget.usedModelCalls // 0),
            correctionCalls: ([.activities[]?
                | select((.operation | startswith("correctTeachingSection|"))
                    or (.operation | startswith("repairCorrectedTeachingSection|")))] | length)
          }
    ' <<<"$response")
	first_section_seconds=$(jq -er '.firstSectionSeconds' <<<"$metrics")
	total_seconds=$(jq -er '.totalSeconds' <<<"$metrics")
	used_model_calls=$(jq -er '.usedModelCalls' <<<"$metrics")
	correction_calls=$(jq -er '.correctionCalls' <<<"$metrics")
	model_call_limit=$((section_count + 4))
	printf 'SMOKE_PERFORMANCE phase=lesson firstSectionSeconds=%s totalSeconds=%s usedModelCalls=%s modelCallLimit=%s correctionCalls=%s\n' \
		"$first_section_seconds" "$total_seconds" "$used_model_calls" "$model_call_limit" "$correction_calls" >&2
	if [ "$first_section_seconds" -gt 15 ]; then
		echo "SMOKE_WARNING First cited lesson section exceeded the 15-second target" >&2
	fi
	if [ "$total_seconds" -gt 90 ]; then
		echo "SMOKE_WARNING Complete cited lesson exceeded the 90-second target" >&2
	fi
	if [ "$used_model_calls" -gt "$model_call_limit" ]; then
		echo "SMOKE_WARNING Lesson model calls exceeded the section-relative target" >&2
	fi
	if [ "$correction_calls" -gt 2 ]; then
		echo "SMOKE_WARNING Post-publication lesson corrections exceeded the target" >&2
	fi
}

refresh_csrf() {
	local response
	response=$(get_json "/api/auth/csrf")
	csrf_header=$(jq -er '.headerName' <<<"$response")
	csrf_token=$(jq -er '.token' <<<"$response")
}

cancel_run() {
	local run_id=$1
	[ -n "$run_id" ] || return 0
	curl --silent --show-error --output /dev/null \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		--request POST --header "$csrf_header: $csrf_token" \
		"$base_url/api/v1/assistant-runs/$run_id/cancellation" || true
}

cleanup() {
	local exit_status=$?
	set +e
	if [ -n "$csrf_header" ] && [ -n "$csrf_token" ]; then
		cancel_run "$lesson_run_id"
		cancel_run "$preparation_run_id"
		if [ -n "$document_id" ]; then
			if curl --silent --show-error --output /dev/null \
				--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
				--request DELETE --header "$csrf_header: $csrf_token" \
				"$base_url/api/v1/documents/$document_id"; then
				log_stage "cleanup-completed"
			else
				log_stage "cleanup-failed"
			fi
		fi
	fi
	rm -rf "$work_dir"
	exit "$exit_status"
}
trap cleanup EXIT

wait_for_document_ready() {
	local version_id=$1
	local deadline=$((SECONDS + timeout_seconds))
	local response status
	while [ "$SECONDS" -lt "$deadline" ]; do
		response=$(get_json "/api/v1/documents")
		status=$(jq -r --arg version_id "$version_id" \
			'.[] | select(.latestVersion.id == $version_id) | .latestVersion.status' <<<"$response")
		case "$status" in
			READY)
				return 0
				;;
			FAILED)
				echo "Document processing failed" >&2
				return 1
				;;
		esac
		sleep 2
	done
	echo "Document processing timed out after ${timeout_seconds}s" >&2
	return 1
}

wait_for_run() {
	local run_id=$1
	local label=$2
	local deadline=$((SECONDS + timeout_seconds))
	local response state
	while [ "$SECONDS" -lt "$deadline" ]; do
		response=$(get_json "/api/v1/assistant-runs/$run_id")
		state=$(jq -er '.run.state' <<<"$response")
		case "$state" in
			COMPLETED)
				printf '%s' "$response"
				return 0
				;;
			FAILED|DEGRADED|CANCELLED)
				echo "$label ended in $state" >&2
				return 1
				;;
		esac
		sleep 2
	done
	echo "$label timed out after ${timeout_seconds}s" >&2
	return 1
}

refresh_csrf
log_stage "csrf-ready"
curl --fail-with-body --silent --show-error --output /dev/null \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "Content-Type: application/x-www-form-urlencoded" \
	--header "$csrf_header: $csrf_token" \
	--data-urlencode "username=$username" \
	--data-urlencode "password=$RULEPILOT_SMOKE_PASSWORD" \
	"$base_url/api/auth/login"

session=$(get_json "/api/auth/session")
if ! jq -e --arg username "$username" '.username == $username' >/dev/null <<<"$session"; then
	echo "Authenticated session did not belong to the smoke user" >&2
	exit 1
fi
log_stage "login-completed"

refresh_csrf
upload_response=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "$csrf_header: $csrf_token" \
	--form "title=$uploaded_title" \
	--form "sourceType=BASE_RULEBOOK" \
	--form "file=@$pdf_file;type=application/pdf" \
	"$base_url/api/v1/documents")

document_id=$(jq -er '.document.id' <<<"$upload_response")
version_id=$(jq -er '.version.id' <<<"$upload_response")
if ! jq -e '.duplicate == false' >/dev/null <<<"$upload_response"; then
	echo "Synthetic smoke upload unexpectedly reused an existing document" >&2
	exit 1
fi
log_stage "upload-completed"
wait_for_document_ready "$version_id"
log_stage "document-ready"

refresh_csrf
preparation_launch=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "Content-Type: application/json" \
	--header "$csrf_header: $csrf_token" \
	--data '{"playerCount":2,"beginnerCount":1,"durationMinutes":20}' \
	"$base_url/api/v1/document-versions/$version_id/teaching-plans")
preparation_run_id=$(jq -er '.assistantRunId' <<<"$preparation_launch")
preparation_result=$(wait_for_run "$preparation_run_id" "Teaching preparation")
preparation_state=$(jq -er '.run.state' <<<"$preparation_result")
log_run_timing "preparation" "$preparation_result"
verify_preparation_critical_path "$preparation_result"
log_stage "teaching-preparation-completed"

plan=$(get_json "/api/v1/document-versions/$version_id/teaching-plans/latest")
plan_id=$(jq -er '.id' <<<"$plan")
plan_title=$(jq -er '.gameTitle' <<<"$plan")
plan_section_count=$(jq -er '.sections | length' <<<"$plan")
log_stage "teaching-plan-inspected title=$plan_title sections=$plan_section_count"
if ! jq -e --arg expected "$expected_title" \
	'(.gameTitle | ascii_downcase) == $expected and (.sections | length > 0)' >/dev/null <<<"$plan"; then
	echo "Teaching plan was unusable: title=$plan_title sections=$plan_section_count" >&2
	exit 1
fi
log_stage "teaching-plan-verified"

documents_response=$(get_json "/api/v1/documents")
document_response=$(jq -er --arg document_id "$document_id" \
	'.[] | select(.document.id == $document_id)' <<<"$documents_response")
actual_title=$(jq -er '.document.title' <<<"$document_response")
if ! jq -e --arg expected "$expected_title" \
	'(.document.title | ascii_downcase) == $expected' >/dev/null <<<"$document_response"; then
	echo "Expected the source-grounded title Lantern Relay, got: $actual_title (plan: $plan_title)" >&2
	exit 1
fi
log_stage "title-verified"

refresh_csrf
lesson_launch=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "$csrf_header: $csrf_token" \
	"$base_url/api/v1/teaching-plans/$plan_id/illustrated-lessons")
lesson_run_id=$(jq -er '.assistantRunId' <<<"$lesson_launch")
lesson_result=$(wait_for_run "$lesson_run_id" "Illustrated lesson")
lesson_state=$(jq -er '.run.state' <<<"$lesson_result")
log_run_timing "lesson" "$lesson_result"
verify_lesson_critical_path "$lesson_result" "$plan_section_count"
log_stage "lesson-generation-completed"

lesson=$(get_json "/api/v1/teaching-plans/$plan_id/illustrated-lessons/latest")
lesson_status=$(jq -er '.status' <<<"$lesson")
section_count=$(jq -er '.sections | length' <<<"$lesson")
if ! jq -e '(.status == "COMPLETE" or .status == "DRAFT_READY") and (.sections | length > 0)' \
	>/dev/null <<<"$lesson"; then
	echo "Illustrated lesson was unusable: status=$lesson_status sections=$section_count" >&2
	exit 1
fi
log_stage "lesson-verified"

jq -n \
	--arg title "$actual_title" \
	--arg preparationState "$preparation_state" \
	--arg lessonState "$lesson_state" \
	--arg lessonStatus "$lesson_status" \
	--argjson sectionCount "$section_count" \
	'{title: $title, preparationState: $preparationState, lessonState: $lessonState,
	  lessonStatus: $lessonStatus, sectionCount: $sectionCount, cleanup: "scheduled"}'
