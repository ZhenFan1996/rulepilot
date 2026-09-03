package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogEditionProvisioning;
import com.rulepilot.document.DocumentTeachingPreparation;
import com.rulepilot.document.RulebookTitleInferencePolicy;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.document.domain.RuleDocument;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
class DocumentTeachingPreparationService implements DocumentTeachingPreparation {

    private final RuleDocumentRepository documents;
    private final CatalogEditionProvisioning catalog;

    DocumentTeachingPreparationService(RuleDocumentRepository documents, CatalogEditionProvisioning catalog) {
        this.documents = documents;
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public VersionScope prepare(UUID documentVersionId, String ownerUsername, String sourceConfirmedGameName) {
        var version = documents.findVersion(documentVersionId)
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        RuleDocument document = documents.findDocument(version.documentId())
                .filter(found -> found.createdBy().equals(ownerUsername))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        if (!"READY".equals(version.status().name())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        if (RulebookTitleInferencePolicy.shouldReplaceWithSourceConfirmedTitle(
                document.title(), sourceConfirmedGameName)) {
            document = document.withTitle(sourceConfirmedGameName.strip());
            documents.update(document);
        }
        if (document.gameEditionId() == null) {
            String gameName = sourceConfirmedGameName == null || sourceConfirmedGameName.isBlank()
                    ? document.title()
                    : sourceConfirmedGameName;
            UUID editionId = catalog.provisionDefaultEdition(gameName);
            boolean assignmentWouldDuplicate = documents.findDocument(
                            editionId, ownerUsername, document.title(), document.sourceType())
                    .filter(existing -> !existing.id().equals(version.documentId()))
                    .isPresent();
            if (!assignmentWouldDuplicate) {
                document = document.assignTo(editionId);
                documents.update(document);
            }
        }
        return new VersionScope(
                version.id(), document.gameEditionId(), version.status().name(), document.createdBy(), document.title(),
                version.checksum());
    }
}
