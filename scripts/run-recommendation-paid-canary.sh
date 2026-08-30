#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid recommendation canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_RECOMMENDATION_PAID_CANARY=true
export RULEPILOT_RECOMMENDATION_CANARY_PROVIDER="${RULEPILOT_RECOMMENDATION_CANARY_PROVIDER:-qwen}"
export RULEPILOT_RECOMMENDATION_CANARY_LABEL="${RULEPILOT_RECOMMENDATION_CANARY_LABEL:-edit-$(date -u +%Y%m%dT%H%M%SZ)}"

case "$RULEPILOT_RECOMMENDATION_CANARY_LABEL" in
	'' | *[!A-Za-z0-9._-]*)
		echo "FAIL RULEPILOT_RECOMMENDATION_CANARY_LABEL must contain only A-Z, a-z, 0-9, dot, underscore, or hyphen"
		exit 2
		;;
esac
if [ "${#RULEPILOT_RECOMMENDATION_CANARY_LABEL}" -gt 80 ]; then
	echo "FAIL RULEPILOT_RECOMMENDATION_CANARY_LABEL must contain at most 80 characters"
	exit 2
fi

case "$RULEPILOT_RECOMMENDATION_CANARY_PROVIDER" in
	qwen)
		if [ -z "${QWEN_API_KEY:-}" ]; then
			echo "FAIL QWEN_API_KEY is required for the authorized paid recommendation canary"
			exit 2
		fi
		;;
	deepseek)
		if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
			echo "FAIL DEEPSEEK_API_KEY is required for the authorized paid recommendation canary"
			exit 2
		fi
		;;
	*)
		echo "FAIL unsupported recommendation canary provider: $RULEPILOT_RECOMMENDATION_CANARY_PROVIDER"
		exit 2
		;;
esac

cd "$ROOT_DIR/backend"
./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#repliesToAGreetingNaturallyWithoutUnneededExternalWork+publishesAComplexTitleBoundedSlateWithAdaptiveResearch' test

echo "Paid recommendation canary passed; raw model diagnostics remain under ignored .local/agent-evaluation/."
