package com.rulepilot.document.application;

import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.RuleDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleDocumentRepository {

    Optional<RuleDocument> findDocument(UUID editionId, String title, DocumentSourceType sourceType);

    RuleDocument save(RuleDocument document);

    Optional<DocumentVersion> findVersionByChecksum(UUID documentId, String checksum);

    int nextVersionNumber(UUID documentId);

    DocumentVersion save(DocumentVersion version);

    List<DocumentSummary> findByEdition(UUID editionId);

    record DocumentSummary(RuleDocument document, DocumentVersion latestVersion) {}
}
