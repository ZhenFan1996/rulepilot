package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentNativeToolScopeAuthorizerTest {

    @Test
    void authorizesOnlyTheReadyVersionOwnedByTheHiddenScopeOwner() {
        DocumentVersionScopeLookup documents = mock(DocumentVersionScopeLookup.class);
        UUID versionId = UUID.randomUUID();
        when(documents.findVersion(versionId)).thenReturn(Optional.of(new VersionScope(
                versionId, UUID.randomUUID(), "READY", "player")));
        PublicRulebookReferenceLookup publicRulebooks = mock(PublicRulebookReferenceLookup.class);
        DocumentNativeToolScopeAuthorizer authorizer = new DocumentNativeToolScopeAuthorizer(
                new DocumentNativeToolAccess(documents, publicRulebooks));

        assertThat(authorizer.isAuthorized(scope("player", versionId))).isTrue();
        assertThat(authorizer.isAuthorized(scope("other-player", versionId))).isFalse();
        assertThat(authorizer.isAuthorized(scope("public-reader", versionId))).isFalse();

        UUID processingVersion = UUID.randomUUID();
        when(documents.findVersion(processingVersion)).thenReturn(Optional.of(new VersionScope(
                processingVersion, UUID.randomUUID(), "PROCESSING", "player")));
        assertThat(authorizer.isAuthorized(scope("player", processingVersion))).isFalse();
        assertThat(authorizer.isAuthorized(scope("player", UUID.randomUUID()))).isFalse();

        when(publicRulebooks.findReference(versionId)).thenReturn(Optional.of(
                new PublicRulebookReferenceLookup.Reference(
                        versionId, UUID.randomUUID(), "Public rules", null, null)));
        assertThat(authorizer.isAuthorized(scope("public-reader", versionId))).isTrue();
    }

    private ToolScope scope(String owner, UUID versionId) {
        return new ToolScope(owner, versionId, UUID.randomUUID(), Instant.now().plusSeconds(30));
    }
}
