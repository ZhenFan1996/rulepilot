package com.rulepilot.document.application;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.document.DocumentUploaded;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.RuleDocument;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class UploadRuleDocumentService {

    private final CatalogEditionLookup catalog;
    private final RuleDocumentStorageService storageService;
    private final DocumentStorage storage;
    private final RuleDocumentRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public UploadRuleDocumentService(
            CatalogEditionLookup catalog,
            RuleDocumentStorageService storageService,
            DocumentStorage storage,
            RuleDocumentRepository repository,
            ApplicationEventPublisher events) {
        this.catalog = catalog;
        this.storageService = storageService;
        this.storage = storage;
        this.repository = repository;
        this.events = events;
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
        catalog.findEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("game edition does not exist"));

        Instant now = Instant.now(clock);
        RuleDocument candidate = RuleDocument.create(editionId, title, sourceType, username, now);
        RuleDocument document = repository.findDocument(editionId, candidate.title(), sourceType)
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
            events.publishEvent(new DocumentUploaded(saved.id()));
            return new UploadResult(document, saved, false);
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<RuleDocumentRepository.DocumentSummary> list(UUID editionId) {
        catalog.findEdition(editionId)
                .orElseThrow(() -> new IllegalArgumentException("game edition does not exist"));
        return repository.findByEdition(editionId);
    }

    public record UploadResult(RuleDocument document, DocumentVersion version, boolean duplicate) {}
}
