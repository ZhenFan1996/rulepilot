#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized Gstone Teaching canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

cd "$ROOT_DIR/backend"
RULEPILOT_GSTONE_VISUAL_TEACHING_CANARY=true \
	RULEPILOT_GSTONE_REUSE_VISUAL_CATALOG=false \
	RULEPILOT_GSTONE_VISUAL_PARALLELISM=10 \
	./mvnw -q \
	-Dtest='VisualTeachingCatalogPaidCanaryTest#preparesACompleteRealGstoneVisualRulebookWithCompactWholeGamePlanning' \
	test

echo "Gstone visual Teaching preparation canary passed."
echo "Artifact: $ROOT_DIR/.local/agent-evaluation/gstone-endeavor-visual-teaching-ten-way-v1.json"
