package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationAgentStateTest {

    @Test
    void admitsFreshlyVerifiedEvidenceWhenRestoredMemoryIsAlreadyFull() {
        List<Game> restoredNewestFirst = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(this::game)
                .toList();
        RecommendationAgentState state = new RecommendationAgentState(
                new ConversationRequest(
                        RecommendationProfile.empty(),
                        "换一款新的",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        restoredNewestFirst),
                System.nanoTime(),
                null,
                false,
                3);

        state.addVerified(game(9));

        assertThat(state.verified.keySet()).contains(9).doesNotContain(8).hasSize(8);
        assertThat(state.verifiedForAgent())
                .extracting(value -> value.ranking().bggId())
                .startsWith(9)
                .containsExactly(9, 1, 2, 3, 4, 5, 6, 7);
    }

    private Game game(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Game " + id,
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.2"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Game " + id,
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.5"),
                        List.of("Strategy"),
                        List.of("Hand Management"),
                        45,
                        60,
                        10,
                        10,
                        "3",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
