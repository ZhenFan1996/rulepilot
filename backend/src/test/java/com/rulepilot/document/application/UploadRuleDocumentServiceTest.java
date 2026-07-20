package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogEditionLookup;
import com.rulepilot.catalog.CatalogEditionLookup.EditionReference;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadRuleDocumentServiceTest {

    private final CatalogEditionLookup catalog = mock(CatalogEditionLookup.class);
    private final RuleDocumentRepository repository = mock(RuleDocumentRepository.class);
    private final UploadRuleDocumentService service = new UploadRuleDocumentService(
            catalog,
            mock(RuleDocumentStorageService.class),
            mock(DocumentStorage.class),
            repository,
            mock(DocumentProcessingQueue.class));

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
                        editionId, UUID.randomUUID(), "First edition", "en", Set.of())));
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
