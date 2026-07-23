package com.rulepilot.document.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleDocumentRemovalServiceTest {

    private final RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final RuleDocumentRemovalService service = new RuleDocumentRemovalService(documents, storage);

    @Test
    void removesOwnedSourceVersionsPageImagesAndDerivedLessons() {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        RuleDocument document = new RuleDocument(
                documentId, null, "Rulebook", DocumentSourceType.BASE_RULEBOOK, "alice", Instant.now());
        DocumentVersion version = new DocumentVersion(
                versionId, documentId, 1, "rules.pdf", "documents/rules.pdf", "a".repeat(64), 12,
                "application/pdf", ProcessingStatus.READY, Instant.now());
        when(documents.findDocument(documentId)).thenReturn(Optional.of(document));
        when(documents.findVersions(documentId)).thenReturn(List.of(version));
        when(documents.findAllPageImages(versionId)).thenReturn(List.of(
                new RuleDocumentRepository.PageImageMetadata(1, "pages/rules-1.jpg", 100, 100)));

        service.removeOwned(documentId, "alice");

        verify(documents).deleteDocument(documentId);
        verify(storage).delete("documents/rules.pdf");
        verify(storage).delete("pages/rules-1.jpg");
    }
}
