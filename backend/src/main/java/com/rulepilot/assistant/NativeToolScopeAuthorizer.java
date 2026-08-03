package com.rulepilot.assistant;

import com.rulepilot.assistant.NativeAgentTool.ToolScope;

/** Revalidates hidden tool scope at the execution boundary. */
public interface NativeToolScopeAuthorizer {

    boolean isAuthorized(ToolScope scope);
}
