package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
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
                (ignoredStage, ignoredFocus) -> {});

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
                (ignoredStage, ignoredFocus) -> {});

        assertThat(outcome.rejected()).isFalse();
        assertThat(outcome.response()).isNotNull();
        assertThat(outcome.response().assistantMessage())
                .isEqualTo("今晚先看这一类。\n\n如果你想更短，我再换一个方向。");
        assertThat(state.actions).containsExactly("REPLY_TO_USER");
    }

    private ConversationRequest request() {
        return new ConversationRequest(RecommendationProfile.empty(), "换一个方向");
    }

    private RecommendationAgentState state(ConversationRequest request) {
        return new RecommendationAgentState(request, System.nanoTime(), null, false, 3);
    }
}
