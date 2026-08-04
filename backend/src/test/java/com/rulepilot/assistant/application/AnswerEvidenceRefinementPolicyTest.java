package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidenceRefinementPolicyTest {

    @Test
    void recognizesGameIndependentVisualReferencesWithoutRoutingOrdinaryQuestions() {
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutVisualReference("这个图标是什么意思？")).isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutVisualReference("What does the arrow in this diagram mean?"))
                .isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutVisualReference("什么时候结算这一效果？")).isFalse();
    }

    @Test
    void routesAVisualReferenceThroughNativeRefinementEvenWhenTextRetrievalHasAnAnchor() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "What does this icon mean?",
                "What does this icon mean?",
                QuestionType.RULE_QUERY,
                List.of("icon"),
                java.util.Set.of());
        AnswerEvidenceRetriever.Result direct = new AnswerEvidenceRetriever.Result(
                List.of(hit("Icon reference", "This icon marks an available action.")),
                AnswerEvidenceRetriever.State.READY);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                        question, new QuestionContext(versionId), direct))
                .isTrue();
    }

    @Test
    void recognizesRuleRelationshipQuestionsAcrossLanguagesWithoutRoutingOrdinaryTimingQuestions() {
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship(
                        "The general rule and this special rule conflict. Which one applies?"))
                .isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship("这个效果是否覆盖通用规则？"))
                .isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship("这个能力与移动规则冲突时怎么办？"))
                .isTrue();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship("什么时候结算这个效果？"))
                .isFalse();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship("冲突牌堆为空时游戏何时结束？"))
                .isFalse();
        assertThat(AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship(
                        "If the Conflict Deck is empty, when does the game end?"))
                .isFalse();
    }

    @Test
    void routesAnExplicitExceptionQuestionEvenWhenInitialEvidenceRepeatsTheTopic() {
        UUID versionId = UUID.randomUUID();
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId,
                "Is there an exception to the movement rule?",
                "Is there an exception to the movement rule?",
                QuestionType.SITUATION_QUERY,
                List.of("movement"),
                java.util.Set.of());
        AnswerEvidenceRetriever.Result direct = new AnswerEvidenceRetriever.Result(
                List.of(hit("Movement", "Move one space during the movement step.")),
                AnswerEvidenceRetriever.State.READY);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                        question, new QuestionContext(versionId), direct))
                .isTrue();
    }

    @Test
    void routesACompoundEndingAndTieBreakerQuestionEvenWhenOneClauseAlreadyMatches() {
        UUID versionId = UUID.randomUUID();
        String value = "At the end of a round, if the Conflict Deck is empty, when does the game end, and what tie-breakers are used in order?";
        UnderstoodQuestion question = new UnderstoodQuestion(
                versionId, value, value, QuestionType.RULE_QUERY, List.of(), java.util.Set.of());
        AnswerEvidenceRetriever.Result partial = new AnswerEvidenceRetriever.Result(
                List.of(hit("Game end", "At the end of a round, an empty Conflict Deck ends the game.")),
                AnswerEvidenceRetriever.State.READY);

        assertThat(AnswerEvidenceRefinementPolicy.requiresRefinement(
                question, new QuestionContext(versionId), partial)).isTrue();
    }

    @Test
    void keepsDirectSingleQuestionEvidenceOnTheZeroAdditionalCallPath() {
        assertThat(AnswerEvidenceRefinementPolicy.lacksDirectLexicalAnchor(
                        "How do I activate the relay?",
                        List.of(hit("Relay activation", "To activate the relay, spend one charge."))))
                .isFalse();
    }

    @Test
    void refinesUnrelatedEvidenceForASingleQuestion() {
        assertThat(AnswerEvidenceRefinementPolicy.lacksDirectLexicalAnchor(
                        "How do I activate the relay?",
                        List.of(hit("Setup", "Place the board and shuffle every deck."))))
                .isTrue();
    }

    @Test
    void usesTheSameInvariantWithDifferentRulebookTerminology() {
        assertThat(AnswerEvidenceRefinementPolicy.lacksDirectLexicalAnchor(
                        "When can a habitat reproduce?",
                        List.of(hit("Habitat growth", "A habitat may reproduce after it receives two nutrients."))))
                .isFalse();
        assertThat(AnswerEvidenceRefinementPolicy.lacksDirectLexicalAnchor(
                        "When can a habitat reproduce?",
                        List.of(hit("Weather", "Move the storm marker clockwise."))))
                .isTrue();
    }

    @Test
    void treatsCrossLanguageEvidenceAsNeedingRefinementRatherThanAsARefusalReadyAnchor() {
        assertThat(AnswerEvidenceRefinementPolicy.lacksDirectLexicalAnchor(
                        "这个行动什么时候可以执行？",
                        List.of(hit("Action timing", "Perform this action only after resolving movement."))))
                .isTrue();
    }

    private HybridEvidenceHit hit(String heading, String excerpt) {
        UUID versionId = UUID.randomUUID();
        return new HybridEvidenceHit(
                new RuleEvidenceHit(
                        UUID.randomUUID(), versionId, "RULE", heading, excerpt, 2, 2, 0.8),
                0.1,
                1,
                null,
                false);
    }
}
