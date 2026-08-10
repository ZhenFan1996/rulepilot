#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_STARTED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

cd "$ROOT_DIR"
make verify
node scripts/evaluate-agent-baseline.mjs
sh scripts/run-real-recommendation-agent.sh
sh scripts/run-real-rulebook-acquisition.sh
sh scripts/run-real-answer-agent.sh
sh scripts/run-real-teaching-agent.sh
sh scripts/run-real-visual-agent.sh
sh scripts/run-real-context-agent.sh

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=NativeAgentSecurityEvaluationTest,NativeReadToolsTest,BoundedNativeToolAgentTest test

cd "$ROOT_DIR"
node scripts/evaluate-agent-security.mjs --adversarial-verified
make e2e
node scripts/verify-complete-agent-release.mjs --real --not-before "$RELEASE_STARTED_AT"
node scripts/verify-conversational-agent-release.mjs \
	--not-before "$RELEASE_STARTED_AT" \
	--deterministic-verified \
	--player-surface-verified

echo "Complete conversational Agent release evidence regenerated and verified for canary."
