package com.rulepilot.teaching.adapter.out.background;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InterruptedTeachingRunRecoveryTest {

    @Test
    void makesInterruptedTeachingAndVisualRunsRetryableAfterRestart() {
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        when(runs.failInterrupted(AssistantRunMode.TEACHING)).thenReturn(1);
        when(runs.failInterrupted(AssistantRunMode.TEACHING_PREPARATION)).thenReturn(1);
        when(runs.failInterrupted(AssistantRunMode.VISUAL_ENRICHMENT)).thenReturn(1);

        new InterruptedTeachingRunRecovery(runs).recover();

        verify(runs).failInterrupted(AssistantRunMode.TEACHING);
        verify(runs).failInterrupted(AssistantRunMode.TEACHING_PREPARATION);
        verify(runs).failInterrupted(AssistantRunMode.VISUAL_ENRICHMENT);
    }
}
