#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid rulebook-acquisition evaluation"
	exit 2
fi

if [ ! -f "$ROOT_DIR/.local/agent-evaluation/rulebook-acquisition-case.json" ]; then
	echo "FAIL ignored .local/agent-evaluation/rulebook-acquisition-case.json is required"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_REAL_RULEBOOK_ACQUISITION_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=OfficialRulebookAcquisitionRealEvaluationTest test

echo "Real rulebook discovery and application download evaluation passed."
