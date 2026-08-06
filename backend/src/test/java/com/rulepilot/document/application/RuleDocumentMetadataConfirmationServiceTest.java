package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BoardGameMetadataLinking;
import com.rulepilot.catalog.BoardGameMetadataLinking.Link;
import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleDocumentMetadataConfirmationServiceTest {

    private final RuleDocumentMetadataSuggestionService suggestions = mock(RuleDocumentMetadataSuggestionService.class);
    private final BoardGameMetadataLinking linking = mock(BoardGameMetadataLinking.class);
    private final UploadRuleDocumentService documents = mock(UploadRuleDocumentService.class);
    private final RuleDocumentMetadataConfirmationService service =
            new RuleDocumentMetadataConfirmationService(suggestions, linking, documents);

    @Test
    void confirmsOnlyACurrentCandidateAndAssignsItsCanonicalEdition() {
        UUID documentId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        Candidate candidate = candidate(266192);
        Link link = new Link(gameId, editionId, 266192, "Wingspan", "https://example.test/cover.jpg", false);
        RuleDocument assigned = document(documentId, editionId);
        when(suggestions.suggest(documentId, "alice")).thenReturn(List.of(candidate));
        when(linking.confirm(266192)).thenReturn(link);
        when(documents.assign(documentId, editionId, "alice")).thenReturn(assigned);

        var confirmation = service.confirm(documentId, 266192, "alice");

        assertThat(confirmation.document()).isEqualTo(assigned);
        assertThat(confirmation.candidate()).isEqualTo(candidate);
        assertThat(confirmation.link()).isEqualTo(link);
    }

    @Test
    void rejectsAnIdThatWasNotReturnedForThisDocumentBeforeImporting() {
        UUID documentId = UUID.randomUUID();
        when(suggestions.suggest(documentId, "alice")).thenReturn(List.of(candidate(266192)));

        assertThatThrownBy(() -> service.confirm(documentId, 123, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a current document candidate");
        verify(linking, never()).confirm(123);
        verify(documents, never()).assign(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void repeatedConfirmationDelegatesToIdempotentCatalogAndDocumentOperations() {
        UUID documentId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        Candidate candidate = candidate(266192);
        RuleDocument assigned = document(documentId, editionId);
        when(suggestions.suggest(documentId, "alice")).thenReturn(List.of(candidate));
        when(linking.confirm(266192))
                .thenReturn(new Link(gameId, editionId, 266192, "Wingspan", "", false))
                .thenReturn(new Link(gameId, editionId, 266192, "Wingspan", "", true));
        when(documents.assign(documentId, editionId, "alice")).thenReturn(assigned);

        var first = service.confirm(documentId, 266192, "alice");
        var repeated = service.confirm(documentId, 266192, "alice");

        assertThat(first.document().gameEditionId()).isEqualTo(editionId);
        assertThat(repeated.document()).isSameAs(assigned);
        assertThat(repeated.link().alreadyImported()).isTrue();
        verify(documents, org.mockito.Mockito.times(2)).assign(documentId, editionId, "alice");
    }

    private Candidate candidate(int bggId) {
        return new Candidate(bggId, "Wingspan", 2019, "", 1, 5, 70, 10, true);
    }

    private RuleDocument document(UUID documentId, UUID editionId) {
        return new RuleDocument(
                documentId,
                editionId,
                "Wingspan",
                DocumentSourceType.BASE_RULEBOOK,
                "alice",
                Instant.parse("2026-08-06T00:00:00Z"));
    }
}
