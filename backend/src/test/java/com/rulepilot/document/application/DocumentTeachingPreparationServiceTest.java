package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogEditionProvisioning;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.DocumentVersion;
import com.rulepilot.document.domain.ProcessingStatus;
import com.rulepilot.document.domain.RuleDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTeachingPreparationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");
    private final RuleDocumentRepository documents = mock(RuleDocumentRepository.class);
    private final CatalogEditionProvisioning catalog = mock(CatalogEditionProvisioning.class);
    private final DocumentTeachingPreparationService preparation =
            new DocumentTeachingPreparationService(documents, catalog);

    @Test
    void automaticallyAssignsAnOwnedReadyRulebook() {
        RuleDocument document = rulebook(null, "alice");
        DocumentVersion version = version(document.id(), ProcessingStatus.READY);
        UUID editionId = UUID.randomUUID();
        when(documents.findVersion(version.id())).thenReturn(Optional.of(version));
        when(documents.findDocument(document.id())).thenReturn(Optional.of(document));
        when(catalog.provisionDefaultEdition("SETI")).thenReturn(editionId);
        when(documents.findDocument(editionId, "alice", document.title(), document.sourceType()))
                .thenReturn(Optional.empty());

        var scope = preparation.prepare(version.id(), "alice", "SETI");

        assertThat(scope.editionId()).isEqualTo(editionId);
        verify(documents).update(document.assignTo(editionId));
    }

    @Test
    void preservesTheUploadedTitleInsteadOfReclassifyingItWithFilenameWords() {
        RuleDocument document = new RuleDocument(
                UUID.randomUUID(), null, "aurora_rulebook_EN_36_web", DocumentSourceType.BASE_RULEBOOK, "alice", NOW);
        DocumentVersion version = version(document.id(), ProcessingStatus.READY);
        UUID editionId = UUID.randomUUID();
        when(documents.findVersion(version.id())).thenReturn(Optional.of(version));
        when(documents.findDocument(document.id())).thenReturn(Optional.of(document));
        when(catalog.provisionDefaultEdition("Aurora")).thenReturn(editionId);
        when(documents.findDocument(editionId, "alice", document.title(), document.sourceType()))
                .thenReturn(Optional.empty());

        var scope = preparation.prepare(version.id(), "alice", "Aurora");

        assertThat(scope.documentTitle()).isEqualTo("aurora_rulebook_EN_36_web");
        verify(documents, never()).update(document.withTitle("Aurora"));
        verify(documents).update(document.assignTo(editionId));
    }

    @Test
    void leavesANewUploadUnassignedWhenAutomaticAssociationWouldDuplicateAnExistingRulebook() {
        RuleDocument document = rulebook(null, "alice");
        DocumentVersion version = version(document.id(), ProcessingStatus.READY);
        UUID editionId = UUID.randomUUID();
        RuleDocument existing = rulebook(editionId, "alice");
        when(documents.findVersion(version.id())).thenReturn(Optional.of(version));
        when(documents.findDocument(document.id())).thenReturn(Optional.of(document));
        when(catalog.provisionDefaultEdition("SETI")).thenReturn(editionId);
        when(documents.findDocument(editionId, "alice", document.title(), document.sourceType()))
                .thenReturn(Optional.of(existing));

        var scope = preparation.prepare(version.id(), "alice", "SETI");

        assertThat(scope.editionId()).isNull();
        verify(documents, never()).update(document.assignTo(editionId));
    }

    @Test
    void keepsAnExistingAssociationWithoutCreatingCatalogData() {
        UUID editionId = UUID.randomUUID();
        RuleDocument document = rulebook(editionId, "alice");
        DocumentVersion version = version(document.id(), ProcessingStatus.READY);
        when(documents.findVersion(version.id())).thenReturn(Optional.of(version));
        when(documents.findDocument(document.id())).thenReturn(Optional.of(document));

        assertThat(preparation.prepare(version.id(), "alice", "SETI").editionId()).isEqualTo(editionId);
        verify(catalog, never()).provisionDefaultEdition(document.title());
    }

    @Test
    void rejectsProcessingOrForeignRulebooksBeforeProvisioning() {
        RuleDocument document = rulebook(null, "alice");
        DocumentVersion version = version(document.id(), ProcessingStatus.EXTRACTING);
        when(documents.findVersion(version.id())).thenReturn(Optional.of(version));
        when(documents.findDocument(document.id())).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> preparation.prepare(version.id(), "alice", "SETI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not ready");
        assertThatThrownBy(() -> preparation.prepare(version.id(), "bob", "SETI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rule document does not exist");
        verify(catalog, never()).provisionDefaultEdition(document.title());
    }

    private RuleDocument rulebook(UUID editionId, String owner) {
        return new RuleDocument(
                UUID.randomUUID(), editionId, "SETI Rules", DocumentSourceType.BASE_RULEBOOK, owner, NOW);
    }

    private DocumentVersion version(UUID documentId, ProcessingStatus status) {
        return new DocumentVersion(
                UUID.randomUUID(),
                documentId,
                1,
                "seti.pdf",
                "documents/seti.pdf",
                "a".repeat(64),
                1024,
                "application/pdf",
                status,
                NOW);
    }
}
