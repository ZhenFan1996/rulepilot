package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecommendationRunBudgetTest {

    @Test
    void chargesTheCompleteModelPromptAndObservedCompletionAgainstOneEnvelope() {
        var budget = new RecommendationRunBudget(10);

        assertThat(budget.beginModelStep(4)).isNull();
        assertThat(budget.completeModel(6, 3, 1)).isNull();

        assertThat(budget.usedTokens()).isEqualTo(9);
        assertThat(budget.remainingTokens()).isOne();
        assertThat(budget.beginModelStep(1))
                .isEqualTo(RecommendationRunBudget.StopReason.TOKEN_BUDGET);
    }

    @Test
    void chargesTypedToolInputAndObservationWithoutInventingARetryCount() {
        var budget = new RecommendationRunBudget(8);

        assertThat(budget.beginModelStep(2)).isNull();
        assertThat(budget.completeModel(0, 0, 1)).isNull();
        assertThat(budget.beginToolCall(1)).isNull();
        assertThat(budget.completeToolCall(3)).isNull();

        assertThat(budget.usedTokens()).isEqualTo(7);
        assertThat(budget.beginToolCall(2))
                .isEqualTo(RecommendationRunBudget.StopReason.TOKEN_BUDGET);
    }
}
