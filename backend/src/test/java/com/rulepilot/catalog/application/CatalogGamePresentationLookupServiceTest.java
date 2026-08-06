package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogGamePresentationLookupServiceTest {

    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final CatalogGamePresentationLookupService presentations =
            new CatalogGamePresentationLookupService(catalog);

    @Test
    void joinsConfirmedEditionIdentityWithAttributedBggDisplayFields() {
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        UUID gameId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        when(catalog.findEdition(editionId))
                .thenReturn(Optional.of(new GameEdition(editionId, gameId, "Wingspan", "en", 2019, now)));
        when(catalog.findGame(gameId)).thenReturn(Optional.of(new Game(gameId, "Wingspan", now)));
        when(catalog.findBggMetadata(gameId)).thenReturn(Optional.of(new BggGameMetadata(
                gameId,
                266192,
                "display-only description",
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                10,
                now)));

        var presentation = presentations.findByEdition(editionId).orElseThrow();

        assertThat(presentation.gameName()).isEqualTo("Wingspan");
        assertThat(presentation.publicationYear()).isEqualTo(2019);
        assertThat(presentation.minPlayers()).isEqualTo(1);
        assertThat(presentation.maxPlayers()).isEqualTo(5);
        assertThat(presentation.playingTimeMinutes()).isEqualTo(70);
        assertThat(presentation.minimumAge()).isEqualTo(10);
        assertThat(presentation.bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/266192");
    }

    @Test
    void leavesAManualCatalogEditionUndecoratedWithoutBggMetadata() {
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        UUID gameId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        when(catalog.findEdition(editionId))
                .thenReturn(Optional.of(new GameEdition(editionId, gameId, "Local edition", "und", null, now)));
        when(catalog.findGame(gameId)).thenReturn(Optional.of(new Game(gameId, "Local game", now)));
        when(catalog.findBggMetadata(gameId)).thenReturn(Optional.empty());

        assertThat(presentations.findByEdition(editionId)).isEmpty();
    }
}
