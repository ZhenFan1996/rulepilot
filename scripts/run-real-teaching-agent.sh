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

cd "$ROOT_DIR/backend"
RULEPILOT_REAL_TEACHING_AGENT_EVAL=true \
	RULEPILOT_REAL_TEACHING_VALUE_EVAL=false \
	./mvnw -q \
	-Dtest='TeachingEvidenceAgentRealRulebookEvaluationTest#fillsTeachingCoverageGapsAcrossThreeRulebooksAndRejectsAnUnrelatedNeed' \
	test
RULEPILOT_REAL_TEACHING_AGENT_EVAL=false \
	RULEPILOT_REAL_TEACHING_VALUE_EVAL=true \
	./mvnw -q \
	-Dtest='TeachingEvidenceAgentRealRulebookEvaluationTest#comparesCompleteTeachingSectionsWithAndWithoutTheBoundedToolPortfolio' \
	test

echo "Real-rulebook teaching Agent evidence and publishable-section evaluations passed."
