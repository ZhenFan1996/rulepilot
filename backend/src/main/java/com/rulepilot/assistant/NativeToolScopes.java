package com.rulepilot.assistant;

import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.util.Optional;
import java.util.UUID;

/** Creates application-owned native-tool scopes for an already-started Agent run. */
public interface NativeToolScopes {

    Optional<ToolScope> create(String ownerUsername, UUID documentVersionId, UUID runId);
}
