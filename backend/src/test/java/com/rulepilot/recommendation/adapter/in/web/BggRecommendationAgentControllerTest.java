package com.rulepilot.recommendation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BggRecommendationPresentation;
import com.rulepilot.catalog.BggRecommendationPresentation.LocalizedTaxonomy;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReason;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReasonKind;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.SessionSnapshot;
import com.rulepilot.recommendation.application.RecommendationConversationCoordinator.TurnResult;
import com.rulepilot.recommendation.application.RecommendationConversationStore.ConversationState;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BggRecommendationAgentControllerTest {

    private final BoardGameRecommendationAgent agent = mock(BoardGameRecommendationAgent.class);
    private final BggRecommendationPresentation presentation = mock(BggRecommendationPresentation.class);
    private final BggRecommendationAgentController controller =
            new BggRecommendationAgentController(agent, presentation);
    private final Principal principal = () -> "player";

    @Test
    void exposesTypedVerifiedSetShortfallAlongsideTheUnchangedAgentExplanation() {
        String rawExplanation = "你要三款，但在五人和九十分钟这两个硬条件下，本轮核对到两款候选；我先把它们都给你。";
        var shortfall = new RecommendationShortfall(3, 2);
        var domain = new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                rawExplanation,
                RecommendationProfile.empty(),
                null,
                0,
                2,
                new BoardGameRecommendationAgent.UserModelView("", List.of()),
                List.of(),
                new BoardGameRecommendationAgent.HarnessTrace(
                        2,
                        1,
                        0,
                        false,
                        List.of(
                                "SEARCH_BGG_CATALOG",
                                "REJECTED_ACTION:bad",
                                "RESEARCH_SKIPPED_FOR_PUBLICATION_ALREADY_ATTEMPTED",
                                "RECOMMEND_GAMES")),
                List.of(),
                null,
                shortfall);

        var response = BggRecommendationAgentController.RecommendationConversationResponse.from(
                domain,
                new LocalizedTaxonomy(Map.of(), Map.of()),
                "zh-CN",
                presentation,
                null,
                null,
                null,
                false);

        assertThat(response.assistantMessage()).isEqualTo(rawExplanation);
        assertThat(response.shortfall()).isNotNull();
        assertThat(response.shortfall().requestedCount()).isEqualTo(3);
        assertThat(response.shortfall().availableCount()).isEqualTo(2);
        assertThat(response.modelCalls()).isEqualTo(2);
        assertThat(response.catalogCalls()).isEqualTo(1);
        assertThat(response.webResearchCalls()).isZero();
        assertThat(response.publicationRecovered()).isTrue();
        assertThat(response.completedWork()).containsExactly("browse_bgg_catalog", "recommend_games");
    }

    @Test
    void exposesTheTypedGuideAndQuestionContinuationWithoutInterpretingAssistantProse() {
        UUID teachingPlanId = UUID.randomUUID();
        Game game = comparisonGame(316554, "Dune: Imperium", 120, List.of("Deck Building"));
        var domain = new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                "这款符合你的偏好。",
                RecommendationProfile.empty(),
                null,
                1,
                1,
                new BoardGameRecommendationAgent.UserModelView("", List.of()),
                List.of(),
                new BoardGameRecommendationAgent.HarnessTrace(1, 1, 0, false, List.of("RECOMMEND_GAMES")),
                List.of(new RecommendedGame(
                        game,
                        List.of("符合偏好"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new BoardGameRecommendationAgent.TeachingContinuation(teachingPlanId, 4, 11))),
                null,
                null,
                "先从这款开始。",
                new BoardGameRecommendationAgent.RecommendationContinuation(
                        BoardGameRecommendationAgent.ContinuationKind.GUIDE_AND_RULE_QA,
                        "先学会第一轮行动，再继续规则答疑",
                        BoardGameRecommendationAgent.ContinuationAvailability.AVAILABLE_FOR_ALL,
                        1,
                        1));

        var response = BggRecommendationAgentController.RecommendationConversationResponse.from(
                domain,
                new LocalizedTaxonomy(Map.of(), Map.of()),
                "zh-CN",
                presentation,
                null,
                null,
                null,
                false);

        assertThat(response.continuation()).satisfies(continuation -> {
            assertThat(continuation.kind()).isEqualTo("guide_and_rule_qa");
            assertThat(continuation.learningGoal()).isEqualTo("先学会第一轮行动，再继续规则答疑");
            assertThat(continuation.availability()).isEqualTo("available_for_all");
            assertThat(continuation.readyCount()).isEqualTo(1);
            assertThat(continuation.candidateCount()).isEqualTo(1);
        });
        assertThat(response.games()).singleElement().satisfies(recommended -> {
            assertThat(recommended.teachingContinuation().teachingPlanId()).isEqualTo(teachingPlanId);
            assertThat(recommended.teachingContinuation().sectionCount()).isEqualTo(4);
            assertThat(recommended.teachingContinuation().stepCount()).isEqualTo(11);
        });
    }

    @Test
    void doesNotPublishInternalExecutionTraceForAnOrdinaryConversationReply() {
        var domain = new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_FAST_PATH,
                "嗨，今天想聊哪款桌游？",
                RecommendationProfile.empty(),
                null,
                0,
                0,
                new BoardGameRecommendationAgent.UserModelView("", List.of()),
                List.of(),
                new BoardGameRecommendationAgent.HarnessTrace(
                        1,
                        0,
                        0,
                        false,
                        List.of("STREAMED_NATURAL_REPLY:GREETING")),
                List.of(),
                null);

        var response = BggRecommendationAgentController.RecommendationConversationResponse.from(
                domain,
                new LocalizedTaxonomy(Map.of(), Map.of()),
                "zh-CN",
                presentation,
                null,
                null,
                null,
                false);

        assertThat(response.assistantMessage()).isEqualTo("嗨，今天想聊哪款桌游？");
        assertThat(response.completedWork()).isEmpty();
        assertThat(response.failureBoundary()).isNull();
    }

    @Test
    void exposesOnlyAStablePlayerSafeFailureBoundary() {
        var domain = new ConversationResponse(
                Outcome.UNAVAILABLE,
                DecisionMode.MODEL_ASSISTED,
                "这轮推荐没有完成。",
                RecommendationProfile.empty(),
                null,
                0,
                0,
                new BoardGameRecommendationAgent.UserModelView("", List.of()),
                List.of(),
                new BoardGameRecommendationAgent.HarnessTrace(
                        1,
                        0,
                        0,
                        false,
                        List.of("RUN_DEADLINE_EXCEEDED", "UNAVAILABLE:RUN_DEADLINE_EXCEEDED")),
                List.of(),
                null);

        var response = BggRecommendationAgentController.RecommendationConversationResponse.from(
                domain,
                new LocalizedTaxonomy(Map.of(), Map.of()),
                "zh-CN",
                presentation,
                null,
                null,
                null,
                false);

        assertThat(response.failureBoundary()).isEqualTo("time_budget");
        assertThat(response.completedWork()).isEmpty();
    }

    @Test
    void preservesALongNaturalUnicodeRequestWithoutAControllerSpecificCharacterLimit() throws Exception {
        String accepted = "😀".repeat(1_500) + "  A\n中";
        List<String> receivedMessages = new ArrayList<>();
        when(agent.converse(any(), eq("zh-CN"), eq("player"))).thenAnswer(invocation -> {
            var command = invocation.getArgument(
                    0, BoardGameRecommendationAgent.ConversationRequest.class);
            receivedMessages.add(command.message());
            return new ConversationResponse(
                    Outcome.CONVERSATION,
                    DecisionMode.MODEL_ASSISTED,
                    "完整收到。",
                    RecommendationProfile.empty(),
                    null,
                    0,
                    0,
                    List.of());
        });
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RecommendationConversationExceptionHandler())
                .build();
        var objectMapper = new ObjectMapper();

        mockMvc.perform(post("/api/v1/bgg/recommendation-agent")
                        .principal(principal)
                        .queryParam("locale", "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("message", accepted))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage").value("完整收到。"));

        assertThat(receivedMessages).containsExactly(accepted);
        verify(agent, times(1)).converse(any(), eq("zh-CN"), eq("player"));
    }

    @Test
    void doesNotApplyThePlayerInputLimitToAnAssistantTranscriptTurn() throws Exception {
        String assistantTurn = "答".repeat(800);
        when(agent.converse(any(), eq("zh-CN"), eq("player"))).thenAnswer(invocation -> {
            var command = invocation.getArgument(
                    0, BoardGameRecommendationAgent.ConversationRequest.class);
            assertThat(command.transcript()).singleElement().satisfies(message -> {
                assertThat(message.role()).isEqualTo("assistant");
                assertThat(message.text()).isEqualTo(assistantTurn);
            });
            return new ConversationResponse(
                    Outcome.CONVERSATION,
                    DecisionMode.MODEL_ASSISTED,
                    "继续。",
                    RecommendationProfile.empty(),
                    null,
                    0,
                    0,
                    List.of());
        });
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RecommendationConversationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/bgg/recommendation-agent")
                        .principal(principal)
                        .queryParam("locale", "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "message", "继续",
                                "transcript", List.of(Map.of("role", "assistant", "text", assistantTurn))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage").value("继续。"));
    }

    @Test
    void exposesStableTurnMetadataAndRestoresAndDeletesOnlyThroughTheConversationCoordinator() {
        RecommendationConversationCoordinator conversations = mock(RecommendationConversationCoordinator.class);
        var statefulController = new BggRecommendationAgentController(agent, presentation, conversations);
        UUID conversationId = UUID.randomUUID();
        UUID clientTurnId = UUID.randomUUID();
        ConversationResponse conversationResponse = new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_ASSISTED,
                "I kept the context.",
                RecommendationProfile.empty(),
                null,
                10,
                0,
                List.of());
        when(conversations.converse(any(), eq("en"), eq("player"), any()))
                .thenReturn(new TurnResult(
                        conversationId, 5, clientTurnId, false, "en", conversationResponse));
        when(presentation.localizeTaxonomy(List.of(), List.of(), "en"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));
        var request = new BggRecommendationAgentController.RecommendationConversationRequest(
                null,
                "Continue",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                conversationId,
                4L,
                clientTurnId);

        var turn = statefulController.converse(request, "en", principal);

        assertThat(turn.conversationId()).isEqualTo(conversationId);
        assertThat(turn.revision()).isEqualTo(5);
        assertThat(turn.clientTurnId()).isEqualTo(clientTurnId);
        assertThat(turn.replayed()).isFalse();
        assertThat(turn.responseLocale()).isEqualTo("en");
        assertThat(turn.assistantMessage()).isEqualTo("I kept the context.");

        ConversationState state = new ConversationState(
                RecommendationProfile.empty(),
                List.of(
                        new BoardGameRecommendationAgent.DialogueMessage("user", "Continue"),
                        new BoardGameRecommendationAgent.DialogueMessage("assistant", "I kept the context.")),
                List.of(new BoardGameRecommendationAgent.KnownGame(1, "Candidate", "Candidate")),
                List.of(1));
        when(conversations.latest("player")).thenReturn(Optional.of(new SessionSnapshot(
                conversationId,
                5,
                state,
                clientTurnId,
                null,
                null,
                conversationResponse,
                "en")));

        var restored = statefulController.latest(principal).getBody();

        assertThat(restored).isNotNull();
        assertThat(restored.conversationId()).isEqualTo(conversationId);
        assertThat(restored.revision()).isEqualTo(5);
        assertThat(restored.transcript()).extracting(value -> value.text())
                .containsExactly("Continue", "I kept the context.");
        assertThat(restored.knownGames()).extracting(value -> value.bggId()).containsExactly(1);
        assertThat(restored.latestResponse().clientTurnId()).isEqualTo(clientTurnId);
        assertThat(restored.latestResponse().assistantMessage()).isEqualTo("I kept the context.");

        statefulController.delete(conversationId, principal);
        verify(conversations).delete(conversationId, "player");
    }

    @Test
    void exposesTheTypedClarificationAndNormalizedProfile() {
        RecommendationProfile profile = new RecommendationProfile(
                ConstraintRange.hard(3, 4, "3–4 人", 2),
                ConstraintRange.hard(60, 90, "60–90 分钟", 2),
                null,
                BggGameType.ALL,
                InteractionPreference.ANY);
        when(agent.converse(any(), eq("zh-CN"), eq("player"))).thenReturn(new ConversationResponse(
                Outcome.NEEDS_CLARIFICATION,
                DecisionMode.MODEL_ASSISTED,
                "你们愿意为一局留出多长时间？",
                profile,
                new Clarification(
                        PreferenceField.CONVERSATION,
                        "你们愿意为一局留出多长时间？",
                        List.of()),
                0,
                0,
                List.of()));
        when(presentation.localizeTaxonomy(List.of(), List.of(), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));

        var response = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(
                        new BggRecommendationAgentController.RecommendationProfileRequest(
                                4, null, null, "all", "any"),
                        ""),
                "zh-CN",
                principal);

        assertThat(response.outcome()).isEqualTo("needs_clarification");
        assertThat(response.profile().players()).isNull();
        assertThat(response.profile().maxMinutes()).isEqualTo(90);
        assertThat(response.profile().playerCount()).satisfies(range -> {
            assertThat(range.minimum()).isEqualTo(3);
            assertThat(range.maximum()).isEqualTo(4);
            assertThat(range.sourceText()).isEqualTo("3–4 人");
            assertThat(range.confirmedTurn()).isEqualTo(2);
        });
        assertThat(response.profile().durationMinutes()).satisfies(range -> {
            assertThat(range.minimum()).isEqualTo(60);
            assertThat(range.maximum()).isEqualTo(90);
        });
        assertThat(response.clarification().field()).isEqualTo("conversation");
        assertThat(response.clarification().options()).isEmpty();
    }

    @Test
    void acceptsTwoSidedConstraintRangesWithoutCollapsingTheirEvidence() {
        var request = new BggRecommendationAgentController.RecommendationConversationRequest(
                new BggRecommendationAgentController.RecommendationProfileRequest(
                        null,
                        null,
                        null,
                        "strategy",
                        "competitive",
                        new BggRecommendationAgentController.ConstraintRangeRequest<>(
                                3, 5, "hard", "3–5 人", 4),
                        new BggRecommendationAgentController.ConstraintRangeRequest<>(
                                90, 150, "hard", "90–150 分钟", 4),
                        new BggRecommendationAgentController.ConstraintRangeRequest<>(
                                new BigDecimal("2.5"), new BigDecimal("3.5"), "hard", "复杂度 2.5–3.5", 4)),
                "继续推荐");

        var profile = request.toCommand().profile();

        assertThat(profile.minPlayers()).isEqualTo(3);
        assertThat(profile.maxPlayers()).isEqualTo(5);
        assertThat(profile.minMinutes()).isEqualTo(90);
        assertThat(profile.maxMinutes()).isEqualTo(150);
        assertThat(profile.minWeight()).isEqualByComparingTo("2.5");
        assertThat(profile.maxWeight()).isEqualByComparingTo("3.5");
        assertThat(profile.playerCount().sourceText()).isEqualTo("3–5 人");
        assertThat(profile.playerCount().confirmedTurn()).isEqualTo(4);
        assertThat(profile.type()).isEqualTo(BggGameType.STRATEGY);
        assertThat(profile.interaction()).isEqualTo(InteractionPreference.COMPETITIVE);
    }

    @Test
    void returnsAttributedCardsWithOfficialChineseNamesAndTranslatedTaxonomy() {
        RecommendationProfile profile = new RecommendationProfile(
                4, 90, new BigDecimal("3.2"), BggGameType.STRATEGY, InteractionPreference.COOPERATIVE);
        Ranking ranked = new Ranking(
                266192,
                "Wingspan",
                2019,
                34,
                new BigDecimal("7.79"),
                new BigDecimal("8.09"),
                102_030);
        Details details = new Details(
                "Wingspan",
                "展翅翱翔",
                "https://example.test/wingspan.jpg",
                1,
                5,
                70,
                new BigDecimal("2.5"),
                List.of("Animals"),
                List.of("Card Drafting"),
                40,
                70,
                10,
                10,
                "3",
                "2-4",
                2,
                1_000,
                List.of("Animals: Birds"),
                List.of("Elizabeth Hargrave"),
                List.of("Stonemaier Games"));
        when(agent.converse(any(), eq("zh-CN"), eq("player"))).thenReturn(new ConversationResponse(
                Outcome.RECOMMENDATIONS,
                DecisionMode.MODEL_ASSISTED,
                "下面这些各有侧重。",
                profile,
                null,
                179_737,
                20,
                List.of(new RecommendedGame(
                        new Game(ranked, details),
                        List.of("支持 4 人游玩", "与参考游戏共有的 BGG 机制/类型：Animals、Card Drafting"),
                        List.of("Card Drafting 这里只是模型原文中的术语，不应在 DTO 层被字符串改写。"),
                        List.of(
                                new RecommendationReason(
                                        ReasonKind.PREFERENCE_INFERENCE,
                                        "模型原文保留 Animals 与 Card Drafting；它支持 4 人且标注 40–70 分钟。",
                                        List.of()),
                                new RecommendationReason(
                                        ReasonKind.BGG_FACT,
                                        "BGG 机制/类型标签：Animals、Card Drafting",
                                        List.of())),
                        List.of(new CandidateClaim(
                                266192,
                                "playerCount",
                                CandidateClaim.Type.CONSTRAINT_FIT,
                                ConstraintRange.Strength.HARD,
                                CandidateClaim.Relation.SATISFIED,
                                "候选人数 1–5 人与硬条件 4 人：满足。",
                                List.of(new CandidateObservation(
                                        "bgg-266192-playerCount",
                                        266192,
                                        CandidateObservation.Kind.STRUCTURED_METADATA,
                                        "playerCount",
                                        "1..5",
                                        List.of()))))))));
        when(presentation.localizeTaxonomy(List.of("Animals"), List.of("Card Drafting"), "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(
                        Map.of("Animals", "动物"), Map.of("Card Drafting", "卡牌轮抽")));
        when(presentation.usesSimplifiedChinese("zh-CN")).thenReturn(true);

        var response = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(null, "四个人一起玩"),
                "zh-CN",
                principal);

        assertThat(response.sourceCount()).isEqualTo(179_737);
        assertThat(response.games()).singleElement().satisfies(game -> {
            assertThat(game.game().name()).isEqualTo("展翅翱翔");
            assertThat(game.game().originalName()).isEqualTo("Wingspan");
            assertThat(game.game().nameLocalized()).isTrue();
            assertThat(game.game().categories()).containsExactly("动物");
            assertThat(game.game().mechanics()).containsExactly("卡牌轮抽");
            assertThat(game.game().minimumPlayTimeMinutes()).isEqualTo(40);
            assertThat(game.game().maximumPlayTimeMinutes()).isEqualTo(70);
            assertThat(game.game().minimumAge()).isEqualTo(10);
            assertThat(game.game().suggestedMinimumAge()).isEqualTo(10);
            assertThat(game.game().bestWith()).isEqualTo("3");
            assertThat(game.game().recommendedWith()).isEqualTo("2-4");
            assertThat(game.game().languageDependenceLevel()).isEqualTo(2);
            assertThat(game.game().weightVotes()).isEqualTo(1_000);
            assertThat(game.game().families()).containsExactly("Animals: Birds");
            assertThat(game.game().designers()).containsExactly("Elizabeth Hargrave");
            assertThat(game.game().publishers()).containsExactly("Stonemaier Games");
            assertThat(game.matches())
                    .containsExactly("支持 4 人游玩", "与参考游戏共有的 BGG 机制/类型：动物、卡牌轮抽");
            assertThat(game.reasons()).first().satisfies(reason -> {
                assertThat(reason.kind()).isEqualTo("preference_inference");
                assertThat(reason.text()).isEqualTo(
                        "模型原文保留 Animals 与 Card Drafting；它支持 4 人且标注 40–70 分钟。");
            });
            assertThat(game.reasons()).last().satisfies(reason -> {
                assertThat(reason.kind()).isEqualTo("bgg_fact");
                assertThat(reason.text()).isEqualTo("BGG 机制/类型标签：动物、卡牌轮抽");
            });
            assertThat(game.tradeoffs())
                    .containsExactly("Card Drafting 这里只是模型原文中的术语，不应在 DTO 层被字符串改写。");
            assertThat(game.fitClaims()).singleElement().satisfies(claim -> {
                assertThat(claim.subject()).isEqualTo("playerCount");
                assertThat(claim.strength()).isEqualTo("hard");
                assertThat(claim.relation()).isEqualTo("satisfied");
                assertThat(claim.text()).contains("候选人数", "满足");
            });
        });
    }

    @Test
    void passesVerifiedConversationGamesAndShownIdsToTheAgentWithoutClientSideInterpretation() {
        var request = new BggRecommendationAgentController.RecommendationConversationRequest(
                null,
                "它和第二款有什么不同？",
                List.of(),
                List.of(new BggRecommendationAgentController.DialogueMessageRequest(
                        "user", "它和第二款有什么不同？")),
                null,
                List.of(
                        new BggRecommendationAgentController.KnownGameRequest(101, "候选一", "Candidate One"),
                        new BggRecommendationAgentController.KnownGameRequest(102, "候选二", "Candidate Two")),
                List.of(101, 102));

        var command = request.toCommand();

        assertThat(command.focusedBggId()).isNull();
        assertThat(command.knownGames()).extracting(game -> game.bggId()).containsExactly(101, 102);
        assertThat(command.shownBggIds()).containsExactly(101, 102);
    }

    @Test
    void presentsAComparisonWithAttributedReportsSourcesAndExplicitUnknownCellsWithoutObservationIds() {
        Game first = comparisonGame(301, "Opaque One", 45, List.of("Pattern Building"));
        Game second = comparisonGame(302, "Opaque Two", 75, List.of("Open Drafting"));
        var comparison = new BoardGameRecommendationAgent.CandidateComparison(
                List.of(
                        new BoardGameRecommendationAgent.ComparisonCandidate(first, List.of()),
                        new BoardGameRecommendationAgent.ComparisonCandidate(second, List.of())),
                List.of(
                        new BoardGameRecommendationAgent.ComparisonAxis(
                                "mechanics",
                                List.of(
                                        new BoardGameRecommendationAgent.ComparisonCell(
                                                301,
                                                new CandidateObservation(
                                                        "B301:mechanics",
                                                        301,
                                                        CandidateObservation.Kind.TAXONOMY,
                                                        "mechanics",
                                                        "Pattern Building",
                                                        List.of())),
                                        new BoardGameRecommendationAgent.ComparisonCell(
                                                302,
                                                new CandidateObservation(
                                                        "B302:mechanics",
                                                        302,
                                                        CandidateObservation.Kind.TAXONOMY,
                                                        "mechanics",
                                                        "Open Drafting",
                                                        List.of())))),
                        new BoardGameRecommendationAgent.ComparisonAxis(
                                "reportedExperience",
                                List.of(
                                        new BoardGameRecommendationAgent.ComparisonCell(
                                                301,
                                                new CandidateObservation(
                                                        "R301:1",
                                                        301,
                                                        CandidateObservation.Kind.ATTRIBUTED_REPORT,
                                                        "reportedExperience",
                                                        "A sourced four-player report describes deliberate interaction.",
                                                        List.of(7))),
                                        new BoardGameRecommendationAgent.ComparisonCell(302, null)))));
        when(agent.converse(any(), eq("zh-CN"), eq("player"))).thenReturn(new ConversationResponse(
                Outcome.CONVERSATION,
                DecisionMode.MODEL_ASSISTED,
                "我把两款并排核对。",
                RecommendationProfile.empty(),
                null,
                0,
                2,
                new BoardGameRecommendationAgent.UserModelView("", List.of()),
                List.of(new BoardGameRecommendationAgent.ResearchSource(
                        7,
                        "Independent play report",
                        "https://reports.example.test/opaque-one",
                        "reports.example.test")),
                new BoardGameRecommendationAgent.HarnessTrace(1, 1, 0, false, List.of("COMPARE_CANDIDATES")),
                List.of(),
                comparison));
        when(presentation.localizeTaxonomy(
                        List.of("Abstract Strategy"),
                        List.of("Pattern Building", "Open Drafting"),
                        "zh-CN"))
                .thenReturn(new LocalizedTaxonomy(Map.of(), Map.of()));

        var response = controller.converse(
                new BggRecommendationAgentController.RecommendationConversationRequest(
                        null, "比较这两款"),
                "zh-CN",
                principal);

        assertThat(response.comparison().candidates())
                .extracting(candidate -> candidate.game().name())
                .containsExactly("Opaque One", "Opaque Two");
        assertThat(response.comparison().axes().getFirst()).satisfies(axis -> {
            assertThat(axis.subject()).isEqualTo("mechanics");
            assertThat(axis.label()).isEqualTo("BGG 机制（仅分类）");
            assertThat(axis.capability()).isEqualTo("taxonomy");
            assertThat(axis.cells()).extracting(cell -> cell.status()).containsOnly("observed");
            assertThat(axis.cells()).extracting(cell -> cell.value())
                    .containsExactly("Pattern Building", "Open Drafting");
        });
        assertThat(response.comparison().axes().get(1)).satisfies(axis -> {
            assertThat(axis.label()).isEqualTo("有来源的玩家体验");
            assertThat(axis.capability()).isEqualTo("attributed_report");
            assertThat(axis.cells().getFirst()).satisfies(cell -> {
                assertThat(cell.status()).isEqualTo("observed");
                assertThat(cell.observationKind()).isEqualTo("attributed_report");
                assertThat(cell.value()).isEqualTo(
                        "A sourced four-player report describes deliberate interaction.");
            });
            assertThat(axis.cells().getLast()).satisfies(cell -> {
                assertThat(cell.status()).isEqualTo("unknown");
                assertThat(cell.observationKind()).isEmpty();
                assertThat(cell.value()).isEmpty();
            });
        });
        assertThat(response.researchSources()).singleElement().satisfies(source -> {
            assertThat(source.index()).isEqualTo(7);
            assertThat(source.title()).isEqualTo("Independent play report");
        });
    }

    private static Game comparisonGame(int id, String name, int minutes, List<String> mechanics) {
        return new Game(
                new Ranking(
                        id,
                        name,
                        2025,
                        null,
                        new BigDecimal("7.1"),
                        new BigDecimal("7.4"),
                        400,
                        List.of()),
                new Details(
                        name,
                        "",
                        "",
                        2,
                        4,
                        minutes,
                        new BigDecimal("2.3"),
                        List.of("Abstract Strategy"),
                        mechanics,
                        minutes,
                        minutes,
                        10,
                        10,
                        "",
                        "",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()));
    }
}
