package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.RuleDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleDocumentRepository {

    Optional<RuleDocument> findDocument(UUID editionId, String title, DocumentSourceType sourceType);

    Optional<RuleDocument> findDocument(UUID documentId);

    RuleDocument save(RuleDocument document);

    Optional<DocumentVersion> findVersionByChecksum(UUID documentId, String checksum);

    int nextVersionNumber(UUID documentId);

    DocumentVersion save(DocumentVersion version);

    Optional<DocumentVersion> findVersion(UUID versionId);

    long ruleDataVersion(UUID versionId);

    long incrementRuleDataVersion(UUID versionId);

    void update(DocumentVersion version);

    void replacePages(UUID versionId, List<DocumentProcessing.ExtractedPage> pages);

    List<DocumentProcessing.PageView> findPages(UUID versionId);

    void updatePageImage(UUID versionId, int pageNumber, String objectKey, int width, int height);

    List<PageImageMetadata> findPageImages(UUID versionId, java.util.Set<Integer> pageNumbers);

    List<DocumentSummary> findByEdition(UUID editionId);

    record DocumentSummary(RuleDocument document, DocumentVersion latestVersion) {}

    record PageImageMetadata(int pageNumber, String objectKey, int width, int height) {}
}
