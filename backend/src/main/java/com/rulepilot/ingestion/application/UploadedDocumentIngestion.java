package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentUploaded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@Profile("!test")
public class UploadedDocumentIngestion {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadedDocumentIngestion.class);

    private final DocumentProcessing documents;
    private final PdfPageExtractor extractor;
    private final ProcessingProgressTracker progress;

    public UploadedDocumentIngestion(
            DocumentProcessing documents,
            PdfPageExtractor extractor,
            ProcessingProgressTracker progress) {
        this.documents = documents;
        this.extractor = extractor;
        this.progress = progress;
    }

    @Async("ingestionTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void extractPages(DocumentUploaded event) {
        try {
            progress.update(event.documentVersionId(), "VALIDATING", 15, 0, false);
            documents.markValidating(event.documentVersionId());
            progress.update(event.documentVersionId(), "EXTRACTING", 30, 0, false);
            documents.markExtracting(event.documentVersionId());
            var pages = extractor.extract(documents.open(event.documentVersionId()));
            progress.update(event.documentVersionId(), "EXTRACTING", 65, pages.size(), false);
            documents.replacePages(event.documentVersionId(), pages);
            documents.markStructuring(event.documentVersionId());
            progress.update(event.documentVersionId(), "STRUCTURING", 75, pages.size(), true);
        } catch (RuntimeException exception) {
            LOGGER.error("PDF extraction failed for documentVersionId={}", event.documentVersionId(), exception);
            try {
                documents.markFailed(event.documentVersionId());
                progress.update(event.documentVersionId(), "FAILED", 100, 0, true);
            } catch (RuntimeException statusException) {
                LOGGER.error(
                        "Could not persist PDF extraction failure for documentVersionId={}",
                        event.documentVersionId(),
                        statusException);
            }
        }
    }
}
