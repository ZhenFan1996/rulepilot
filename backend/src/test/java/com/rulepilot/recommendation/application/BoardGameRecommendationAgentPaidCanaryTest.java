package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.adapter.out.model.SpringAiBoardGameRecommendationModel;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.KnownGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * One-provider paid canaries for the recommendation critical path. They deliberately stay
 * outside normal CI and stores raw action arguments only under ignored {@code .local/}.
 */
@Tag("paid-recommendation-canary")
class BoardGameRecommendationAgentPaidCanaryTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesDirectBoundsAcrossTheProductionTwoTurnJourney() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        Capture capture = new Capture(provider, environment(prefix + "_MODEL", null));
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                environment(prefix + "_MODEL", null),
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);

        List<Map<String, Object>> visibleTurns = new ArrayList<>();
        try {
            String openingPrompt = "嗨，今晚五个人聚会，最近合作玩得有点腻，但我还没想清楚换什么方向。你会先怎么帮我挑？";
            long openingStarted = System.nanoTime();
            var opening = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), openingPrompt),
                    "zh-CN");
            visibleTurns.add(visible("production-opening", opening, elapsed(openingStarted)));
            assertThat(opening.harness().fallbackUsed()).isFalse();

            String recommendationPrompt = "我想换成能谈判、互相骗一骗的；有两个新手，90 分钟内。你直接挑三款吧。";
            List<DialogueMessage> transcript = List.of(
                    new DialogueMessage("user", openingPrompt),
                    new DialogueMessage("assistant", opening.assistantMessage()),
                    new DialogueMessage("user", recommendationPrompt));
            List<KnownGame> known = opening.games().stream()
                    .map(entry -> new KnownGame(
                            entry.game().ranking().bggId(),
                            entry.game().details().name(),
                            entry.game().ranking().sourceName()))
                    .toList();
            List<Integer> shown = opening.games().stream()
                    .map(entry -> entry.game().ranking().bggId())
                    .toList();
            long recommendationStarted = System.nanoTime();
            var recommendation = agent.converse(
                    new ConversationRequest(
                            opening.profile(),
                            recommendationPrompt,
                            List.of(),
                            transcript,
                            null,
                            known,
                            shown),
                    "zh-CN");
            visibleTurns.add(visible(
                    "production-explicit-three",
                    recommendation,
                    elapsed(recommendationStarted)));

            assertThat(recommendation.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(recommendation.profile().playerCount().minimum()).isEqualTo(5);
            assertThat(recommendation.profile().playerCount().maximum()).isEqualTo(5);
            assertThat(recommendation.profile().durationMinutes().minimum()).isNull();
            assertThat(recommendation.profile().durationMinutes().maximum()).isEqualTo(90);
            assertThat(recommendation.games()).hasSize(3).allSatisfy(entry -> {
                assertThat(entry.game().details().minPlayers()).isLessThanOrEqualTo(5);
                assertThat(entry.game().details().maxPlayers()).isGreaterThanOrEqualTo(5);
                assertThat(entry.game().details().maximumPlayTimeMinutes()).isLessThanOrEqualTo(90);
                assertThat(entry.reasons().getFirst().kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.PREFERENCE_INFERENCE);
                assertThat(entry.tradeoffs()).isNotEmpty();
            });
            assertThat(recommendation.harness().actions()).contains("RECOMMEND_GAMES");
            assertThat(recommendation.harness().fallbackUsed()).isFalse();
            assertThat(recommendation.harness().modelCalls()).isLessThanOrEqualTo(3);

            writeArtifact(capture, visibleTurns, null);
        } catch (Throwable failure) {
            writeArtifact(capture, visibleTurns, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void honorsAnExplicitResultCountWithGroundedNaturalCards() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        Capture capture = new Capture(provider, environment(prefix + "_MODEL", null));
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                environment(prefix + "_MODEL", null),
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);

        List<Map<String, Object>> visibleTurns = new ArrayList<>();
        try {
            String request = "我们 3 到 4 个人，想找 30 到 60 分钟、复杂度不超过 3.0 的桌游。请给我三款，并具体说清适合点和代价。";
            long started = System.nanoTime();
            var response = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), request),
                    "zh-CN");
            visibleTurns.add(visible("explicit-count-opening", response, elapsed(started)));

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.games()).hasSize(3).allSatisfy(entry -> {
                Details details = entry.game().details();
                assertThat(details.minPlayers()).isLessThanOrEqualTo(3);
                assertThat(details.maxPlayers()).isGreaterThanOrEqualTo(4);
                assertThat(details.minimumPlayTimeMinutes()).isGreaterThanOrEqualTo(30);
                assertThat(details.maximumPlayTimeMinutes()).isLessThanOrEqualTo(60);
                assertThat(details.averageWeight()).isLessThanOrEqualTo(new BigDecimal("3.0"));
                assertThat(entry.reasons().getFirst().kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.PREFERENCE_INFERENCE);
                assertThat(entry.reasons().getFirst().text()).hasSizeGreaterThanOrEqualTo(20);
                assertThat(entry.tradeoffs()).isNotEmpty();
            });
            assertThat(response.harness().actions()).contains("SEARCH_BGG_CATALOG", "RECOMMEND_GAMES");
            assertThat(response.harness().fallbackUsed()).isFalse();
            assertThat(response.harness().modelCalls()).isLessThanOrEqualTo(3);

            writeArtifact(capture, visibleTurns, null);
        } catch (Throwable failure) {
            writeArtifact(capture, visibleTurns, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void keepsHardGatesAndNaturalTradeoffsAcrossOneCorrection() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        Capture capture = new Capture(provider, environment(prefix + "_MODEL", null));
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                environment(prefix + "_MODEL", null),
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);

        List<Map<String, Object>> visibleTurns = new ArrayList<>();
        try {
            String opening = "我们 3 到 4 个人，想找 30 到 60 分钟、复杂度不超过 3.0 的桌游。请给我三款，并具体说清适合点和代价。";
            long firstStarted = System.nanoTime();
            var first = agent.converse(new ConversationRequest(RecommendationProfile.empty(), opening), "zh-CN");
            long firstLatency = elapsed(firstStarted);
            visibleTurns.add(visible("opening", first, firstLatency));

            assertThat(first.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(first.profile().playerCount()).isNotNull();
            assertThat(first.profile().playerCount().minimum()).isEqualTo(3);
            assertThat(first.profile().playerCount().maximum()).isEqualTo(4);
            assertThat(first.profile().durationMinutes()).isNotNull();
            assertThat(first.profile().durationMinutes().minimum()).isEqualTo(30);
            assertThat(first.profile().durationMinutes().maximum()).isEqualTo(60);
            assertThat(first.profile().complexity()).isNotNull();
            assertThat(first.profile().complexity().maximum()).isEqualByComparingTo("3.0");
            assertThat(first.assistantMessage().codePointCount(0, first.assistantMessage().length()))
                    .as("opening should explain how the candidates differ, not collapse to a generic connective")
                    .isGreaterThanOrEqualTo(60);
            assertThat(first.games()).hasSize(3).allSatisfy(entry -> {
                Details details = entry.game().details();
                assertThat(details.minPlayers()).isLessThanOrEqualTo(3);
                assertThat(details.maxPlayers()).isGreaterThanOrEqualTo(4);
                assertThat(details.minimumPlayTimeMinutes()).isGreaterThanOrEqualTo(30);
                assertThat(details.maximumPlayTimeMinutes()).isLessThanOrEqualTo(60);
                assertThat(details.averageWeight()).isLessThanOrEqualTo(new BigDecimal("3.0"));
                assertThat(entry.reasons()).isNotEmpty();
                assertThat(entry.reasons().getFirst().kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.PREFERENCE_INFERENCE);
                assertThat(entry.reasons().getFirst().text().codePointCount(
                                0, entry.reasons().getFirst().text().length()))
                        .isGreaterThanOrEqualTo(20);
                assertThat(entry.tradeoffs())
                        .as("the player explicitly asked for a concrete cost or choice boundary per candidate")
                        .isNotEmpty();
            });

            String followup = "周末临时缩短了，最多 45 分钟。保留其他条件，重新给我两款，别只列标签，直接讲怎么选。";
            List<DialogueMessage> transcript = List.of(
                    new DialogueMessage("user", opening),
                    new DialogueMessage("assistant", first.assistantMessage()),
                    new DialogueMessage("user", followup));
            List<KnownGame> known = first.games().stream()
                    .map(entry -> new KnownGame(
                            entry.game().ranking().bggId(),
                            entry.game().details().name(),
                            entry.game().ranking().sourceName()))
                    .toList();
            List<Integer> shown = first.games().stream()
                    .map(entry -> entry.game().ranking().bggId())
                    .toList();
            long secondStarted = System.nanoTime();
            var second = agent.converse(
                    new ConversationRequest(
                            first.profile(), followup, List.of(), transcript, null, known, shown),
                    "zh-CN");
            long secondLatency = elapsed(secondStarted);
            visibleTurns.add(visible("correction", second, secondLatency));

            assertThat(second.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(second.profile().playerCount().minimum()).isEqualTo(3);
            assertThat(second.profile().playerCount().maximum()).isEqualTo(4);
            assertThat(second.profile().durationMinutes().minimum()).isEqualTo(30);
            assertThat(second.profile().durationMinutes().maximum()).isEqualTo(45);
            assertThat(second.profile().complexity().maximum()).isEqualByComparingTo("3.0");
            assertThat(second.assistantMessage().codePointCount(0, second.assistantMessage().length()))
                    .as("the follow-up should naturally explain how to choose")
                    .isGreaterThanOrEqualTo(40);
            assertThat(second.games()).hasSize(2).allSatisfy(entry -> {
                Details details = entry.game().details();
                assertThat(details.minimumPlayTimeMinutes()).isGreaterThanOrEqualTo(30);
                assertThat(details.maximumPlayTimeMinutes()).isLessThanOrEqualTo(45);
                assertThat(details.averageWeight()).isLessThanOrEqualTo(new BigDecimal("3.0"));
                assertThat(entry.reasons()).isNotEmpty();
                assertThat(entry.reasons().getFirst().kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.PREFERENCE_INFERENCE);
                assertThat(entry.tradeoffs()).isNotEmpty();
            });

            String comparisonFollowup = "把刚才这两款按时长、复杂度和机制放在一起比较，直接告诉我怎么选；资料不知道的地方就局部标出来。";
            List<DialogueMessage> comparisonTranscript = new ArrayList<>(transcript);
            comparisonTranscript.add(new DialogueMessage("assistant", second.assistantMessage()));
            comparisonTranscript.add(new DialogueMessage("user", comparisonFollowup));
            List<KnownGame> comparisonKnown = second.games().stream()
                    .map(entry -> new KnownGame(
                            entry.game().ranking().bggId(),
                            entry.game().details().name(),
                            entry.game().ranking().sourceName()))
                    .toList();
            List<Integer> comparisonShown = second.games().stream()
                    .map(entry -> entry.game().ranking().bggId())
                    .toList();
            long comparisonStarted = System.nanoTime();
            var comparison = agent.converse(
                    new ConversationRequest(
                            second.profile(),
                            comparisonFollowup,
                            List.of(),
                            comparisonTranscript,
                            null,
                            comparisonKnown,
                            comparisonShown),
                    "zh-CN");
            long comparisonLatency = elapsed(comparisonStarted);
            visibleTurns.add(visible("comparison-followup", comparison, comparisonLatency));

            assertThat(comparison.outcome()).isEqualTo(Outcome.CONVERSATION);
            assertThat(comparison.comparison()).isNotNull();
            assertThat(comparison.comparison().candidates()).hasSize(2);
            assertThat(comparison.comparison().axes())
                    .extracting(BoardGameRecommendationAgent.ComparisonAxis::subject)
                    .contains("durationMinutes", "complexity", "mechanics");
            assertThat(comparison.assistantMessage().codePointCount(
                            0, comparison.assistantMessage().length()))
                    .as("the comparison must end with a concrete, natural choice boundary")
                    .isGreaterThanOrEqualTo(50);
            assertThat(comparison.harness().modelCalls()).isLessThanOrEqualTo(2);
            assertThat(comparison.harness().fallbackUsed()).isFalse();

            String openEnded = "今晚想随便挑两款方向不同的桌游，请具体讲为什么值得先看、各自要注意什么，并告诉我怎么选。";
            long openEndedStarted = System.nanoTime();
            var open = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), openEnded),
                    "zh-CN");
            long openEndedLatency = elapsed(openEndedStarted);
            visibleTurns.add(visible("open-ended", open, openEndedLatency));

            assertThat(open.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(open.assistantMessage().codePointCount(0, open.assistantMessage().length()))
                    .as("an open request still needs a concrete decision-oriented synthesis")
                    .isGreaterThanOrEqualTo(60);
            assertThat(open.games()).hasSize(2).allSatisfy(entry -> {
                assertThat(entry.reasons()).isNotEmpty();
                assertThat(entry.reasons().getFirst().kind())
                        .isEqualTo(BoardGameRecommendationAgent.ReasonKind.PREFERENCE_INFERENCE);
                assertThat(entry.reasons().getFirst().text().codePointCount(
                                0, entry.reasons().getFirst().text().length()))
                        .isGreaterThanOrEqualTo(20);
                assertThat(entry.tradeoffs()).isNotEmpty();
            });
            assertThat(open.harness().modelCalls()).isLessThanOrEqualTo(3);
            assertThat(open.harness().fallbackUsed()).isFalse();

            writeArtifact(capture, visibleTurns, null);
        } catch (Throwable failure) {
            writeArtifact(capture, visibleTurns, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void preservesAStructuredObservedComparisonDecisionWithoutFlatteningTheNaturalAnswer() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        Capture capture = new Capture(provider, environment(prefix + "_MODEL", null));
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                environment(prefix + "_MODEL", null),
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);

        List<Map<String, Object>> visibleTurns = new ArrayList<>();
        try {
            String requestText = "比较 River Market 和 Harbor Chorus 的时长、复杂度与机制，并直接告诉我怎么选。自然分析可以说明机制标签不能证明哪些体验；最后只给一个选择规则，只按已核对的复杂度数值决定，不要再给基于机制或桌感的另一种选择。";
            long started = System.nanoTime();
            var response = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            requestText,
                            List.of(),
                            List.of(new DialogueMessage("user", requestText)),
                            null,
                            List.of(
                                    new KnownGame(101, "River Market", "River Market"),
                                    new KnownGame(105, "Harbor Chorus", "Harbor Chorus")),
                            List.of(101, 105)),
                    "zh-CN");
            visibleTurns.add(visible("comparison-only", response, elapsed(started)));

            assertThat(response.outcome()).isEqualTo(Outcome.CONVERSATION);
            assertThat(response.comparison()).isNotNull();
            assertThat(response.comparison().candidates()).hasSize(2);
            assertThat(response.comparison().axes())
                    .extracting(BoardGameRecommendationAgent.ComparisonAxis::subject)
                    .contains("durationMinutes", "complexity", "mechanics");
            assertThat(response.harness().modelCalls())
                    .as("one evidence-local self-repair is allowed; repeated review or fallback is not")
                    .isBetween(1, 2);
            assertThat(response.harness().actions())
                    .doesNotContain("REJECTED_REPEATED_ACTION", "REPLY_TO_USER");
            assertThat(response.harness().fallbackUsed()).isFalse();

            JsonNode rawAction = json.readTree(capture.lastArguments(BoardGameRecommendationAgent.COMPARE_TOOL));
            assertThat(rawAction.path("decisionMode").asText()).isEqualTo("OBSERVED_ONLY");
            assertThat(rawAction.path("decisionEvidenceIds").isArray()).isTrue();
            assertThat(rawAction.path("decisionEvidenceIds").size()).isGreaterThan(0);
            assertThat(rawAction.path("candidateBggIds").size()).isEqualTo(2);
            assertThat(rawAction.path("subjects").size()).isEqualTo(3);

            String rawMessage = rawAction.path("message").asText();
            String rawDecision = rawAction.path("decision").asText();
            String visible = response.assistantMessage();
            assertThat(visible)
                    .as("validated free prose and the evidence-bound decision must reach the player verbatim")
                    .isEqualTo(rawMessage + " " + rawDecision);
            assertThat(visible.codePointCount(0, visible.length()))
                    .as("the evidence boundary must not flatten the comparison into a generic sentence")
                    .isGreaterThanOrEqualTo(70);

            writeArtifact(capture, visibleTurns, null);
        } catch (Throwable failure) {
            writeArtifact(capture, visibleTurns, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    @Test
    void keepsImaginativePreferencesSoftAndAppliesOnlyExplicitMidConversationCorrections() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_RECOMMENDATION_PAID_CANARY")));
        String provider = environment("RULEPILOT_RECOMMENDATION_CANARY_PROVIDER", "qwen")
                .toLowerCase(Locale.ROOT);
        String prefix = provider.toUpperCase(Locale.ROOT);
        Capture capture = new Capture(provider, environment(prefix + "_MODEL", null));
        BoardGameRecommendationModel model = model(
                provider,
                environment(prefix + "_API_KEY", null),
                environment(prefix + "_BASE_URL", null),
                environment(prefix + "_MODEL", null),
                capture);
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
        var agent = new BoardGameRecommendationAgent(
                model,
                new BoardGameRecommendationTools(new CanaryCatalog(), noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);

        List<Map<String, Object>> visibleTurns = new ArrayList<>();
        try {
            String metaphor = "今晚想找一种像暴雨天围着壁炉讲秘密的感觉。别把这个比喻硬翻成人数、时长、难度、类型或交互模式；目录事实不能证明桌感时，可以诚实限定，也可以问一个答案真会改变候选集的问题。给我两个值得看的方向，并具体分清已知与未知。";
            long metaphorStarted = System.nanoTime();
            var metaphorResponse = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), metaphor),
                    "zh-CN");
            visibleTurns.add(visible("metaphor-implicit-preference", metaphorResponse, elapsed(metaphorStarted)));

            assertThat(metaphorResponse.outcome()).isEqualTo(Outcome.NEEDS_CLARIFICATION);
            assertEmptyTypedProfile(metaphorResponse.profile());
            assertThat(metaphorResponse.assistantMessage().codePointCount(
                            0, metaphorResponse.assistantMessage().length()))
                    .isGreaterThanOrEqualTo(20);
            assertThat(metaphorResponse.harness().fallbackUsed()).isFalse();
            assertThat(metaphorResponse.harness().modelCalls()).isLessThanOrEqualTo(2);
            assertThat(metaphorResponse.harness().actions())
                    .doesNotContain("REJECTED_REPEATED_ACTION");
            ToolCall rawClarification = capture.lastToolCall();
            assertClarificationPreserved(rawClarification, metaphorResponse);
            assertThat(metaphorResponse.harness().actions())
                    .as("a genuine clarification must not mask a rejected action or retrieval failure")
                    .allMatch(action -> !action.startsWith("REJECTED_")
                            && !action.startsWith("WEB_RESEARCH_DEGRADED:"));

            String selectedDirection = metaphorResponse.clarification().options().getFirst().value();
            String clarificationAnswer = selectedDirection
                    + "。我们 3 个人、最多 45 分钟；这个选项只是在帮你分方向，不是合作/对抗、类型或复杂度硬条件。现在请给我两款，不能证明的桌感仍保留为未知。";
            List<DialogueMessage> clarificationTranscript = List.of(
                    new DialogueMessage("user", metaphor),
                    new DialogueMessage("assistant", metaphorResponse.assistantMessage()),
                    new DialogueMessage("user", clarificationAnswer));
            long clarificationAnswerStarted = System.nanoTime();
            var afterClarification = agent.converse(
                    new ConversationRequest(
                            metaphorResponse.profile(),
                            clarificationAnswer,
                            List.of(),
                            clarificationTranscript,
                            null,
                            List.of(),
                            List.of()),
                    "zh-CN");
            visibleTurns.add(visible(
                    "answer-to-high-information-clarification",
                    afterClarification,
                    elapsed(clarificationAnswerStarted)));

            assertThat(afterClarification.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(afterClarification.profile().playerCount().minimum()).isEqualTo(3);
            assertThat(afterClarification.profile().playerCount().maximum()).isEqualTo(3);
            assertThat(afterClarification.profile().durationMinutes().minimum()).isNull();
            assertThat(afterClarification.profile().durationMinutes().maximum()).isEqualTo(45);
            assertThat(afterClarification.profile().complexity()).isNull();
            assertThat(afterClarification.profile().type()).isEqualTo(BggGameType.ALL);
            assertThat(afterClarification.profile().interaction())
                    .isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
            assertThat(afterClarification.games()).hasSize(2).allSatisfy(entry -> {
                assertThat(entry.game().details().minPlayers()).isLessThanOrEqualTo(3);
                assertThat(entry.game().details().maxPlayers()).isGreaterThanOrEqualTo(3);
                assertThat(entry.game().details().maximumPlayTimeMinutes()).isLessThanOrEqualTo(45);
            });
            assertThat(afterClarification.harness().fallbackUsed()).isFalse();
            assertThat(afterClarification.harness().modelCalls()).isLessThanOrEqualTo(3);
            assertTerminalProsePreserved(capture.lastToolCall(), afterClarification);
            assertRecommendationNarrativesPreserved(capture.lastToolCall(), afterClarification);
            assertNoPreferenceLinks(capture.lastToolCall());

            String mixedOpening = "我们原本 4 个人，最多 75 分钟。想要前半段各自埋线、最后全桌突然倒吸一口气的感觉，但这只是愿望，不是合作/对抗、类型或复杂度硬条件。先给两个方向；不能从目录事实证明的桌感就保留为未知或明确是推测。";
            long mixedOpeningStarted = System.nanoTime();
            var mixedFirst = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), mixedOpening),
                    "zh-CN");
            visibleTurns.add(visible("mixed-hard-and-soft-opening", mixedFirst, elapsed(mixedOpeningStarted)));

            assertThat(mixedFirst.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(mixedFirst.profile().playerCount()).isNotNull();
            assertThat(mixedFirst.profile().playerCount().minimum()).isEqualTo(4);
            assertThat(mixedFirst.profile().playerCount().maximum()).isEqualTo(4);
            assertThat(mixedFirst.profile().durationMinutes()).isNotNull();
            assertThat(mixedFirst.profile().durationMinutes().minimum()).isNull();
            assertThat(mixedFirst.profile().durationMinutes().maximum()).isEqualTo(75);
            assertThat(mixedFirst.profile().complexity()).isNull();
            assertThat(mixedFirst.profile().type()).isEqualTo(BggGameType.ALL);
            assertThat(mixedFirst.profile().interaction())
                    .isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
            assertThat(mixedFirst.games()).hasSize(2).allSatisfy(entry -> {
                assertThat(entry.game().details().minPlayers()).isLessThanOrEqualTo(4);
                assertThat(entry.game().details().maxPlayers()).isGreaterThanOrEqualTo(4);
                assertThat(entry.game().details().maximumPlayTimeMinutes()).isLessThanOrEqualTo(75);
            });
            assertThat(mixedFirst.harness().fallbackUsed()).isFalse();
            assertThat(mixedFirst.harness().modelCalls()).isLessThanOrEqualTo(3);
            assertTerminalProsePreserved(capture.lastToolCall(), mixedFirst);
            assertRecommendationNarrativesPreserved(capture.lastToolCall(), mixedFirst);
            assertNoPreferenceLinks(capture.lastToolCall());

            String correction = "等等，临时有人先走：改成 3 个人、45 分钟以内。刚才那种戏剧性仍只是愿望，不要把它存成合作/对抗、类型或复杂度硬条件。重新给我两款；无法证明的桌感请继续局部标成未知或有边界的推测。";
            List<DialogueMessage> correctionTranscript = List.of(
                    new DialogueMessage("user", mixedOpening),
                    new DialogueMessage("assistant", mixedFirst.assistantMessage()),
                    new DialogueMessage("user", correction));
            List<KnownGame> correctionKnown = mixedFirst.games().stream()
                    .map(entry -> new KnownGame(
                            entry.game().ranking().bggId(),
                            entry.game().details().name(),
                            entry.game().ranking().sourceName()))
                    .toList();
            List<Integer> correctionShown = mixedFirst.games().stream()
                    .map(entry -> entry.game().ranking().bggId())
                    .toList();
            long correctionStarted = System.nanoTime();
            var corrected = agent.converse(
                    new ConversationRequest(
                            mixedFirst.profile(),
                            correction,
                            List.of(),
                            correctionTranscript,
                            null,
                            correctionKnown,
                            correctionShown),
                    "zh-CN");
            visibleTurns.add(visible("explicit-mid-conversation-correction", corrected, elapsed(correctionStarted)));

            assertThat(corrected.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(corrected.profile().playerCount().minimum()).isEqualTo(3);
            assertThat(corrected.profile().playerCount().maximum()).isEqualTo(3);
            assertThat(corrected.profile().durationMinutes().minimum()).isNull();
            assertThat(corrected.profile().durationMinutes().maximum()).isEqualTo(45);
            assertThat(corrected.profile().complexity()).isNull();
            assertThat(corrected.profile().type()).isEqualTo(BggGameType.ALL);
            assertThat(corrected.profile().interaction())
                    .isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
            assertThat(corrected.games()).hasSize(2).allSatisfy(entry -> {
                assertThat(entry.game().details().minPlayers()).isLessThanOrEqualTo(3);
                assertThat(entry.game().details().maxPlayers()).isGreaterThanOrEqualTo(3);
                assertThat(entry.game().details().maximumPlayTimeMinutes()).isLessThanOrEqualTo(45);
                assertThat(entry.reasons()).isNotEmpty();
                assertThat(entry.tradeoffs()).isNotEmpty();
            });
            assertThat(corrected.harness().fallbackUsed()).isFalse();
            assertThat(corrected.harness().modelCalls()).isLessThanOrEqualTo(3);
            assertTerminalProsePreserved(capture.lastToolCall(), corrected);
            assertRecommendationNarrativesPreserved(capture.lastToolCall(), corrected);
            assertNoPreferenceLinks(capture.lastToolCall());

            writeArtifact(capture, visibleTurns, null);
        } catch (Throwable failure) {
            writeArtifact(capture, visibleTurns, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private void assertEmptyTypedProfile(RecommendationProfile profile) {
        assertThat(profile.playerCount()).isNull();
        assertThat(profile.durationMinutes()).isNull();
        assertThat(profile.complexity()).isNull();
        assertThat(profile.type()).isEqualTo(BggGameType.ALL);
        assertThat(profile.interaction()).isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
    }

    private void assertTerminalProsePreserved(
            ToolCall call,
            BoardGameRecommendationAgent.ConversationResponse response) throws Exception {
        JsonNode arguments = json.readTree(call.argumentsJson());
        String field = BoardGameRecommendationAgent.ASK_TOOL.equals(call.name()) ? "question" : "message";
        assertThat(response.assistantMessage())
                .as("validated terminal prose may only lose leading or trailing whitespace")
                .isEqualTo(arguments.path(field).asText().strip());
    }

    private void assertClarificationPreserved(
            ToolCall call,
            BoardGameRecommendationAgent.ConversationResponse response) throws Exception {
        assertThat(call.name()).isEqualTo(BoardGameRecommendationAgent.ASK_TOOL);
        JsonNode arguments = json.readTree(call.argumentsJson());
        String rawQuestion = arguments.path("question").asText().strip();
        assertThat(response.assistantMessage()).isEqualTo(rawQuestion);
        assertThat(response.clarification()).isNotNull();
        assertThat(response.clarification().prompt()).isEqualTo(rawQuestion);
        assertThat(rawQuestion.codePoints().filter(value -> value == '?' || value == '？').count())
                .isEqualTo(1);
        assertThat(arguments.path("options").isArray()).isTrue();
        assertThat(arguments.path("options").size()).isBetween(2, 3);
        List<String> rawOptions = new ArrayList<>();
        arguments.path("options").forEach(option -> rawOptions.add(option.asText().strip()));
        assertThat(response.clarification().options())
                .extracting(BoardGameRecommendationAgent.ClarificationOption::value)
                .containsExactlyElementsOf(rawOptions);
        assertThat(response.clarification().options())
                .allSatisfy(option -> assertThat(option.label()).isEqualTo(option.value()));
    }

    private void assertRecommendationNarrativesPreserved(
            ToolCall call,
            BoardGameRecommendationAgent.ConversationResponse response) throws Exception {
        assertThat(call.name()).isEqualTo(BoardGameRecommendationAgent.RECOMMEND_TOOL);
        JsonNode selections = json.readTree(call.argumentsJson()).path("selections");
        for (JsonNode selection : selections) {
            assertThat(selection.path("narrativeMode").asText())
                    .isEqualTo("OBSERVED_ONLY");
            int bggId = selection.path("bggId").asInt();
            var visible = response.games().stream()
                    .filter(entry -> entry.game().ranking().bggId() == bggId)
                    .findFirst()
                    .orElseThrow();
            if (selection.has("why")) {
                assertThat(visible.reasons())
                        .extracting(BoardGameRecommendationAgent.RecommendationReason::text)
                        .contains(selection.path("why").asText().strip());
            }
            if (selection.has("tradeoff")) {
                assertThat(visible.tradeoffs()).contains(selection.path("tradeoff").asText().strip());
            }
        }
    }

    private void assertNoPreferenceLinks(ToolCall call) throws Exception {
        JsonNode selections = json.readTree(call.argumentsJson()).path("selections");
        for (JsonNode selection : selections) {
            assertThat(selection.has("preferenceLink"))
                    .as("numeric constraints and explicitly unverified table-feel wishes cannot ground taxonomy links")
                    .isFalse();
        }
    }

    private BoardGameRecommendationModel model(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            Capture capture) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(30))
                .create(provider, apiKey, baseUrl, modelName);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider);
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(modelName);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek".equals(provider));
        BoardGameRecommendationModel delegate = new SpringAiBoardGameRecommendationModel(configuration, 0.2);
        return new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Turn next(Request request) {
                long started = System.nanoTime();
                Turn result = delegate.next(request);
                capture.add("react", result, elapsed(started));
                return result;
            }
        };
    }

    private BoardGameRecommendationWebResearch noResearch() {
        return new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public Optional<Research> research(Request request) {
                return Optional.empty();
            }
        };
    }

    private Map<String, Object> visible(
            String turn,
            BoardGameRecommendationAgent.ConversationResponse response,
            long latencyMs) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("turn", turn);
        value.put("outcome", response.outcome().name());
        value.put("latencyMs", latencyMs);
        value.put("assistantMessage", response.assistantMessage());
        value.put("assistantMessageCharacters", response.assistantMessage().codePointCount(0, response.assistantMessage().length()));
        value.put("profile", response.profile());
        value.put("modelCalls", response.harness().modelCalls());
        value.put("catalogCalls", response.harness().catalogCalls());
        value.put("actions", response.harness().actions());
        if (response.clarification() != null) {
            value.put("clarification", Map.of(
                    "prompt", response.clarification().prompt(),
                    "options", response.clarification().options().stream()
                            .map(option -> Map.of("value", option.value(), "label", option.label()))
                            .toList()));
        }
        value.put("games", response.games().stream()
                .map(entry -> Map.of(
                        "bggId", entry.game().ranking().bggId(),
                        "name", entry.game().ranking().sourceName(),
                        "reasons", entry.reasons().stream().map(reason -> reason.text()).toList(),
                        "tradeoffs", entry.tradeoffs()))
                .toList());
        if (response.comparison() != null) {
            value.put("comparison", Map.of(
                    "candidateBggIds", response.comparison().candidates().stream()
                            .map(candidate -> candidate.game().ranking().bggId())
                            .toList(),
                    "axes", response.comparison().axes().stream()
                            .map(axis -> Map.of(
                                    "subject", axis.subject(),
                                    "cells", axis.cells().stream()
                                            .map(cell -> Map.of(
                                                    "bggId", cell.bggId(),
                                                    "known", cell.known(),
                                                    "value", cell.known()
                                                            ? cell.observation().value()
                                                            : ""))
                                            .toList()))
                            .toList()));
        }
        return Map.copyOf(value);
    }

    private void writeArtifact(Capture capture, List<Map<String, Object>> visibleTurns, String failure) throws Exception {
        String label = environment("RULEPILOT_RECOMMENDATION_CANARY_LABEL", "current")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        Path output = Path.of(System.getProperty("user.dir"))
                .getParent()
                .resolve(".local/agent-evaluation/recommendation-paid-canary-" + label + ".json");
        Files.createDirectories(output.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("provider", capture.provider);
        report.put("model", capture.model);
        report.put("rawModelCalls", capture.calls);
        report.put("visibleTurns", List.copyOf(visibleTurns));
        report.put("failure", failure == null ? "" : failure);
        Files.writeString(
                output,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            assumeTrue(defaultValue != null, name + " is required for the paid canary");
            return defaultValue;
        }
        return value.strip();
    }

    private static long elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static Game game(
            int id,
            String name,
            int minPlayers,
            int maxPlayers,
            int minMinutes,
            int maxMinutes,
            String weight,
            List<String> categories,
            List<String> mechanics) {
        Ranking ranking = new Ranking(
                id,
                name,
                2025,
                id - 100,
                new BigDecimal("7.2"),
                new BigDecimal("7.5"),
                2_000,
                List.of(BggGameType.STRATEGY));
        Details details = new Details(
                name,
                "",
                "",
                minPlayers,
                maxPlayers,
                maxMinutes,
                new BigDecimal(weight),
                categories,
                mechanics,
                minMinutes,
                maxMinutes,
                10,
                10,
                "4",
                "3–4",
                2,
                500,
                List.of(),
                List.of("Canary Designer"),
                List.of("Canary Publisher"));
        return new Game(ranking, details);
    }

    private static final class Capture {
        private final String provider;
        private final String model;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final List<ToolCall> toolCalls = new ArrayList<>();

        private Capture(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        private synchronized void add(String operation, Turn turn, long latencyMs) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("ordinal", calls.size() + 1);
            call.put("operation", operation);
            call.put("latencyMs", latencyMs);
            call.put("assistantText", turn.text());
            call.put("toolCalls", turn.toolCalls().stream()
                    .map(tool -> Map.of(
                            "name", tool.name(),
                            "argumentsJson", tool.argumentsJson()))
                    .toList());
            calls.add(Map.copyOf(call));
            toolCalls.addAll(turn.toolCalls());
        }

        private synchronized String lastArguments(String toolName) {
            for (int index = toolCalls.size() - 1; index >= 0; index--) {
                ToolCall toolCall = toolCalls.get(index);
                if (toolName.equals(toolCall.name())) return toolCall.argumentsJson();
            }
            throw new AssertionError("missing captured tool call " + toolName);
        }

        private synchronized ToolCall lastToolCall() {
            if (toolCalls.isEmpty()) throw new AssertionError("missing captured tool call");
            return toolCalls.getLast();
        }
    }

    private static final class CanaryCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games;
        private final Map<String, Integer> names;

        private CanaryCatalog() {
            List<Game> values = List.of(
                    game(101, "River Market", 2, 4, 30, 45, "2.2", List.of("Family"), List.of("Open Drafting", "Set Collection")),
                    game(102, "Signal Grove", 3, 5, 45, 60, "2.8", List.of("Strategy"), List.of("Cooperative Game", "Communication Limits")),
                    game(103, "Clockwork Gallery", 3, 5, 50, 60, "2.9", List.of("Strategy"), List.of("Worker Placement", "Contracts")),
                    game(104, "Lantern Route", 2, 4, 25, 40, "1.9", List.of("Family"), List.of("Push Your Luck", "Network and Route Building")),
                    game(105, "Harbor Chorus", 3, 6, 30, 45, "2.4", List.of("Party Game"), List.of("Simultaneous Action Selection", "Voting")),
                    game(106, "Quiet Foundry", 1, 4, 40, 45, "2.7", List.of("Strategy"), List.of("Deck Building", "Hand Management")),
                    game(107, "Cedar Pact", 3, 5, 35, 70, "2.6", List.of("Strategy"), List.of("Negotiation", "Auction/Bidding")));
            games = values.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    value -> value.ranking().bggId(), value -> value));
            names = values.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    value -> value.ranking().sourceName().toLowerCase(Locale.ROOT),
                    value -> value.ranking().bggId()));
        }

        @Override
        public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            return new CandidateSet(games.size(), games.values().stream().limit(maximum).toList());
        }

        @Override
        public List<Ranking> searchByNames(List<String> requested) {
            return requested.stream()
                    .map(value -> names.get(value.toLowerCase(Locale.ROOT)))
                    .filter(java.util.Objects::nonNull)
                    .map(games::get)
                    .map(Game::ranking)
                    .toList();
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> ids) {
            return ids.stream().map(games::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public int gameCount() {
            return games.size();
        }
    }
}
