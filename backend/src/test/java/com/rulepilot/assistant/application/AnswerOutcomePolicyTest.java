package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
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
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import com.rulepilot.assistant.domain.SituationCheckStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerOutcomePolicyTest {

    private final UUID documentVersionId = UUID.randomUUID();

    @Test
    void projectsReadableCitationsWhileKeepingEvidenceIdentityInsideTheModuleBoundary() {
        UUID assistantRunId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.ANSWERED,
                "先放置标记。",
                "把标记放到起始区域。",
                List.of(new RuleCitation(chunkId, documentVersionId, "SETUP", "设置", "放置标记。", 2, 2)),
                List.of(),
                AnswerConfidence.HIGH,
                false,
                null,
                null,
                null);

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(assistantRunId, answer);

        assertThat(result.assistantRunId()).isEqualTo(assistantRunId);
        assertThat(result.citedEvidenceIds()).containsExactly(chunkId);
        assertThat(result.answer()).satisfies(publicAnswer -> {
            assertThat(publicAnswer.status()).isEqualTo("ANSWERED");
            assertThat(publicAnswer.answerBasis()).isEqualTo("DIRECT_RULE");
            assertThat(publicAnswer.citations()).containsExactly(new RuleAnswering.Citation("设置", 2, 2));
        });
    }

    @Test
    void projectsVerifiedCalculationsWithoutTurningThemIntoCitations() {
        UUID assistantRunId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.ANSWERED,
                "共得10分。",
                "8个资源组成两组。",
                List.of(new RuleCitation(chunkId, documentVersionId, "SCORING", "计分", "每3个得5分。", 2, 2)),
                List.of(),
                AnswerConfidence.HIGH,
                AnswerBasis.GROUNDED_APPLICATION,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(new RuleCalculation("floor(8 / 3) * 5", "10")));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(assistantRunId, answer);

        assertThat(result.answer().calculations())
                .containsExactly(new RuleAnswering.Calculation("floor(8 / 3) * 5", "10"));
        assertThat(result.answer().citations()).containsExactly(new RuleAnswering.Citation("计分", 2, 2));
    }

    @Test
    void projectsSituationChecksWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "还需确认窗口。", "前置条件已完成，但窗口未知。",
                List.of(new RuleCitation(chunkId, documentVersionId, "ACTIONS", "行动", "必须在行动窗口内结算。", 3, 3)),
                List.of(), AnswerConfidence.MEDIUM, AnswerBasis.GROUNDED_APPLICATION,
                false, null, null, null, List.of(), List.of(),
                List.of(
                        new RuleSituationCheck("前置条件完成", SituationCheckStatus.CONFIRMED, "我已完成前置条件", List.of(chunkId)),
                        new RuleSituationCheck("行动窗口开放", SituationCheckStatus.NOT_PROVIDED, "", List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().situationChecks()).containsExactly(
                new RuleAnswering.SituationCheck("前置条件完成", "CONFIRMED", "我已完成前置条件"),
                new RuleAnswering.SituationCheck("行动窗口开放", "NOT_PROVIDED", ""));
    }

    @Test
    void projectsWalkthroughStepsWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Pay, then resolve.", "Follow the cited sequence.",
                List.of(new RuleCitation(chunkId, documentVersionId, "PROCEDURE", "Resolution", "Pay first, then resolve.", 3, 3)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(),
                List.of(new RuleWalkthroughStep(
                        "Pay the cost.", "Complete payment before resolving the effect.",
                        WalkthroughOrderBasis.RULE_ORDER, List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().walkthroughSteps()).containsExactly(new RuleAnswering.WalkthroughStep(
                "Pay the cost.", "Complete payment before resolving the effect.", "RULE_ORDER"));
        assertThat(result.citedEvidenceIds()).containsExactly(chunkId);
    }

    @Test
    void projectsDecisionBranchesWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Compare the tied place.", "Each place has its own reward.",
                List.of(new RuleCitation(chunkId, documentVersionId, "TIES", "Tie rewards", "First-place ties get second reward.", 12, 12)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleDecisionBranch(
                        "Players tie for first place.", "Each receives the second reward.",
                        DecisionBranchBasis.EXPLICIT_RULE, List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().decisionBranches()).containsExactly(new RuleAnswering.DecisionBranch(
                "Players tie for first place.", "Each receives the second reward.", "EXPLICIT_RULE"));
        assertThat(result.answer().decisionBranches().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsExceptionClausesWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Two limits apply.", "Check each stated condition.",
                List.of(new RuleCitation(chunkId, documentVersionId, "LIMIT", "Limits", "Unless supplied, it cannot be used.", 7, 7)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleExceptionClause(
                        "When the required item is unavailable.", "The action cannot be completed.", List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().exceptionClauses()).containsExactly(new RuleAnswering.ExceptionClause(
                "When the required item is unavailable.", "The action cannot be completed."));
        assertThat(result.answer().exceptionClauses().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsTermDefinitionsWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Control is defined by majority.", "Use the cited definition.",
                List.of(new RuleCitation(chunkId, documentVersionId, "TERM", "Control", "Control requires a majority.", 5, 5)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleTermDefinition(
                        "Control", "Having the required majority in the area.",
                        "A tie does not grant control.", List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().termDefinitions()).containsExactly(new RuleAnswering.TermDefinition(
                "Control", "Having the required majority in the area.", "A tie does not grant control."));
        assertThat(result.answer().termDefinitions().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsWorkedExamplesWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Apply the modifier.", "Follow the cited example.",
                List.of(new RuleCitation(chunkId, documentVersionId, "EXAMPLE", "Modifier", "1 plus -4 is -3.", 11, 11)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleWorkedExample(
                        "A card has base value 1 and a -4 modifier.",
                        "Apply the modifier to the base value.",
                        "The final value is -3.",
                        WorkedExampleBasis.RULEBOOK_EXAMPLE,
                        List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().workedExamples()).containsExactly(new RuleAnswering.WorkedExample(
                "A card has base value 1 and a -4 modifier.",
                "Apply the modifier to the base value.",
                "The final value is -3.",
                "RULEBOOK_EXAMPLE"));
        assertThat(result.answer().workedExamples().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsRulePriorityWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "The card effect overrides the rule.",
                "The cited relationship explicitly establishes the priority.",
                List.of(new RuleCitation(chunkId, documentVersionId, "PRIORITY", "Fundamental rules",
                        "Effects of cards override rules.", 24, 24)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(new RulePriorityResolution(
                        "The general rule forbids the action.",
                        "The card effect permits the action.",
                        "Apply the card effect for this conflict.",
                        RulePriorityBasis.EXPLICIT_OVERRIDE,
                        List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().priorityResolutions()).containsExactly(new RuleAnswering.RulePriorityResolution(
                "The general rule forbids the action.",
                "The card effect permits the action.",
                "Apply the card effect for this conflict.",
                "EXPLICIT_OVERRIDE"));
        assertThat(result.answer().priorityResolutions().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsTimingResolutionWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "The current player chooses the order.",
                "The cited simultaneous-timing rule assigns the choice to the player taking the turn.",
                List.of(new RuleCitation(chunkId, documentVersionId, "TIMING", "Simultaneous timing",
                        "The player taking their turn chooses the order.", 22, 22)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(new RuleTimingResolution(
                        "Two things happen at the same time during a player's turn.",
                        "Resolve them in the order selected by that player.",
                        "The player taking the current turn.",
                        TimingOrderBasis.CURRENT_PLAYER_CHOOSES,
                        List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().timingResolutions()).containsExactly(new RuleAnswering.RuleTimingResolution(
                "Two things happen at the same time during a player's turn.",
                "Resolve them in the order selected by that player.",
                "The player taking the current turn.",
                "CURRENT_PLAYER_CHOOSES"));
        assertThat(result.answer().timingResolutions().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsTieResolutionWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Compare both tie-breakers in order.",
                "The cited scoring rule ends with a shared victory.",
                List.of(new RuleCitation(chunkId, documentVersionId, "SCORING", "Ties",
                        "Compare cards, then gold; if still tied, share the win.", 12, 12)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new RuleTieResolution(
                        "Players are tied on score.",
                        List.of("Compare cards.", "If still tied, compare gold."),
                        "If still tied, share the win.",
                        TieResolutionBasis.ORDERED_TIEBREAKERS,
                        List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().tieResolutions()).containsExactly(new RuleAnswering.RuleTieResolution(
                "Players are tied on score.",
                List.of("Compare cards.", "If still tied, compare gold."),
                "If still tied, share the win.",
                "ORDERED_TIEBREAKERS"));
        assertThat(result.answer().tieResolutions().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsRuleScopeWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Do not use dominance cards.",
                "The stated two-player setup matches the cited restriction.",
                List.of(new RuleCitation(chunkId, documentVersionId, "SETUP", "Two-player games",
                        "When playing with two players, do not use dominance cards.", 22, 22)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.GROUNDED_APPLICATION,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleScopeResolution(
                        "Dominance cards in a two-player game.",
                        "When playing with two players, do not use dominance cards.",
                        "We are playing a two-player game.",
                        ScopeMatchStatus.MATCHES_SCOPE,
                        "Do not use dominance cards.",
                        ScopeBasis.PLAYER_COUNT,
                        List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().scopeResolutions()).containsExactly(new RuleAnswering.RuleScopeResolution(
                "Dominance cards in a two-player game.",
                "When playing with two players, do not use dominance cards.",
                "We are playing a two-player game.",
                "MATCHES_SCOPE",
                "Do not use dominance cards.",
                "PLAYER_COUNT"));
        assertThat(result.answer().scopeResolutions().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsConceptComparisonsWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Influence and Goodwill differ.",
                "Their cited functions are not interchangeable.",
                List.of(new RuleCitation(chunkId, documentVersionId, "DRAFTING", "Influence",
                        "Goodwill may not be spent as Influence.", 11, 11)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleConceptComparison(
                        "Influence", "Spend it to skip cards.", "Goodwill", "Keep it for end-game scoring.",
                        "Both use the same physical token.", "Goodwill may not be spent as Influence.",
                        "Spend Influence while drafting; score Goodwill at game end.",
                        ConceptComparisonBasis.RESOURCE_FUNCTION, List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().conceptComparisons()).containsExactly(new RuleAnswering.RuleConceptComparison(
                "Influence", "Spend it to skip cards.", "Goodwill", "Keep it for end-game scoring.",
                "Both use the same physical token.", "Goodwill may not be spent as Influence.",
                "Spend Influence while drafting; score Goodwill at game end.", "RESOURCE_FUNCTION"));
        assertThat(result.answer().conceptComparisons().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void projectsRuleOptionsWithoutExposingInternalCitationIds() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId, AnswerStatus.ANSWERED, "Recruit from one of three sources.",
                "Each source has its own after-effect.",
                List.of(new RuleCitation(chunkId, documentVersionId, "RECRUIT", "Recruit",
                        "You must recruit in one of three ways.", 9, 9)),
                List.of(), AnswerConfidence.HIGH, AnswerBasis.DIRECT_RULE,
                false, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleOption(
                        "Recruit one card.", "You must choose exactly one of three sources.",
                        "Take from the Park", "A card is available in the Park.",
                        "Take it and replace it from the Park deck.", RuleOptionBasis.SOURCE_SELECTION,
                        List.of(chunkId))));

        RuleAnswering.AnswerResult result = AnswerOutcomePolicy.publicReaderAnswer(UUID.randomUUID(), answer);

        assertThat(result.answer().ruleOptions()).containsExactly(new RuleAnswering.RuleOption(
                "Recruit one card.", "You must choose exactly one of three sources.",
                "Take from the Park", "A card is available in the Park.",
                "Take it and replace it from the Park deck.", "SOURCE_SELECTION"));
        assertThat(result.answer().ruleOptions().toString()).doesNotContain(chunkId.toString());
    }

    @Test
    void mapsAConfirmedRulingWithItsVersionedIdentityAndOfficialStatus() {
        UUID rulingId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = AnswerOutcomePolicy.confirmedRuling(new ConfirmedRulingLookup.ConfirmedAnswer(
                rulingId,
                documentVersionId,
                "使用官方裁定。",
                "按最新裁定处理。",
                List.of(new ConfirmedRulingLookup.Citation(
                        chunkId, documentVersionId, "FAQ", "官方裁定", "按最新裁定处理。", 9, 9)),
                List.of("只适用于当前扩展。"),
                "MEDIUM",
                true,
                4));

        assertThat(answer).satisfies(mapped -> {
            assertThat(mapped.status()).isEqualTo(AnswerStatus.ANSWERED);
            assertThat(mapped.official()).isTrue();
            assertThat(mapped.confirmedRulingId()).isEqualTo(rulingId);
            assertThat(mapped.confirmedRulingVersion()).isEqualTo(4);
            assertThat(mapped.citations()).extracting(RuleCitation::chunkId).containsExactly(chunkId);
        });
    }

    @Test
    void requestsMissingContextInStablePlayerReadableOrder() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                documentVersionId,
                "我可以这样做吗？",
                "我可以这样做吗？",
                QuestionType.SITUATION_QUERY,
                List.of(),
                Set.of(MissingQuestionContext.SITUATION_DETAILS));

        StructuredRuleAnswer answer = AnswerOutcomePolicy.clarification(question, PlayerLocale.ZH_CN);

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.explanation()).isEqualTo("问题中有无法安全确定的指代。");
        assertThat(answer.clarification()).contains("要判断的对象", "发生时机", "紧接着之前");
        assertThat(answer.clarification()).doesNotContain("SITUATION_DETAILS");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void localizesAnUnresolvedObjectWithoutExposingInternalPolicyNames() {
        UnderstoodQuestion question = new UnderstoodQuestion(
                documentVersionId,
                "When does this trigger?",
                "when does this trigger?",
                QuestionType.SITUATION_QUERY,
                List.of(),
                Set.of(MissingQuestionContext.REFERENCED_OBJECT));

        StructuredRuleAnswer answer = AnswerOutcomePolicy.clarification(question, PlayerLocale.EN);

        assertThat(answer.shortVerdict()).isEqualTo("I need one more detail before I can verify the rule.");
        assertThat(answer.clarification()).contains("What exactly", "rulebook name", "card, action, effect, or area");
        assertThat(answer.clarification()).doesNotContain("REFERENCED_OBJECT");
    }

    @Test
    void createsAUniformSafeFailureWithoutPublishingRuleEvidence() {
        StructuredRuleAnswer answer = AnswerOutcomePolicy.safeFailure(
                documentVersionId, AnswerStatus.MODEL_TIMEOUT, "回答生成超时，可以稍后重试。 ");

        assertThat(answer).satisfies(safe -> {
            assertThat(safe.shortVerdict()).isEqualTo("回答生成超时，可以稍后重试。 ");
            assertThat(safe.explanation()).isEqualTo("回答生成超时，可以稍后重试。 ");
            assertThat(safe.confidence()).isEqualTo(AnswerConfidence.LOW);
            assertThat(safe.citations()).isEmpty();
            assertThat(safe.answerBasis()).isNull();
        });
    }

    @Test
    void qualifiesAnEvidenceScopedAnswerWithoutDiscardingItsConclusion() {
        UUID chunkId = UUID.randomUUID();
        StructuredRuleAnswer answer = new StructuredRuleAnswer(
                documentVersionId,
                AnswerStatus.ANSWERED,
                "先放置标记。",
                "把标记放到起始区域。",
                List.of(new RuleCitation(chunkId, documentVersionId, "SETUP", "设置", "放置标记。", 2, 2)),
                List.of(),
                AnswerConfidence.HIGH,
                false,
                null,
                null,
                null);

        StructuredRuleAnswer warned = AnswerOutcomePolicy.withWarnings(
                answer, List.of(new AnswerWarning(AnswerWarning.Type.REVIEW_UNAVAILABLE)));

        assertThat(warned.status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(warned.shortVerdict()).isEqualTo(answer.shortVerdict());
        assertThat(warned.citations()).isEqualTo(answer.citations());
        assertThat(warned.warnings()).extracting(AnswerWarning::type)
                .containsExactly(AnswerWarning.Type.REVIEW_UNAVAILABLE);
    }

    @Test
    void exposesBoundedSourcesForInsufficiencyWithoutPublishingAConclusion() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), documentVersionId, "RULES", "相关规则", "规则原文。", 3, 3, 0.8);

        StructuredRuleAnswer answer = AnswerOutcomePolicy.insufficientWithSources(
                documentVersionId,
                "现有证据未能直接回答这个问题。",
                List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.answerBasis()).isNull();
        assertThat(answer.citations()).extracting(RuleCitation::chunkId).containsExactly(source.chunkId());
    }
}
