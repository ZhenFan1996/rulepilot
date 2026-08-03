package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.BudgetSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentNativeToolScopeFactoryTest {

    @Test
    void createsOnlyAnAuthorizedScopeBoundedByTheRunDeadline() {
        DocumentNativeToolAccess access = mock(DocumentNativeToolAccess.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID versionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(30);
        when(access.canRead("player", versionId)).thenReturn(true);
        when(execution.budget(runId)).thenReturn(new BudgetSnapshot(
                40, 24, 16, 24_000, 0, 0, 0, deadline, null));
        DocumentNativeToolScopeFactory factory = new DocumentNativeToolScopeFactory(access, execution);

        var scope = factory.create("player", versionId, runId);

        assertThat(scope).isPresent();
        assertThat(scope.orElseThrow().ownerUsername()).isEqualTo("player");
        assertThat(scope.orElseThrow().documentVersionId()).isEqualTo(versionId);
        assertThat(scope.orElseThrow().runId()).isEqualTo(runId);
        assertThat(scope.orElseThrow().deadlineAt()).isEqualTo(deadline);
        assertThat(factory.create("other", versionId, runId)).isEmpty();
    }

    @Test
    void rejectsExpiredOrUnavailableRunBudgets() {
        DocumentNativeToolAccess access = mock(DocumentNativeToolAccess.class);
        AgentExecutionControl execution = mock(AgentExecutionControl.class);
        UUID versionId = UUID.randomUUID();
        UUID expiredRun = UUID.randomUUID();
        UUID missingRun = UUID.randomUUID();
        when(access.canRead("player", versionId)).thenReturn(true);
        when(execution.budget(expiredRun)).thenReturn(new BudgetSnapshot(
                40, 24, 16, 24_000, 0, 0, 0, Instant.now().minusSeconds(1), null));
        when(execution.budget(missingRun)).thenThrow(new IllegalArgumentException("missing"));
        DocumentNativeToolScopeFactory factory = new DocumentNativeToolScopeFactory(access, execution);

        assertThat(factory.create("player", versionId, expiredRun)).isEmpty();
        assertThat(factory.create("player", versionId, missingRun)).isEmpty();
    }
}
