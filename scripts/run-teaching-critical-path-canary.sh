#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ "${RULEPILOT_ALLOW_PAID_CANARY:-false}" != "true" ]; then
	echo "FAIL set RULEPILOT_ALLOW_PAID_CANARY=true to authorize this one-case paid canary"
	exit 2
fi

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid teaching critical-path canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

cd "$ROOT_DIR/backend"
RULEPILOT_REAL_TEACHING_AGENT_EVAL=false \
	RULEPILOT_REAL_TEACHING_VALUE_EVAL=false \
	RULEPILOT_REAL_TEACHING_CRITICAL_PATH_CANARY=true \
	RULEPILOT_TEACHING_VALUE_CASE="${RULEPILOT_TEACHING_VALUE_CASE:-rr-text-001}" \
	./mvnw -q \
	-Dtest='TeachingEvidenceAgentRealRulebookEvaluationTest#publishesOneRealSectionThroughTheSimplifiedCriticalPath' \
	test

echo "Teaching critical-path canary passed."
echo "Artifact: $ROOT_DIR/.local/agent-evaluation/teaching-critical-path-canary.json"
