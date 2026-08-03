package com.rulepilot.assistant.application;

import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Shared ready-document access policy for hidden native-tool scopes. */
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

    boolean canRead(String ownerUsername, UUID documentVersionId) {
        if (ownerUsername == null || ownerUsername.isBlank() || documentVersionId == null) return false;
        return documents.findVersion(documentVersionId)
                .filter(version -> "READY".equals(version.processingStatus()))
                .filter(version -> version.createdBy().equals(ownerUsername)
                        || (PUBLIC_READER.equals(ownerUsername)
                                && publicRulebooks.findReference(documentVersionId).isPresent()))
                .isPresent();
    }
}
