package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentPageImageStore;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
class DocumentProcessingService implements DocumentProcessing, DocumentPageImageStore, DocumentPageImages {

    private static final int MAX_PAGE_IMAGE_BYTES = 5 * 1024 * 1024;

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
    @Transactional
    public void prepareRetry(UUID documentVersionId, com.rulepilot.document.DocumentProcessingStage stage) {
        ProcessingStatus resumeFrom = switch (stage) {
            case PARSE -> ProcessingStatus.UPLOADED;
            case CHUNK -> ProcessingStatus.STRUCTURING;
            case EMBED -> ProcessingStatus.CHUNKING;
        };
        repository.update(requireVersion(documentVersionId).retryFromFailure(resumeFrom));
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
    public int pageCount(UUID documentVersionId) {
        requireVersion(documentVersionId);
        return repository.countPages(documentVersionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageView> pages(UUID documentVersionId) {
        requireVersion(documentVersionId);
        return repository.findPages(documentVersionId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(UUID documentVersionId, RenderedPageImage image) {
        requireVersion(documentVersionId);
        byte[] content = image.content();
        if (content.length > MAX_PAGE_IMAGE_BYTES) {
            throw new IllegalArgumentException("rendered page image exceeds the size limit");
        }
        String objectKey = "documents/%s/pages/%04d.jpg".formatted(documentVersionId, image.pageNumber());
        storage.store(objectKey, new ByteArrayInputStream(content), content.length, "image/jpeg");
        repository.updatePageImage(
                documentVersionId, image.pageNumber(), objectKey, image.width(), image.height());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageImage> read(UUID documentVersionId, Set<Integer> pageNumbers) {
        requireVersion(documentVersionId);
        if (pageNumbers == null || pageNumbers.isEmpty() || pageNumbers.size() > DocumentPageImages.MAX_PAGES_PER_READ
                || pageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
            throw new IllegalArgumentException("requested document page images are invalid");
        }
        return repository.findPageImages(documentVersionId, Set.copyOf(pageNumbers)).stream()
                .map(metadata -> new PageImage(
                        metadata.pageNumber(),
                        "image/jpeg",
                        readImage(metadata.objectKey()),
                        metadata.width(),
                        metadata.height()))
                .toList();
    }

    private byte[] readImage(String objectKey) {
        try (InputStream input = storage.open(objectKey)) {
            byte[] content = input.readNBytes(MAX_PAGE_IMAGE_BYTES + 1);
            if (content.length > MAX_PAGE_IMAGE_BYTES) {
                throw new IllegalStateException("stored page image exceeds the size limit");
            }
            return content;
        } catch (IOException exception) {
            throw new UncheckedIOException("could not read page image", exception);
        }
    }

    private void transition(UUID versionId, ProcessingStatus next) {
        DocumentVersion version = requireVersion(versionId);
        if (version.status() == next
                || (version.status() != ProcessingStatus.FAILED
                        && version.status().ordinal() > next.ordinal())) {
            return;
        }
        repository.update(version.transitionTo(next));
    }

    private DocumentVersion requireVersion(UUID versionId) {
        return repository.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("document version does not exist"));
    }
}
