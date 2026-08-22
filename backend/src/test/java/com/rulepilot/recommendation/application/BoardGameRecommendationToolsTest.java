package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolName;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationToolsTest {

    @Test
    void keepsFocusedIdLookupSeparateFromBroadCandidateSearch() {
        List<String> calls = new ArrayList<>();
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                calls.add("search");
                return new CandidateSet(179_737, List.of(game(10)));
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                calls.add("ids:" + bggIds);
                return bggIds.stream().map(BoardGameRecommendationToolsTest::game).toList();
            }

            @Override
            public int gameCount() {
                return 179_737;
            }
        };
        BoardGameRecommendationTools tools = new BoardGameRecommendationTools(catalog, new DisabledResearch());

        var observation = tools.lookupGame(20);

        assertThat(calls).containsExactly("ids:[20]");
        assertThat(observation.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(observation.tool()).isEqualTo(ToolName.LOOKUP_BGG_GAME);
        assertThat(observation.games()).extracting(game -> game.ranking().bggId()).containsExactly(20);
    }

    @Test
    void resolvesAndHydratesTitleHypothesesInOneAgentFacingRead() {
        List<String> calls = new ArrayList<>();
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                throw new AssertionError("broad ranking search must not run");
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                calls.add("names:" + names);
                return names.getFirst().endsWith("One")
                        ? List.of(game(21).ranking())
                        : List.of(game(20).ranking());
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                calls.add("ids:" + bggIds);
                return bggIds.stream().map(BoardGameRecommendationToolsTest::game).toList();
            }

            @Override
            public int gameCount() {
                return 179_737;
            }
        };
        BoardGameRecommendationTools tools = new BoardGameRecommendationTools(catalog, new DisabledResearch());

        var observation = tools.inspectTitles(List.of("Synthetic Twenty", "Synthetic Twenty One"));

        assertThat(calls).containsExactly(
                "names:[Synthetic Twenty]",
                "names:[Synthetic Twenty One]",
                "ids:[20, 21]");
        assertThat(observation.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(observation.tool()).isEqualTo(ToolName.INSPECT_BGG_TITLES);
        assertThat(observation.games()).extracting(value -> value.ranking().bggId()).containsExactly(20, 21);
        assertThat(observation.titleResolutions())
                .extracting(value -> value.correlationId() + ":" + value.bggId())
                .containsExactly("title-1:20", "title-2:21");
    }

    @Test
    void returnsATypedErrorObservationInsteadOfLeakingCatalogFailures() {
        BoardGameRecommendationCatalog failing = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                throw new IllegalStateException("offline");
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                throw new IllegalStateException("offline");
            }

            @Override
            public int gameCount() {
                throw new IllegalStateException("offline");
            }
        };
        BoardGameRecommendationTools tools = new BoardGameRecommendationTools(failing, new DisabledResearch());

        var observation = tools.searchCatalog(BggGameType.ALL, List.of(), 20);

        assertThat(observation.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(observation.code()).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(observation.games()).isEmpty();
    }

    private static Game game(int bggId) {
        return new Game(
                new Ranking(
                        bggId, "Synthetic " + bggId, 2025, bggId, new BigDecimal("8.0"),
                        new BigDecimal("8.2"), 1_000),
                new Details(
                        "Synthetic " + bggId, "", "", 2, 5, 60, new BigDecimal("2.5"),
                        List.of("Science Fiction"), List.of("Cooperative Game"), 45, 60, 10, 10,
                        "5", "3-5", 2, 100, List.of(), List.of(), List.of()));
    }

    private static final class DisabledResearch implements BoardGameRecommendationWebResearch {
        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public Optional<Research> research(Request request) {
            throw new AssertionError("disabled research must not run");
        }
    }
}
