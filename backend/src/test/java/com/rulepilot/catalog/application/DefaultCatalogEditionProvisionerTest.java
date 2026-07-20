package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultCatalogEditionProvisionerTest {

    @Test
    void createsAGameAndNeutralEditionFromTheRulebookTitle() {
        CatalogRepository repository = mock(CatalogRepository.class);
        when(repository.findGameByName("SETI Rules")).thenReturn(Optional.empty());
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findEdition(any(UUID.class), any(String.class), any(String.class)))
                .thenReturn(Optional.empty());
        when(repository.save(any(GameEdition.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UUID editionId = new DefaultCatalogEditionProvisioner(repository).provisionDefaultEdition("SETI Rules");

        ArgumentCaptor<Game> game = ArgumentCaptor.forClass(Game.class);
        ArgumentCaptor<GameEdition> edition = ArgumentCaptor.forClass(GameEdition.class);
        verify(repository).save(game.capture());
        verify(repository).save(edition.capture());
        assertThat(editionId).isEqualTo(edition.getValue().id());
        assertThat(game.getValue().name()).isEqualTo("SETI Rules");
        assertThat(edition.getValue().name()).isEqualTo("规则书自动版本");
        assertThat(edition.getValue().language()).isEqualTo("und");
    }

    @Test
    void reusesAnExistingDefaultEdition() {
        CatalogRepository repository = mock(CatalogRepository.class);
        Game game = new Game(UUID.randomUUID(), "SETI Rules", Instant.parse("2026-07-20T10:00:00Z"));
        GameEdition edition = new GameEdition(
                UUID.randomUUID(), game.id(), "规则书自动版本", "und", null, game.createdAt());
        when(repository.findGameByName(game.name())).thenReturn(Optional.of(game));
        when(repository.findEdition(game.id(), edition.name(), edition.language())).thenReturn(Optional.of(edition));

        assertThat(new DefaultCatalogEditionProvisioner(repository).provisionDefaultEdition(game.name()))
                .isEqualTo(edition.id());
    }
}
