package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.domain.GameEdition;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogEditionLanguageConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void persistsAConfirmedLanguageForAnUnknownEdition() {
        UUID editionId = UUID.randomUUID();
        CatalogRepository repository = mock(CatalogRepository.class);
        when(repository.findEdition(editionId)).thenReturn(Optional.of(new GameEdition(
                editionId, UUID.randomUUID(), "Base edition", "und", 2024, NOW)));
        when(repository.confirmEditionLanguageIfUnknown(editionId, "en")).thenReturn(true);

        boolean updated = new CatalogEditionLanguageConfirmationService(repository)
                .confirmIfUnknown(editionId, "en");

        assertThat(updated).isTrue();
        verify(repository).confirmEditionLanguageIfUnknown(editionId, "en");
    }

    @Test
    void preservesAKnownLanguageWhenTheSelectedSourceConflicts() {
        UUID editionId = UUID.randomUUID();
        CatalogRepository repository = mock(CatalogRepository.class);
        when(repository.findEdition(editionId)).thenReturn(Optional.of(new GameEdition(
                editionId, UUID.randomUUID(), "Chinese edition", "zh-CN", 2024, NOW)));

        boolean updated = new CatalogEditionLanguageConfirmationService(repository)
                .confirmIfUnknown(editionId, "en");

        assertThat(updated).isFalse();
        verify(repository, never()).confirmEditionLanguageIfUnknown(
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());
    }
}
