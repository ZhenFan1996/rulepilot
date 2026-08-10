#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid recommendation Agent evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_REAL_RECOMMENDATION_AGENT_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=BoardGameRecommendationAgentRealConversationEvaluationTest test

echo "Real natural-conversation recommendation Agent evaluation passed."
