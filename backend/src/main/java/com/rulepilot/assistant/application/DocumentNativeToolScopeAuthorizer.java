package com.rulepilot.assistant.application;

import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolScopeAuthorizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DocumentNativeToolScopeAuthorizer implements NativeToolScopeAuthorizer {

    private final DocumentNativeToolAccess access;

    public DocumentNativeToolScopeAuthorizer(DocumentNativeToolAccess access) {
        this.access = access;
    }

    @Override
    public boolean isAuthorized(ToolScope scope) {
        if (scope == null) return false;
        return access.canRead(scope.ownerUsername(), scope.documentVersionId());
    }
}
