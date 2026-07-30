package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidenceReconsiderationPolicyTest {

    @Test
    void asksForABoundedApplicationInsteadOfRefusingAnEvidenceBackedPlayerCondition() {
        String feedback = AnswerEvidenceReconsiderationPolicy.feedbackFor(request(
                        "When the marker reaches the end, who wins?",
                        "When the marker reaches the end, the game ends."))
                .getFirst();

        assertThat(feedback).contains(
                "EVIDENCE_SUFFICIENCY",
                "answer the rule directly",
                "answer conditionally",
                "bounded grounded application")
                .doesNotContain("DIRECT_REPLENISHMENT_PROCEDURE");
    }

    @Test
    void usesTheSameGenericReminderForAnotherEvidenceBackedCondition() {
        String feedback = AnswerEvidenceReconsiderationPolicy.feedbackFor(request(
                        "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。"))
                .getFirst();

        assertThat(feedback)
                .contains("EVIDENCE_SUFFICIENCY", "bounded grounded application")
                .doesNotContain("replenishment", "DIRECT_REPLENISHMENT_PROCEDURE");
    }

    private RuleAnswerModel.ModelRequest request(String question, String excerpt) {
        return new RuleAnswerModel.ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new RuleAnswerModel.AnswerContext(null, null, null, com.rulepilot.assistant.PlayerLocale.ZH_CN),
                List.of(new RuleAnswerModel.EvidenceInput(
                        UUID.randomUUID(), "RULES", "Rule", excerpt, 2, 2)));
    }
}
