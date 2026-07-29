package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.PublicRulebookReferenceLookup.Reference;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.RuleDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RuleDocumentRepository {

    Optional<RuleDocument> findDocument(
            UUID editionId, String createdBy, String title, DocumentSourceType sourceType);

    Optional<RuleDocument> findUnassignedDocument(String createdBy, String title, DocumentSourceType sourceType);

    Optional<RuleDocument> findDocument(UUID documentId);

    RuleDocument save(RuleDocument document);

    void update(RuleDocument document);

    Optional<DocumentVersion> findVersionByChecksum(UUID documentId, String checksum);

    int nextVersionNumber(UUID documentId);

    DocumentVersion save(DocumentVersion version);

    Optional<DocumentVersion> findVersion(UUID versionId);

    List<DocumentVersion> findVersions(UUID documentId);

    long ruleDataVersion(UUID versionId);

    long incrementRuleDataVersion(UUID versionId);

    void update(DocumentVersion version);

    void replacePages(UUID versionId, List<DocumentProcessing.ExtractedPage> pages);

    List<DocumentProcessing.PageView> findPages(UUID versionId);

    void updatePageImage(UUID versionId, int pageNumber, String objectKey, int width, int height);

    List<PageImageMetadata> findPageImages(UUID versionId, java.util.Set<Integer> pageNumbers);

    List<PageImageMetadata> findAllPageImages(UUID versionId);

    void deleteDocument(UUID documentId);

    List<DocumentSummary> findByEdition(UUID editionId, String createdBy);

    List<DocumentSummary> findByOwner(String createdBy);

    Map<UUID, Reference> findReferences(Collection<UUID> documentVersionIds);

    record DocumentSummary(RuleDocument document, DocumentVersion latestVersion) {}

    record PageImageMetadata(int pageNumber, String objectKey, int width, int height) {}
}
