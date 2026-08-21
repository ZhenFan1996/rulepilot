package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameCatalogServiceTest {

    @Test
    void assemblesTheWholeShelfFromBoundedBatchReads() {
        CatalogRepository repository = mock(CatalogRepository.class);
        GameCatalogService service = new GameCatalogService(repository);
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        Game first = new Game(UUID.randomUUID(), "Orbit", now);
        Game second = new Game(UUID.randomUUID(), "Harbor", now);
        GameEdition edition = new GameEdition(UUID.randomUUID(), first.id(), "Base", "zh", 2026, now);
        BggGameMetadata metadata = new BggGameMetadata(
                first.id(), 42, "", "https://example.test/orbit.jpg", 1, 4, 45, 10, now);
        List<UUID> ids = List.of(first.id(), second.id());
        when(repository.findGames()).thenReturn(List.of(first, second));
        when(repository.findEditionsByGames(ids)).thenReturn(Map.of(first.id(), List.of(edition)));
        when(repository.findExpansionsByGames(ids)).thenReturn(Map.of());
        when(repository.findBggMetadataByGames(ids)).thenReturn(Map.of(first.id(), metadata));

        assertThat(service.listCatalog()).satisfiesExactly(
                view -> {
                    assertThat(view.game()).isEqualTo(first);
                    assertThat(view.editions()).containsExactly(edition);
                    assertThat(view.bggMetadata()).contains(metadata);
                },
                view -> {
                    assertThat(view.game()).isEqualTo(second);
                    assertThat(view.editions()).isEmpty();
                    assertThat(view.bggMetadata()).isEmpty();
                });
        verify(repository).findEditionsByGames(ids);
        verify(repository).findExpansionsByGames(ids);
        verify(repository).findBggMetadataByGames(ids);
        verify(repository, never()).findEditions(first.id());
        verify(repository, never()).findExpansions(first.id());
        verify(repository, never()).findBggMetadata(first.id());
    }
}
