package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Availability;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolName;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

        var observation = tools.inspectTitleHypotheses(List.of(
                new BoardGameRecommendationTools.TitleHypothesis("discovery-1", "Synthetic Twenty"),
                new BoardGameRecommendationTools.TitleHypothesis("discovery-2", "Synthetic Twenty One")));

        assertThat(calls).containsExactly(
                "names:[Synthetic Twenty]",
                "names:[Synthetic Twenty One]",
                "ids:[20, 21]");
        assertThat(observation.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(observation.tool()).isEqualTo(ToolName.INSPECT_BGG_TITLES);
        assertThat(observation.games()).extracting(value -> value.ranking().bggId()).containsExactly(20, 21);
        assertThat(observation.titleResolutions())
                .extracting(value -> value.correlationId() + ":" + value.bggId())
                .containsExactly("discovery-1:20", "discovery-2:21");
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

        var observation = tools.searchCatalog(
                List.of(BggGameType.ALL), List.of(), List.of(), List.of(), 20);

        assertThat(observation.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(observation.code()).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(observation.games()).isEmpty();
    }

    @Test
    void keepsAnUnavailableTeachingProjectionDistinctFromNoReadyCandidate() {
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        PublicTeachingContinuationCatalog continuations = ignored -> Availability.unavailable();
        BoardGameRecommendationTools tools = new BoardGameRecommendationTools(catalog, research, continuations);

        BoardGameRecommendationTools.CatalogObservation observation =
                tools.lookupReadyTeachingContinuations(List.of(game(20)));

        assertThat(observation.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(observation.code()).isEqualTo("READY_TEACHING_CATALOG_UNAVAILABLE");
        assertThat(observation.code()).isNotEqualTo("NO_READY_PUBLIC_TEACHING");
    }

    @Test
    void preservesExactReadyTeachingFromAPartiallyResolvedProjection() {
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        UUID planId = UUID.randomUUID();
        PublicTeachingContinuationCatalog continuations = ignored -> Availability.partial(Map.of(
                20,
                new PublicTeachingContinuationCatalog.Continuation(20, planId, 4, 12)));
        BoardGameRecommendationTools tools = new BoardGameRecommendationTools(catalog, research, continuations);

        BoardGameRecommendationTools.CatalogObservation observation =
                tools.lookupReadyTeachingContinuations(List.of(game(20), game(21)));

        assertThat(observation.status()).isEqualTo(ToolStatus.PARTIAL);
        assertThat(observation.code()).isEqualTo("READY_TEACHING_CATALOG_PARTIAL");
        assertThat(observation.teachingContinuations()).containsOnlyKeys(20);
        assertThat(observation.teachingContinuations().get(20).teachingPlanId()).isEqualTo(planId);
    }

    @Test
    void joinsTargetTeachingAvailabilityByResolvedBggId() {
        BoardGameRecommendationCatalog catalog = mock(BoardGameRecommendationCatalog.class);
        BoardGameRecommendationWebResearch research = mock(BoardGameRecommendationWebResearch.class);
        AtomicReference<List<PublicTeachingContinuationCatalog.Candidate>> observed = new AtomicReference<>();
        UUID planId = UUID.randomUUID();
        PublicTeachingContinuationCatalog continuations = candidates -> {
            observed.set(candidates);
            return Availability.available(Map.of(
                    20,
                    new PublicTeachingContinuationCatalog.Continuation(20, planId, 4, 12),
                    999,
                    new PublicTeachingContinuationCatalog.Continuation(999, UUID.randomUUID(), 9, 27)));
        };
        BoardGameRecommendationTools tools = new BoardGameRecommendationTools(catalog, research, continuations);

        BoardGameRecommendationTools.CatalogObservation observation =
                tools.lookupReadyTeachingContinuations(List.of(game(20)));

        assertThat(observed.get())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.bggId()).isEqualTo(20);
                    assertThat(candidate.authoritativeTitle()).isEqualTo("Synthetic 20");
                });
        assertThat(observation.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(observation.tool()).isEqualTo(ToolName.LOOKUP_READY_TEACHING_CONTINUATIONS);
        assertThat(observation.teachingContinuations()).containsOnlyKeys(20);
        assertThat(observation.teachingContinuations().get(20).teachingPlanId()).isEqualTo(planId);
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
