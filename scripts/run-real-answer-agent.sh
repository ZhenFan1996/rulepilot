#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid answer Agent evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_REAL_ANSWER_AGENT_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=AnswerEvidenceAgentRealRulebookEvaluationTest test

echo "Real-rulebook answer Agent evaluation passed."
