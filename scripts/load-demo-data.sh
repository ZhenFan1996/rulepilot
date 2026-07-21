#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BASE_URL=${DEMO_BASE_URL:-http://127.0.0.1:8080}
FRONTEND_URL=${DEMO_FRONTEND_URL:-http://127.0.0.1:5173}
DEMO_DIR="$ROOT_DIR/.local/demo"
PDF_FILE="$DEMO_DIR/lantern-relay-rulebook.pdf"
COOKIE_FILE="$DEMO_DIR/admin.cookie"
GAME_NAME="Lantern Relay"
EDITION_NAME="Original CC0 Demo Edition"
DOCUMENT_TITLE="Lantern Relay Rules"

if [ -f "$ROOT_DIR/.env" ]; then
	set -a
	. "$ROOT_DIR/.env"
	set +a
fi

ADMIN_NAME=${DEMO_ADMIN_NAME:-${RULEPILOT_ADMIN_USERNAME:-admin}}
ADMIN_PASSWORD=${DEMO_ADMIN_PASSWORD:-${RULEPILOT_ADMIN_PASSWORD:-rulepilot-admin-local}}

fail() {
	echo "FAIL $*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

checked_status() {
	url=$1
	output=$2
	curl -sS -o "$output" -w '%{http_code}' -b "$COOKIE_FILE" "$url"
}

require_command curl
require_command jq
require_command node
mkdir -p "$DEMO_DIR"

curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1 \
	|| fail "backend is not ready at $BASE_URL; run make compose-up and make dev first"

node "$ROOT_DIR/scripts/generate-demo-pdf.mjs" \
	"$ROOT_DIR/examples/lantern-relay-rules.txt" "$PDF_FILE" >/dev/null

csrf_json=$(curl -fsS -c "$COOKIE_FILE" "$BASE_URL/api/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)
curl -fsS -o /dev/null -b "$COOKIE_FILE" -c "$COOKIE_FILE" \
	-H "$csrf_header: $csrf_token" \
	-H 'Content-Type: application/x-www-form-urlencoded' \
	--data-urlencode "username=$ADMIN_NAME" --data-urlencode "password=$ADMIN_PASSWORD" \
	"$BASE_URL/api/auth/login"

csrf_json=$(curl -fsS -b "$COOKIE_FILE" -c "$COOKIE_FILE" "$BASE_URL/api/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -er .headerName)
csrf_token=$(printf '%s' "$csrf_json" | jq -er .token)

catalog=$(curl -fsS -b "$COOKIE_FILE" "$BASE_URL/api/v1/games")
game_id=$(printf '%s' "$catalog" | jq -r --arg name "$GAME_NAME" '.[] | select(.game.name == $name) | .game.id' | head -n 1)
if [ -z "$game_id" ]; then
	game=$(curl -fsS -b "$COOKIE_FILE" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
		--data "$(jq -nc --arg name "$GAME_NAME" '{name: $name}')" "$BASE_URL/api/v1/games")
	game_id=$(printf '%s' "$game" | jq -er .id)
	catalog='[]'
fi

edition_id=$(printf '%s' "$catalog" | jq -r --arg gameId "$game_id" --arg name "$EDITION_NAME" \
	'.[] | select(.game.id == $gameId) | .editions[] | select(.name == $name) | .id' | head -n 1)
if [ -z "$edition_id" ]; then
	edition=$(curl -fsS -b "$COOKIE_FILE" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
		--data "$(jq -nc --arg name "$EDITION_NAME" '{name: $name, language: "en", publicationYear: 2026}')" \
		"$BASE_URL/api/v1/games/$game_id/editions")
	edition_id=$(printf '%s' "$edition" | jq -er .id)
fi

upload=$(curl -fsS -b "$COOKIE_FILE" -H "$csrf_header: $csrf_token" \
	-F "title=$DOCUMENT_TITLE" -F 'sourceType=BASE_RULEBOOK' \
	-F "file=@$PDF_FILE;type=application/pdf" "$BASE_URL/api/v1/editions/$edition_id/documents")
document_version_id=$(printf '%s' "$upload" | jq -er .version.id)

attempt=1
status=''
while [ "$attempt" -le 90 ]; do
	documents=$(curl -fsS -b "$COOKIE_FILE" "$BASE_URL/api/v1/editions/$edition_id/documents")
	status=$(printf '%s' "$documents" | jq -r --arg id "$document_version_id" \
		'.[] | select(.latestVersion.id == $id) | .latestVersion.status')
	[ "$status" = READY ] && break
	case "$status" in FAILED|DEAD_LETTERED) fail "demo rulebook processing ended in $status" ;; esac
	attempt=$((attempt + 1))
	sleep 1
done
[ "$status" = READY ] || fail "demo rulebook did not reach READY within 90 seconds"

latest_plan="$DEMO_DIR/latest-plan.json"
plan_status=$(checked_status "$BASE_URL/api/v1/document-versions/$document_version_id/teaching-plans/latest" "$latest_plan")
if [ "$plan_status" = 200 ]; then
	plan_id=$(jq -er .id "$latest_plan")
else
	plan=$(curl -fsS -b "$COOKIE_FILE" -H "$csrf_header: $csrf_token" -H 'Content-Type: application/json' \
		--data '{"playerCount":3,"beginnerCount":2,"durationMinutes":35}' \
		"$BASE_URL/api/v1/document-versions/$document_version_id/teaching-plans")
	plan_id=$(printf '%s' "$plan" | jq -er .id)
	printf '%s\n' "$plan" > "$latest_plan"
fi

latest_lesson="$DEMO_DIR/latest-lesson.json"
lesson_status=$(checked_status "$BASE_URL/api/v1/teaching-plans/$plan_id/illustrated-lessons/latest" "$latest_lesson")
if [ "$lesson_status" != 200 ]; then
	curl -fsS -o "$latest_lesson" -b "$COOKIE_FILE" -H "$csrf_header: $csrf_token" -X POST \
		"$BASE_URL/api/v1/teaching-plans/$plan_id/illustrated-lessons"
fi

evaluation_file="$DEMO_DIR/retrieval-evaluation.json"
curl -fsS -o "$evaluation_file" -b "$COOKIE_FILE" -H "$csrf_header: $csrf_token" -X POST \
	"$BASE_URL/api/admin/document-versions/$document_version_id/retrieval-evaluation"
sample_count=$(jq -er .sampleCount "$evaluation_file")
hit_count=$(jq -er .hitCount "$evaluation_file")
recall_at_5=$(jq -er '.recallAt5 * 10000 | round / 100' "$evaluation_file")
mrr=$(jq -er '.meanReciprocalRank * 1000 | round / 1000' "$evaluation_file")
p95_ms=$(jq -er '.p95LatencyMillis * 1000 | round / 1000' "$evaluation_file")

echo "PASS demo game: $GAME_NAME"
echo "PASS demo document: READY ($document_version_id)"
echo "PASS demo teaching plan and cited lesson: $plan_id"
echo "PASS retrieval evaluation: $hit_count/$sample_count hits, Recall@5 ${recall_at_5}%, MRR $mrr, p95 ${p95_ms}ms"
echo "Open after signing in as $ADMIN_NAME: $FRONTEND_URL/lesson/$plan_id"
