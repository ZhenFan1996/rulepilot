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
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
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
        assertThat(result.games()).extracting(game -> game.ranking().bggId()).containsExactly(60, 61);
        assertThat(result.games()).allSatisfy(game ->
                assertThat(game.details().mechanics()).contains("Area Control"));
        assertThat(steps).containsExactly(
                BoardGameRecommendationCandidateAgent.Step.MODEL_SELECTING,
                BoardGameRecommendationCandidateAgent.Step.SEARCHING_NAMES,
                BoardGameRecommendationCandidateAgent.Step.LOOKING_UP_DETAILS);
    }

    @Test
    void givesTheAgentConversationContextAndFiltersGamesAlreadyShownToThePlayer() {
        BoardGameRecommendationCandidateModel model = new BoardGameRecommendationCandidateModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                assertThat(request.messages().get(1).content())
                        .contains("想要更强的对抗感", "不想再看上一批", "低随机性", "\"alreadyShownBggIds\":[60]");
                return new Turn("", List.of(new ToolCall(
                        "search-context",
                        BoardGameRecommendationCandidateAgent.SEARCH_TOOL,
                        "{\"names\":[\"Kemet\",\"Inis\"]}")));
            }
        };
        var context = new BoardGameRecommendationCandidateAgent.DiscoveryContext(
                new UserModel("想要更强的对抗感", List.of()),
                List.of(
                        new DialogueMessage("assistant", "上一批有 Kemet。"),
                        new DialogueMessage("user", "不想再看上一批，最好低随机性")),
                List.of(60),
                1);

        var result = agent(model, new FakeCatalog(List.of(61)))
                .discover(areaControl(), profile(), context, "zh-CN", ignored -> {});

        assertThat(result.succeeded()).isTrue();
        assertThat(result.games()).extracting(game -> game.ranking().bggId()).containsExactly(61);
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

    @Test
    void continuesTheAgentLoopWhenObservedGamesFailApplicationHardGates() {
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
                            "search-first",
                            BoardGameRecommendationCandidateAgent.SEARCH_TOOL,
                            "{\"names\":[\"Candidate Alpha\",\"Candidate Beta\"]}")));
                }
                assertThat(request.messages().getLast().content())
                        .contains(
                                "VERIFIED_CANDIDATE_GAP",
                                "\"rejectedByApplicationGates\":1",
                                "\"remaining\":1");
                return new Turn("", List.of(new ToolCall(
                        "search-second",
                        BoardGameRecommendationCandidateAgent.SEARCH_TOOL,
                        "{\"names\":[\"Candidate Gamma\",\"Candidate Delta\"]}")));
            }
        };
        BoardGameRecommendationCatalog catalog = new BoardGameRecommendationCatalog() {
            @Override
            public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
                throw new AssertionError("the Agent must continue its own retrieval loop");
            }

            @Override
            public List<Ranking> searchByNames(List<String> names) {
                return names.stream().map(name -> switch (name) {
                    case "Candidate Alpha" -> ranking(60, name);
                    case "Candidate Beta" -> ranking(61, name);
                    case "Candidate Gamma" -> ranking(62, name);
                    case "Candidate Delta" -> ranking(63, name);
                    default -> throw new AssertionError("unexpected candidate title");
                }).toList();
            }

            @Override
            public List<Game> findGamesByIds(List<Integer> bggIds) {
                return bggIds.stream()
                        .map(id -> candidateGame(id, id == 60 ? 180 : 120))
                        .toList();
            }

            @Override
            public int gameCount() {
                return 1000;
            }
        };

        var result = agent(model, catalog).discover(areaControl(), profile(), "zh-CN");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(4);
        assertThat(result.games()).extracting(game -> game.ranking().bggId())
                .containsExactly(60, 61, 62, 63);
    }

    @Test
    void letsTheAgentChooseSourceGroundedPublicDiscoveryInsteadOfGuessingTitles() {
        BoardGameRecommendationCandidateModel model = new BoardGameRecommendationCandidateModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                assertThat(request.tools()).extracting(tool -> tool.name())
                        .contains(BoardGameRecommendationCandidateAgent.DISCOVER_TOOL);
                return new Turn("", List.of(new ToolCall(
                        "discover-public",
                        BoardGameRecommendationCandidateAgent.DISCOVER_TOOL,
                        "{}")));
            }
        };
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                assertThat(request.signals()).singleElement().satisfies(signal ->
                        assertThat(signal.term()).isEqualTo("Area Control"));
                return Optional.of(new CandidateDiscovery(
                        List.of(
                                new CandidateLead(60, "Kemet", "source fit", List.of(1)),
                                new CandidateLead(61, "Inis", "source fit", List.of(1))),
                        List.of(new Source(1, "Public guide", "https://example.test/guide", "example.test"))));
            }

            @Override
            public Optional<Research> research(BoardGameRecommendationWebResearch.Request request) {
                return Optional.empty();
            }
        };

        var result = agent(model, new FakeCatalog(), research)
                .discover(areaControl(), profile(), "zh-CN");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.actions())
                .containsExactly("MODEL_SELECT_TOOLS", "DISCOVER_CANDIDATES", "LOOKUP_BGG_CANDIDATES");
        assertThat(result.games()).extracting(game -> game.ranking().bggId()).containsExactly(60, 61);
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
        return agent(model, catalog, noResearch);
    }

    private BoardGameRecommendationCandidateAgent agent(
            BoardGameRecommendationCandidateModel model,
            BoardGameRecommendationCatalog catalog,
            BoardGameRecommendationWebResearch research) {
        return new BoardGameRecommendationCandidateAgent(
                model,
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(
                        new BoardGameRecommendationProperties(8, 2, new BigDecimal("0.66"))),
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

    private static Game candidateGame(int id, int maximumMinutes) {
        String name = "Candidate " + id;
        return new Game(
                ranking(id, name),
                new Details(
                        name,
                        "",
                        "",
                        2,
                        5,
                        maximumMinutes,
                        new BigDecimal("3.1"),
                        List.of("Strategy"),
                        List.of("Area Control"),
                        90,
                        maximumMinutes,
                        14,
                        14,
                        "Best with 4 players",
                        "Recommended with 3–5 players",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private static Ranking ranking(int id, String name) {
        return new Ranking(
                id,
                name,
                2025,
                id,
                new BigDecimal("8.0"),
                new BigDecimal("8.2"),
                10_000);
    }

    private static final class FakeCatalog implements BoardGameRecommendationCatalog {
        private final List<Integer> expectedLookupIds;

        private FakeCatalog() {
            this(List.of(60, 61));
        }

        private FakeCatalog(List<Integer> expectedLookupIds) {
            this.expectedLookupIds = List.copyOf(expectedLookupIds);
        }

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
            assertThat(bggIds).containsExactlyElementsOf(expectedLookupIds);
            return expectedLookupIds.stream().map(id -> new Game(
                    ranking(id, id == 60 ? "Kemet" : "Inis"),
                    new Details(
                            id == 60 ? "Kemet" : "Inis",
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
                            List.of())))
                    .toList();
        }

        @Override
        public int gameCount() {
            return 179_737;
        }

    }
}
