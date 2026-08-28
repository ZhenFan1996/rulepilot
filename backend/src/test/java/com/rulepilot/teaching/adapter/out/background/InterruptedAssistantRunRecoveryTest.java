package com.rulepilot.teaching.adapter.out.background;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

class InterruptedAssistantRunRecoveryTest {

    @Test
    void makesEveryOrphanedAgentRunRetryableWithoutReplayingIncompleteWork() {
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        for (AssistantRunMode mode : AssistantRunMode.values()) when(runs.failInterrupted(mode)).thenReturn(1);

        new InterruptedAssistantRunRecovery(runs).recover();

        for (AssistantRunMode mode : AssistantRunMode.values()) verify(runs).failInterrupted(mode);
    }

    @Test
    void runsBeforeAnyReadyHandoffCanCreateFreshAgentWork() throws NoSuchMethodException {
        Order order = InterruptedAssistantRunRecovery.class
                .getDeclaredMethod("recover")
                .getAnnotation(Order.class);

        org.assertj.core.api.Assertions.assertThat(order).isNotNull();
        org.assertj.core.api.Assertions.assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
