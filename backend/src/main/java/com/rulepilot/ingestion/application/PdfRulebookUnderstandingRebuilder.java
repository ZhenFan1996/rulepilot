package com.rulepilot.ingestion.application;

import com.rulepilot.document.DocumentProcessing;
import com.rulepilot.ingestion.RulebookUnderstandingRebuilder;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Repairs pre-layout documents on demand without requiring a user to upload their PDF again. */
@Service
@Profile("!test")
class PdfRulebookUnderstandingRebuilder implements RulebookUnderstandingRebuilder {

    private final DocumentProcessing documents;
    private final PdfPageExtractor extractor;
    private final RuleStructureService structures;

    PdfRulebookUnderstandingRebuilder(
            DocumentProcessing documents, PdfPageExtractor extractor, RuleStructureService structures) {
        this.documents = documents;
        this.extractor = extractor;
        this.structures = structures;
    }

    @Override
    public void rebuild(UUID documentVersionId) {
        if (documentVersionId == null) {
            throw new IllegalArgumentException("document version is required");
        }
        var pages = extractor.extract(documents.open(documentVersionId));
        if (pages.isEmpty()) {
            throw new IllegalStateException("a PDF must contain at least one page");
        }
        structures.organize(documentVersionId, pages);
    }
}
