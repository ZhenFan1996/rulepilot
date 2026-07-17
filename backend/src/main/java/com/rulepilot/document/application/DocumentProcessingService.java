package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
class DocumentProcessingService implements DocumentProcessing {

    private final RuleDocumentRepository repository;
    private final DocumentStorage storage;

    DocumentProcessingService(RuleDocumentRepository repository, DocumentStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public InputStream open(UUID documentVersionId) {
        DocumentVersion version = requireVersion(documentVersionId);
        return storage.open(version.objectKey());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markValidating(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.VALIDATING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExtracting(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.EXTRACTING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStructuring(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.STRUCTURING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markChunking(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.CHUNKING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEmbedding(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.EMBEDDING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexing(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.INDEXING);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(UUID documentVersionId) {
        transition(documentVersionId, ProcessingStatus.READY);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentVersionId) {
        DocumentVersion version = requireVersion(documentVersionId);
        if (version.status().canTransitionTo(ProcessingStatus.FAILED)) {
            repository.update(version.transitionTo(ProcessingStatus.FAILED));
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replacePages(UUID documentVersionId, List<ExtractedPage> pages) {
        requireVersion(documentVersionId);
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("a PDF must contain at least one page");
        }
        repository.replacePages(documentVersionId, pages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageView> pages(UUID documentVersionId) {
        requireVersion(documentVersionId);
        return repository.findPages(documentVersionId);
    }

    private void transition(UUID versionId, ProcessingStatus next) {
        repository.update(requireVersion(versionId).transitionTo(next));
    }

    private DocumentVersion requireVersion(UUID versionId) {
        return repository.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("document version does not exist"));
    }
}
