#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid teaching Agent evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_REAL_TEACHING_AGENT_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=TeachingEvidenceAgentRealRulebookEvaluationTest test

echo "Real-rulebook teaching Agent evaluation passed."
