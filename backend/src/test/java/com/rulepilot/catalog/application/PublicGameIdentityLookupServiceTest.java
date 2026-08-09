package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
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
    void batchLookupSkipsInvalidOrUnavailableOptionalMetadata() {
        when(rankedGames.find(any())).thenThrow(new IllegalStateException("snapshot unavailable"));

        assertThat(lookup.findByTitles(List.of("Orbit", "x"))).isEmpty();
    }

    private RankedGame game(int bggId, String name) {
        return new RankedGame(
                bggId, name, 2024, bggId, BigDecimal.valueOf(7.1), BigDecimal.valueOf(7.4),
                1000, false, Map.of(BggGameType.STRATEGY, bggId));
    }
}
