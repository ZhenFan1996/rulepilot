package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.document.DocumentUploaded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@Profile("!test")
public class UploadedDocumentIngestion {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadedDocumentIngestion.class);

    private final DocumentProcessing documents;
    private final PdfPageExtractor extractor;

    public UploadedDocumentIngestion(DocumentProcessing documents, PdfPageExtractor extractor) {
        this.documents = documents;
        this.extractor = extractor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void extractPages(DocumentUploaded event) {
        try {
            documents.markValidating(event.documentVersionId());
            documents.markExtracting(event.documentVersionId());
            var pages = extractor.extract(documents.open(event.documentVersionId()));
            documents.replacePages(event.documentVersionId(), pages);
            documents.markStructuring(event.documentVersionId());
        } catch (RuntimeException exception) {
            LOGGER.error("PDF extraction failed for documentVersionId={}", event.documentVersionId(), exception);
            try {
                documents.markFailed(event.documentVersionId());
            } catch (RuntimeException statusException) {
                LOGGER.error(
                        "Could not persist PDF extraction failure for documentVersionId={}",
                        event.documentVersionId(),
                        statusException);
            }
        }
    }
}
