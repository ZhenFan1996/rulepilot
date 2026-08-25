#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid context Agent evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_REAL_CONTEXT_AGENT_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=AnswerEvidenceAgentRealRulebookEvaluationTest test

echo "Real-rulebook context and recovery Agent evaluation passed."
