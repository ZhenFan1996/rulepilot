#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid Agent evaluation"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_REAL_AGENT_EVAL=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=NativeToolRealRulebookEvaluationTest test

echo "Real-rulebook native tool loop evaluation passed."
