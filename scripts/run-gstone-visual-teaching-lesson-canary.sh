#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized Gstone lesson canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

cd "$ROOT_DIR/backend"
RULEPILOT_GSTONE_VISUAL_LESSON_CANARY=true \
	./mvnw -q \
	-Dtest='TeachingRichLessonPaidCanaryTest#publishesTheCapturedCompleteGstoneVisualLedgerAsARichLesson' \
	test

echo "Gstone visual Teaching lesson canary passed."
echo "Artifact: $ROOT_DIR/.local/agent-evaluation/gstone-endeavor-visual-teaching-lesson-v1.json"
