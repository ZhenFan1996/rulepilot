package com.rulepilot.teaching.adapter.out.background;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRuns;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InterruptedAssistantRunRecoveryTest {

    @Test
    void makesEveryOrphanedAgentRunRetryableWithoutReplayingIncompleteWork() {
        AssistantRuns runs = Mockito.mock(AssistantRuns.class);
        for (AssistantRunMode mode : AssistantRunMode.values()) when(runs.failInterrupted(mode)).thenReturn(1);

        new InterruptedAssistantRunRecovery(runs).recover();

        for (AssistantRunMode mode : AssistantRunMode.values()) verify(runs).failInterrupted(mode);
    }
}
