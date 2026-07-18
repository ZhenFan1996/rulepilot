#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/infra/compose.yml"
RUN_ID=$(date +%Y%m%d-%H%M%S)
RESULT_DIR="$ROOT_DIR/.local/performance/p11-02-$RUN_ID"
PDF_FILE="$RESULT_DIR/performance-rulebook.pdf"
BACKEND_LOG="$RESULT_DIR/backend.log"
RUN_IDS_FILE="$RESULT_DIR/assistant-run-ids.txt"

BASE_URL=${PERF_BASE_URL:-http://127.0.0.1:8080}
REQUESTS=${PERF_REQUESTS:-6}
CONCURRENCY=${PERF_CONCURRENCY:-3}
MAX_ANSWER_P95_MS=${PERF_MAX_ANSWER_P95_MS:-3000}
MAX_COLD_MODEL_CALLS=${PERF_MAX_COLD_MODEL_CALLS:-$CONCURRENCY}
MAX_PDF_SECONDS=${PERF_MAX_PDF_SECONDS:-60}
MAX_FTS_MS=${PERF_MAX_FTS_MS:-100}
MAX_VECTOR_MS=${PERF_MAX_VECTOR_MS:-100}
USER_NAME=${PERF_USER_NAME:-player}
USER_PASSWORD=${PERF_USER_PASSWORD:-rulepilot-local}
ADMIN_NAME=${PERF_ADMIN_NAME:-admin}
ADMIN_PASSWORD=${PERF_ADMIN_PASSWORD:-rulepilot-admin-local}

backend_pid=
backend_started=false
infra_started=false
game_id=
document_id=
document_version_id=

compose() {
	if [ -f "$ROOT_DIR/.env" ]; then
		docker compose --env-file "$ROOT_DIR/.env" -f "$COMPOSE_FILE" "$@"
	else
		docker compose -f "$COMPOSE_FILE" "$@"
	fi
}

fail() {
	echo "FAIL $*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

database_value() {
	compose exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"$1\""
}

cleanup() {
	status=$?
	trap - EXIT INT TERM
	if [ -s "$RUN_IDS_FILE" ]; then
		ids=$(paste -sd, "$RUN_IDS_FILE")
		database_value "delete from assistant_run where id::text = any(string_to_array('$ids', ','))" >/dev/null 2>&1 || true
	fi
	if [ -n "$document_version_id" ]; then
		object_key=$(database_value "select object_key from document_version where id = '$document_version_id'" 2>/dev/null || true)
		if [ -n "$object_key" ]; then
			compose exec -T minio sh -c 'mc rm "local/${MINIO_BUCKET:-rulepilot-documents}/$1"' sh "$object_key" >/dev/null 2>&1 || true
		fi
	fi
	if [ -n "$document_id" ]; then
		database_value "delete from rule_document where id = '$document_id'" >/dev/null 2>&1 || true
	fi
	if [ -n "$game_id" ]; then
		database_value "delete from game where id = '$game_id'" >/dev/null 2>&1 || true
	fi
	if [ "$backend_started" = true ] && [ -n "$backend_pid" ]; then
		kill "$backend_pid" >/dev/null 2>&1 || true
		wait "$backend_pid" >/dev/null 2>&1 || true
	fi
	if [ "$infra_started" = true ]; then
		sh "$ROOT_DIR/scripts/verify-compose.sh" down >/dev/null 2>&1 || true
	fi
	if [ "$status" -eq 0 ]; then
		echo "Performance results: $RESULT_DIR"
	else
		echo "Performance run failed; diagnostics: $RESULT_DIR" >&2
	fi
	exit "$status"
}

wait_for_backend() {
	attempt=1
	while [ "$attempt" -le 60 ]; do
		if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
			return
		fi
		attempt=$((attempt + 1))
		sleep 1
	done
	fail "backend did not become ready"
}

login() {
	username=$1
	password=$2
	cookie=$3
	csrf_json=$(curl -fsS -c "$cookie" "$BASE_URL/api/auth/csrf")
	csrf_header=$(printf '%s' "$csrf_json" | jq -r .headerName)
	csrf_token=$(printf '%s' "$csrf_json" | jq -r .token)
	curl -fsS -o /dev/null -b "$cookie" -c "$cookie" \
		-H "$csrf_header: $csrf_token" \
		-H 'Content-Type: application/x-www-form-urlencoded' \
		--data-urlencode "username=$username" --data-urlencode "password=$password" \
		"$BASE_URL/api/auth/login"
}

csrf() {
	cookie=$1
	csrf_json=$(curl -fsS -b "$cookie" -c "$cookie" "$BASE_URL/api/auth/csrf")
	CSRF_HEADER=$(printf '%s' "$csrf_json" | jq -r .headerName)
	CSRF_TOKEN=$(printf '%s' "$csrf_json" | jq -r .token)
}

run_answer_batch() {
	label=$1
	question=$2
	cookie=$3
	csrf_header=$4
	csrf_token=$5
	directory="$RESULT_DIR/$label"
	mkdir -p "$directory"
	i=1
	active=0
	while [ "$i" -le "$REQUESTS" ]; do
		(
			curl -sS -o "$directory/response-$i.json" \
				-w '%{http_code} %{time_total}\n' \
				-b "$cookie" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
				--data "{\"question\":\"$question\",\"currentLessonSection\":\"SCORING\",\"playerCount\":3,\"activeExpansions\":[]}" \
				"$BASE_URL/api/v1/document-versions/$document_version_id/answers" \
				>"$directory/result-$i.txt"
		) &
		active=$((active + 1))
		if [ "$active" -ge "$CONCURRENCY" ]; then
			wait
			active=0
		fi
		i=$((i + 1))
	done
	wait

	for response in "$directory"/response-*.json; do
		jq -er '.assistantRunId? | select(test("^[0-9a-f-]{36}$"))' "$response" >>"$RUN_IDS_FILE" 2>/dev/null || true
	done
	successes=$(awk '$1 == 200 {count++} END {print count + 0}' "$directory"/result-*.txt)
	[ "$successes" -eq "$REQUESTS" ] || fail "$label answer batch returned $successes/$REQUESTS HTTP 200 responses"
	awk '{print $2 * 1000}' "$directory"/result-*.txt | sort -n >"$directory/latency-ms.txt"
	p95=$(awk -v count="$REQUESTS" 'NR == int((count * 95 + 99) / 100) {printf "%.3f", $1}' "$directory/latency-ms.txt")
	ids=$(for response in "$directory"/response-*.json; do jq -r .assistantRunId "$response"; done | paste -sd, -)
	model_calls=$(database_value "select count(*) from assistant_run_activity where assistant_run_id::text = any(string_to_array('$ids', ',')) and activity_type in ('MODEL', 'CRITIC')")
	printf '%s\t%s\t%s\n' "$successes" "$p95" "$model_calls"
}

within_limit() {
	value=$1
	limit=$2
	label=$3
	awk -v value="$value" -v limit="$limit" 'BEGIN {exit !(value <= limit)}' || fail "$label $value exceeded $limit"
}

require_command curl
require_command jq
require_command node
require_command docker
mkdir -p "$RESULT_DIR"
: >"$RUN_IDS_FILE"
trap cleanup EXIT INT TERM

if [ -z "$(compose ps -q 2>/dev/null)" ]; then
	infra_started=true
fi
sh "$ROOT_DIR/scripts/verify-compose.sh" up

if ! curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
	(cd "$ROOT_DIR/backend" && ./mvnw -q -DskipTests package)
	RULEPILOT_USER_USERNAME="$USER_NAME" RULEPILOT_USER_PASSWORD="$USER_PASSWORD" \
	RULEPILOT_ADMIN_USERNAME="$ADMIN_NAME" RULEPILOT_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
	ANSWER_RATE_USER_REQUESTS=1000 ANSWER_RATE_SESSION_CONCURRENCY="$REQUESTS" \
	ANSWER_RATE_PROVIDER_CONCURRENCY="$REQUESTS" TRACING_SAMPLING_PROBABILITY=0.1 \
		java -jar "$ROOT_DIR/backend/target/rulepilot-backend-0.1.0-SNAPSHOT.jar" >"$BACKEND_LOG" 2>&1 &
	backend_pid=$!
	backend_started=true
	wait_for_backend
fi

node "$ROOT_DIR/scripts/generate-performance-pdf.mjs" "$PDF_FILE" 5 >/dev/null
ADMIN_COOKIE="$RESULT_DIR/admin.cookie"
USER_COOKIE="$RESULT_DIR/user.cookie"
login "$ADMIN_NAME" "$ADMIN_PASSWORD" "$ADMIN_COOKIE"
csrf "$ADMIN_COOKIE"

game_json=$(curl -fsS -b "$ADMIN_COOKIE" -H "$CSRF_HEADER: $CSRF_TOKEN" -H 'Content-Type: application/json' \
	--data "{\"name\":\"RulePilot Performance $RUN_ID\"}" "$BASE_URL/api/v1/games")
game_id=$(printf '%s' "$game_json" | jq -er .id)
edition_json=$(curl -fsS -b "$ADMIN_COOKIE" -H "$CSRF_HEADER: $CSRF_TOKEN" -H 'Content-Type: application/json' \
	--data '{"name":"Benchmark Edition","language":"en","publicationYear":2026}' \
	"$BASE_URL/api/v1/games/$game_id/editions")
edition_id=$(printf '%s' "$edition_json" | jq -er .id)

pdf_started=$(date +%s)
upload_json=$(curl -fsS -b "$ADMIN_COOKIE" -H "$CSRF_HEADER: $CSRF_TOKEN" \
	-F "title=Performance Rulebook $RUN_ID" -F 'sourceType=BASE_RULEBOOK' \
	-F "file=@$PDF_FILE;type=application/pdf" "$BASE_URL/api/v1/editions/$edition_id/documents")
document_id=$(printf '%s' "$upload_json" | jq -er .document.id)
document_version_id=$(printf '%s' "$upload_json" | jq -er .version.id)

attempt=1
while [ "$attempt" -le "$MAX_PDF_SECONDS" ]; do
	status=$(database_value "select processing_status from document_version where id = '$document_version_id'")
	[ "$status" = READY ] && break
	case "$status" in FAILED|DEAD_LETTERED) fail "PDF processing ended in $status" ;; esac
	attempt=$((attempt + 1))
	sleep 1
done
[ "${status:-}" = READY ] || fail "PDF processing did not reach READY"
pdf_seconds=$(($(date +%s) - pdf_started))

fts_plan=$(database_value "explain (analyze, buffers, format json) with search_query as (select websearch_to_tsquery('simple', 'coins scoring') as value) select c.id from rule_chunk c cross join search_query q where c.document_version_id = '$document_version_id' and c.content_tsv @@ q.value order by ts_rank_cd(c.content_tsv, q.value, 32) desc limit 5")
fts_ms=$(printf '%s' "$fts_plan" | jq -r '.[0]["Execution Time"]')
vector_plan=$(database_value "explain (analyze, buffers, format json) select id from rule_chunk where document_version_id = '$document_version_id' and embedding is not null order by embedding <=> (select embedding from rule_chunk where document_version_id = '$document_version_id' and embedding is not null limit 1) limit 5")
vector_ms=$(printf '%s' "$vector_plan" | jq -r '.[0]["Execution Time"]')

login "$USER_NAME" "$USER_PASSWORD" "$USER_COOKIE"
csrf "$USER_COOKIE"
question="How are coins scored in benchmark $RUN_ID?"
cold=$(run_answer_batch cold "$question" "$USER_COOKIE" "$CSRF_HEADER" "$CSRF_TOKEN")
cold_p95=$(printf '%s' "$cold" | cut -f2)
cold_model_calls=$(printf '%s' "$cold" | cut -f3)
warm=$(run_answer_batch warm "$question" "$USER_COOKIE" "$CSRF_HEADER" "$CSRF_TOKEN")
warm_p95=$(printf '%s' "$warm" | cut -f2)
warm_model_calls=$(printf '%s' "$warm" | cut -f3)

within_limit "$pdf_seconds" "$MAX_PDF_SECONDS" "PDF processing seconds"
within_limit "$fts_ms" "$MAX_FTS_MS" "full-text execution milliseconds"
within_limit "$vector_ms" "$MAX_VECTOR_MS" "vector execution milliseconds"
within_limit "$cold_p95" "$MAX_ANSWER_P95_MS" "cold answer p95 milliseconds"
within_limit "$warm_p95" "$MAX_ANSWER_P95_MS" "warm answer p95 milliseconds"
within_limit "$cold_model_calls" "$MAX_COLD_MODEL_CALLS" "cold cache model calls"
[ "$warm_model_calls" -eq 0 ] || fail "warm cache batch made $warm_model_calls model calls"

jq -n \
	--arg runId "$RUN_ID" \
	--argjson requests "$REQUESTS" \
	--argjson concurrency "$CONCURRENCY" \
	--argjson pdfSeconds "$pdf_seconds" \
	--argjson fullTextMs "$fts_ms" \
	--argjson vectorMs "$vector_ms" \
	--argjson coldP95Ms "$cold_p95" \
	--argjson coldModelCalls "$cold_model_calls" \
	--argjson warmP95Ms "$warm_p95" \
	--argjson warmModelCalls "$warm_model_calls" \
	'{runId: $runId, requests: $requests, concurrency: $concurrency, pdfSeconds: $pdfSeconds, retrieval: {fullTextMs: $fullTextMs, vectorMs: $vectorMs}, answers: {coldP95Ms: $coldP95Ms, coldModelCalls: $coldModelCalls, warmP95Ms: $warmP95Ms, warmModelCalls: $warmModelCalls}}' \
	>"$RESULT_DIR/summary.json"

echo "PASS PDF processing: ${pdf_seconds}s"
echo "PASS PostgreSQL retrieval: full-text ${fts_ms}ms, vector ${vector_ms}ms"
echo "PASS cold answers: p95 ${cold_p95}ms, model/Critic calls $cold_model_calls"
echo "PASS warm answers: p95 ${warm_p95}ms, model/Critic calls $warm_model_calls"
