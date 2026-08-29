package com.rulepilot.assistant.application;

import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolScopes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates an application-owned scope; document identity and deadline never come from model arguments. */
@Component
@Profile("!test")
public class DocumentNativeToolScopeFactory implements NativeToolScopes {

    private final DocumentNativeToolAccess access;
    private final AgentExecutionControl execution;

    public DocumentNativeToolScopeFactory(DocumentNativeToolAccess access, AgentExecutionControl execution) {
        this.access = access;
        this.execution = execution;
    }

    @Override
    public Optional<ToolScope> create(String ownerUsername, UUID documentVersionId, UUID runId) {
        if (!access.canRead(ownerUsername, documentVersionId) || runId == null) return Optional.empty();
        Instant now = Instant.now();
        Instant runDeadline;
        try {
            runDeadline = execution.budget(runId).deadlineAt();
        } catch (RuntimeException unavailableBudget) {
            return Optional.empty();
        }
        if (!runDeadline.isAfter(now)) return Optional.empty();
        return Optional.of(new ToolScope(ownerUsername, documentVersionId, runId, runDeadline));
    }
}
