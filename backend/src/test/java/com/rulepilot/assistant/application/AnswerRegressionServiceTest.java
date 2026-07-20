package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AnswerRegressionSet;
import com.rulepilot.assistant.application.StructuredRuleAnswerService.AnswerCreation;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerRegressionCase;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerRegressionServiceTest {

    @Test
    void reportsMachineReadableFailuresWithoutReturningModelProse() {
        UUID versionId = UUID.randomUUID();
        StructuredRuleAnswerService answers = mock(StructuredRuleAnswerService.class);
        when(answers.evaluateWithRun(any(), any(), any(), any())).thenReturn(new AnswerCreation(
                UUID.randomUUID(),
                new StructuredRuleAnswer(
                        versionId, AnswerStatus.ANSWERED, "费用一样", "按相同费用支付",
                        List.of(new RuleCitation(
                                UUID.randomUUID(), versionId, "ACTIONS", "Moon", "same cost", 11, 11)),
                        List.of(), AnswerConfidence.HIGH, false, null, null, null)));
        AnswerRegressionSet cases = new AnswerRegressionSet() {
            @Override
            public String name() {
                return "test-v1";
            }

            @Override
            public List<AnswerRegressionCase> cases() {
                return List.of(new AnswerRegressionCase(
                        "moon", "费用？", null, "主要行动", 4, AnswerStatus.ANSWERED,
                        List.of(17), List.of(List.of("相同", "一样")), List.of("减免"), 10_000));
            }
        };

        var report = new AnswerRegressionService(answers, cases).evaluate(versionId, "admin");

        assertThat(report.isPassed()).isFalse();
        assertThat(report.cases().getFirst().failures()).containsExactly("MISSING_PAGE_17");
    }
}
