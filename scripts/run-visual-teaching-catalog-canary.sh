#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid visual teaching catalog canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_VISUAL_TEACHING_CATALOG_CANARY=true

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=VisualTeachingCatalogPaidCanaryTest test

echo "Visual teaching catalog paid canary passed."
