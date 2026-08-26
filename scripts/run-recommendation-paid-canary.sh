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
scenario="${RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-critical-path}"

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

case "$scenario" in
	critical-path|ready-continuation|comparison-only|deep-comparison|classic-awards|classic-awards-conversation|creator-alias|creator-alias-identity|public-context|wild-chat|imaginative|production-two-turn|availability-shortfall|direct-target|all) ;;
	*)
		echo "FAIL unsupported recommendation canary scenario: $scenario"
		exit 2
		;;
esac

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
if [ "$scenario" = "critical-path" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#publishesOneClaimScopedRecommendationAfterValidation' test
elif [ "$scenario" = "ready-continuation" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#prefersAReadyGuideWhenThePlayerRequiresRecommendationTeachingAndQuestions' test
elif [ "$scenario" = "comparison-only" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#preservesANaturalComparisonWithoutASeparateDecisionReviewTurn' test
elif [ "$scenario" = "deep-comparison" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#answersADeeperSourceBackedComparisonWithoutVerifiedCardFallback' test
elif [ "$scenario" = "classic-awards" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#understandsAwardWinningClassicsAndAnImaginativeEquivalentWithoutFallback' test
elif [ "$scenario" = "classic-awards-conversation" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#carriesAwardWinningClassicsThroughCorrectionAndComparisonWithoutRediscovery' test
elif [ "$scenario" = "creator-alias" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#resolvesAPlayerCreatorAliasThroughTheGeneralAgentAndLocalBggWhenPossible' test
elif [ "$scenario" = "creator-alias-identity" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#recognizesThePlayerCreatorAliasWithoutPublishingAGuessedIdentity' test
elif [ "$scenario" = "public-context" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#answersCurrentPublicEventContextFromOneSourcedDiscoveryWithoutABggCarrier' test
elif [ "$scenario" = "wild-chat" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#routesWildConversationIntoCardsOnlyWhenThePlayerActuallyAsksForChoices' test
elif [ "$scenario" = "imaginative" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#keepsImaginativePreferencesSoftAndAppliesOnlyExplicitMidConversationCorrections' test
elif [ "$scenario" = "production-two-turn" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#preservesDirectBoundsAcrossTheProductionTwoTurnJourney' test
elif [ "$scenario" = "availability-shortfall" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#publishesAvailableCardsOnceWhenTheHardEligiblePoolIsSmallerThanTheRequestedCount' test
elif [ "$scenario" = "direct-target" ]; then
	./mvnw -q '-Dtest=BoardGameRecommendationAgentPaidCanaryTest#publishesAPlayerNamedBilingualTargetInTheResolvingTurn' test
elif [ "$scenario" = "all" ]; then
	./mvnw -q -Dtest=BoardGameRecommendationAgentPaidCanaryTest test
else
	echo "FAIL unsupported recommendation canary scenario: $scenario"
	exit 2
fi

echo "Paid recommendation canary passed; raw model diagnostics remain under ignored .local/agent-evaluation/."
