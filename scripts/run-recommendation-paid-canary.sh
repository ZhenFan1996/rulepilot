#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -f "$ROOT_DIR/.env" ]; then
	echo "FAIL .env is required for the authorized paid recommendation canary"
	exit 2
fi

set -a
. "$ROOT_DIR/.env"
set +a

export RULEPILOT_RECOMMENDATION_PAID_CANARY=true
export RULEPILOT_RECOMMENDATION_CANARY_PROVIDER="${RULEPILOT_RECOMMENDATION_CANARY_PROVIDER:-qwen}"
export RULEPILOT_RECOMMENDATION_CANARY_LABEL="${RULEPILOT_RECOMMENDATION_CANARY_LABEL:-current}"

cd "$ROOT_DIR/backend"
if [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "comparison-only" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#preservesAStructuredObservedComparisonDecisionWithoutFlatteningTheNaturalAnswer' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "imaginative" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#keepsImaginativePreferencesSoftAndAppliesOnlyExplicitMidConversationCorrections' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "production-two-turn" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#preservesDirectBoundsAcrossTheProductionTwoTurnJourney' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "availability-shortfall" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#publishesAvailableCardsOnceWhenTheHardEligiblePoolIsSmallerThanTheRequestedCount' test
else
	./mvnw -q -Dtest=BoardGameRecommendationAgentPaidCanaryTest test
fi

echo "Paid recommendation canary passed; sanitized diagnostics are under .local/agent-evaluation/."
