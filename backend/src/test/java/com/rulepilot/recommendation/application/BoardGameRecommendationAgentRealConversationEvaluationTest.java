package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceInterpretation;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceInterpretationRequest;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceReview;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceReviewRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.adapter.out.model.SpringAiBoardGameRecommendationModel;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
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

@Tag("real-recommendation-agent-evaluation")
class BoardGameRecommendationAgentRealConversationEvaluationTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private RawModelCapture activeRawModelCapture;

    @Test
    void preservesANaturalTitleCorrectionAcrossPaidProviders() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RECOMMENDATION_AGENT_EVAL")));
        List<Map<String, Object>> results = new ArrayList<>();
        double temperature = evaluationTemperature();

        for (String providerName : List.of("deepseek", "qwen")) {
            Provider provider = provider(providerName);
            assertThat(prohibitedModel(provider.model())).isFalse();
            results.add(runConversation(provider, temperature));
        }

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result).containsEntry("outcome", "RECOMMENDATIONS")
                    .containsEntry("continuationResolved", true)
                    .containsEntry("openingPlayersUnspecified", true)
                    .containsEntry("targetOutcome", "RECOMMENDATIONS")
                    .containsEntry("targetSelected", true)
                    .containsEntry("everydayOutcome", "RECOMMENDATIONS")
                    .containsEntry("everydayPlayers", 4)
                    .containsEntry("everydayMaxMinutes", 60)
                    .containsEntry("everydayPreferenceUpdates", true)
                    .containsEntry("everydayOpeningPlayersUnspecified", true)
                    .containsEntry("everydayOpeningContextualPlayers", 3)
                    .containsEntry("everydayOpeningOutcome", "RECOMMENDATIONS")
                    .containsEntry("datedRelationshipOutcome", "RECOMMENDATIONS")
                    .containsEntry("datedRelationshipDiscovered", true)
                    .containsEntry("aliasRelationshipOutcome", "RECOMMENDATIONS")
                    .containsEntry("aliasRelationshipDiscovered", true)
                    .containsEntry("revisionAcknowledgementOutcome", "CONVERSATION")
                    .containsEntry("revisionNoCards", true)
                    .containsEntry("revisionPlayers", 2)
                    .containsEntry("revisionType", "PARTY")
                    .containsEntry("revisionCardsMatchProfile", true)
                    .containsEntry("revisionFollowupOutcome", "RECOMMENDATIONS")
                    .containsEntry("negatedInteractionUnspecified", true)
                    .containsEntry("qualitativeWeightUnspecified", true)
                    .containsEntry("dynamicCountOutcome", "RECOMMENDATIONS")
                    .containsEntry("dynamicCount", 5)
                    .containsEntry("dynamicCountDidNotSetPlayers", true)
                    .containsEntry("refreshOutcome", "RECOMMENDATIONS")
                    .containsEntry("refreshOverlap", 0)
                    .containsEntry("fallbackUsed", false);
            assertThat((Integer) result.get("recommendationCount")).isGreaterThanOrEqualTo(2);
            assertThat((Integer) result.get("everydayRecommendationCount")).isGreaterThanOrEqualTo(2);
            assertThat((Long) result.get("totalLatencyMs")).isLessThanOrEqualTo(30_000L);
            assertThat(result.get("scenarioTags"))
                    .isEqualTo(List.of(
                            "comparison-correction",
                            "direct-target",
                            "everyday-refinement",
                            "dated-external-relationship",
                            "creator-alias-relationship",
                            "dynamic-result-count",
                            "natural-refresh-without-repeats",
                            "preference-revision-without-unwanted-cards",
                            "negated-interaction",
                            "qualitative-weight-without-number"));
            assertThat((Integer) result.get("rawModelTurnCount")).isPositive();
        });

        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path output = root.resolve(".local/agent-evaluation/recommendation-conversation-real.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 5,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "controls", Map.of(
                        "explicitPreferenceEnumInjected", false,
                        "rawModelOutputStored", false,
                        "separateLocalRawDiagnosticStored", true,
                        "hiddenReasoningStored", false,
                        "evaluationTemperature", temperature,
                        "prohibitedQwenPlusUsed", false))) + "\n", StandardCharsets.UTF_8);
    }

    private Map<String, Object> runConversation(Provider provider, double temperature) {
        activeRawModelCapture = new RawModelCapture(provider.name(), provider.model(), temperature, json);
        UnlockableCatalog catalog = new UnlockableCatalog();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            activeRawModelCapture.scenario("comparison-correction");
            String opening = "我想找和《马赛克花园》机制接近的游戏。";
            long openingStarted = System.nanoTime();
            var first = agent.converse(new ConversationRequest(RecommendationProfile.empty(), opening), "zh-CN");
            long openingLatencyMs = Duration.ofNanos(System.nanoTime() - openingStarted).toMillis();
            assertThat(first.outcome()).isIn(Outcome.NEEDS_CLARIFICATION, Outcome.CONVERSATION);
            assertThat(first.assistantMessage()).isNotBlank();
            assertThat(first.profile().players())
                    .as("an opening without a player count must not invent one")
                    .isNull();
            assertThat(first.harness().fallbackUsed()).isFalse();
            assertThat(openingLatencyMs).isLessThanOrEqualTo(30_000L);

            catalog.unlock();
            String correction = "Mosaic Field";
            List<DialogueMessage> transcript = List.of(
                    new DialogueMessage("user", opening),
                    new DialogueMessage("assistant", first.assistantMessage()),
                    new DialogueMessage("user", correction));
            long correctionStarted = System.nanoTime();
            var second = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            correction,
                            List.of(),
                            transcript,
                            null,
                            List.of(),
                            List.of()),
                    "zh-CN");
            long correctionLatencyMs = Duration.ofNanos(System.nanoTime() - correctionStarted).toMillis();

            assertThat(second.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(second.games()).hasSizeBetween(2, 8);
            assertThat(second.games()).extracting(game -> game.game().ranking().bggId()).doesNotHaveDuplicates();
            boolean continuationResolved = catalog.resolvedTitle(correction);
            assertThat(continuationResolved).isTrue();
            assertThat(catalog.inspectedTitle(correction))
                    .as("a player-authored correction is not mixed into Agent-generated candidates")
                    .isFalse();
            boolean referenceExcluded = second.games().stream()
                    .noneMatch(game -> game.game().ranking().bggId() == 50);
            boolean referenceGrounded = second.games().stream()
                    .allMatch(game -> game.matches().stream()
                            .anyMatch(match -> match.startsWith("与参考游戏共有的 BGG 机制/类型：")));
            assertThat(referenceExcluded).as("the comparison target is not a recommendation").isTrue();
            assertThat(referenceGrounded).as("each card is compared with the corrected reference facts").isTrue();
            assertThat(second.harness().actions()).contains("RECOMMEND_GAMES");
            assertThat(second.harness().fallbackUsed()).isFalse();
            assertThat(correctionLatencyMs).isLessThanOrEqualTo(30_000L);
            TargetEvaluation target = runTargetSelection(provider);
            EverydayRefinementEvaluation everyday = runEverydayRefinement(provider);
            ExternalRelationshipEvaluation datedRelationship = runExternalRelationship(
                    provider,
                    "dated-external-relationship",
                    "我就想玩 2025 年 Spiel des Jahres 获奖的那款桌游，直接给我它。",
                    List.of(new CandidateLead(
                            "Bomb Busters",
                            "Spiel des Jahres 官方 2025 档案将 Bomb Busters 列为当年获奖作品。",
                            List.of(1))));
            ExternalRelationshipEvaluation aliasRelationship = runExternalRelationship(
                    provider,
                    "creator-alias-relationship",
                    "我想玩大家叫“复杂哥”的那位设计师做的桌游，给我两款。",
                    List.of(
                            new CandidateLead(
                                    "On Mars",
                                    "中文桌游资料将“复杂哥”对应为 Vital Lacerda；设计师作品页列出本作。",
                                    List.of(1)),
                            new CandidateLead(
                                    "Lisboa",
                                    "中文桌游资料将“复杂哥”对应为 Vital Lacerda；设计师作品页列出本作。",
                                    List.of(1))));
            CountRefreshEvaluation countRefresh = runCountAndRefresh(provider);
            PreferenceRevisionEvaluation revision = runPreferenceRevision(provider);
            SemanticBoundaryEvaluation semanticBoundary = runSemanticBoundaries(provider);

            Set<Integer> selected = new LinkedHashSet<>();
            second.games().forEach(game -> selected.add(game.game().ranking().bggId()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("caseId", "cx-rec-" + provider.name());
            result.put("provider", provider.name());
            result.put("model", provider.model());
            result.put("temperature", temperature);
            result.put("outcome", second.outcome().name());
            result.put("continuationResolved", continuationResolved);
            result.put("openingPlayersUnspecified", first.profile().players() == null);
            result.put("referenceExcluded", referenceExcluded);
            result.put("referenceGrounded", referenceGrounded);
            result.put("naturalTurnCount", transcript.size());
            result.put("recommendationCount", second.games().size());
            result.put("uniqueRecommendationCount", selected.size());
            result.put("targetOutcome", target.outcome());
            result.put("targetSelected", target.selected());
            result.put("targetRecommendationCount", target.recommendationCount());
            result.put("targetLatencyMs", target.latencyMs());
            result.put("targetActions", target.actions());
            result.put("everydayOutcome", everyday.outcome());
            result.put("everydayNaturalTurnCount", everyday.naturalTurnCount());
            result.put("everydayPlayers", everyday.players());
            result.put("everydayMaxMinutes", everyday.maxMinutes());
            result.put("everydayPreferenceUpdates", everyday.preferenceUpdates());
            result.put("everydayOpeningPlayersUnspecified", everyday.openingPlayersUnspecified());
            result.put("everydayOpeningContextualPlayers", everyday.openingContextualPlayers());
            result.put("everydayOpeningOutcome", everyday.openingOutcome());
            result.put("everydayOpeningRecommendationCount", everyday.openingRecommendationCount());
            result.put("everydayRecommendationCount", everyday.recommendationCount());
            result.put("everydayLatencyMs", everyday.latencyMs());
            result.put("everydayActions", everyday.actions());
            result.put("datedRelationshipOutcome", datedRelationship.outcome());
            result.put("datedRelationshipDiscovered", datedRelationship.discovered());
            result.put("datedRelationshipRecommendationCount", datedRelationship.recommendationCount());
            result.put("datedRelationshipLatencyMs", datedRelationship.latencyMs());
            result.put("datedRelationshipActions", datedRelationship.actions());
            result.put("aliasRelationshipOutcome", aliasRelationship.outcome());
            result.put("aliasRelationshipDiscovered", aliasRelationship.discovered());
            result.put("aliasRelationshipRecommendationCount", aliasRelationship.recommendationCount());
            result.put("aliasRelationshipLatencyMs", aliasRelationship.latencyMs());
            result.put("aliasRelationshipActions", aliasRelationship.actions());
            result.put("dynamicCountOutcome", countRefresh.firstOutcome());
            result.put("dynamicCount", countRefresh.firstCount());
            result.put("dynamicCountDidNotSetPlayers", countRefresh.resultCountDidNotSetPlayers());
            result.put("refreshOutcome", countRefresh.refreshOutcome());
            result.put("refreshCount", countRefresh.refreshCount());
            result.put("refreshOverlap", countRefresh.overlap());
            result.put("countRefreshLatencyMs", countRefresh.latencyMs());
            result.put("countRefreshActions", countRefresh.actions());
            result.put("revisionAcknowledgementOutcome", revision.acknowledgementOutcome());
            result.put("revisionNoCards", revision.noCards());
            result.put("revisionPlayers", revision.players());
            result.put("revisionType", revision.type());
            result.put("revisionCardsMatchProfile", revision.cardsMatchProfile());
            result.put("revisionFollowupOutcome", revision.followupOutcome());
            result.put("revisionFollowupRecommendationCount", revision.followupRecommendationCount());
            result.put("revisionLatencyMs", revision.latencyMs());
            result.put("revisionActions", revision.actions());
            result.put("negatedInteractionOutcome", semanticBoundary.negatedInteractionOutcome());
            result.put("negatedInteractionUnspecified", semanticBoundary.negatedInteractionUnspecified());
            result.put("qualitativeWeightOutcome", semanticBoundary.qualitativeWeightOutcome());
            result.put("qualitativeWeightUnspecified", semanticBoundary.qualitativeWeightUnspecified());
            result.put("semanticBoundaryLatencyMs", semanticBoundary.latencyMs());
            result.put("semanticBoundaryActions", semanticBoundary.actions());
            result.put("scenarioTags", List.of(
                    "comparison-correction",
                    "direct-target",
                    "everyday-refinement",
                    "dated-external-relationship",
                    "creator-alias-relationship",
                    "dynamic-result-count",
                    "natural-refresh-without-repeats",
                    "preference-revision-without-unwanted-cards",
                    "negated-interaction",
                    "qualitative-weight-without-number"));
            result.put("modelCalls", Math.max(semanticBoundary.modelCalls(), Math.max(
                    Math.max(
                            revision.modelCalls(),
                            Math.max(
                                    countRefresh.modelCalls(),
                                    Math.max(datedRelationship.modelCalls(), aliasRelationship.modelCalls()))),
                    Math.max(
                            everyday.modelCalls(),
                            Math.max(
                                    target.modelCalls(),
                                    Math.max(first.harness().modelCalls(), second.harness().modelCalls()))))));
            result.put("catalogCalls", Math.max(semanticBoundary.catalogCalls(), Math.max(
                    Math.max(
                            revision.catalogCalls(),
                            Math.max(
                                    countRefresh.catalogCalls(),
                                    Math.max(datedRelationship.catalogCalls(), aliasRelationship.catalogCalls()))),
                    Math.max(
                            everyday.catalogCalls(),
                            Math.max(
                                    target.catalogCalls(),
                                    Math.max(first.harness().catalogCalls(), second.harness().catalogCalls()))))));
            result.put("webResearchCalls", Math.max(semanticBoundary.webResearchCalls(), Math.max(
                    Math.max(
                            revision.webResearchCalls(),
                            Math.max(
                                    countRefresh.webResearchCalls(),
                                    Math.max(datedRelationship.webResearchCalls(), aliasRelationship.webResearchCalls()))),
                    Math.max(
                            everyday.webResearchCalls(),
                            Math.max(
                                    target.webResearchCalls(),
                                    Math.max(first.harness().webResearchCalls(), second.harness().webResearchCalls()))))));
            result.put(
                    "conversationModelCalls",
                    first.harness().modelCalls()
                            + second.harness().modelCalls()
                            + everyday.conversationModelCalls()
                            + semanticBoundary.modelCalls());
            result.put("fallbackUsed", first.harness().fallbackUsed()
                    || second.harness().fallbackUsed()
                    || target.fallbackUsed()
                    || everyday.fallbackUsed()
                    || datedRelationship.fallbackUsed()
                    || aliasRelationship.fallbackUsed()
                    || countRefresh.fallbackUsed()
                    || revision.fallbackUsed()
                    || semanticBoundary.fallbackUsed());
            result.put(
                    "totalLatencyMs",
                    Math.max(semanticBoundary.latencyMs(), Math.max(
                            Math.max(
                                    revision.latencyMs(),
                                    Math.max(
                                            countRefresh.latencyMs(),
                                            Math.max(datedRelationship.latencyMs(), aliasRelationship.latencyMs()))),
                            Math.max(
                                    everyday.latencyMs(),
                                    Math.max(target.latencyMs(), Math.max(openingLatencyMs, correctionLatencyMs))))));
            result.put("conversationLatencyMs", openingLatencyMs + correctionLatencyMs + everyday.latencyMs());
            result.put("actions", java.util.stream.Stream.of(
                            first.harness().actions(),
                            second.harness().actions(),
                            target.actions(),
                            everyday.actions(),
                            datedRelationship.actions(),
                            aliasRelationship.actions(),
                            countRefresh.actions(),
                            revision.actions(),
                            semanticBoundary.actions())
                    .flatMap(List::stream)
                    .distinct()
                    .toList());
            result.put("rawModelTurnCount", activeRawModelCapture.count());
            return Map.copyOf(result);
        } finally {
            agent.stopBoundedCalls();
            activeRawModelCapture = null;
        }
    }

    private TargetEvaluation runTargetSelection(Provider provider) {
        activeRawModelCapture.scenario("direct-target");
        UnlockableCatalog catalog = new UnlockableCatalog();
        catalog.unlock();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            String request = "我已经决定了，今晚就玩 Mosaic Field。请直接选这款，接下来我要找它的规则书。";
            long started = System.nanoTime();
            var response = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), request),
                    "zh-CN");
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.games()).singleElement()
                    .extracting(game -> game.game().ranking().bggId())
                    .isEqualTo(50);
            assertThat(catalog.resolvedTitle("Mosaic Field")).isTrue();
            assertThat(response.harness().actions()).contains("RESOLVE_BGG_REFERENCE", "RECOMMEND_GAMES");
            assertThat(response.harness().fallbackUsed()).isFalse();
            assertThat(latencyMs).isLessThanOrEqualTo(30_000L);
            return new TargetEvaluation(
                    response.outcome().name(),
                    true,
                    response.games().size(),
                    response.harness().modelCalls(),
                    response.harness().catalogCalls(),
                    response.harness().webResearchCalls(),
                    response.harness().fallbackUsed(),
                    latencyMs,
                    response.harness().actions());
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private EverydayRefinementEvaluation runEverydayRefinement(Provider provider) {
        activeRawModelCapture.scenario("everyday-refinement");
        UnlockableCatalog catalog = new UnlockableCatalog();
        catalog.unlock();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            String opening = "周末想带爸妈玩一局，轻松一点就好。你先按常理给我两三款，不用问东问西，人数有变化我会说。";
            long openingStarted = System.nanoTime();
            var first = agent.converse(new ConversationRequest(RecommendationProfile.empty(), opening), "zh-CN");
            long openingLatencyMs = Duration.ofNanos(System.nanoTime() - openingStarted).toMillis();
            assertThat(first.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(first.assistantMessage()).isNotBlank();
            assertThat(first.profile().players())
                    .as("a contextual group interpretation must not become a confirmed hard filter")
                    .isNull();
            assertThat(first.userModel().hypotheses())
                    .as("the natural three-player interpretation should remain visible and reversible")
                    .anySatisfy(hypothesis -> {
                        assertThat(hypothesis.field()).isEqualTo("players");
                        assertThat(hypothesis.value()).isEqualTo("3");
                    });
            assertThat(first.games()).hasSizeBetween(2, 3);
            assertThat(first.harness().actions())
                    .contains("RECORD_CONTEXTUAL_PREFERENCE", "RECOMMEND_GAMES")
                    .doesNotContain("ASK_USER");
            assertThat(first.harness().fallbackUsed()).isFalse();
            assertThat(openingLatencyMs).isLessThanOrEqualTo(30_000L);

            String refinement = "其实我哥也来，一共 4 个人，最多 60 分钟。他们平时不太玩桌游，规则要容易上手；按新条件再给两三款。";
            List<DialogueMessage> transcript = List.of(
                    new DialogueMessage("user", opening),
                    new DialogueMessage("assistant", first.assistantMessage()),
                    new DialogueMessage("user", refinement));
            List<BoardGameRecommendationAgent.KnownGame> knownGames = first.games().stream()
                    .map(game -> new BoardGameRecommendationAgent.KnownGame(
                            game.game().ranking().bggId(),
                            game.game().details().officialChineseName(),
                            game.game().ranking().sourceName()))
                    .toList();
            List<Integer> shownBggIds = first.games().stream()
                    .map(game -> game.game().ranking().bggId())
                    .toList();
            long refinementStarted = System.nanoTime();
            var second = agent.converse(
                    new ConversationRequest(
                            first.profile(),
                            refinement,
                            List.of(),
                            transcript,
                            null,
                            knownGames,
                            shownBggIds),
                    "zh-CN");
            long refinementLatencyMs = Duration.ofNanos(System.nanoTime() - refinementStarted).toMillis();

            assertThat(second.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(second.profile().players()).isEqualTo(4);
            assertThat(second.profile().maxMinutes()).isEqualTo(60);
            assertThat(second.games()).hasSizeBetween(2, 3);
            assertThat(second.harness().actions()).contains("UPDATE_PREFERENCES", "RECOMMEND_GAMES");
            assertThat(second.harness().fallbackUsed()).isFalse();
            assertThat(refinementLatencyMs).isLessThanOrEqualTo(30_000L);

            return new EverydayRefinementEvaluation(
                    second.outcome().name(),
                    transcript.size(),
                    first.profile().players() == null,
                    first.userModel().hypotheses().stream()
                            .filter(hypothesis -> "players".equals(hypothesis.field()))
                            .map(BoardGameRecommendationAgent.PreferenceHypothesisView::value)
                            .map(Integer::valueOf)
                            .findFirst()
                            .orElse(null),
                    first.outcome().name(),
                    first.games().size(),
                    second.profile().players(),
                    second.profile().maxMinutes(),
                    second.harness().actions().contains("UPDATE_PREFERENCES"),
                    second.games().size(),
                    Math.max(first.harness().modelCalls(), second.harness().modelCalls()),
                    Math.max(first.harness().catalogCalls(), second.harness().catalogCalls()),
                    Math.max(first.harness().webResearchCalls(), second.harness().webResearchCalls()),
                    first.harness().fallbackUsed() || second.harness().fallbackUsed(),
                    Math.max(openingLatencyMs, refinementLatencyMs),
                    first.harness().modelCalls() + second.harness().modelCalls(),
                    java.util.stream.Stream.concat(
                                    first.harness().actions().stream(), second.harness().actions().stream())
                            .distinct()
                            .toList());
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private ExternalRelationshipEvaluation runExternalRelationship(
            Provider provider,
            String scenario,
            String prompt,
            List<CandidateLead> candidates) {
        activeRawModelCapture.scenario(scenario);
        UnlockableCatalog catalog = new UnlockableCatalog();
        catalog.unlock();
        List<DiscoveryRequest> discoveries = new ArrayList<>();
        BoardGameRecommendationWebResearch research = new BoardGameRecommendationWebResearch() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public Optional<Research> research(Request request) {
                throw new AssertionError("relationship scenario must use candidate discovery only");
            }

            @Override
            public Optional<CandidateDiscovery> discover(DiscoveryRequest request) {
                discoveries.add(request);
                return Optional.of(new CandidateDiscovery(
                        candidates,
                        List.of(new Source(
                                1,
                                "Independent relationship archive",
                                "https://evidence.example.test/relationship",
                                "evidence.example.test"))));
            }
        };
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, research),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            long started = System.nanoTime();
            var response = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), prompt),
                    "zh-CN");
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.games()).hasSize(candidates.size());
            assertThat(response.games())
                    .extracting(game -> game.game().ranking().sourceName())
                    .containsExactlyInAnyOrderElementsOf(candidates.stream().map(CandidateLead::name).toList());
            assertThat(discoveries).singleElement().satisfies(request -> {
                assertThat(request.query()).isNotBlank();
                assertThat(request.locale()).isEqualTo("zh-CN");
            });
            assertThat(response.harness().webResearchCalls()).isEqualTo(1);
            assertThat(response.harness().actions())
                    .containsSubsequence(
                            "DISCOVER_CANDIDATES",
                            "SEARCH_BGG_BY_NAME",
                            "LOOKUP_BGG_CANDIDATES",
                            "RECOMMEND_GAMES");
            assertThat(response.harness().fallbackUsed()).isFalse();
            assertThat(latencyMs).isLessThanOrEqualTo(30_000L);
            return new ExternalRelationshipEvaluation(
                    response.outcome().name(),
                    true,
                    response.games().size(),
                    response.harness().modelCalls(),
                    response.harness().catalogCalls(),
                    response.harness().webResearchCalls(),
                    response.harness().fallbackUsed(),
                    latencyMs,
                    response.harness().actions());
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private CountRefreshEvaluation runCountAndRefresh(Provider provider) {
        activeRawModelCapture.scenario("dynamic-result-count");
        UnlockableCatalog catalog = new UnlockableCatalog();
        catalog.unlock();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            String opening = "我现在只是想铺开看看，别按固定三款来；给我五款不同方向的桌游，不用再问。";
            long openingStarted = System.nanoTime();
            var first = agent.converse(
                    new ConversationRequest(RecommendationProfile.empty(), opening),
                    "zh-CN");
            long openingLatencyMs = Duration.ofNanos(System.nanoTime() - openingStarted).toMillis();

            assertThat(first.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(first.games()).hasSize(5);
            assertThat(first.profile().players())
                    .as("the requested number of result cards must not become a player-count preference")
                    .isNull();
            assertThat(first.harness().fallbackUsed()).isFalse();
            Set<Integer> shown = first.games().stream()
                    .map(game -> game.game().ranking().bggId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            assertThat(shown).hasSize(5);

            activeRawModelCapture.scenario("natural-refresh-without-repeats");
            String refresh = "这五个先留着但都别重复，再换一批，给我三个没出现过的方向。";
            List<DialogueMessage> transcript = List.of(
                    new DialogueMessage("user", opening),
                    new DialogueMessage("assistant", first.assistantMessage()),
                    new DialogueMessage("user", refresh));
            List<BoardGameRecommendationAgent.KnownGame> knownGames = first.games().stream()
                    .map(game -> new BoardGameRecommendationAgent.KnownGame(
                            game.game().ranking().bggId(),
                            game.game().details().officialChineseName(),
                            game.game().ranking().sourceName()))
                    .toList();
            long refreshStarted = System.nanoTime();
            var second = agent.converse(
                    new ConversationRequest(
                            first.profile(),
                            refresh,
                            List.of(),
                            transcript,
                            null,
                            knownGames,
                            shown.stream().toList()),
                    "zh-CN");
            long refreshLatencyMs = Duration.ofNanos(System.nanoTime() - refreshStarted).toMillis();

            assertThat(second.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(second.games()).hasSize(3);
            Set<Integer> refreshed = second.games().stream()
                    .map(game -> game.game().ranking().bggId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<Integer> overlap = new LinkedHashSet<>(shown);
            overlap.retainAll(refreshed);
            assertThat(overlap).isEmpty();
            assertThat(second.harness().actions()).contains("RECOMMEND_GAMES");
            assertThat(second.harness().fallbackUsed()).isFalse();
            assertThat(Math.max(openingLatencyMs, refreshLatencyMs)).isLessThanOrEqualTo(30_000L);

            return new CountRefreshEvaluation(
                    first.outcome().name(),
                    first.games().size(),
                    first.profile().players() == null,
                    second.outcome().name(),
                    second.games().size(),
                    overlap.size(),
                    Math.max(first.harness().modelCalls(), second.harness().modelCalls()),
                    Math.max(first.harness().catalogCalls(), second.harness().catalogCalls()),
                    Math.max(first.harness().webResearchCalls(), second.harness().webResearchCalls()),
                    first.harness().fallbackUsed() || second.harness().fallbackUsed(),
                    Math.max(openingLatencyMs, refreshLatencyMs),
                    java.util.stream.Stream.concat(
                                    first.harness().actions().stream(), second.harness().actions().stream())
                            .distinct()
                            .toList());
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private PreferenceRevisionEvaluation runPreferenceRevision(Provider provider) {
        activeRawModelCapture.scenario("preference-revision-without-unwanted-cards");
        UnlockableCatalog catalog = new UnlockableCatalog();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            String original = "我们四个人，想玩策略游戏。";
            String correction = "等等我改口了，现在只有两个人，而且不想玩策略了，改成派对游戏。先只记住，暂时别给我卡片。";
            List<DialogueMessage> correctionTranscript = List.of(
                    new DialogueMessage("user", original),
                    new DialogueMessage("assistant", "好，我记下四个人和策略游戏。"),
                    new DialogueMessage("user", correction));
            long correctionStarted = System.nanoTime();
            var acknowledged = agent.converse(
                    new ConversationRequest(
                            new RecommendationProfile(
                                    4, null, null, BggGameType.STRATEGY,
                                    BoardGameRecommendationAgent.InteractionPreference.ANY),
                            correction,
                            List.of(),
                            correctionTranscript,
                            null,
                            List.of(),
                            List.of()),
                    "zh-CN");
            long correctionLatencyMs = Duration.ofNanos(System.nanoTime() - correctionStarted).toMillis();

            assertThat(acknowledged.outcome()).isEqualTo(Outcome.CONVERSATION);
            assertThat(acknowledged.games()).isEmpty();
            assertThat(acknowledged.profile().players()).isEqualTo(2);
            assertThat(acknowledged.profile().type()).isEqualTo(BggGameType.PARTY);
            assertThat(acknowledged.harness().catalogCalls()).isZero();
            assertThat(acknowledged.harness().webResearchCalls()).isZero();
            assertThat(acknowledged.harness().actions()).contains("UPDATE_PREFERENCES", "REPLY_TO_USER");
            assertThat(acknowledged.harness().actions()).doesNotContain("RECOMMEND_GAMES");
            assertThat(correctionLatencyMs).isLessThanOrEqualTo(30_000L);

            catalog.unlock();
            String followup = "好，现在按刚改的新条件给我两款；不用再问。";
            List<DialogueMessage> followupTranscript = new ArrayList<>(correctionTranscript);
            followupTranscript.add(new DialogueMessage("assistant", acknowledged.assistantMessage()));
            followupTranscript.add(new DialogueMessage("user", followup));
            long followupStarted = System.nanoTime();
            var response = agent.converse(
                    new ConversationRequest(
                            acknowledged.profile(),
                            followup,
                            List.of(),
                            followupTranscript,
                            null,
                            List.of(),
                            List.of()),
                    "zh-CN");
            long followupLatencyMs = Duration.ofNanos(System.nanoTime() - followupStarted).toMillis();

            assertThat(response.outcome()).isEqualTo(Outcome.RECOMMENDATIONS);
            assertThat(response.profile().players()).isEqualTo(2);
            assertThat(response.profile().type()).isEqualTo(BggGameType.PARTY);
            assertThat(response.games()).hasSizeBetween(1, 3);
            boolean cardsMatchProfile = response.games().stream().allMatch(game ->
                    game.game().ranking().types().contains(BggGameType.PARTY)
                            && game.game().details().minPlayers() <= 2
                            && game.game().details().maxPlayers() >= 2);
            assertThat(cardsMatchProfile)
                    .as("every returned card must satisfy the corrected player count and BGG type")
                    .isTrue();
            assertThat(response.harness().actions()).contains("RECOMMEND_GAMES");
            assertThat(response.harness().fallbackUsed()).isFalse();
            assertThat(followupLatencyMs).isLessThanOrEqualTo(30_000L);

            return new PreferenceRevisionEvaluation(
                    acknowledged.outcome().name(),
                    acknowledged.games().isEmpty(),
                    acknowledged.profile().players(),
                    acknowledged.profile().type().name(),
                    cardsMatchProfile,
                    response.outcome().name(),
                    response.games().size(),
                    Math.max(acknowledged.harness().modelCalls(), response.harness().modelCalls()),
                    Math.max(acknowledged.harness().catalogCalls(), response.harness().catalogCalls()),
                    Math.max(acknowledged.harness().webResearchCalls(), response.harness().webResearchCalls()),
                    acknowledged.harness().fallbackUsed() || response.harness().fallbackUsed(),
                    Math.max(correctionLatencyMs, followupLatencyMs),
                    java.util.stream.Stream.concat(
                                    acknowledged.harness().actions().stream(),
                                    response.harness().actions().stream())
                            .distinct()
                            .toList());
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private SemanticBoundaryEvaluation runSemanticBoundaries(Provider provider) {
        UnlockableCatalog catalog = new UnlockableCatalog();
        var properties = new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(20));
        var agent = new BoardGameRecommendationAgent(
                model(provider),
                new BoardGameRecommendationTools(catalog, noResearch()),
                new BoardGameRecommendationSelector(properties),
                properties,
                json);
        try {
            activeRawModelCapture.scenario("negated-interaction");
            long negatedStarted = System.nanoTime();
            var negated = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "合作游戏已经玩腻了，想换换口味。先别给卡片，只记下我明确说过的偏好。"),
                    "zh-CN");
            long negatedLatencyMs = Duration.ofNanos(System.nanoTime() - negatedStarted).toMillis();

            assertThat(negated.outcome()).isIn(Outcome.CONVERSATION, Outcome.NEEDS_CLARIFICATION);
            assertThat(negated.games()).isEmpty();
            assertThat(negated.profile().interaction())
                    .as("a negated mode must not be converted into any positive hard constraint")
                    .isEqualTo(BoardGameRecommendationAgent.InteractionPreference.ANY);
            assertThat(negated.harness().fallbackUsed()).isFalse();
            assertThat(negatedLatencyMs).isLessThanOrEqualTo(30_000L);

            activeRawModelCapture.scenario("qualitative-weight-without-number");
            long qualitativeStarted = System.nanoTime();
            var qualitative = agent.converse(
                    new ConversationRequest(
                            RecommendationProfile.empty(),
                            "我想玩重策，但没有给 BGG 复杂度数字。先别给卡片，只记下我明确说过的偏好。"),
                    "zh-CN");
            long qualitativeLatencyMs = Duration.ofNanos(System.nanoTime() - qualitativeStarted).toMillis();

            assertThat(qualitative.outcome()).isIn(Outcome.CONVERSATION, Outcome.NEEDS_CLARIFICATION);
            assertThat(qualitative.games()).isEmpty();
            assertThat(qualitative.profile().maxWeight())
                    .as("a qualitative taste must not become an invented numeric ceiling")
                    .isNull();
            assertThat(qualitative.harness().fallbackUsed()).isFalse();
            assertThat(qualitativeLatencyMs).isLessThanOrEqualTo(30_000L);

            return new SemanticBoundaryEvaluation(
                    negated.outcome().name(),
                    negated.profile().interaction()
                            == BoardGameRecommendationAgent.InteractionPreference.ANY,
                    qualitative.outcome().name(),
                    qualitative.profile().maxWeight() == null,
                    Math.max(negated.harness().modelCalls(), qualitative.harness().modelCalls()),
                    Math.max(negated.harness().catalogCalls(), qualitative.harness().catalogCalls()),
                    Math.max(negated.harness().webResearchCalls(), qualitative.harness().webResearchCalls()),
                    negated.harness().fallbackUsed() || qualitative.harness().fallbackUsed(),
                    Math.max(negatedLatencyMs, qualitativeLatencyMs),
                    java.util.stream.Stream.concat(
                                    negated.harness().actions().stream(),
                                    qualitative.harness().actions().stream())
                            .distinct()
                            .toList());
        } finally {
            agent.stopBoundedCalls();
        }
    }

    private BoardGameRecommendationModel model(Provider provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(30))
                .create(provider.name(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider.name());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek".equals(provider.name()));
        RawModelCapture capture = activeRawModelCapture;
        if (capture == null) throw new IllegalStateException("raw model capture is required for real evaluation");
        BoardGameRecommendationModel delegate = new SpringAiBoardGameRecommendationModel(
                configuration, capture.temperature());
        return new BoardGameRecommendationModel() {
            @Override
            public boolean configured() {
                return delegate.configured();
            }

            @Override
            public boolean preferenceReviewConfigured() {
                return delegate.preferenceReviewConfigured();
            }

            @Override
            public boolean preferenceInterpretationConfigured() {
                return delegate.preferenceInterpretationConfigured();
            }

            @Override
            public PreferenceInterpretation interpretPreferences(PreferenceInterpretationRequest request) {
                PreferenceInterpretation interpretation = delegate.interpretPreferences(request);
                capture.record(interpretation.rawTurn());
                return interpretation;
            }

            @Override
            public PreferenceReview reviewPreferences(PreferenceReviewRequest request) {
                PreferenceReview review = delegate.reviewPreferences(request);
                capture.record(review.rawTurn());
                return review;
            }

            @Override
            public Turn next(Request request) {
                Turn turn = delegate.next(request);
                capture.record(turn);
                return turn;
            }
        };
    }

    private Provider provider(String name) {
        String prefix = name.toUpperCase(Locale.ROOT);
        return new Provider(
                name,
                requiredEnvironment(prefix + "_API_KEY"),
                requiredEnvironment(prefix + "_BASE_URL"),
                requiredEnvironment(prefix + "_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private double evaluationTemperature() {
        String configured = System.getenv("RULEPILOT_REAL_RECOMMENDATION_TEMPERATURE");
        double value = configured == null || configured.isBlank() ? 0.2 : Double.parseDouble(configured.strip());
        assumeTrue(Double.isFinite(value) && value >= 0.0 && value <= 2.0,
                "RULEPILOT_REAL_RECOMMENDATION_TEMPERATURE must be between 0 and 2");
        return value;
    }

    private boolean prohibitedModel(String model) {
        String normalized = model.toLowerCase(Locale.ROOT);
        return normalized.equals("qwen-plus")
                || normalized.startsWith("qwen-plus-")
                || normalized.startsWith("qwen-plus_");
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

    private static Game game(
            int id,
            String name,
            List<String> categories,
            List<String> mechanics,
            BigDecimal weight) {
        return game(
                id,
                name,
                2024,
                categories,
                mechanics,
                weight,
                2,
                4,
                35,
                60,
                List.of("Designer A"));
    }

    private static Game game(
            int id,
            String name,
            int year,
            List<String> categories,
            List<String> mechanics,
            BigDecimal weight,
            int minPlayers,
            int maxPlayers,
            int minimumMinutes,
            int maximumMinutes,
            List<String> designers) {
        return new Game(
                new Ranking(
                        id,
                        name,
                        year,
                        id,
                        new BigDecimal("7.5"),
                        new BigDecimal("7.8"),
                        1_000,
                        typesFor(categories)),
                new Details(
                        name,
                        "",
                        "",
                        minPlayers,
                        maxPlayers,
                        maximumMinutes,
                        weight,
                        categories,
                        mechanics,
                        minimumMinutes,
                        maximumMinutes,
                        10,
                        10,
                        "Best within the listed player range",
                        "Recommended within the listed player range",
                        2,
                        100,
                        List.of("Spatial Games"),
                        designers,
                        List.of("Publisher A")));
    }

    private static List<BggGameType> typesFor(List<String> categories) {
        Set<BggGameType> types = new LinkedHashSet<>();
        for (String category : categories) {
            String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
            if (normalized.contains("abstract")) types.add(BggGameType.ABSTRACT);
            if (normalized.contains("party")) types.add(BggGameType.PARTY);
            if (normalized.contains("family")) types.add(BggGameType.FAMILY);
            if (normalized.contains("strategy")) types.add(BggGameType.STRATEGY);
            if (normalized.contains("thematic")) types.add(BggGameType.THEMATIC);
        }
        return List.copyOf(types);
    }

    private record Provider(String name, String apiKey, String baseUrl, String model) {}

    private static final class RawModelCapture {
        private final String provider;
        private final String model;
        private final double temperature;
        private final ObjectMapper json;
        private final Path output;
        private final List<Map<String, Object>> turns = new ArrayList<>();
        private String scenario = "unassigned";

        private RawModelCapture(String provider, String model, double temperature, ObjectMapper json) {
            this.provider = provider;
            this.model = model;
            this.temperature = temperature;
            this.json = json;
            String fileName = "recommendation-raw-visible-turns-"
                    + provider.replaceAll("[^a-zA-Z0-9._-]", "_")
                    + "-t" + Double.toString(temperature).replace('.', '_') + ".jsonl";
            this.output = Path.of(System.getProperty("user.dir"))
                    .getParent()
                    .resolve(".local/agent-evaluation")
                    .resolve(fileName);
            try {
                Files.createDirectories(output.getParent());
                Files.writeString(
                        output,
                        "",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("could not initialize raw recommendation diagnostic", exception);
            }
        }

        private synchronized void scenario(String scenario) {
            this.scenario = scenario;
        }

        private synchronized void record(BoardGameRecommendationModel.Turn turn) {
            List<Map<String, String>> calls = turn.toolCalls().stream()
                    .map(call -> Map.of(
                            "id", call.id(),
                            "name", call.name(),
                            "argumentsJson", call.argumentsJson()))
                    .toList();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ordinal", turns.size() + 1);
            value.put("scenario", scenario);
            value.put("provider", provider);
            value.put("model", model);
            value.put("temperature", temperature);
            value.put("visibleText", turn.text());
            value.put("toolCalls", calls);
            Map<String, Object> captured = Map.copyOf(value);
            turns.add(captured);
            try {
                Files.writeString(
                        output,
                        json.writeValueAsString(captured) + "\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("could not persist raw recommendation diagnostic", exception);
            }
        }

        private synchronized int count() {
            return turns.size();
        }

        private double temperature() {
            return temperature;
        }
    }

    private record TargetEvaluation(
            String outcome,
            boolean selected,
            int recommendationCount,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            long latencyMs,
            List<String> actions) {}

    private record EverydayRefinementEvaluation(
            String outcome,
            int naturalTurnCount,
            boolean openingPlayersUnspecified,
            Integer openingContextualPlayers,
            String openingOutcome,
            int openingRecommendationCount,
            Integer players,
            Integer maxMinutes,
            boolean preferenceUpdates,
            int recommendationCount,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            long latencyMs,
            int conversationModelCalls,
            List<String> actions) {}

    private record ExternalRelationshipEvaluation(
            String outcome,
            boolean discovered,
            int recommendationCount,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            long latencyMs,
            List<String> actions) {}

    private record CountRefreshEvaluation(
            String firstOutcome,
            int firstCount,
            boolean resultCountDidNotSetPlayers,
            String refreshOutcome,
            int refreshCount,
            int overlap,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            long latencyMs,
            List<String> actions) {}

    private record PreferenceRevisionEvaluation(
            String acknowledgementOutcome,
            boolean noCards,
            Integer players,
            String type,
            boolean cardsMatchProfile,
            String followupOutcome,
            int followupRecommendationCount,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            long latencyMs,
            List<String> actions) {}

    private record SemanticBoundaryEvaluation(
            String negatedInteractionOutcome,
            boolean negatedInteractionUnspecified,
            String qualitativeWeightOutcome,
            boolean qualitativeWeightUnspecified,
            int modelCalls,
            int catalogCalls,
            int webResearchCalls,
            boolean fallbackUsed,
            long latencyMs,
            List<String> actions) {}

    private static final class UnlockableCatalog implements BoardGameRecommendationCatalog {
        private final Map<Integer, Game> games;
        private final Map<String, Integer> names;
        private final Set<String> inspectedTitles = new LinkedHashSet<>();
        private final Set<String> resolvedTitles = new LinkedHashSet<>();
        private boolean unlocked;

        private UnlockableCatalog() {
            Map<Integer, Game> values = new LinkedHashMap<>();
            values.put(50, game(
                    50,
                    "Mosaic Field",
                    List.of("Abstract Strategy"),
                    List.of("Pattern Building", "Tile Placement", "Open Drafting"),
                    new BigDecimal("2.2")));
            values.put(60, game(
                    60,
                    "Glass Orchard",
                    List.of("Abstract Strategy"),
                    List.of("Pattern Building", "Open Drafting"),
                    new BigDecimal("2.3")));
            values.put(61, game(
                    61,
                    "Loom City",
                    List.of("Abstract Strategy"),
                    List.of("Tile Placement", "Pattern Building"),
                    new BigDecimal("2.6")));
            values.put(62, game(
                    62,
                    "Prism Workshop",
                    List.of("Strategy"),
                    List.of("Open Drafting", "Contracts"),
                    new BigDecimal("2.8")));
            values.put(64, game(
                    64,
                    "Echo Picnic",
                    List.of("Party Game"),
                    List.of("Communication Limits", "Team-Based Game"),
                    new BigDecimal("1.4")));
            values.put(65, game(
                    65,
                    "Lantern Chorus",
                    List.of("Party Game"),
                    List.of("Simultaneous Action Selection", "Voting"),
                    new BigDecimal("1.3")));
            values.put(66, game(
                    66,
                    "Paper Harbor",
                    List.of("Family"),
                    List.of("Set Collection", "Open Drafting"),
                    new BigDecimal("1.8")));
            values.put(67, game(
                    67,
                    "Quiet Comet",
                    List.of("Thematic"),
                    List.of("Push Your Luck", "Dice Rolling"),
                    new BigDecimal("2.0")));
            values.put(68, game(
                    68,
                    "Copper Parade",
                    List.of("Family"),
                    List.of("Area Majority", "Hand Management"),
                    new BigDecimal("2.1")));
            values.put(69, game(
                    69,
                    "Tidal Library",
                    List.of("Strategy"),
                    List.of("Worker Placement", "Contracts"),
                    new BigDecimal("3.0")));
            values.put(413246, game(
                    413246,
                    "Bomb Busters",
                    2024,
                    List.of("Deduction", "Puzzle"),
                    List.of("Cooperative Game", "Communication Limits"),
                    new BigDecimal("2.2"),
                    2,
                    5,
                    20,
                    40,
                    List.of("Hisashi Hayashi")));
            values.put(184267, game(
                    184267,
                    "On Mars",
                    2020,
                    List.of("Economic", "Science Fiction", "Strategy"),
                    List.of("Worker Placement", "Action Points", "Contracts"),
                    new BigDecimal("4.7"),
                    1,
                    4,
                    90,
                    150,
                    List.of("Vital Lacerda")));
            values.put(161533, game(
                    161533,
                    "Lisboa",
                    2017,
                    List.of("City Building", "Economic", "Strategy"),
                    List.of("Hand Management", "Tile Placement", "Variable Set-up"),
                    new BigDecimal("4.6"),
                    1,
                    4,
                    60,
                    120,
                    List.of("Vital Lacerda")));
            games = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            names = games.values().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    game -> game.ranking().sourceName(), game -> game.ranking().bggId()));
        }

        private void unlock() {
            unlocked = true;
        }

        private boolean inspectedTitle(String title) {
            return inspectedTitles.contains(title);
        }

        private boolean resolvedTitle(String title) {
            return resolvedTitles.contains(title);
        }

        @Override
        public CandidateSet findCandidates(
                BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
            List<Game> matching = unlocked
                    ? games.values().stream()
                            .filter(game -> requiredType == null
                                    || requiredType == BggGameType.ALL
                                    || game.ranking().types().contains(requiredType))
                            .limit(maximum)
                            .toList()
                    : List.of();
            return new CandidateSet(
                    unlocked ? games.size() : 0,
                    matching);
        }

        @Override
        public List<Ranking> searchByNames(List<String> titles) {
            if (!unlocked) return List.of();
            inspectedTitles.addAll(titles);
            return titles.stream()
                    .map(names::get)
                    .filter(java.util.Objects::nonNull)
                    .map(games::get)
                    .map(Game::ranking)
                    .toList();
        }

        @Override
        public List<Game> resolveReferenceTitle(String title) {
            if (!unlocked) return List.of();
            resolvedTitles.add(title);
            Integer id = names.get(title);
            return id == null ? List.of() : List.of(games.get(id));
        }

        @Override
        public List<Game> findGamesByIds(List<Integer> bggIds) {
            if (!unlocked) return List.of();
            return bggIds.stream().map(games::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public int gameCount() {
            return unlocked ? games.size() : 0;
        }
    }
}
