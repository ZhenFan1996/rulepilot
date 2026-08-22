package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerQuestionPlanTest {

    @Test
    void fallbackDoesNotClassifyNaturalLanguageKeywords() {
        for (String question : List.of(
                "有没有赢的策略？",
                "Do you have any strategy tips for winning?")) {
            assertThat(AnswerQuestionPlan.fallback(question(question)).evidenceNeeds())
                    .containsExactly(EvidenceNeed.DIRECT_RULE);
        }
    }

    @Test
    void fallbackDoesNotTreatNamedStrategyComponentsAsPlayerAdvice() {
        for (String question : List.of(
                "策略牌什么时候打出？",
                "What does the Strategy Card do?")) {
            assertThat(AnswerQuestionPlan.fallback(question(question)).evidenceNeeds())
                    .containsExactly(EvidenceNeed.DIRECT_RULE);
        }
    }

    @Test
    void fallbackLeavesCompleteListClassificationToTheStructuredAgentPlan() {
        for (String question : List.of(
                "这款游戏我怎么赢？",
                "How do I win?")) {
            assertThat(AnswerQuestionPlan.fallback(question(question)).evidenceNeeds())
                    .containsExactly(EvidenceNeed.DIRECT_RULE);
        }
    }

    @Test
    void fallbackDoesNotTreatVictoryNamedComponentsAsACompleteWinRequest() {
        for (String question : List.of(
                "胜利标记什么时候移动？",
                "When does the victory marker move?")) {
            assertThat(AnswerQuestionPlan.fallback(question(question)).evidenceNeeds())
                    .containsExactly(EvidenceNeed.DIRECT_RULE);
        }
    }

    private UnderstoodQuestion question(String text) {
        return new UnderstoodQuestion(
                UUID.randomUUID(), text, text, QuestionType.RULE_QUERY, List.of(), Set.of());
    }
}
