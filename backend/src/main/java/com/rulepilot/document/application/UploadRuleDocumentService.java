package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.RuleDocument;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class UploadRuleDocumentService {

    private final CatalogEditionLookup catalog;
    private final RuleDocumentStorageService storageService;
    private final DocumentStorage storage;
    private final RuleDocumentRepository repository;
    private final DocumentProcessingQueue processingQueue;
    private final UploadedRulebookTeachingHandoffService teachingHandoffs;
    private final Clock clock = Clock.systemUTC();

    public UploadRuleDocumentService(
            CatalogEditionLookup catalog,
            RuleDocumentStorageService storageService,
            DocumentStorage storage,
            RuleDocumentRepository repository,
            DocumentProcessingQueue processingQueue,
            UploadedRulebookTeachingHandoffService teachingHandoffs) {
        this.catalog = catalog;
        this.storageService = storageService;
        this.storage = storage;
        this.repository = repository;
        this.processingQueue = processingQueue;
        this.teachingHandoffs = teachingHandoffs;
    }

    @Transactional
    public UploadResult upload(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String officialCoverUrl,
            String originalFilename,
            String contentType,
            long size,
            InputStream content,
            String username) {
        return upload(
                editionId,
                title,
                sourceType,
                officialSourceUrl,
                officialCoverUrl,
                originalFilename,
                contentType,
                size,
                content,
                username,
                false,
                null);
    }

    @Transactional
    public UploadResult upload(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String officialCoverUrl,
            String originalFilename,
            String contentType,
            long size,
            InputStream content,
            String username,
            boolean startTeaching,
            String learningGoal) {
        if (!startTeaching && learningGoal != null && !learningGoal.isBlank()) {
            throw new IllegalArgumentException("teaching goal requires an automatic teaching handoff");
        }
        if (editionId != null) {
            requireEdition(editionId);
        }

        Instant now = Instant.now(clock);
        RuleDocument candidate = RuleDocument.create(
                editionId, title, sourceType, officialSourceUrl, officialCoverUrl, username, now);
        var existing = editionId == null
                ? repository.findUnassignedDocument(username, candidate.title(), sourceType)
                : repository.findDocument(editionId, username, candidate.title(), sourceType);
        RuleDocument document = existing.orElseGet(() -> repository.save(candidate));
        if (document.officialSourceUrl() == null && candidate.officialSourceUrl() != null) {
            document = document.withOfficialSourceUrl(candidate.officialSourceUrl());
            repository.update(document);
        }
        if (document.officialCoverUrl() == null && candidate.officialCoverUrl() != null) {
            document = document.withOfficialCoverUrl(candidate.officialCoverUrl());
            repository.update(document);
        }

        DocumentStorage.StoredDocument stored =
                storageService.storePdf(content, size, contentType, originalFilename);
        var duplicate = repository.findVersionByChecksum(document.id(), stored.sha256());
        if (duplicate.isPresent()) {
            storage.delete(stored.objectKey());
            DocumentVersion version = duplicate.orElseThrow();
            if (startTeaching) teachingHandoffs.request(version.id(), learningGoal, username);
            return new UploadResult(document, version, true);
        }

        DocumentVersion version = DocumentVersion.create(
                document.id(),
                repository.nextVersionNumber(document.id()),
                originalFilename,
                stored.objectKey(),
                stored.sha256(),
                stored.size(),
                stored.contentType(),
                now);
        try {
            DocumentVersion saved = repository.save(version);
            processingQueue.enqueue(saved.id(), now);
            if (startTeaching) teachingHandoffs.request(saved.id(), learningGoal, username);
            return new UploadResult(document, saved, false);
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            throw exception;
        }
    }

    public UploadResult upload(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String originalFilename,
            String contentType,
            long size,
            InputStream content,
            String username) {
        return upload(
                editionId, title, sourceType, officialSourceUrl, null, originalFilename, contentType, size, content, username);
    }

    @Transactional(readOnly = true)
    public List<RuleDocumentRepository.DocumentSummary> list(UUID editionId, String username) {
        requireEdition(editionId);
        return repository.findByEdition(editionId, username);
    }

    @Transactional(readOnly = true)
    public List<RuleDocumentRepository.DocumentSummary> listOwned(String username) {
        return repository.findByOwner(username);
    }

    @Transactional
    public RuleDocument assign(UUID documentId, UUID editionId, String username) {
        requireEdition(editionId);
        RuleDocument document = repository.findDocument(documentId)
                .filter(found -> found.createdBy().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("rule document does not exist"));
        RuleDocument assigned = document.assignTo(editionId);
        if (assigned == document) {
            return document;
        }
        repository.findDocument(editionId, username, document.title(), document.sourceType())
                .filter(existing -> !existing.id().equals(document.id()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("this game edition already has a rule document with that title");
                });
        repository.update(assigned);
        return assigned;
    }

    private void requireEdition(UUID editionId) {
        catalog.findEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("game edition does not exist"));
    }

    public record UploadResult(RuleDocument document, DocumentVersion version, boolean duplicate) {}
}
