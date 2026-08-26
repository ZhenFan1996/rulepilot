package com.rulepilot.assistant.application;

import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Shared READY-document access policy for private answers and hidden native-tool scopes. */
@Component
@Profile("!test")
public class DocumentNativeToolAccess {

    static final String PUBLIC_READER = "public-reader";

    private final DocumentVersionScopeLookup documents;
    private final PublicRulebookReferenceLookup publicRulebooks;

    public DocumentNativeToolAccess(
            DocumentVersionScopeLookup documents, PublicRulebookReferenceLookup publicRulebooks) {
        this.documents = documents;
        this.publicRulebooks = publicRulebooks;
    }

    /** Private answer and tool entry points may read only the caller's own READY version. */
    public boolean canReadOwnedReadyVersion(String ownerUsername, UUID documentVersionId) {
        if (ownerUsername == null || ownerUsername.isBlank() || documentVersionId == null) return false;
        return readyVersion(documentVersionId)
                .filter(version -> version.createdBy().equals(ownerUsername))
                .isPresent();
    }

    boolean canRead(String ownerUsername, UUID documentVersionId) {
        if (ownerUsername == null || ownerUsername.isBlank() || documentVersionId == null) return false;
        var readyVersion = readyVersion(documentVersionId);
        return readyVersion.filter(version -> version.createdBy().equals(ownerUsername)).isPresent()
                || (PUBLIC_READER.equals(ownerUsername)
                        && readyVersion.isPresent()
                        && publicRulebooks.findReference(documentVersionId).isPresent());
    }

    private Optional<DocumentVersionScopeLookup.VersionScope> readyVersion(UUID documentVersionId) {
        return documents.findVersion(documentVersionId)
                .filter(version -> "READY".equals(version.processingStatus()));
    }
}
