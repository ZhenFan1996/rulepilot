#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

cd "$ROOT_DIR/backend"
RULEPILOT_REAL_TEACHING_AGENT_EVAL=true \
	RULEPILOT_REAL_TEACHING_VALUE_EVAL=false \
	./mvnw -q \
	-Dtest='TeachingEvidenceAgentRealRulebookEvaluationTest#fillsTeachingCoverageGapsAcrossThreeRulebooksAndRejectsAnUnrelatedNeed' \
	test

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid teaching composition evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

RULEPILOT_REAL_TEACHING_AGENT_EVAL=false \
	RULEPILOT_REAL_TEACHING_VALUE_EVAL=true \
	./mvnw -q \
	-Dtest='TeachingEvidenceAgentRealRulebookEvaluationTest#comparesCompleteTeachingSectionsWithAndWithoutTheBoundedToolPortfolio' \
	test

echo "Real-rulebook deterministic evidence and paid publishable-section evaluations passed."
