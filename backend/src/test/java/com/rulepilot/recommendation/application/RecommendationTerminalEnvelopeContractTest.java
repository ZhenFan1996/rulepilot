package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Details;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Ranking;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.InteractionPreference;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationTerminalEnvelopeContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RecommendationReActLoop runtime = mock(RecommendationReActLoop.class);
    private final RecommendationEvidenceReview evidenceReview = new RecommendationEvidenceReview(json, runtime);
    private final RecommendationActions actions =
            new RecommendationActions(null, null, null, json, evidenceReview, runtime);

    @Test
    void rejectsTheRetiredMessageFieldInsteadOfReadingBusinessOutputFromIt() {
        ConversationRequest request = request();
        RecommendationAgentState state = state(request);

        RecommendationActions.ActionOutcome outcome = actions.execute(
                new ToolCall("call-1", BoardGameRecommendationAgent.REPLY_TOOL, "{\"message\":\"旧协议回答\"}"),
                state,
                request,
                "zh-CN",
                ignored -> {});

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.response()).isNull();
        assertThat(state.actions).doesNotContain("REPLY_TO_USER");
    }

    @Test
    void publishesOnlyTheValidatedFreeReplyFieldAndPreservesItsNaturalFormatting() {
        ConversationRequest request = request();
        RecommendationAgentState state = state(request);

        RecommendationActions.ActionOutcome outcome = actions.execute(
                new ToolCall(
                        "call-2",
                        BoardGameRecommendationAgent.REPLY_TOOL,
                        "{\"playerReply\":\"  今晚先看这一类。\\n\\n如果你想更短，我再换一个方向。  \"}"),
                state,
                request,
                "zh-CN",
                ignored -> {});

        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.response()).isNotNull();
        assertThat(outcome.response().assistantMessage())
                .isEqualTo("今晚先看这一类。\n\n如果你想更短，我再换一个方向。");
        assertThat(state.actions).containsExactly("REPLY_TO_USER");
    }

    @Test
    void publishesOneNaturalReasonPerCardWhileCardFactsRemainCatalogOwned() {
        RecommendationProfile profile = new RecommendationProfile(
                3, 60, null, BggGameType.ALL, InteractionPreference.ANY);
        ConversationRequest request = new ConversationRequest(profile, "三个人，六十分钟以内");
        RecommendationAgentState state = state(request);
        Game game = game(101);
        state.addVerified(game);
        BoardGameRecommendationSelector selector = selector();
        RecommendationReActLoop recommendationRuntime = mock(RecommendationReActLoop.class);
        when(recommendationRuntime.recommendableIds(state)).thenReturn(List.of(101));
        when(recommendationRuntime.chinese("zh-CN")).thenReturn(true);
        when(recommendationRuntime.responseSources(eq(state), anyList())).thenReturn(List.of());
        RecommendationEvidenceReview review = new RecommendationEvidenceReview(json, recommendationRuntime);
        RecommendationActions recommendationActions = new RecommendationActions(
                null,
                selector,
                properties(),
                json,
                review,
                recommendationRuntime);

        RecommendationActions.ActionOutcome outcome = recommendationActions.execute(
                new ToolCall(
                        "call-3",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        """
                        {
                          "selections":[{
                            "bggId":101,
                            "reason":"它把合作目标放在很直观的路径修复上，三个人讨论起来会有共同焦点。",
                            "tradeoff":"如果你们今晚只想轻松闲聊，它仍可能需要持续协商。"
                          }],
                          "requestedCount":1,
                          "playerReply":"这款最值得先看。"
                        }
                        """),
                state,
                request,
                "zh-CN",
                ignored -> {});

        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.response().recommendationLead()).isEqualTo("这款最值得先看。");
        assertThat(outcome.response().assistantMessage())
                .contains("它把合作目标放在很直观的路径修复上")
                .contains("如果你们今晚只想轻松闲聊");
        assertThat(outcome.response().games()).singleElement().satisfies(recommended -> {
            assertThat(recommended.game().details().minPlayers()).isEqualTo(2);
            assertThat(recommended.game().details().maxPlayers()).isEqualTo(4);
            assertThat(recommended.game().details().maximumPlayTimeMinutes()).isEqualTo(60);
            assertThat(recommended.replyParts()).hasSize(2).allSatisfy(part -> {
                assertThat(part.claim().type())
                        .isEqualTo(com.rulepilot.recommendation.CandidateClaim.Type.PREFERENCE_INFERENCE);
                assertThat(part.claim().evidence()).isEmpty();
            });
        });
    }

    @Test
    void rejectsAReasonAttachedToAnUnverifiedCardIdentity() {
        RecommendationProfile profile = new RecommendationProfile(
                3, 60, null, BggGameType.ALL, InteractionPreference.ANY);
        ConversationRequest request = new ConversationRequest(profile, "三个人，六十分钟以内");
        RecommendationAgentState state = state(request);
        state.addVerified(game(101));
        BoardGameRecommendationSelector selector = selector();
        RecommendationReActLoop recommendationRuntime = mock(RecommendationReActLoop.class);
        when(recommendationRuntime.recommendableIds(state)).thenReturn(List.of(101));
        when(recommendationRuntime.chinese("zh-CN")).thenReturn(true);
        RecommendationActions recommendationActions = new RecommendationActions(
                null,
                selector,
                properties(),
                json,
                new RecommendationEvidenceReview(json, recommendationRuntime),
                recommendationRuntime);

        RecommendationActions.ActionOutcome outcome = recommendationActions.execute(
                new ToolCall(
                        "call-4",
                        BoardGameRecommendationAgent.RECOMMEND_TOOL,
                        """
                        {"selections":[{"bggId":999,"reason":"这款很适合你们。"}],"requestedCount":1,"playerReply":"先看这款。"}
                        """),
                state,
                request,
                "zh-CN",
                ignored -> {});

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.response()).isNull();
        assertThat(state.actions).contains("REJECTED_ACTION:FINAL_ID_NOT_VERIFIED");
    }

    private ConversationRequest request() {
        return new ConversationRequest(RecommendationProfile.empty(), "换一个方向");
    }

    private RecommendationAgentState state(ConversationRequest request) {
        return new RecommendationAgentState(request, System.nanoTime(), null, false, 3);
    }

    private BoardGameRecommendationProperties properties() {
        return new BoardGameRecommendationProperties(
                8, 3, new BigDecimal("0.66"), Duration.ofSeconds(30));
    }

    private BoardGameRecommendationSelector selector() {
        return new BoardGameRecommendationSelector(properties());
    }

    private Game game(int id) {
        return new Game(
                new Ranking(
                        id,
                        "Signal Grove",
                        2024,
                        id,
                        new BigDecimal("7.0"),
                        new BigDecimal("7.3"),
                        500,
                        List.of(BggGameType.STRATEGY)),
                new Details(
                        "Signal Grove",
                        "",
                        "",
                        2,
                        4,
                        60,
                        new BigDecimal("2.8"),
                        List.of("Strategy"),
                        List.of("Cooperative Game"),
                        45,
                        60,
                        10,
                        10,
                        "4",
                        "2-4",
                        2,
                        100,
                        List.of(),
                        List.of(),
                        List.of(),
                        "Players restore paths through the grove.",
                        ""));
    }
}
