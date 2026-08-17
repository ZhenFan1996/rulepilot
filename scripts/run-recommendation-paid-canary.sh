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

if [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "creator-alias" ] && [ -z "${OPENAI_API_KEY:-}" ]; then
	echo "FAIL OPENAI_API_KEY is required for the creator-alias public-discovery canary"
	exit 2
fi

cd "$ROOT_DIR/backend"
if [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "comparison-only" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#preservesANaturalComparisonWithoutASeparateDecisionReviewTurn' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "classic-awards" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#understandsAwardWinningClassicsAndAnImaginativeEquivalentWithoutFallback' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "creator-alias" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#resolvesAPlayerCreatorAliasThroughTheRealPublicDiscoveryTool' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "imaginative" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#keepsImaginativePreferencesSoftAndAppliesOnlyExplicitMidConversationCorrections' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "production-two-turn" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#preservesDirectBoundsAcrossTheProductionTwoTurnJourney' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "availability-shortfall" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#publishesAvailableCardsOnceWhenTheHardEligiblePoolIsSmallerThanTheRequestedCount' test
elif [ "${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-}" = "direct-target" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#publishesAPlayerNamedBilingualTargetInTheResolvingTurn' test
else
	./mvnw -q -Dtest=BoardGameRecommendationAgentPaidCanaryTest test
fi

echo "Paid recommendation canary passed; sanitized diagnostics are under .local/agent-evaluation/."
