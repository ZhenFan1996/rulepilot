#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ "${RULEPILOT_ALLOW_PAID_CANARY:-false}" != "true" ]; then
	echo "FAIL set RULEPILOT_ALLOW_PAID_CANARY=true to authorize the real Gstone Teaching canary"
	exit 2
fi

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized Gstone Teaching canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

cd "$ROOT_DIR/backend"
RULEPILOT_GSTONE_VISUAL_TEACHING_CANARY=true \
	./mvnw -q \
	-Dtest='VisualTeachingCatalogPaidCanaryTest#preparesACompleteRealGstoneVisualRulebookWithCompactWholeGamePlanning' \
	test

echo "Gstone visual Teaching preparation canary passed."
echo "Artifact: $ROOT_DIR/.local/agent-evaluation/gstone-endeavor-visual-teaching-preparation-v1.json"
