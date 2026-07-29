package com.rulepilot.document.application;

import com.rulepilot.document.PublicRulebookReferenceLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class DocumentPublicRulebookReferenceService implements PublicRulebookReferenceLookup {

    private final RuleDocumentRepository documents;

    DocumentPublicRulebookReferenceService(RuleDocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public java.util.Optional<Reference> findReference(java.util.UUID documentVersionId) {
        return documents.findVersion(documentVersionId).flatMap(version -> documents.findDocument(version.documentId())
                .map(document -> new Reference(
                        version.id(), document.gameEditionId(), document.title(), document.officialSourceUrl(), document.officialCoverUrl())));
    }

    @Override
    public Map<UUID, Reference> findReferences(Collection<UUID> documentVersionIds) {
        if (documentVersionIds == null || documentVersionIds.isEmpty()) return Map.of();
        return documents.findReferences(documentVersionIds);
    }
}
