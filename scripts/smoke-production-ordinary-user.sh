#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
	cat <<'EOF'
Usage: RULEPILOT_SMOKE_PASSWORD=... smoke-production-ordinary-user.sh \
  --base-url URL --pdf FILE [--username USER] [--timeout-seconds SECONDS] \
  [--expected-title TITLE] [--uploaded-title TITLE] [--official-source-url URL] \
  [--preparation-mode text|visual] [--navigation-mode all|api] \
  [--visual-expectation any|required|forbidden] \
  [--question SOURCE-GROUNDED-QUESTION] \
  [--navigation-file FILE] [--result-file FILE]

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
official_source_url=
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

if [ -z "$base_url" ] || [ -z "$pdf_file" ] || [ ! -f "$pdf_file" ]; then
	usage >&2
	exit 2
fi
if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
	echo "--timeout-seconds must be a positive integer" >&2
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
preparation_run_id=
lesson_run_id=
visual_run_id=
visual_result=null
csrf_header=
csrf_token=
probe_index=0

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
	local path=${paths[$((probe_index % ${#paths[@]}))]}
	local measurement http_code elapsed_seconds
	measurement=$(curl --location --silent --show-error --output /dev/null \
		--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
		--write-out '%{http_code}\t%{time_total}' \
		"$base_url$path" || printf '000\t0')
	http_code=${measurement%%$'\t'*}
	elapsed_seconds=${measurement#*$'\t'}
	printf '%s\t%s\t%s\t%s\n' "$phase" "$path" "$http_code" "$elapsed_seconds" >> "$navigation_file"
	probe_index=$((probe_index + 1))
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

cleanup() {
	local exit_status=$?
	set +e
	if [ -n "$csrf_header" ] && [ -n "$csrf_token" ]; then
		cancel_run "$visual_run_id"
		cancel_run "$lesson_run_id"
		cancel_run "$preparation_run_id"
		wait_for_cancelled_run "$visual_run_id"
		wait_for_cancelled_run "$lesson_run_id"
		wait_for_cancelled_run "$preparation_run_id"
		if [ -n "$document_id" ]; then
			if curl --silent --show-error --output /dev/null \
				--connect-timeout 5 --max-time 60 \
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
log_stage "upload-completed"
wait_for_document_ready "$version_id"
log_stage "document-ready"

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

plan=$(get_json "/api/v1/document-versions/$version_id/teaching-plans/latest")
plan_id=$(jq -er '.id' <<<"$plan")
plan_title=$(jq -er '.gameTitle' <<<"$plan")
plan_section_count=$(jq -er '.sections | length' <<<"$plan")
log_stage "teaching-plan-inspected title=$plan_title sections=$plan_section_count"
if [ -n "$result_file" ]; then
	mkdir -p "$(dirname "$result_file")"
	jq -n \
		--arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
		--arg preparationState "$preparation_state" \
		--argjson sectionCount "$plan_section_count" \
		'{schemaVersion: 1, generatedAt: $generatedAt, stage: "plan",
		  preparationState: $preparationState, planSectionCount: $sectionCount}' > "$result_file"
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

refresh_csrf
answer_payload=$(jq -cn --arg question "$question" '{question: $question, language: "en"}')
answer_response=$(curl --fail-with-body --silent --show-error \
	--cookie "$cookie_jar" --cookie-jar "$cookie_jar" \
	--request POST --header "Content-Type: application/json" \
	--header "$csrf_header: $csrf_token" \
	--data "$answer_payload" \
	"$base_url/api/v1/document-versions/$version_id/answers")
if [ -n "$result_file" ]; then
	answer_checkpoint="${result_file}.answer.tmp"
	jq --arg answerStatus "$(jq -er '.answer.status' <<<"$answer_response")" \
		--argjson answerCitationCount "$(jq -er '.answer.citations | length' <<<"$answer_response")" \
		'.stage = "answer"
		| .answerStatus = $answerStatus
		| .answerCitationCount = $answerCitationCount' \
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

navigation=$(navigation_summary)
navigation_failures=$(jq -er '.failureCount' <<<"$navigation")
log_stage "navigation-verified requests=$(jq -er '.requestCount' <<<"$navigation") averageMs=$(jq -er '.averageMs' <<<"$navigation") maxMs=$(jq -er '.maxMs' <<<"$navigation")"
if [ "$navigation_failures" -gt 0 ]; then
	echo "Concurrent navigation observed $navigation_failures non-successful responses" >&2
	exit 1
fi

summary=$(jq -n \
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
	'{titleVerified: true, preparationState: $preparationState, lessonState: $lessonState,
	  lessonStatus: $lessonStatus, answerStatus: $answerStatus,
	  sectionCount: $sectionCount, answerCitationCount: $answerCitationCount,
	  visualStepCount: $visualStepCount, focusedVisualStepCount: $focusedVisualStepCount,
	  visualEnrichmentState: $visualEnrichmentState, navigation: $navigation,
	  cleanup: "scheduled"}')

if [ -n "$result_file" ]; then
	mkdir -p "$(dirname "$result_file")"
	jq -n \
		--arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
		--argjson summary "$summary" \
		'{schemaVersion: 1, generatedAt: $generatedAt, stage: "completed",
		  completed: true, summary: $summary}' > "$result_file"
	chmod 600 "$result_file"
fi

printf '%s\n' "$summary"
