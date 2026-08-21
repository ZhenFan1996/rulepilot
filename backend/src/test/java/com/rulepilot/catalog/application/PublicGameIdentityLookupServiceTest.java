package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalogRepository.ExactNameMatch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicGameIdentityLookupServiceTest {

    private final BggRankedCatalogRepository rankedGames = mock(BggRankedCatalogRepository.class);
    private final PublicGameIdentityLookupService lookup = new PublicGameIdentityLookupService(rankedGames);

    @Test
    void linksOnlyAnExactNormalizedTitleFromTheLocalSnapshot() {
        when(rankedGames.find(any())).thenReturn(new Page(2, 0, 5, List.of(
                game(42, "Orbit"),
                game(43, "Orbit: The Card Game"))));

        assertThat(lookup.findByTitle("  ORBIT ")).hasValueSatisfying(identity -> {
            assertThat(identity.bggId()).isEqualTo(42);
            assertThat(identity.name()).isEqualTo("Orbit");
            assertThat(identity.bggUrl()).isEqualTo("https://boardgamegeek.com/boardgame/42");
        });
    }

    @Test
    void doesNotAttachAFirstFuzzyResultToAPublicLesson() {
        when(rankedGames.find(any())).thenReturn(new Page(1, 0, 5, List.of(game(43, "Orbit: The Card Game"))));

        assertThat(lookup.findByTitle("Orbit")).isEmpty();
    }

    @Test
    void linksAnExactSingleCharacterTitle() {
        when(rankedGames.find(any())).thenReturn(new Page(1, 0, 5, List.of(game(7, "碁"))));

        assertThat(lookup.findByTitle("碁")).hasValueSatisfying(identity -> {
            assertThat(identity.bggId()).isEqualTo(7);
            assertThat(identity.name()).isEqualTo("碁");
        });
    }

    @Test
    void batchLookupSkipsInvalidOrUnavailableOptionalMetadata() {
        when(rankedGames.findExactNames(any())).thenThrow(new IllegalStateException("snapshot unavailable"));

        assertThat(lookup.findByTitles(List.of("Orbit", "x"))).isEmpty();
    }

    @Test
    void batchLookupResolvesEveryPublicCardInOneRepositoryCall() {
        when(rankedGames.findExactNames(any())).thenReturn(List.of(
                new ExactNameMatch("orbit", game(42, "Orbit")),
                new ExactNameMatch("轨道", game(42, "Orbit"))));

        assertThat(lookup.findByTitles(List.of("Orbit", "轨道")))
                .containsOnlyKeys("Orbit", "轨道")
                .allSatisfy((title, identity) -> assertThat(identity.bggId()).isEqualTo(42));
        verify(rankedGames).findExactNames(any());
    }

    private RankedGame game(int bggId, String name) {
        return new RankedGame(
                bggId, name, 2024, bggId, BigDecimal.valueOf(7.1), BigDecimal.valueOf(7.4),
                1000, false, Map.of(BggGameType.STRATEGY, bggId));
    }
}
