package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureConstraint;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureMode;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.FeatureSource;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoardGameRecommendationCandidateAgentTest {

    @Test
    void letsTheModelSearchTheFullNameSnapshotThenLookupOnlyObservedBggIds() {
        BoardGameRecommendationCandidateModel model = new BoardGameRecommendationCandidateModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                return new Turn("", List.of(new ToolCall(
                        "search-1",
                        BoardGameRecommendationCandidateAgent.SEARCH_TOOL,
                        "{\"names\":[\"Kemet\",\"Inis\"]}")));
            }
        };
        var agent = agent(model, new FakeCatalog());
        List<BoardGameRecommendationCandidateAgent.Step> steps = new ArrayList<>();

        var result = agent.discover(areaControl(), profile(), "zh-CN", steps::add);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.actions())
                .containsExactly(
                        "MODEL_SELECT_TOOLS",
                        "SEARCH_BGG_BY_NAME",
                        "LOOKUP_BGG_CANDIDATES");
        assertThat(result.games()).extracting(game -> game.ranking().bggId()).containsExactly(60);
        assertThat(result.games()).allSatisfy(game ->
                assertThat(game.details().mechanics()).contains("Area Control"));
        assertThat(steps).containsExactly(
                BoardGameRecommendationCandidateAgent.Step.MODEL_SELECTING,
                BoardGameRecommendationCandidateAgent.Step.SEARCHING_NAMES,
                BoardGameRecommendationCandidateAgent.Step.LOOKING_UP_DETAILS);
    }

    @Test
    void rejectsAnInventedIdThatWasNotReturnedByTheNameSearchTool() {
        AtomicInteger turns = new AtomicInteger();
        BoardGameRecommendationCandidateModel model = new BoardGameRecommendationCandidateModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                if (turns.getAndIncrement() == 0) {
                    return new Turn("", List.of(new ToolCall(
                            "lookup-1",
                            BoardGameRecommendationCandidateAgent.LOOKUP_TOOL,
                            "{\"bggIds\":[999]}")));
                }
                assertThat(request.messages().getLast().content()).contains("ID_NOT_OBSERVED");
                return new Turn("cannot continue", List.of());
            }
        };

        var result = agent(model, new FakeCatalog()).discover(areaControl(), profile(), "zh-CN");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.actions()).contains("REJECTED_TOOL_CALL");
        assertThat(result.games()).isEmpty();
    }

    @Test
    void completesAfterTheFirstSearchInsteadOfSpendingAnotherModelTurn() {
        AtomicInteger turns = new AtomicInteger();
        BoardGameRecommendationCandidateModel model = new BoardGameRecommendationCandidateModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                int turn = turns.getAndIncrement();
                return new Turn("", List.of(new ToolCall(
                        "search-" + turn,
                        BoardGameRecommendationCandidateAgent.SEARCH_TOOL,
                        "{\"names\":[\"Kemet\",\"Inis\"]}")));
            }
        };

        var result = agent(model, new FakeCatalog()).discover(areaControl(), profile(), "zh-CN");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(turns).hasValue(1);
        assertThat(result.actions()).doesNotContain("REJECTED_TOOL_CALL");
    }

    private BoardGameRecommendationCandidateAgent agent(
            BoardGameRecommendationCandidateModel model,
            BoardGameRecommendationCatalog catalog) {
        BoardGameRecommendationWebResearch noResearch = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }
        };
        return new BoardGameRecommendationCandidateAgent(
                model,
                new BoardGameRecommendationTools(catalog, noResearch),
                new ObjectMapper());
    }

    private RetrievalPlan areaControl() {
        return new RetrievalPlan(
                List.of(BggGameType.STRATEGY),
                List.of(new FeatureConstraint(
                        "Area Control",
                        FeatureMode.REQUIRED,
                        FeatureSource.BGG_METADATA,
                        "区控")),
                true);
    }

    private RecommendationProfile profile() {
        return new RecommendationProfile(4, 120, null, BggGameType.ALL, InteractionPreference.ANY);
    }

    private static final class FakeCatalog implements BoardGameRecommendationCatalog {
        @Override
        public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            throw new AssertionError("native candidate retrieval must not query a ranked prefix");
        }

        @Override
        public List<Ranking> searchByNames(List<String> names) {
            assertThat(names).containsExactly("Kemet", "Inis");
            return List.of(
                    ranking(60, "Kemet"),
                    ranking(61, "Inis"));
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            assertThat(bggIds).containsExactly(60, 61);
            return List.of(new Game(
                    ranking(60, "Kemet"),
                    new Details(
                            "Kemet",
                            "",
                            "",
                            2,
                            5,
                            120,
                            new BigDecimal("3.1"),
                            List.of("Strategy"),
                            List.of("Area Control"),
                            90,
                            120,
                            14,
                            14,
                            "Best with 4 players",
                            "Recommended with 3–5 players",
                            2,
                            100,
                            List.of(),
                            List.of(),
                            List.of())));
        }

        @Override
        public int gameCount() {
            return 179_737;
        }

        private Ranking ranking(int id, String name) {
            return new Ranking(
                    id,
                    name,
                    2025,
                    id,
                    new BigDecimal("8.0"),
                    new BigDecimal("8.2"),
                    10_000);
        }
    }
}
