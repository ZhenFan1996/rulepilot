#!/usr/bin/env bash

set -Eeuo pipefail

validate_public_status() {
	local status_file=${1:-}
	local expected_exit=${2:-}
	if [ -z "$status_file" ] || ! [[ "$expected_exit" =~ ^(0|[1-9][0-9]{0,2})$ ]] \
			|| [ "$expected_exit" -gt 255 ] || [ ! -f "$status_file" ]; then
		return 2
	fi
	jq -e --argjson expectedExit "$expected_exit" '
		type == "object"
		and (keys | sort == ["cleanupOutcome", "exitCode", "failureCode", "lastCompletedStage", "outcome"])
		and (.exitCode == $expectedExit)
		and (.lastCompletedStage | type == "string" and test("^[a-z0-9-]+$"))
		and (.cleanupOutcome == "SUCCEEDED" or .cleanupOutcome == "FAILED" or .cleanupOutcome == "NOT_REQUIRED")
		and (
			if $expectedExit == 0 then
				.outcome == "SUCCEEDED"
				and .failureCode == null
				and .lastCompletedStage == "journey-completed"
				and .cleanupOutcome != "FAILED"
			else
				.outcome == "FAILED"
				and (.failureCode == "INPUT_INVALID"
					or .failureCode == "AUTHENTICATION_FAILED"
					or .failureCode == "SOURCE_DISCOVERY_FAILED"
					or .failureCode == "SOURCE_UPLOAD_FAILED"
					or .failureCode == "OFFICIAL_IMPORT_FAILED"
					or .failureCode == "PAGE_SEQUENCE_INVALID"
					or .failureCode == "TEACHING_PREPARATION_FAILED"
					or .failureCode == "TEACHING_PLAN_INVALID"
					or .failureCode == "LESSON_GENERATION_FAILED"
					or .failureCode == "ANSWER_EVIDENCE_INVALID"
					or .failureCode == "NAVIGATION_FAILED"
					or .failureCode == "CLEANUP_FAILED"
					or .failureCode == "UNEXPECTED_SMOKE_FAILURE")
				and (if .failureCode == "CLEANUP_FAILED" then
					.cleanupOutcome == "FAILED" and .lastCompletedStage == "journey-completed"
				else true end)
			end
		)
	' "$status_file" >/dev/null
}

# Workflow-only validation keeps the public artifact contract executable and testable.
if [ "${1:-}" = --validate-public-status ]; then
	if validate_public_status "${2:-}" "${3:-}"; then
		exit 0
	else
		exit $?
	fi
fi

public_status_file=${RULEPILOT_SMOKE_PUBLIC_STATUS_FILE:-}
last_completed_stage=not-started
pending_failure_code=INPUT_INVALID
cleanup_outcome=NOT_REQUIRED
cleanup_required=false

write_public_status() {
	local exit_status=$1
	[ -n "$public_status_file" ] || return 0
	local outcome failure_json safe_stage safe_code safe_cleanup status_dir status_tmp
	safe_stage=$last_completed_stage
	if ! [[ "$safe_stage" =~ ^[a-z0-9-]+$ ]]; then safe_stage=unknown; fi
	safe_code=$pending_failure_code
	case "$safe_code" in
		INPUT_INVALID|AUTHENTICATION_FAILED|SOURCE_DISCOVERY_FAILED|SOURCE_UPLOAD_FAILED|OFFICIAL_IMPORT_FAILED|PAGE_SEQUENCE_INVALID|TEACHING_PREPARATION_FAILED|TEACHING_PLAN_INVALID|LESSON_GENERATION_FAILED|ANSWER_EVIDENCE_INVALID|NAVIGATION_FAILED|CLEANUP_FAILED|UNEXPECTED_SMOKE_FAILURE) ;;
		*) safe_code=UNEXPECTED_SMOKE_FAILURE ;;
	esac
	safe_cleanup=$cleanup_outcome
	case "$safe_cleanup" in
		SUCCEEDED|FAILED|NOT_REQUIRED) ;;
		*) safe_cleanup=FAILED ;;
	esac
	if [ "$exit_status" -eq 0 ]; then
		outcome=SUCCEEDED
		failure_json=null
	else
		outcome=FAILED
		failure_json="\"$safe_code\""
	fi
	status_dir=$(dirname "$public_status_file")
	mkdir -p "$status_dir"
	status_tmp="${public_status_file}.tmp.$$"
	umask 077
	printf '{"outcome":"%s","exitCode":%d,"lastCompletedStage":"%s","failureCode":%s,"cleanupOutcome":"%s"}\n' \
		"$outcome" "$exit_status" "$safe_stage" "$failure_json" "$safe_cleanup" > "$status_tmp"
	chmod 600 "$status_tmp"
	mv "$status_tmp" "$public_status_file"
}

write_early_public_status() {
	local exit_status=$?
	trap - EXIT
	write_public_status "$exit_status"
	exit "$exit_status"
}
trap write_early_public_status EXIT

usage() {
	cat <<'EOF'
Usage: RULEPILOT_SMOKE_PASSWORD=... smoke-production-ordinary-user.sh \
  --base-url URL [--pdf FILE | --source-mode official_image_gallery] \
  [--username USER] [--timeout-seconds SECONDS] \
  [--expected-title TITLE] [--uploaded-title TITLE] [--official-source-url URL] \
  [--bgg-id ID] [--expected-page-count COUNT] [--language LANGUAGE] [--canary-id ID] \
  [--rights-confirmed] \
  [--preparation-mode text|visual] [--navigation-mode all|api] \
  [--visual-expectation any|required|forbidden] \
  [--question SOURCE-GROUNDED-QUESTION] \
  [--navigation-file FILE] [--result-file FILE]

Runs the authenticated source -> processing -> teaching plan -> illustrated lesson
journey and removes only the fresh canary document before exiting.
EOF
}

base_url=
pdf_file=
source_mode=upload
username=player
timeout_seconds=1500
cleanup_import_wait_seconds=${RULEPILOT_SMOKE_CLEANUP_TIMEOUT_SECONDS:-60}
expected_title="lantern relay"
uploaded_title="Lantern Relay rulebook EN v4 12pages"
official_source_url=
bgg_id=
expected_page_count=
answer_language=en
canary_id=
rights_confirmed=false
preparation_mode=text
navigation_mode=all
visual_expectation=any
question="How many victory points is each lit dock worth during final scoring?"
navigation_file=
result_file=

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
		--source-mode)
			source_mode=${2:-}
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
		--expected-title)
			expected_title=${2:-}
			shift 2
			;;
		--uploaded-title)
			uploaded_title=${2:-}
			shift 2
			;;
		--official-source-url)
			official_source_url=${2:-}
			shift 2
			;;
		--bgg-id)
			bgg_id=${2:-}
			shift 2
			;;
		--expected-page-count)
			expected_page_count=${2:-}
			shift 2
			;;
		--language)
			answer_language=${2:-}
			shift 2
			;;
		--canary-id)
			canary_id=${2:-}
			shift 2
			;;
		--rights-confirmed)
			rights_confirmed=true
			shift
			;;
		--preparation-mode)
			preparation_mode=${2:-}
			shift 2
			;;
		--navigation-mode)
			navigation_mode=${2:-}
			shift 2
			;;
		--visual-expectation)
			visual_expectation=${2:-}
			shift 2
			;;
		--question)
			question=${2:-}
			shift 2
			;;
		--navigation-file)
			navigation_file=${2:-}
			shift 2
			;;
		--result-file)
			result_file=${2:-}
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

if [ -z "$base_url" ]; then
	usage >&2
	exit 2
fi
if [ "$source_mode" != upload ] && [ "$source_mode" != official_image_gallery ]; then
	echo "--source-mode must be upload or official_image_gallery" >&2
	exit 2
fi
if [ "$source_mode" = upload ] && { [ -z "$pdf_file" ] || [ ! -f "$pdf_file" ]; }; then
	usage >&2
	exit 2
fi
if [ "$source_mode" = official_image_gallery ]; then
	if [ -n "$pdf_file" ]; then
		echo "--pdf cannot be combined with official_image_gallery" >&2
		exit 2
	fi
	if ! [[ "$bgg_id" =~ ^[1-9][0-9]*$ ]]; then
		echo "--bgg-id must be a positive integer for official_image_gallery" >&2
		exit 2
	fi
	if ! [[ "$expected_page_count" =~ ^[1-9][0-9]*$ ]] || [ "$expected_page_count" -gt 500 ]; then
		echo "--expected-page-count must be between 1 and 500 for official_image_gallery" >&2
		exit 2
	fi
	if [ -z "$canary_id" ] || ! [[ "$canary_id" =~ ^[A-Za-z0-9._-]{1,80}$ ]]; then
		echo "--canary-id must contain 1-80 safe identifier characters for official_image_gallery" >&2
		exit 2
	fi
	if [ "$rights_confirmed" != true ]; then
		echo "--rights-confirmed is required for official_image_gallery" >&2
		exit 2
	fi
	preparation_mode=visual
elif [ "$rights_confirmed" = true ]; then
	echo "--rights-confirmed is only valid for official_image_gallery" >&2
	exit 2
fi
if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
	echo "--timeout-seconds must be a positive integer" >&2
	exit 2
fi
if ! [[ "$cleanup_import_wait_seconds" =~ ^[1-9][0-9]*$ ]] || [ "$cleanup_import_wait_seconds" -gt 300 ]; then
	echo "RULEPILOT_SMOKE_CLEANUP_TIMEOUT_SECONDS must be between 1 and 300" >&2
	exit 2
fi
if [ -z "$expected_title" ] || [ -z "$uploaded_title" ]; then
	echo "--expected-title and --uploaded-title must not be blank" >&2
	exit 2
fi
if [ -n "$official_source_url" ] && [[ "$official_source_url" != https://* ]]; then
	echo "--official-source-url must use HTTPS" >&2
	exit 2
fi
if [ "$source_mode" = official_image_gallery ] && [ -z "$official_source_url" ]; then
	echo "--official-source-url is required for official_image_gallery" >&2
	exit 2
fi
if [ -z "$answer_language" ] || [ "${#answer_language}" -gt 40 ]; then
	echo "--language must contain 1-40 characters" >&2
	exit 2
fi
if [ "$preparation_mode" != text ] && [ "$preparation_mode" != visual ]; then
	echo "--preparation-mode must be text or visual" >&2
	exit 2
fi
if [ "$navigation_mode" != all ] && [ "$navigation_mode" != api ]; then
	echo "--navigation-mode must be all or api" >&2
	exit 2
fi
if [ "$visual_expectation" != any ] && [ "$visual_expectation" != required ] && [ "$visual_expectation" != forbidden ]; then
	echo "--visual-expectation must be any, required, or forbidden" >&2
	exit 2
fi
if [ -z "$question" ] || [ "${#question}" -gt 500 ]; then
	echo "--question must contain 1-500 characters" >&2
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
cleanup_document=false
official_import_job_id=
official_import_edition_id=
official_import_canary_title=
preparation_run_id=
lesson_run_id=
visual_run_id=
visual_result=null
page_attempts=null
csrf_header=
csrf_token=

probe_navigation() {
	[ -n "$navigation_file" ] || return 0
	local phase=$1
	local paths
	if [ "$navigation_mode" = api ]; then
		paths=(
			"/api/v1/documents"
			"/api/v1/teaching-plans"
			"/api/public/lessons"
		)
	else
		paths=(
			"/"
			"/teach"
			"/lessons"
			"/catalog"
			"/library"
			"/account"
			"/api/v1/documents"
			"/api/v1/teaching-plans"
			"/api/public/lessons"
		)
	fi
	local completed_probe_count=0
	if [ -s "$navigation_file" ]; then
		completed_probe_count=$(awk 'END { print NR + 0 }' "$navigation_file")
	fi
	local path=${paths[$((completed_probe_count % ${#paths[@]}))]}
	local measurement http_code elapsed_seconds
	measurement=$(curl --location --silent --show-error --output /dev/null \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		--write-out '%{http_code}\t%{time_total}' \
		"$base_url$path" || printf '000\t0')
	http_code=${measurement%%$'\t'*}
	elapsed_seconds=${measurement#*$'\t'}
	printf '%s\t%s\t%s\t%s\n' "$phase" "$path" "$http_code" "$elapsed_seconds" >> "$navigation_file"
}

navigation_summary() {
	if [ -z "$navigation_file" ] || [ ! -s "$navigation_file" ]; then
		printf '{"requestCount":0,"failureCount":0,"averageMs":0,"maxMs":0}'
		return
	fi
	awk -F '\t' '
		BEGIN { count = 0; failures = 0; total = 0; max = 0 }
		{
			count += 1
			milliseconds = $4 * 1000
			total += milliseconds
			if (milliseconds > max) max = milliseconds
			if ($3 !~ /^2[0-9][0-9]$/) failures += 1
		}
		END {
			average = count == 0 ? 0 : total / count
			printf "{\"requestCount\":%d,\"failureCount\":%d,\"averageMs\":%.1f,\"maxMs\":%.1f}", count, failures, average, max
		}
	' "$navigation_file"
}

get_json() {
	curl --fail-with-body --silent --show-error \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		"$base_url$1"
}

post_json() {
	local path=$1
	local payload=$2
	curl --fail-with-body --silent --show-error \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		--request POST --header "Content-Type: application/json" \
		--header "$csrf_header: $csrf_token" \
		--data "$payload" \
		"$base_url$path"
}

wait_for_official_import() {
	local job_id=$1
	local deadline=$((SECONDS + timeout_seconds))
	local response stage handoff
	while [ "$SECONDS" -lt "$deadline" ]; do
		probe_navigation "official-import"
		response=$(get_json "/api/v1/documents/official-imports/$job_id")
		if ! jq -e --arg job_id "$job_id" '.id == $job_id' >/dev/null <<<"$response"; then
			echo "Official import returned a different job identity" >&2
			return 1
		fi
		stage=$(jq -er '.stage' <<<"$response")
		handoff=$(jq -er '.teachingHandoffState' <<<"$response")
		if [ "$stage" = FAILED ] || [ "$handoff" = FAILED ]; then
			printf '%s' "$response"
			return 0
		fi
		if [ "$stage" = COMPLETED ] && [ "$handoff" = LAUNCHED ]; then
			printf '%s' "$response"
			return 0
		fi
		sleep 2
	done
	echo "Official image-gallery import timed out after ${timeout_seconds}s" >&2
	return 1
}

wait_for_latest_teaching_run_id() {
	local plan_id=$1
	local deadline=$((SECONDS + timeout_seconds))
	local response http_code body
	while [ "$SECONDS" -lt "$deadline" ]; do
		response=$(curl --silent --show-error --write-out $'\n%{http_code}' \
			--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
			"$base_url/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=$plan_id")
		http_code=${response##*$'\n'}
		body=${response%$'\n'*}
		if [ "$http_code" = 200 ]; then
			jq -er '.run.id' <<<"$body"
			return 0
		fi
		if [ "$http_code" != 404 ]; then
			echo "Latest Teaching run endpoint returned HTTP $http_code" >&2
			return 1
		fi
		sleep 1
	done
	echo "Teaching run did not appear after ${timeout_seconds}s" >&2
	return 1
}

page_attempt_report() {
	local response=$1
	local expected=$2
	jq -c --argjson expected "$expected" '
	        def three_part_attempts($prefix):
	          [.activities[]?
	            | select(.operation | startswith($prefix + "|"))
	            | (.operation | split("|")) as $parts
	            | select(($parts | length) == 3)
	            | {page: ($parts[1] | tonumber), total: ($parts[2] | tonumber), outcome}];
	        def repair_attempts:
	          [.activities[]?
	            | select(.operation | startswith("inspectTeachingVisualRepair|"))
	            | (.operation | split("|")) as $parts
	            | select(($parts | length) == 4)
	            | {page: ($parts[1] | tonumber), total: ($parts[2] | tonumber),
	               repairCode: $parts[3], outcome}];
	        [.activities[]? | select(.operation | startswith("transcribeTeachingVisualPage|"))] as $rawOcr
	        | [.activities[]? | select(.operation | startswith("transcribeTeachingVisualRetry|"))] as $rawOcrRetries
	        | [.activities[]? | select(.operation | startswith("inspectTeachingVisualPage|"))] as $rawInitial
	        | [.activities[]? | select(.operation | startswith("inspectTeachingVisualRetry|"))] as $rawRetries
	        | [.activities[]? | select(.operation | startswith("inspectTeachingVisualRepair|"))] as $rawRepairs
	        | three_part_attempts("transcribeTeachingVisualPage") as $ocr
	        | three_part_attempts("transcribeTeachingVisualRetry") as $ocrRetries
	        | three_part_attempts("inspectTeachingVisualPage") as $initial
	        | three_part_attempts("inspectTeachingVisualRetry") as $retries
	        | repair_attempts as $repairs
	        | (($retries | map(. + {recoveryKind: "TRANSIENT_RETRY", repairCode: null}))
	          + ($repairs | map(. + {recoveryKind: "CONTRACT_REPAIR"}))) as $recoveries
	        | [range(1; $expected + 1)] as $pages
	        | [$pages[] as $page
	            | (($recoveries | map(select(.page == $page)) | last)
	              // ($initial | map(select(.page == $page)) | last))
	            | select(.outcome != "SUCCEEDED")
	            | .page] as $unavailable
	        | [$pages[] as $page
	            | (($initial | map(select(.page == $page)) | length)
	              + ($recoveries | map(select(.page == $page)) | length))] as $semanticAttemptCounts
	        | [$pages[] as $page
	            | ($ocr | map(select(.page == $page)) | last) as $ocrAttempt
	            | ($initial | map(select(.page == $page)) | last) as $first
	            | ($recoveries | map(select(.page == $page)) | last) as $recovery
	            | {
	                page: $page,
	                ocrOutcome: ($ocrAttempt.outcome // null),
	                initialOutcome: ($first.outcome // null),
	                recoveryKind: ($recovery.recoveryKind // null),
	                repairCode: ($recovery.repairCode // null),
	                recoveryOutcome: ($recovery.outcome // null),
	                semanticAttempts: ((if $first == null then 0 else 1 end)
	                  + (if $recovery == null then 0 else 1 end)),
	                finalOutcome: (($recovery // $first).outcome // null)
	              }] as $pageStats
	        | {
	            pages: $pageStats,
	            ocrSucceeded: ($ocr | map(select(.outcome == "SUCCEEDED")) | length),
	            ocrFailed: ($ocr | map(select(.outcome == "FAILED")) | length),
	            ocrRejected: ($ocr | map(select(.outcome == "REJECTED")) | length),
	            ocrRetryAttempted: ($ocrRetries | length),
	            initialSucceeded: ($initial | map(select(.outcome == "SUCCEEDED")) | length),
	            initialFailed: ($initial | map(select(.outcome == "FAILED")) | length),
	            initialRejected: ($initial | map(select(.outcome == "REJECTED")) | length),
	            transientRetryAttempted: ($retries | length),
	            transientRetrySucceeded: ($retries | map(select(.outcome == "SUCCEEDED")) | length),
	            transientRetryFailed: ($retries | map(select(.outcome != "SUCCEEDED")) | length),
	            repairAttempted: ($repairs | length),
	            repairSucceeded: ($repairs | map(select(.outcome == "SUCCEEDED")) | length),
	            repairFailed: ($repairs | map(select(.outcome != "SUCCEEDED")) | length),
	            finalUnavailablePages: $unavailable,
	            maximumSemanticAttemptsForAnyPage: ($semanticAttemptCounts | max // 0),
	            valid: (
	              ($rawOcr | length) == ($ocr | length)
	              and ($rawOcrRetries | length) == ($ocrRetries | length)
	              and ($ocrRetries | length) == 0
	              and ($rawInitial | length) == ($initial | length)
	              and ($rawRetries | length) == ($retries | length)
	              and ($rawRepairs | length) == ($repairs | length)
	              and ($ocr | length) == $expected
	              and ($ocr | map(.page) | unique) == $pages
	              and all($ocr[]; .total == $expected and .outcome == "SUCCEEDED")
	              and ($initial | length) == $expected
	              and ($initial | map(.page) | unique) == $pages
	              and all($initial[]; .total == $expected)
	              and all($retries[]; .total == $expected)
	              and all($repairs[];
	                .total == $expected
	                and (.repairCode == "MALFORMED_JSON"
	                  or .repairCode == "SCHEMA_MISMATCH"
	                  or .repairCode == "DUPLICATE_RULE_GROUP"
	                  or .repairCode == "PAGE_BINDING_MISMATCH"))
	              and all($pages[]; . as $page
	                | ($recoveries | map(select(.page == $page)) | length) <= 1)
	              and all($recoveries[]; . as $recovery
	                | $recovery.page >= 1 and $recovery.page <= $expected
	                and any($initial[]; .page == $recovery.page and .outcome != "SUCCEEDED"))
	              and ($semanticAttemptCounts | max // 0) <= 2
	              and ($unavailable | length) == 0
	            )
	          }
	    ' <<<"$response"
}

log_stage() {
	local event=${1%% *}
	printf 'SMOKE_STAGE %s\n' "$1" >&2
	if [[ "$event" =~ ^[a-z0-9-]+$ ]] && [[ "$event" != cleanup-* ]]; then
		last_completed_stage=$event
	fi
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
	if [ "$preparation_mode" = visual ]; then
		if ! jq -e '.activities[]? | select(.operation == "selectProgressiveTeachingStart" and .outcome == "SUCCEEDED")' \
			>/dev/null <<<"$response"; then
			echo "SMOKE_WARNING Visual-only rulebook preparation did not report progressive cited-page selection" >&2
		fi
		jq -r '
            [.activities[]? | select(.operation == "selectProgressiveTeachingStart")] as $starts
            | [.activities[]? | select((.operation | startswith("inspectTeachingVisualBatch"))
                or (.operation | startswith("inspectTeachingVisualRetry")))] as $legacy
            | "SMOKE_PERFORMANCE phase=preparation progressiveStartCalls=\($starts | length) progressiveStartLatencyMs=\([$starts[].latencyMs // 0] | add // 0) legacyFullFactCalls=\($legacy | length)"
        ' <<<"$response" >&2
		return
	fi
	if jq -e '.activities[]? | select((.operation == "selectProgressiveTeachingStart")
            or (.operation | startswith("inspectTeachingVisualBatch"))
            or (.operation | startswith("inspectRulebookVisualBatch")))' \
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

report_preparation_start_to_first_section() {
	local preparation=$1
	local lesson=$2
	jq -nr \
		--argjson preparation "$preparation" \
		--argjson lesson "$lesson" '
        def epoch: sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;
        ($preparation.run.createdAt | epoch) as $preparationStarted
        | ([$lesson.activities[]?
            | select(.operation | startswith("publishTeachingSection|"))
            | select(.outcome == "SUCCEEDED")
            | .occurredAt | epoch] | min) as $firstPublished
        | [$lesson.activities[]?
            | select(.operation | startswith("prefetchProgressiveVisualPages"))] as $prefetch
        | "SMOKE_PERFORMANCE phase=preparation-start-to-first-cited-section seconds=\($firstPublished - $preparationStarted) backgroundPrefetchCalls=\($prefetch | length) backgroundPrefetchLatencyMs=\([$prefetch[].latencyMs // 0] | add // 0)"
    ' >&2
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

wait_for_cancelled_run() {
	local run_id=$1
	[ -n "$run_id" ] || return 0
	local attempt response state
	for attempt in {1..15}; do
		response=$(get_json "/api/v1/assistant-runs/$run_id" 2>/dev/null) || return 0
		state=$(jq -r '.run.state // ""' <<<"$response")
		case "$state" in
			COMPLETED|FAILED|DEGRADED|INSUFFICIENT_EVIDENCE|CANCELLED) return 0 ;;
		esac
		sleep 2
	done
}

resolve_pending_official_import_for_cleanup() {
	[ "$source_mode" = official_image_gallery ] || return 0
	[ -n "$official_import_job_id" ] || return 0
	[ "$cleanup_document" = false ] || return 0
	if [ -z "$official_import_edition_id" ] || [ -z "$official_import_canary_title" ]; then
		log_stage "cleanup-import-identity-missing job=$official_import_job_id"
		return 1
	fi
	local deadline=$((SECONDS + cleanup_import_wait_seconds))
	local response stage duplicate version_id documents matched_document_id discovered_run_id
	while [ "$SECONDS" -lt "$deadline" ]; do
		response=$(get_json "/api/v1/documents/official-imports/$official_import_job_id" 2>/dev/null) || {
			sleep 2
			continue
		}
		if ! jq -e --arg job_id "$official_import_job_id" '.id == $job_id' >/dev/null <<<"$response"; then
			log_stage "cleanup-import-identity-mismatch job=$official_import_job_id"
			return 1
		fi
		stage=$(jq -r '.stage // ""' <<<"$response")
		duplicate=$(jq -r '.duplicate // false' <<<"$response")
		version_id=$(jq -r '.documentVersionId // empty' <<<"$response")
		if [ "$duplicate" = true ]; then
			log_stage "cleanup-import-skipped-duplicate job=$official_import_job_id"
			return 0
		fi
		if [ -n "$version_id" ]; then
			documents=$(get_json "/api/v1/documents" 2>/dev/null) || {
				sleep 2
				continue
			}
			matched_document_id=$(jq -er \
				--arg version_id "$version_id" \
				--arg title "$official_import_canary_title" \
				--arg edition_id "$official_import_edition_id" '
			        first(.[]? | select(.latestVersion.id == $version_id
			          and .document.title == $title
			          and .document.gameEditionId == $edition_id)) | .document.id
			    ' <<<"$documents" 2>/dev/null) || {
				sleep 2
				continue
			}
			document_id=$matched_document_id
			cleanup_document=true
			discovered_run_id=$(jq -r '.teachingPreparationRunId // empty' <<<"$response")
			if [ -n "$discovered_run_id" ]; then preparation_run_id=$discovered_run_id; fi
			log_stage "cleanup-import-resolved job=$official_import_job_id version=$version_id"
			return 0
		fi
		if [ "$stage" = FAILED ]; then
			log_stage "cleanup-import-failed-without-document job=$official_import_job_id"
			return 0
		fi
		sleep 2
	done
	log_stage "cleanup-import-unresolved job=$official_import_job_id"
	return 1
}

cleanup() {
	local exit_status=$?
	local forward_stage=$last_completed_stage
	local forward_failure_code=$pending_failure_code
	local cleanup_failed=0
	set +e
	if [ "$cleanup_required" = true ]; then
		cleanup_outcome=SUCCEEDED
	fi
	if [ "$cleanup_required" = true ] && { [ -z "$csrf_header" ] || [ -z "$csrf_token" ]; }; then
		cleanup_failed=1
	elif [ -n "$csrf_header" ] && [ -n "$csrf_token" ]; then
		if ! resolve_pending_official_import_for_cleanup; then cleanup_failed=1; fi
		cancel_run "$visual_run_id"
		cancel_run "$lesson_run_id"
		cancel_run "$preparation_run_id"
		wait_for_cancelled_run "$visual_run_id"
		wait_for_cancelled_run "$lesson_run_id"
		wait_for_cancelled_run "$preparation_run_id"
		if [ "$cleanup_document" = true ] && [ -n "$document_id" ]; then
			if curl --fail-with-body --silent --show-error --output /dev/null \
				--connect-timeout 5 --max-time 60 \
				--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
				--request DELETE --header "$csrf_header: $csrf_token" \
					"$base_url/api/v1/documents/$document_id"; then
					log_stage "cleanup-completed"
				else
					log_stage "cleanup-failed"
					cleanup_failed=1
				fi
			fi
		fi
	last_completed_stage=$forward_stage
	pending_failure_code=$forward_failure_code
	if [ "$cleanup_failed" -ne 0 ]; then
		cleanup_outcome=FAILED
	fi
	if [ "$cleanup_failed" -ne 0 ] && [ "$exit_status" -eq 0 ]; then
		exit_status=1
		pending_failure_code=CLEANUP_FAILED
	fi
	write_public_status "$exit_status"
	rm -rf "$work_dir"
	trap - EXIT
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
	local lesson_plan_id=${3:-}
	local deadline=$((SECONDS + timeout_seconds))
	local response state lesson_response lesson_http lesson_body lesson_status lesson_rank
	local lesson_seen=0
	local previous_lesson_rank=0
	while [ "$SECONDS" -lt "$deadline" ]; do
		probe_navigation "$label"
		response=$(get_json "/api/v1/assistant-runs/$run_id")
		if ! jq -e --arg run_id "$run_id" '.run.id == $run_id' >/dev/null <<<"$response"; then
			echo "$label returned a different run identity" >&2
			return 1
		fi
		state=$(jq -er '.run.state' <<<"$response")
		if [ -n "$lesson_plan_id" ]; then
			lesson_response=$(curl --silent --show-error --write-out $'\n%{http_code}' \
				--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
				"$base_url/api/v1/teaching-plans/$lesson_plan_id/illustrated-lessons/latest")
			lesson_http=${lesson_response##*$'\n'}
			lesson_body=${lesson_response%$'\n'*}
			if [ "$lesson_http" = "200" ]; then
				lesson_status=$(jq -er '.status' <<<"$lesson_body")
				case "$lesson_status" in
					INCOMPLETE) lesson_rank=1 ;;
					DRAFT_READY) lesson_rank=2 ;;
					COMPLETE) lesson_rank=3 ;;
					*) echo "Unknown lesson status: $lesson_status" >&2; return 1 ;;
				esac
				if [ "$lesson_rank" -lt "$previous_lesson_rank" ]; then
					echo "Lesson status regressed from rank $previous_lesson_rank to $lesson_status" >&2
					return 1
				fi
				lesson_seen=1
				previous_lesson_rank=$lesson_rank
			elif [ "$lesson_http" = "404" ]; then
				if [ "$lesson_seen" -eq 1 ]; then
					echo "A visible lesson disappeared during generation" >&2
					return 1
				fi
			else
				echo "Lesson progress endpoint returned HTTP $lesson_http" >&2
				return 1
			fi
		fi
		case "$state" in
			COMPLETED)
				printf '%s' "$response"
				return 0
				;;
			FAILED|DEGRADED|INSUFFICIENT_EVIDENCE|CANCELLED)
				log_run_timing "${label// /-}-failure" "$response"
				echo "$label ended in $state" >&2
				return 1
				;;
		esac
		sleep 2
	done
	echo "$label timed out after ${timeout_seconds}s" >&2
	return 1
}

wait_for_visual_enrichment() {
	local plan_id=$1
	local deadline=$((SECONDS + timeout_seconds))
	local appearance_deadline=$((SECONDS + 30))
	if [ "$appearance_deadline" -gt "$deadline" ]; then appearance_deadline=$deadline; fi
	local response http_code body state
	while [ "$SECONDS" -lt "$deadline" ]; do
		response=$(curl --silent --show-error --write-out $'\n%{http_code}' \
			--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
			"$base_url/api/v1/assistant-runs/latest?mode=VISUAL_ENRICHMENT&subjectId=$plan_id")
		http_code=${response##*$'\n'}
		body=${response%$'\n'*}
		if [ "$http_code" = 404 ]; then
			if [ "$visual_expectation" != required ]; then return 0; fi
			if [ "$SECONDS" -ge "$appearance_deadline" ]; then
				echo "A vision-capable lesson did not launch visual enrichment within 30 seconds" >&2
				return 1
			fi
			sleep 1
			continue
		fi
		if [ "$http_code" != 200 ]; then
			echo "Visual enrichment progress endpoint returned HTTP $http_code" >&2
			return 1
		fi
		visual_run_id=$(jq -er '.run.id' <<<"$body")
		state=$(jq -er '.run.state' <<<"$body")
		case "$state" in
			COMPLETED)
				visual_result=$body
				log_run_timing "visual-enrichment" "$body"
				log_stage "visual-enrichment-completed run=$visual_run_id"
				return 0
				;;
			FAILED|DEGRADED|INSUFFICIENT_EVIDENCE|CANCELLED)
				visual_result=$body
				log_run_timing "visual-enrichment-failure" "$body"
				if [ "$visual_expectation" = required ]; then
					echo "Visual enrichment ended in $state" >&2
					return 1
				fi
				return 0
				;;
		esac
		sleep 2
	done
	if [ "$visual_expectation" = required ]; then
		echo "Visual enrichment timed out after ${timeout_seconds}s" >&2
		return 1
	fi
	return 0
}

verify_launched_run() {
	local run_id=$1
	local label=$2
	local response state active
	response=$(get_json "/api/v1/assistant-runs/$run_id")
	if ! jq -e --arg run_id "$run_id" '.run.id == $run_id' >/dev/null <<<"$response"; then
		echo "$label launch did not resolve to its returned run identity" >&2
		return 1
	fi
	state=$(jq -er '.run.state' <<<"$response")
	case "$state" in
		COMPLETED|FAILED|DEGRADED|INSUFFICIENT_EVIDENCE) ;;
		*)
			active=$(get_json "/api/v1/assistant-runs/active?mode=TEACHING")
			if ! jq -e --arg run_id "$run_id" '.[] | select(.id == $run_id)' >/dev/null <<<"$active"; then
				echo "$label was neither terminal nor visible in active background work" >&2
				return 1
			fi
			;;
	esac
	log_stage "lesson-launch-visible run=$run_id state=$state"
}

run_official_image_gallery() {
	local binding edition_id language_query discovery candidate separator effective_source canary_title
	local import_payload import_launch import_result import_stage import_handoff version_id documents document_response
	local page_summaries preparation_result preparation_state plan plan_id plan_title plan_section_count
	local lesson_result lesson_state lesson lesson_status section_count visual_step_count focused_visual_step_count
	local answer_payload answer_response answer_run_id answer_status answer_citation_count navigation navigation_failures summary

	pending_failure_code=SOURCE_DISCOVERY_FAILED
	refresh_csrf
	binding=$(post_json "/api/v1/bgg/games/$bgg_id/import" '{}')
	if ! jq -e --argjson bgg_id "$bgg_id" '.bggId == $bgg_id and (.edition.id | type == "string" and length > 0)' \
		>/dev/null <<<"$binding"; then
		echo "BGG binding did not preserve the requested game identity" >&2
		return 1
	fi
	edition_id=$(jq -er '.edition.id' <<<"$binding")
	log_stage "bgg-binding-verified bgg=$bgg_id edition=$edition_id"

	language_query=$(jq -rn --arg value "$answer_language" '$value | @uri')
	discovery=$(get_json "/api/v1/documents/rulebook-candidates?editionId=$edition_id&language=$language_query")
	if ! jq -e --arg edition_id "$edition_id" '.configured == true and .identity.editionId == $edition_id' \
		>/dev/null <<<"$discovery"; then
		echo "Rulebook discovery did not preserve the bound edition identity" >&2
		return 1
	fi
	candidate=$(jq -ec --arg source "$official_source_url" --arg language "$answer_language" '
        first(.candidates[]? | select(
          .url == $source
          and .acquisitionMode == "IMAGE_GALLERY"
          and .capability == "CONTIGUOUS_RULE_PAGES"
          and (.capabilityEvidence | index("ORDERED_PAGE_SEQUENCE_CONFIRMED") != null)
          and .nextAction == "IMPORT_PAGE_SEQUENCE"
          and .language == $language
          and .languageVerified == true
          and (.title | type == "string" and length > 0)
          and (.edition | type == "string" and length > 0)
        ))
    ' <<<"$discovery") || {
		echo "Exact ordered image-gallery candidate was not verified" >&2
		return 1
	}
	case "$official_source_url" in
		*\?*) separator='&' ;;
		*) separator='?' ;;
	esac
	effective_source="${official_source_url}${separator}rulepilot_canary=${canary_id}"
	canary_title="$uploaded_title · RulePilot canary $canary_id"
	if [ "${#canary_title}" -gt 160 ]; then
		echo "Unique image-gallery canary title exceeds 160 characters" >&2
		return 1
	fi
	official_import_edition_id=$edition_id
	official_import_canary_title=$canary_title
	log_stage "image-gallery-candidate-verified source=$official_source_url pages=$expected_page_count"
	pending_failure_code=OFFICIAL_IMPORT_FAILED

	import_payload=$(jq -cn \
		--arg editionId "$edition_id" \
		--arg title "$canary_title" \
		--arg sourceUrl "$effective_source" \
		--arg discoveredForEditionId "$edition_id" \
		--arg sourceEdition "$(jq -r '.edition' <<<"$candidate")" \
		--arg sourceLanguage "$answer_language" \
		--argjson rightsConfirmed "$rights_confirmed" \
		'{editionId: $editionId, title: $title, sourceType: "BASE_RULEBOOK",
		  officialSourceUrl: $sourceUrl, rightsConfirmed: $rightsConfirmed, startTeaching: true,
		  learningGoal: null, discoveredForEditionId: $discoveredForEditionId,
		  sourceEdition: $sourceEdition, sourceLanguage: $sourceLanguage,
		  sourceLanguageVerified: true, identityConfirmed: true}')
	refresh_csrf
	import_launch=$(post_json "/api/v1/documents/official-imports" "$import_payload")
	if ! jq -e --arg edition_id "$edition_id" --arg source "$effective_source" '
        .reused == false and .editionId == $edition_id and .officialSourceUrl == $source
        and (.id | type == "string" and length > 0)
    ' >/dev/null <<<"$import_launch"; then
		echo "Official image-gallery import unexpectedly reused or changed identity" >&2
		return 1
	fi
	official_import_job_id=$(jq -er '.id' <<<"$import_launch")
	cleanup_required=true
	log_stage "official-import-accepted job=$official_import_job_id"

	import_result=$(wait_for_official_import "$official_import_job_id")
	import_stage=$(jq -er '.stage' <<<"$import_result")
	import_handoff=$(jq -er '.teachingHandoffState' <<<"$import_result")
	version_id=$(jq -r '.documentVersionId // empty' <<<"$import_result")
	if [ "$import_stage" = COMPLETED ] && [ -n "$version_id" ] \
			&& jq -e '.duplicate == false' >/dev/null <<<"$import_result"; then
		documents=$(get_json "/api/v1/documents")
		document_response=$(jq -ec --arg version_id "$version_id" --arg title "$canary_title" --arg edition_id "$edition_id" '
            first(.[]? | select(.latestVersion.id == $version_id
              and .document.title == $title
              and .document.gameEditionId == $edition_id))
        ' <<<"$documents") || {
			echo "Fresh image-gallery document could not be identified safely for cleanup" >&2
			return 1
		}
		document_id=$(jq -er '.document.id' <<<"$document_response")
		cleanup_document=true
	fi
	if [ "$import_stage" != COMPLETED ]; then
		echo "Official image-gallery import ended in $import_stage: $(jq -r '.errorCode // "UNKNOWN_IMPORT_ERROR"' <<<"$import_result")" >&2
		return 1
	fi
	if ! jq -e '
        .duplicate == false and .downloadedBytes > 0
        and .downloadCompletedAt != null and .importCompletedAt != null
        and .documentVersionId != null
    ' >/dev/null <<<"$import_result"; then
		echo "Official image-gallery import did not prove a fresh completed download" >&2
		return 1
	fi
	if [ "$import_handoff" != LAUNCHED ]; then
		echo "Official image-gallery Teaching handoff ended in $import_handoff: $(jq -r '.teachingErrorCode // "UNKNOWN_TEACHING_HANDOFF_ERROR"' <<<"$import_result")" >&2
		return 1
	fi
	preparation_run_id=$(jq -er '.teachingPreparationRunId' <<<"$import_result")
	log_stage "official-import-completed version=$version_id"
	pending_failure_code=PAGE_SEQUENCE_INVALID

	page_summaries=$(get_json "/api/v1/document-versions/$version_id/pages/summaries")
	if ! jq -e --argjson expected "$expected_page_count" '
        length == $expected and [.[].pageNumber] == [range(1; $expected + 1)]
    ' >/dev/null <<<"$page_summaries"; then
		echo "Processed image-gallery pages were not the expected ordered sequence" >&2
		return 1
	fi
	log_stage "image-gallery-pages-verified count=$expected_page_count"
	pending_failure_code=TEACHING_PREPARATION_FAILED

	preparation_result=$(wait_for_run "$preparation_run_id" "Teaching preparation")
	preparation_state=$(jq -er '.run.state' <<<"$preparation_result")
	log_run_timing "preparation" "$preparation_result"
	page_attempts=$(page_attempt_report "$preparation_result" "$expected_page_count")
	if ! jq -e --argjson expected "$expected_page_count" '
	        .valid == true
	        and .ocrSucceeded == $expected
	        and .maximumSemanticAttemptsForAnyPage <= 2
	        and (.finalUnavailablePages | length) == 0
	    ' >/dev/null <<<"$page_attempts"; then
		echo "Image-gallery OCR or semantic inspection did not complete every page within the bounded recovery contract" >&2
		return 1
	fi
	printf 'SMOKE_PAGE_ATTEMPTS %s\n' "$page_attempts" >&2
	log_stage "teaching-preparation-completed"
	pending_failure_code=TEACHING_PLAN_INVALID

	plan=$(get_json "/api/v1/document-versions/$version_id/teaching-plans/latest")
	plan_id=$(jq -er '.id' <<<"$plan")
	plan_title=$(jq -er '.gameTitle' <<<"$plan")
	plan_section_count=$(jq -er '.sections | length' <<<"$plan")
	if ! jq -e --arg version_id "$version_id" --arg expected "$expected_title" '
        def normalized: ascii_downcase | gsub("[^a-z0-9]+"; " ") | gsub("^ | $"; "");
        .documentVersionId == $version_id
        and (.gameTitle | normalized) == ($expected | normalized)
        and (.sections | length > 0)
    ' >/dev/null <<<"$plan"; then
		echo "Teaching plan was unusable: title=$plan_title sections=$plan_section_count" >&2
		return 1
	fi
	log_stage "teaching-plan-verified"
	pending_failure_code=LESSON_GENERATION_FAILED

	lesson_run_id=$(wait_for_latest_teaching_run_id "$plan_id")
	verify_launched_run "$lesson_run_id" "Illustrated lesson"
	lesson_result=$(wait_for_run "$lesson_run_id" "Illustrated lesson" "$plan_id")
	lesson_state=$(jq -er '.run.state' <<<"$lesson_result")
	log_run_timing "lesson" "$lesson_result"
	verify_lesson_critical_path "$lesson_result" "$plan_section_count"
	report_preparation_start_to_first_section "$preparation_result" "$lesson_result"
	lesson=$(get_json "/api/v1/teaching-plans/$plan_id/illustrated-lessons/latest")
	lesson_status=$(jq -er '.status' <<<"$lesson")
	section_count=$(jq -er '.sections | length' <<<"$lesson")
	visual_step_count=$(jq -er '[.sections[].steps[]? | select(.kind == "VISUAL")] | length' <<<"$lesson")
	focused_visual_step_count=$(jq -er '[.sections[].steps[]? | select(.kind == "VISUAL" and .visualFocus != null)] | length' <<<"$lesson")
	if ! jq -e '.status == "COMPLETE" and (.sections | length > 0)' >/dev/null <<<"$lesson"; then
		echo "Illustrated lesson was unusable: status=$lesson_status sections=$section_count" >&2
		return 1
	fi
	log_stage "lesson-verified"
	pending_failure_code=ANSWER_EVIDENCE_INVALID

	refresh_csrf
	answer_payload=$(jq -cn --arg question "$question" --arg language "$answer_language" \
		'{question: $question, language: $language}')
	answer_response=$(post_json "/api/v1/document-versions/$version_id/answers" "$answer_payload")
	answer_run_id=$(jq -r '.assistantRunId // "not-exposed"' <<<"$answer_response")
	answer_status=$(jq -er '.answer.status' <<<"$answer_response")
	answer_citation_count=$(jq -er '.answer.citations | length' <<<"$answer_response")
	if ! jq -e --arg language "$answer_language" --argjson expected "$expected_page_count" '
	        (.answer.status == "ANSWERED" or .answer.status == "ANSWERED_WITH_WARNING")
	        and .answer.language == $language
	        and (.answer.shortVerdict | length > 0)
        and (.answer.explanation | length > 0)
        and (.answer.citations | length > 0)
        and all(.answer.citations[];
	          .pageFrom >= 1 and .pageTo >= .pageFrom and .pageTo <= $expected
	          and (.excerpt | length > 0))
        and ((.rulingReference.citationIds | length) == (.answer.citations | length))
        and ((.rulingReference.citationIds | unique | length) == (.rulingReference.citationIds | length))
        and all(.rulingReference.citationIds[]; type == "string" and length > 0)
    ' >/dev/null <<<"$answer_response"; then
		echo "Rule answer did not publish a conclusion with page evidence and aligned source references" >&2
		return 1
	fi
	log_stage "answer-verified run=$answer_run_id status=$answer_status citations=$answer_citation_count"
	pending_failure_code=NAVIGATION_FAILED

	navigation=$(navigation_summary)
	navigation_failures=$(jq -er '.failureCount' <<<"$navigation")
	if [ "$navigation_failures" -gt 0 ]; then
		echo "Concurrent navigation observed $navigation_failures non-successful responses" >&2
		return 1
	fi
	log_stage "navigation-verified requests=$(jq -er '.requestCount' <<<"$navigation") averageMs=$(jq -er '.averageMs' <<<"$navigation") maxMs=$(jq -er '.maxMs' <<<"$navigation")"
	summary=$(jq -n \
		--arg title "$plan_title" \
		--arg preparationState "$preparation_state" \
		--arg lessonState "$lesson_state" \
		--arg lessonStatus "$lesson_status" \
		--arg answerStatus "$answer_status" \
		--arg sourceUrl "$official_source_url" \
		--arg effectiveSourceUrl "$effective_source" \
		--argjson pageCount "$expected_page_count" \
		--argjson pageAttempts "$page_attempts" \
		--argjson sectionCount "$section_count" \
		--argjson answerCitationCount "$answer_citation_count" \
		--argjson visualStepCount "$visual_step_count" \
		--argjson focusedVisualStepCount "$focused_visual_step_count" \
		--argjson navigation "$navigation" \
		'{sourceMode: "official_image_gallery", title: $title, sourceUrl: $sourceUrl,
		  effectiveSourceUrl: $effectiveSourceUrl, pageCount: $pageCount, pageAttempts: $pageAttempts,
		  preparationState: $preparationState, lessonState: $lessonState, lessonStatus: $lessonStatus,
		  answerStatus: $answerStatus, sectionCount: $sectionCount,
		  answerCitationCount: $answerCitationCount, visualStepCount: $visualStepCount,
		  focusedVisualStepCount: $focusedVisualStepCount, visualEnrichmentState: "NOT_STARTED",
		  navigation: $navigation, cleanup: "scheduled"}')
	if [ -n "$result_file" ]; then
		mkdir -p "$(dirname "$result_file")"
		jq -n \
			--arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
			--arg username "$username" \
			--arg importJobId "$official_import_job_id" \
			--arg documentId "$document_id" \
			--arg documentVersionId "$version_id" \
			--argjson summary "$summary" \
			--argjson preparationRun "$preparation_result" \
			--argjson plan "$plan" \
			--argjson lessonRun "$lesson_result" \
			--argjson lesson "$lesson" \
			--argjson answer "$answer_response" \
			'{generatedAt: $generatedAt, stage: "lesson", username: $username,
			  importJobId: $importJobId, documentId: $documentId, documentVersionId: $documentVersionId,
			  summary: $summary, preparationRun: $preparationRun, plan: $plan,
			  lessonRun: $lessonRun, lesson: $lesson, answer: $answer}' > "$result_file"
		chmod 600 "$result_file"
	fi
	log_stage "journey-completed"
	printf '%s\n' "$summary"
}

pending_failure_code=AUTHENTICATION_FAILED
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

if [ "$source_mode" = official_image_gallery ]; then
	run_official_image_gallery
	exit 0
fi

pending_failure_code=SOURCE_UPLOAD_FAILED
refresh_csrf
upload_form=(
	--form "title=$uploaded_title"
	--form "sourceType=BASE_RULEBOOK"
	--form "file=@$pdf_file;type=application/pdf"
)
if [ -n "$official_source_url" ]; then
	upload_form+=(--form "officialSourceUrl=$official_source_url")
fi
upload_response=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "$csrf_header: $csrf_token" \
	"${upload_form[@]}" \
	"$base_url/api/v1/documents")

document_id=$(jq -er '.document.id' <<<"$upload_response")
version_id=$(jq -er '.version.id' <<<"$upload_response")
if ! jq -e '.duplicate == false' >/dev/null <<<"$upload_response"; then
	echo "Synthetic smoke upload unexpectedly reused an existing document" >&2
	exit 1
fi
cleanup_document=true
cleanup_required=true
log_stage "upload-completed"
wait_for_document_ready "$version_id"
log_stage "document-ready"
pending_failure_code=TEACHING_PREPARATION_FAILED

refresh_csrf
preparation_launch=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "Content-Type: application/json" \
	--header "$csrf_header: $csrf_token" \
	--data '{}' \
	"$base_url/api/v1/document-versions/$version_id/teaching-plans")
preparation_run_id=$(jq -er '.assistantRunId' <<<"$preparation_launch")
preparation_result=$(wait_for_run "$preparation_run_id" "Teaching preparation")
preparation_state=$(jq -er '.run.state' <<<"$preparation_result")
log_run_timing "preparation" "$preparation_result"
verify_preparation_critical_path "$preparation_result"
log_stage "teaching-preparation-completed"
pending_failure_code=TEACHING_PLAN_INVALID

plan=$(get_json "/api/v1/document-versions/$version_id/teaching-plans/latest")
plan_id=$(jq -er '.id' <<<"$plan")
plan_title=$(jq -er '.gameTitle' <<<"$plan")
plan_section_count=$(jq -er '.sections | length' <<<"$plan")
log_stage "teaching-plan-inspected title=$plan_title sections=$plan_section_count"
if [ -n "$result_file" ]; then
	mkdir -p "$(dirname "$result_file")"
	jq -n \
		--arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
		--arg username "$username" \
		--arg sourceUrl "$official_source_url" \
		--argjson preparationRun "$preparation_result" \
		--argjson plan "$plan" \
		'{generatedAt: $generatedAt, stage: "plan", username: $username,
		  sourceUrl: $sourceUrl, preparationRun: $preparationRun, plan: $plan}' > "$result_file"
	chmod 600 "$result_file"
fi
if ! jq -e --arg expected "$expected_title" '
	def normalized: ascii_downcase | gsub("[^a-z0-9]+"; " ") | gsub("^ | $"; "");
	(.gameTitle | normalized) == ($expected | normalized) and (.sections | length > 0)
' >/dev/null <<<"$plan"; then
	echo "Teaching plan was unusable: title=$plan_title sections=$plan_section_count" >&2
	exit 1
fi
log_stage "teaching-plan-verified"
pending_failure_code=LESSON_GENERATION_FAILED

documents_response=$(get_json "/api/v1/documents")
document_response=$(jq -er --arg document_id "$document_id" \
	'.[] | select(.document.id == $document_id)' <<<"$documents_response")
actual_title=$(jq -er '.document.title' <<<"$document_response")
if ! jq -e --arg expected "$expected_title" '
	def normalized: ascii_downcase | gsub("[^a-z0-9]+"; " ") | gsub("^ | $"; "");
	(.document.title | normalized) == ($expected | normalized)
' >/dev/null <<<"$document_response"; then
	echo "Expected the source-grounded title $expected_title, got: $actual_title (plan: $plan_title)" >&2
	exit 1
fi
log_stage "title-verified"

refresh_csrf
lesson_launch=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "$csrf_header: $csrf_token" \
	"$base_url/api/v1/teaching-plans/$plan_id/illustrated-lessons")
lesson_run_id=$(jq -er '.assistantRunId' <<<"$lesson_launch")
verify_launched_run "$lesson_run_id" "Illustrated lesson"
lesson_result=$(wait_for_run "$lesson_run_id" "Illustrated lesson" "$plan_id")
lesson_state=$(jq -er '.run.state' <<<"$lesson_result")
log_run_timing "lesson" "$lesson_result"
verify_lesson_critical_path "$lesson_result" "$plan_section_count"
report_preparation_start_to_first_section "$preparation_result" "$lesson_result"
log_stage "lesson-generation-completed"

wait_for_visual_enrichment "$plan_id"

lesson=$(get_json "/api/v1/teaching-plans/$plan_id/illustrated-lessons/latest")
lesson_status=$(jq -er '.status' <<<"$lesson")
section_count=$(jq -er '.sections | length' <<<"$lesson")
visual_step_count=$(jq -er '[.sections[].steps[]? | select(.kind == "VISUAL")] | length' <<<"$lesson")
focused_visual_step_count=$(jq -er '[.sections[].steps[]? | select(.kind == "VISUAL" and .visualFocus != null)] | length' <<<"$lesson")
if ! jq -e '(.status == "COMPLETE" or .status == "DRAFT_READY") and (.sections | length > 0)' \
	>/dev/null <<<"$lesson"; then
	echo "Illustrated lesson was unusable: status=$lesson_status sections=$section_count" >&2
	exit 1
fi
if [ "$visual_expectation" = required ] && [ "$focused_visual_step_count" -lt 1 ]; then
	echo "A vision-capable lesson finished without a grounded visual crop" >&2
	exit 1
fi
if [ "$visual_expectation" = forbidden ] && [ "$visual_step_count" -gt 0 ]; then
	echo "A text-only lesson unexpectedly published visual steps" >&2
	exit 1
fi
log_stage "visual-expectation-verified expectation=$visual_expectation visualSteps=$visual_step_count focusedVisualSteps=$focused_visual_step_count"
log_stage "lesson-verified"
pending_failure_code=ANSWER_EVIDENCE_INVALID

refresh_csrf
answer_payload=$(jq -cn --arg question "$question" --arg language "$answer_language" \
	'{question: $question, language: $language}')
answer_response=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "Content-Type: application/json" \
	--header "$csrf_header: $csrf_token" \
	--data "$answer_payload" \
	"$base_url/api/v1/document-versions/$version_id/answers")
if [ -n "$result_file" ]; then
	answer_checkpoint="${result_file}.answer.tmp"
	jq --argjson answer "$answer_response" '.stage = "answer" | .answer = $answer' \
		"$result_file" > "$answer_checkpoint"
	mv "$answer_checkpoint" "$result_file"
	chmod 600 "$result_file"
fi
answer_run_id=$(jq -r '.assistantRunId // "not-exposed"' <<<"$answer_response")
answer_status=$(jq -er '.answer.status' <<<"$answer_response")
answer_citation_count=$(jq -er '.answer.citations | length' <<<"$answer_response")
if ! jq -e '
	(.answer.status == "ANSWERED" or .answer.status == "ANSWERED_WITH_WARNING")
	and (.answer.shortVerdict | length > 0)
	and (.answer.explanation | length > 0)
	and (.answer.citations | length > 0)
	and all(.answer.citations[];
		.pageFrom >= 1
		and .pageTo >= .pageFrom
		and (.excerpt | length > 0))
	and ((.rulingReference.citationIds | length) == (.answer.citations | length))
	and ((.rulingReference.citationIds | unique | length) == (.rulingReference.citationIds | length))
	and all(.rulingReference.citationIds[]; type == "string" and length > 0)
' >/dev/null <<<"$answer_response"; then
	echo "Rule answer did not publish a conclusion with page evidence and aligned source references" >&2
	exit 1
fi
log_stage "answer-verified run=$answer_run_id status=$answer_status citations=$answer_citation_count"
pending_failure_code=NAVIGATION_FAILED

navigation=$(navigation_summary)
navigation_failures=$(jq -er '.failureCount' <<<"$navigation")
if [ "$navigation_failures" -gt 0 ]; then
	echo "Concurrent navigation observed $navigation_failures non-successful responses" >&2
	exit 1
fi
log_stage "navigation-verified requests=$(jq -er '.requestCount' <<<"$navigation") averageMs=$(jq -er '.averageMs' <<<"$navigation") maxMs=$(jq -er '.maxMs' <<<"$navigation")"

summary=$(jq -n \
	--arg title "$actual_title" \
	--arg preparationState "$preparation_state" \
	--arg lessonState "$lesson_state" \
	--arg lessonStatus "$lesson_status" \
	--arg answerStatus "$answer_status" \
	--argjson sectionCount "$section_count" \
	--argjson answerCitationCount "$answer_citation_count" \
	--argjson visualStepCount "$visual_step_count" \
	--argjson focusedVisualStepCount "$focused_visual_step_count" \
	--arg visualEnrichmentState "$(jq -r '.run.state // "NOT_STARTED"' <<<"$visual_result")" \
	--argjson navigation "$navigation" \
	'{title: $title, preparationState: $preparationState, lessonState: $lessonState,
	  lessonStatus: $lessonStatus, answerStatus: $answerStatus,
	  sectionCount: $sectionCount, answerCitationCount: $answerCitationCount,
	  visualStepCount: $visualStepCount, focusedVisualStepCount: $focusedVisualStepCount,
	  visualEnrichmentState: $visualEnrichmentState, navigation: $navigation,
	  cleanup: "scheduled"}')

if [ -n "$result_file" ]; then
	mkdir -p "$(dirname "$result_file")"
	jq -n \
		--arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
		--arg username "$username" \
		--arg sourceUrl "$official_source_url" \
		--argjson summary "$summary" \
		--argjson preparationRun "$preparation_result" \
		--argjson plan "$plan" \
		--argjson lessonRun "$lesson_result" \
		--argjson visualRun "$visual_result" \
		--argjson lesson "$lesson" \
		--argjson answer "$answer_response" \
		'{generatedAt: $generatedAt, stage: "lesson", username: $username, sourceUrl: $sourceUrl,
		  summary: $summary, preparationRun: $preparationRun, plan: $plan,
		  lessonRun: $lessonRun, visualRun: $visualRun, lesson: $lesson, answer: $answer}' > "$result_file"
	chmod 600 "$result_file"
fi

log_stage "journey-completed"
printf '%s\n' "$summary"
