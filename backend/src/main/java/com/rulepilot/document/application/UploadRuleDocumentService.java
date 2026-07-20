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
    private final Clock clock = Clock.systemUTC();

    public UploadRuleDocumentService(
            CatalogEditionLookup catalog,
            RuleDocumentStorageService storageService,
            DocumentStorage storage,
            RuleDocumentRepository repository,
            DocumentProcessingQueue processingQueue) {
        this.catalog = catalog;
        this.storageService = storageService;
        this.storage = storage;
        this.repository = repository;
        this.processingQueue = processingQueue;
    }

    @Transactional
    public UploadResult upload(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String originalFilename,
            String contentType,
            long size,
            InputStream content,
            String username) {
        if (editionId != null) {
            requireEdition(editionId);
        }

        Instant now = Instant.now(clock);
        RuleDocument candidate = RuleDocument.create(editionId, title, sourceType, username, now);
        var existing = editionId == null
                ? repository.findUnassignedDocument(username, candidate.title(), sourceType)
                : repository.findDocument(editionId, username, candidate.title(), sourceType);
        RuleDocument document = existing
                .orElseGet(() -> repository.save(candidate));

        DocumentStorage.StoredDocument stored =
                storageService.storePdf(content, size, contentType, originalFilename);
        var duplicate = repository.findVersionByChecksum(document.id(), stored.sha256());
        if (duplicate.isPresent()) {
            storage.delete(stored.objectKey());
            return new UploadResult(document, duplicate.get(), true);
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
            return new UploadResult(document, saved, false);
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            throw exception;
        }
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
