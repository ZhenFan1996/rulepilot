#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

cd "$ROOT_DIR/backend"
./mvnw -q -Dtest=NativeAgentSecurityEvaluationTest,NativeReadToolsTest,BoundedNativeToolAgentTest test

cd "$ROOT_DIR"
node scripts/evaluate-agent-security.mjs --adversarial-verified
