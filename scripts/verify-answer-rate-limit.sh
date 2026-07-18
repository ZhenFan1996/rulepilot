#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/infra/compose.yml"
PREFIX="rulepilot:test:answer-rate-limit:$$"
USER_KEY="$PREFIX:user"
SESSION_A="$PREFIX:session:a"
SESSION_B="$PREFIX:session:b"
PROVIDER_KEY="$PREFIX:provider"

compose() {
	if [ -f "$ROOT_DIR/.env" ]; then
		docker compose --env-file "$ROOT_DIR/.env" -f "$COMPOSE_FILE" "$@"
	else
		docker compose -f "$COMPOSE_FILE" "$@"
	fi
}

redis() {
	compose exec -T redis redis-cli --raw "$@"
}

line() {
	printf '%s\n' "$1" | sed -n "${2}p"
}

cleanup() {
	redis DEL "$USER_KEY" "$SESSION_A" "$SESSION_B" "$PROVIDER_KEY" >/dev/null 2>&1 || true
}

trap cleanup EXIT INT TERM

USER_SCRIPT=$(cat "$ROOT_DIR/backend/src/main/resources/redis/answer-user-rate-limit.lua")
ACQUIRE_SCRIPT=$(cat "$ROOT_DIR/backend/src/main/resources/redis/answer-model-concurrency-acquire.lua")
RELEASE_SCRIPT=$(cat "$ROOT_DIR/backend/src/main/resources/redis/answer-model-concurrency-release.lua")

first=$(redis EVAL "$USER_SCRIPT" 1 "$USER_KEY" 2 60000)
second=$(redis EVAL "$USER_SCRIPT" 1 "$USER_KEY" 2 60000)
third=$(redis EVAL "$USER_SCRIPT" 1 "$USER_KEY" 2 60000)
[ "$(line "$first" 1)" = "1" ]
[ "$(line "$second" 1)" = "1" ]
[ "$(line "$third" 1)" = "0" ]

session_first=$(redis EVAL "$ACQUIRE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" 1 2 30000 token-a)
session_second=$(redis EVAL "$ACQUIRE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" 1 2 30000 token-b)
[ "$(line "$session_first" 1)" = "1" ]
[ "$(line "$session_second" 1)" = "0" ]
[ "$(line "$session_second" 2)" = "1" ]
redis EVAL "$RELEASE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" token-a >/dev/null

provider_first=$(redis EVAL "$ACQUIRE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" 2 1 30000 token-c)
provider_second=$(redis EVAL "$ACQUIRE_SCRIPT" 2 "$SESSION_B" "$PROVIDER_KEY" 2 1 30000 token-d)
[ "$(line "$provider_first" 1)" = "1" ]
[ "$(line "$provider_second" 1)" = "0" ]
[ "$(line "$provider_second" 2)" = "2" ]
redis EVAL "$RELEASE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" token-c >/dev/null

redis EVAL "$ACQUIRE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" 1 1 50 expired-token >/dev/null
sleep 0.1
redis EVAL "$ACQUIRE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" 1 1 30000 current-token >/dev/null
redis EVAL "$RELEASE_SCRIPT" 2 "$SESSION_A" "$PROVIDER_KEY" expired-token >/dev/null
[ "$(redis ZCARD "$SESSION_A")" = "1" ]

echo "Answer rate-limit Lua integration tests passed."
