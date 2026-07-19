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
    void givesTeachingRunsTheirLongerExecutionWindow() {
        AssistantRunRepository repository = mock(AssistantRunRepository.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        AssistantRunService service = new AssistantRunService(
                repository, execution, 40, 24, 16, 24_000, Duration.ofMinutes(2), Duration.ofMinutes(5));

        service.start(AssistantRunMode.TEACHING, UUID.randomUUID(), "player");
        service.start(AssistantRunMode.QUESTION_ANSWER, UUID.randomUUID(), "player");

        ArgumentCaptor<BudgetLimits> limits = ArgumentCaptor.forClass(BudgetLimits.class);
        verify(execution, times(2)).initialize(any(), limits.capture(), any());
        assertThat(limits.getAllValues())
                .extracting(BudgetLimits::timeout)
                .containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(2));
    }
}
