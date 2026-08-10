package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.document.DocumentVersionScopeLookup.VersionScope;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingPlanCatalogPresentationServiceTest {

    private final DocumentVersionScopeLookup documents = mock(DocumentVersionScopeLookup.class);
    private final CatalogGamePresentationLookup catalog = mock(CatalogGamePresentationLookup.class);
    private final TeachingPlanCatalogPresentationService presentations =
            new TeachingPlanCatalogPresentationService(documents, catalog);

    @Test
    void decoratesOnlyTheOwnedPlansLinkedEditionAtThePresentationBoundary() {
        TeachingPlan plan = plan("alice");
        UUID editionId = UUID.randomUUID();
        var game = new CatalogGamePresentationLookup.Presentation(
                editionId,
                "Wingspan",
                "Wingspan",
                "en",
                2019,
                266192,
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                10,
                "https://boardgamegeek.com/boardgame/266192");
        when(documents.findVersion(plan.documentVersionId()))
                .thenReturn(Optional.of(new VersionScope(
                        plan.documentVersionId(), editionId, "READY", "alice", "Uploaded rulebook")));
        when(catalog.findByEdition(editionId)).thenReturn(Optional.of(game));

        assertThat(presentations.findForOwnedPlan(plan)).contains(game);
        verify(catalog).findByEdition(editionId);
    }

    @Test
    void doesNotExposeAnotherOwnersVersionOrConsultCatalog() {
        TeachingPlan plan = plan("alice");
        when(documents.findVersion(plan.documentVersionId()))
                .thenReturn(Optional.of(new VersionScope(
                        plan.documentVersionId(), UUID.randomUUID(), "READY", "mallory", "Other rulebook")));

        assertThat(presentations.findForOwnedPlan(plan)).isEmpty();
        verify(catalog, never()).findByEdition(org.mockito.ArgumentMatchers.any());
    }

    private TeachingPlan plan(String owner) {
        return new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Rulebook-derived title",
                "Rulebook-derived premise",
                List.of(),
                owner,
                Instant.parse("2026-08-06T00:00:00Z"));
    }
}
