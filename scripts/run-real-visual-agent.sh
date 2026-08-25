#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid visual Agent evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

if [ -z "${RULEPILOT_VISUAL_CANDIDATE_FIXTURE_DIR:-}" ]; then
	echo "FAIL RULEPILOT_VISUAL_CANDIDATE_FIXTURE_DIR is required for the paid visual candidate evaluation"
	exit 2
fi

export RULEPILOT_REAL_VISUAL_CANDIDATE_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=VisualCandidateSelectionPaidCanaryTest test

echo "Real-rulebook visual candidate evaluation passed."
