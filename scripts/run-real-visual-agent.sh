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

if [ -z "${RULEPILOT_VISUAL_PAGE_MANIFEST:-}" ]; then
	echo "FAIL RULEPILOT_VISUAL_PAGE_MANIFEST is required for the paid source illustration evaluation"
	exit 2
fi

export RULEPILOT_REAL_SOURCE_ILLUSTRATION=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=SourceIllustrationPaidCanaryTest test

echo "Real-rulebook source illustration evaluation passed."
