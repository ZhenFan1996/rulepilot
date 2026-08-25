package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.adapter.out.model.RecommendationDecisionStream.Failure;
import com.rulepilot.recommendation.adapter.out.model.RecommendationDecisionStream.FailureCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RecommendationDecisionStreamTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<ToolSpec> actions = List.of(
            new ToolSpec(
                    "search_catalog",
                    "Read verified local candidates.",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":80}},\"required\":[\"query\"]}"),
            new ToolSpec(
                    "ask_user",
                    "Ask one useful clarification.",
                    "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"question\":{\"type\":\"string\",\"maxLength\":160}},\"required\":[\"question\"]}"));

    @Test
    void publishesReplyDeltasOnlyAfterTheCompleteEnvelopePassesAtEveryCutPoint() {
        String payload =
                "{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"你好，\",\"一起找桌游吧 🎲\"]}";

        for (int cut = 0; cut <= payload.length(); cut++) {
            List<String> replies = new ArrayList<>();
            RecommendationDecisionStream stream = new RecommendationDecisionStream(json, actions, replies::add);

            stream.accept(payload.substring(0, cut));
            stream.accept(payload.substring(cut));
            assertThat(replies)
                    .as("provisional reply events at UTF-16 cut %s", cut)
                    .isEmpty();
            stream.finish();
            assertThat(replies).isEmpty();
            stream.publishReply();

            assertThat(replies)
                    .as("reply events at UTF-16 cut %s", cut)
                    .containsExactly("你好，", "你好，一起找桌游吧 🎲");
            assertThat(stream.turn(CompletionStatus.COMPLETE)).satisfies(turn -> {
                assertThat(turn.text()).isEqualTo("你好，一起找桌游吧 🎲");
                assertThat(turn.toolCalls()).isEmpty();
                assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.COMPLETE);
            });
        }
    }

    @Test
    void decodesOneTypedActionWithoutPublishingItsArgumentsOrNarration() {
        List<String> replies = new ArrayList<>();
        RecommendationDecisionStream stream = new RecommendationDecisionStream(json, actions, replies::add);
        String payload =
                "{\"mode\":\"ACTION\",\"action\":{\"name\":\"search_catalog\",\"arguments\":{\"query\":\"cooperative mystery\"}},\"replyDeltas\":[]}";

        for (int index = 0; index < payload.length(); index++) {
            stream.accept(payload.substring(index, index + 1));
        }
        stream.finish();

        assertThat(replies).isEmpty();
        assertThat(stream.turn(CompletionStatus.COMPLETE).toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("decision-action-1");
            assertThat(call.name()).isEqualTo("search_catalog");
            assertThat(call.argumentsJson()).isEqualTo("{\"query\":\"cooperative mystery\"}");
        });
        assertThat(stream.output().jsonSchema())
                .contains("search_catalog", "ask_user", "maxLength")
                .doesNotContain("playerReply");
    }

    @Test
    void rejectsTextOnAnActionAndAnActionOnAReplyBeforeAnythingCanReachTheListener() {
        assertFailure(
                FailureCode.INVALID_MODE_PAYLOAD,
                "{\"mode\":\"ACTION\",\"action\":null,\"replyDeltas\":[]}");
        assertFailure(
                FailureCode.INVALID_MODE_PAYLOAD,
                "{\"mode\":\"REPLY\",\"action\":{\"name\":\"search_catalog\",\"arguments\":{}},\"replyDeltas\":[]}");
        assertFailure(
                FailureCode.INVALID_MODE_PAYLOAD,
                "{\"mode\":\"ACTION\",\"action\":{\"name\":\"search_catalog\",\"arguments\":{}},\"replyDeltas\":[\"leak\"]}");
    }

    @Test
    void acceptsRootMembersInAnyOrderButRejectsUnknownActionsFieldsAndTrailingData() {
        assertFailure(
                FailureCode.INVALID_ACTION,
                "{\"mode\":\"ACTION\",\"action\":{\"name\":\"delete_everything\",\"arguments\":{}},\"replyDeltas\":[]}");
        List<String> replies = new ArrayList<>();
        RecommendationDecisionStream reordered = new RecommendationDecisionStream(json, actions, replies::add);
        reordered.accept("{\"replyDeltas\":[\"hello\"],\"action\":null,\"mode\":\"REPLY\"}");
        reordered.finish();
        reordered.publishReply();
        assertThat(reordered.turn(CompletionStatus.COMPLETE).text()).isEqualTo("hello");
        assertThat(replies).containsExactly("hello");
        assertFailure(
                FailureCode.INVALID_ENVELOPE,
                "{\"mode\":\"REPLY\",\"action\":null}");
        assertFailure(
                FailureCode.UNKNOWN_ROOT_FIELD,
                "{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"hello\"],\"extra\":true}");
        assertFailure(
                FailureCode.TRAILING_DATA,
                "{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"hello\"]} true");
    }

    @Test
    void acceptsOneLongProviderFragmentButStillEnforcesTheWholeReplyAndUnicodeLimits() {
        String longFragment = "x".repeat(320);
        List<String> replies = new ArrayList<>();
        RecommendationDecisionStream accepted = new RecommendationDecisionStream(json, actions, replies::add);
        accepted.accept("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\""
                + longFragment
                + "\"]}");
        accepted.finish();
        assertThat(replies).isEmpty();
        accepted.publishReply();
        assertThat(replies).containsExactly(longFragment);

        assertFailure(
                FailureCode.REPLY_LIMIT_EXCEEDED,
                "{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\""
                        + "x".repeat(700)
                        + "\",\""
                        + "y".repeat(501)
                        + "\"]}");

        RecommendationDecisionStream truncated = new RecommendationDecisionStream(json, actions, ignored -> {});
        truncated.accept("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"unfinished");
        assertFailure(FailureCode.TRUNCATED_JSON, truncated::finish);

        RecommendationDecisionStream surrogate = new RecommendationDecisionStream(json, actions, ignored -> {});
        surrogate.accept("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"");
        surrogate.accept("\uD83D");
        assertFailure(FailureCode.INVALID_UNICODE, surrogate::finish);
    }

    @Test
    void listenerFailureIsTerminalAndNeverInvokesLaterDeltas() {
        AtomicInteger calls = new AtomicInteger();
        RecommendationDecisionStream stream = new RecommendationDecisionStream(json, actions, ignored -> {
            calls.incrementAndGet();
            throw new IllegalStateException("client disconnected");
        });

        stream.accept(
                "{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"first\",\"second\"]}");
        stream.finish();
        assertThat(calls).hasValue(0);
        Failure failure = assertFailure(FailureCode.LISTENER_FAILURE, stream::publishReply);

        assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
        assertFailure(FailureCode.LISTENER_FAILURE, stream::publishReply);
    }

    private Failure assertFailure(FailureCode expected, String payload) {
        RecommendationDecisionStream stream = new RecommendationDecisionStream(json, actions, ignored -> {});
        return assertFailure(expected, () -> {
            stream.accept(payload);
            stream.finish();
        });
    }

    private Failure assertFailure(FailureCode expected, ThrowingCallable operation) {
        Throwable thrown = catchThrowable(operation);
        assertThat(thrown).isInstanceOf(Failure.class);
        Failure failure = (Failure) thrown;
        assertThat(failure.code()).isEqualTo(expected);
        return failure;
    }
}
