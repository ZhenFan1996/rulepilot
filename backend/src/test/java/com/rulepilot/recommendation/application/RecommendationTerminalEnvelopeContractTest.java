package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationTerminalEnvelopeContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RecommendationReActLoop runtime = mock(RecommendationReActLoop.class);
    private final RecommendationEvidenceReview evidenceReview = new RecommendationEvidenceReview(json, runtime);
    private final RecommendationActions actions =
            new RecommendationActions(null, null, null, json, evidenceReview, runtime);

    @Test
    void rejectsTheRetiredReplyActionInsteadOfReassemblingModelProse() {
        ConversationRequest request = request();
        RecommendationAgentState state = state(request);

        RecommendationActions.ActionOutcome outcome = actions.execute(
                new ToolCall("call-1", "reply_to_user", "{\"playerReply\":\"旧协议回答\"}"),
                state,
                request,
                "zh-CN",
                (ignoredStage, ignoredFocus) -> {});

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.response()).isNull();
        assertThat(state.actions).doesNotContain("REPLY_TO_USER");
    }

    @Test
    void preferenceActionCanPublishItsCompleteTypedPlayerReply() {
        ConversationRequest request = request();
        RecommendationAgentState state = state(request);

        RecommendationActions.ActionOutcome outcome = actions.execute(
                new ToolCall(
                        "call-2",
                        BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL,
                        "{\"preferenceUpdates\":{\"evidence\":\"U1\",\"playerCount\":4},"
                                + "\"playerReply\":\"记住了：以后默认按四个人玩。\"}"),
                state,
                request,
                "zh-CN",
                (ignoredStage, ignoredFocus) -> {});

        assertThat(outcome.rejected())
                .withFailMessage("unexpected rejection: %s / %s", outcome.rejectionCode(), outcome.observation())
                .isFalse();
        assertThat(outcome.response()).isNotNull();
        assertThat(outcome.response().assistantMessage()).isEqualTo("记住了：以后默认按四个人玩。");
        assertThat(outcome.observation()).isEmpty();
        assertThat(state.profile.playerCount().minimum()).isEqualTo(4);
        assertThat(state.profile.playerCount().maximum()).isEqualTo(4);
        assertThat(state.actions).containsExactly("UPDATE_PREFERENCES");
    }

    @Test
    void rejectsDedicatedPreferenceActionWhenUpdateListIsEmpty() {
        ConversationRequest request = request();
        RecommendationAgentState state = state(request);

        RecommendationActions.ActionOutcome outcome = actions.execute(
                new ToolCall(
                        "call-empty-preferences",
                        BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL,
                        "{\"preferenceUpdates\":[]}"),
                state,
                request,
                "zh-CN",
                (ignoredStage, ignoredFocus) -> {});

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.rejectionCode()).isEqualTo("EMPTY_PREFERENCE_UPDATE");
        assertThat(state.profile).isEqualTo(RecommendationProfile.empty());
    }

    @Test
    void rejectsDedicatedPreferenceActionWhenEveryUpdateIsInvalid() {
        ConversationRequest request = request();
        RecommendationAgentState state = state(request);

        RecommendationActions.ActionOutcome outcome = actions.execute(
                new ToolCall(
                        "call-invalid-preferences",
                        BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL,
                        "{\"preferenceUpdates\":[{\"field\":\"playerCount\",\"value\":\"four\","
                                + "\"evidence\":\"U1\",\"evidenceClassification\":\"DIRECT\"}]}"),
                state,
                request,
                "zh-CN",
                (ignoredStage, ignoredFocus) -> {});

        assertThat(outcome.rejected()).isTrue();
        assertThat(outcome.rejectionCode()).isEqualTo("ARGUMENT_OBJECT_REQUIRED");
        assertThat(state.profile).isEqualTo(RecommendationProfile.empty());
    }

    private ConversationRequest request() {
        String message = "以后默认 4 个人玩。";
        return new ConversationRequest(
                RecommendationProfile.empty(),
                message,
                List.of(),
                List.of(new DialogueMessage("user", message)),
                null,
                List.of(),
                List.of());
    }

    private RecommendationAgentState state(ConversationRequest request) {
        return new RecommendationAgentState(request, System.nanoTime(), null, false);
    }
}
