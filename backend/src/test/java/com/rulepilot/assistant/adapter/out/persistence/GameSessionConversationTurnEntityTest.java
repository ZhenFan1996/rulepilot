package com.rulepilot.assistant.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import com.rulepilot.assistant.domain.RuleTermDefinition;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import com.rulepilot.assistant.domain.RulePriorityResolution;
import com.rulepilot.assistant.domain.RuleTimingResolution;
import com.rulepilot.assistant.domain.TimingOrderBasis;
import com.rulepilot.assistant.domain.RuleTieResolution;
import com.rulepilot.assistant.domain.RuleScopeResolution;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import com.rulepilot.assistant.domain.RuleConceptComparison;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import com.rulepilot.assistant.domain.SituationCheckStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameSessionConversationTurnEntityTest {

    @Test
    void roundTripsVerifiedCalculationsWithConversationHistory() {
        UUID versionId = UUID.randomUUID();
        UUID citationId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                versionId,
                AnswerStatus.ANSWERED,
                "You score 10 points.",
                "Two complete sets score 10 points.",
                List.of(new RuleCitation(
                        citationId, versionId, "SCORING", "Set scoring", "Each set of 3 scores 5.", 4, 4)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.GROUNDED_APPLICATION,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(new RuleCalculation("floor(8 / 3) * 5", "10")),
                List.of(new RuleSituationCheck(
                        "The player has eight resources.",
                        SituationCheckStatus.CONFIRMED,
                        "I have 8 resources",
                        List.of(citationId))),
                List.of(new RuleWalkthroughStep(
                        "Count complete sets.",
                        "Divide the available resources into complete groups before scoring them.",
                        WalkthroughOrderBasis.RULE_ORDER,
                        List.of(citationId))),
                List.of(new RuleDecisionBranch(
                        "The player has two complete sets.",
                        "The player scores ten points.",
                        DecisionBranchBasis.EXPLICIT_RULE,
                        List.of(citationId))),
                List.of(new RuleExceptionClause(
                        "When fewer than three resources remain.",
                        "The incomplete set scores nothing.",
                        List.of(citationId))),
                List.of(new RuleTermDefinition(
                        "Complete set", "A group of exactly three resources.",
                        "Fewer than three resources is not a complete set.", List.of(citationId))),
                List.of(new RuleWorkedExample(
                        "The player has eight resources.",
                        "Split them into two complete groups of three, leaving two resources.",
                        "The two complete sets score ten points; the remainder scores nothing.",
                        WorkedExampleBasis.EVIDENCE_BOUND_ILLUSTRATION,
                        List.of(citationId))),
                List.of(new RulePriorityResolution(
                        "The general rule requires three resources per complete set.",
                        "A cited effect permits this player to treat two resources as a complete set.",
                        "Use the cited effect for this player while it applies.",
                        RulePriorityBasis.EXPLICIT_OVERRIDE,
                        List.of(citationId))),
                List.of(new RuleTimingResolution(
                        "Two effects happen simultaneously.",
                        "Resolve them in the order chosen by the current player.",
                        "The player taking the current turn.",
                        TimingOrderBasis.CURRENT_PLAYER_CHOOSES,
                        List.of(citationId))),
                List.of(new RuleTieResolution(
                        "Players remain tied after scoring.",
                        List.of("Compare completed locations.", "Then compare remaining gold."),
                        "If still tied, the tied players share the win.",
                        TieResolutionBasis.ORDERED_TIEBREAKERS,
                        List.of(citationId))),
                List.of(new RuleScopeResolution(
                        "Two-player component setup.",
                        "When playing with two players, do not use dominance cards.",
                        "We are playing a two-player game.",
                        ScopeMatchStatus.MATCHES_SCOPE,
                        "Do not use dominance cards.",
                        ScopeBasis.PLAYER_COUNT,
                        List.of(citationId))),
                List.of(new RuleConceptComparison(
                        "Influence", "Spend it to skip cards.", "Goodwill", "Keep it for end-game scoring.",
                        "Both use the same physical token.", "Goodwill may not be spent as Influence.",
                        "Spend Influence while drafting; score Goodwill at game end.",
                        ConceptComparisonBasis.RESOURCE_FUNCTION, List.of(citationId))),
                List.of(new RuleOption(
                        "Recruit one card.", "You must choose exactly one of three sources.",
                        "Take from the Park", "A card is available in the Park.",
                        "Take it and replace it from the Park deck.", RuleOptionBasis.SOURCE_SELECTION,
                        List.of(citationId))));
        GameSessionConversationTurn turn = GameSessionConversationTurn.create(
                UUID.randomUUID(), "I have 8 resources. How many points?", answer, "player", Instant.now());

        GameSessionConversationTurn restored = new GameSessionConversationTurnEntity(turn).toDomain();

        assertThat(restored.answer().calculations()).isEqualTo(answer.calculations());
        assertThat(restored.answer().situationChecks()).isEqualTo(answer.situationChecks());
        assertThat(restored.answer().walkthroughSteps()).isEqualTo(answer.walkthroughSteps());
        assertThat(restored.answer().decisionBranches()).isEqualTo(answer.decisionBranches());
        assertThat(restored.answer().exceptionClauses()).isEqualTo(answer.exceptionClauses());
        assertThat(restored.answer().termDefinitions()).isEqualTo(answer.termDefinitions());
        assertThat(restored.answer().workedExamples()).isEqualTo(answer.workedExamples());
        assertThat(restored.answer().priorityResolutions()).isEqualTo(answer.priorityResolutions());
        assertThat(restored.answer().timingResolutions()).isEqualTo(answer.timingResolutions());
        assertThat(restored.answer().tieResolutions()).isEqualTo(answer.tieResolutions());
        assertThat(restored.answer().scopeResolutions()).isEqualTo(answer.scopeResolutions());
        assertThat(restored.answer().conceptComparisons()).isEqualTo(answer.conceptComparisons());
        assertThat(restored.answer().ruleOptions()).isEqualTo(answer.ruleOptions());
    }
}
