package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BoardGameMetadataMatching;
import com.rulepilot.catalog.BoardGameMetadataMatching.Candidate;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleDocumentMetadataSuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private final RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
    private final BoardGameMetadataMatching matching = mock(BoardGameMetadataMatching.class);
    private final RuleDocumentMetadataSuggestionService service =
            new RuleDocumentMetadataSuggestionService(documents, matching);

    @Test
    void readyRulebookOwnerReceivesTitleBoundCandidates() {
        UUID documentId = UUID.randomUUID();
        RuleDocument document = document(documentId, "alice", "Wingspan");
        when(documents.findDocument(documentId)).thenReturn(Optional.of(document));
        when(documents.findVersions(documentId)).thenReturn(List.of(version(documentId, ProcessingStatus.READY)));
        Candidate candidate = new Candidate(266192, "Wingspan", 2019, "", 1, 5, 70, 10, true);
        when(matching.findExactCandidates("Wingspan")).thenReturn(List.of(candidate));

        assertThat(service.suggest(documentId, "alice")).containsExactly(candidate);
        verify(matching).findExactCandidates("Wingspan");
    }

    @Test
    void anotherUserCannotProbeSuggestionsForTheRulebook() {
        UUID documentId = UUID.randomUUID();
        when(documents.findDocument(documentId)).thenReturn(Optional.of(document(documentId, "alice", "Wingspan")));

        assertThatThrownBy(() -> service.suggest(documentId, "mallory"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule document does not exist");
        verify(documents, never()).findVersions(documentId);
        verify(matching, never()).findExactCandidates(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void processingRulebookNeverCallsBgg() {
        UUID documentId = UUID.randomUUID();
        when(documents.findDocument(documentId)).thenReturn(Optional.of(document(documentId, "alice", "Wingspan")));
        when(documents.findVersions(documentId)).thenReturn(List.of(version(documentId, ProcessingStatus.EXTRACTING)));

        assertThatThrownBy(() -> service.suggest(documentId, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not ready");
        verify(matching, never()).findExactCandidates(org.mockito.ArgumentMatchers.anyString());
    }

    private RuleDocument document(UUID id, String owner, String title) {
        return new RuleDocument(id, null, title, DocumentSourceType.BASE_RULEBOOK, owner, NOW);
    }

    private DocumentVersion version(UUID documentId, ProcessingStatus status) {
        return new DocumentVersion(
                UUID.randomUUID(),
                documentId,
                1,
                "rules.pdf",
                "documents/rules.pdf",
                "a".repeat(64),
                1024,
                "application/pdf",
                status,
                NOW);
    }
}
