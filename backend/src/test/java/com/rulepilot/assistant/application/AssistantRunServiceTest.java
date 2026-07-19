package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetLimits;
import com.rulepilot.assistant.AssistantRunMode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantRunServiceTest {

    @Test
    void givesTeachingRunsTheirDedicatedExecutionBudget() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = new AssistantRunService(
                repository,
                execution,
                72,
                24,
                16,
                24_000,
                Duration.ofMinutes(2),
                72,
                40,
                300_000,
                Duration.ofMinutes(30));

        service.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player");

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution, times(2)).initialize(any(), limits.capture(), any());
        assertThat(limits.getAllValues())
                .extracting(BudgetLimits::maxTokens, BudgetLimits::timeout)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(300_000, Duration.ofMinutes(30)),
                        org.assertj.core.groups.Tuple.tuple(24_000, Duration.ofMinutes(2)));
    }
}
