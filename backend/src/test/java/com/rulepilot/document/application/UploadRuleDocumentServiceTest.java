package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.catalog.CatalogEditionLookup.EditionReference;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Instant;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadRuleDocumentServiceTest {

    private final CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
    private final RuleDocumentStorageService storageService = mock(RuleDocumentStorageService.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final RuleDocumentRepository repository = mock(RuleDocumentRepository.class);
    private final DocumentProcessingQueue processingQueue = mock(DocumentProcessingQueue.class);
    private final UploadedRulebookTeachingHandoffService teachingHandoffs =
            mock(UploadedRulebookTeachingHandoffService.class);
    private final UploadRuleDocumentService service = new UploadRuleDocumentService(
            catalog,
            storageService,
            storage,
            repository,
            processingQueue,
            teachingHandoffs);

    @Test
    void persistsAutomaticTeachingIntentTogetherWithANewPlayerUpload() {
        when(repository.findUnassignedDocument("alice", "SETI Rules", DocumentSourceType.BASE_RULEBOOK))
                .thenReturn(Optional.empty());
        when(repository.save(any(RuleDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storageService.storePdf(any(), anyLong(), anyString(), anyString()))
                .thenReturn(new DocumentStorage.StoredDocument(
                        "documents/new.pdf", 3, "application/pdf", "a".repeat(64)));
        when(repository.findVersionByChecksum(any(), anyString())).thenReturn(Optional.empty());
        when(repository.nextVersionNumber(any())).thenReturn(1);
        when(repository.save(any(com.rulepilot.document.domain.DocumentVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.upload(
                null,
                "SETI Rules",
                DocumentSourceType.BASE_RULEBOOK,
                null,
                null,
                "seti.pdf",
                "application/pdf",
                3,
                InputStream.nullInputStream(),
                "alice",
                true,
                "先讲清开局。 ");

        verify(processingQueue).enqueue(org.mockito.ArgumentMatchers.eq(result.version().id()), any(Instant.class));
        verify(teachingHandoffs).request(result.version().id(), "先讲清开局。 ", "alice");
    }

    @Test
    void rejectsATeachingGoalWhenNoAutomaticHandoffWasRequested() {
        assertThatThrownBy(() -> service.upload(
                        null,
                        "SETI Rules",
                        DocumentSourceType.BASE_RULEBOOK,
                        null,
                        null,
                        "seti.pdf",
                        "application/pdf",
                        3,
                        InputStream.nullInputStream(),
                        "alice",
                        false,
                        "先讲清开局。"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("teaching goal requires an automatic teaching handoff");
        verify(storageService, never()).storePdf(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void ownerCanAssignAnUnassignedRulebook() {
        UUID documentId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        RuleDocument document = document(documentId, null, "alice");
        editionExists(editionId);
        when(repository.findDocument(documentId)).thenReturn(Optional.of(document));
        when(repository.findDocument(editionId, "alice", document.title(), document.sourceType()))
                .thenReturn(Optional.empty());

        RuleDocument assigned = service.assign(documentId, editionId, "alice");

        assertThat(assigned.gameEditionId()).isEqualTo(editionId);
        verify(repository).update(assigned);
    }

    @Test
    void assignmentDoesNotExposeAnotherUsersRulebook() {
        UUID documentId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        editionExists(editionId);
        when(repository.findDocument(documentId)).thenReturn(Optional.of(document(documentId, null, "alice")));

        assertThatThrownBy(() -> service.assign(documentId, editionId, "bob"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule document does not exist");
        verify(repository, never()).update(org.mockito.ArgumentMatchers.any(RuleDocument.class));
    }

    @Test
    void repeatedAssignmentToTheSameEditionDoesNotWriteAgain() {
        UUID documentId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        RuleDocument document = document(documentId, editionId, "alice");
        editionExists(editionId);
        when(repository.findDocument(documentId)).thenReturn(Optional.of(document));

        assertThat(service.assign(documentId, editionId, "alice")).isSameAs(document);
        verify(repository, never()).update(org.mockito.ArgumentMatchers.any(RuleDocument.class));
    }

    private void editionExists(UUID editionId) {
        when(catalog.findEdition(editionId))
                .thenReturn(Optional.of(new EditionReference(
                        editionId, UUID.randomUUID(), "SETI", "First edition", "en", Set.of())));
    }

    private RuleDocument document(UUID id, UUID editionId, String owner) {
        return new RuleDocument(
                id,
                editionId,
                "SETI Rules",
                DocumentSourceType.BASE_RULEBOOK,
                owner,
                Instant.parse("2026-07-20T10:00:00Z"));
    }
}
