package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.application.RecommendationPublicationStream.Failure;
import com.rulepilot.recommendation.application.RecommendationPublicationStream.FailureCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RecommendationPublicationStreamTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void decodesEveryCharacterCutPointWithoutLosingEscapesBracesChineseOrEmoji() throws Exception {
        String payload = """
                {"decision":{"kind":"recommend","message":"quote: \\\" slash: \\\\ braces: {[]} 中文 😀","nested":{"ok":true}},"replyBlocks":[{"type":"text","text":"first {literal}\\n中文 😀"},{"type":"card","data":{"title":"second"}}]}
                """
                .strip();
        JsonNode expectedDecision = json.readTree(
                """
                {"kind":"recommend","message":"quote: \\\" slash: \\\\ braces: {[]} 中文 😀","nested":{"ok":true}}
                """);
        List<JsonNode> expectedBlocks = List.of(
                json.readTree("{\"type\":\"text\",\"text\":\"first {literal}\\n中文 😀\"}"),
                json.readTree("{\"type\":\"card\",\"data\":{\"title\":\"second\"}}"));

        for (int cut = 0; cut <= payload.length(); cut++) {
            List<JsonNode> decisions = new ArrayList<>();
            List<JsonNode> blocks = new ArrayList<>();
            RecommendationPublicationStream stream =
                    new RecommendationPublicationStream(json, decisions::add, blocks::add);

            stream.accept(payload.substring(0, cut));
            stream.accept(payload.substring(cut));
            stream.finish();

            assertThat(decisions).as("decision at UTF-16 cut %s", cut).containsExactly(expectedDecision);
            assertThat(blocks).as("blocks at UTF-16 cut %s", cut).containsExactlyElementsOf(expectedBlocks);
        }
    }

    @Test
    void acceptsOneUtf16CodeUnitPerDeltaIncludingASplitSurrogatePair() {
        String payload = "{\"decision\":{\"text\":\"中文😀\"},\"replyBlocks\":[{\"text\":\"完成🎲\"}]}";
        List<JsonNode> decisions = new ArrayList<>();
        List<JsonNode> blocks = new ArrayList<>();
        RecommendationPublicationStream stream =
                new RecommendationPublicationStream(json, decisions::add, blocks::add);

        for (int index = 0; index < payload.length(); index++) {
            stream.accept(payload.substring(index, index + 1));
        }
        stream.finish();

        assertThat(decisions).extracting(node -> node.path("text").asText()).containsExactly("中文😀");
        assertThat(blocks).extracting(node -> node.path("text").asText()).containsExactly("完成🎲");
    }

    @Test
    void waitsForTheDecisionEndObjectBeforePublishingIt() {
        List<JsonNode> decisions = new ArrayList<>();
        RecommendationPublicationStream stream =
                new RecommendationPublicationStream(json, decisions::add, ignored -> {});

        stream.accept("{\"decision\":{\"kind\":\"recommend\",\"nested\":{");
        stream.accept("\"complete\":true}");

        assertThat(decisions).isEmpty();

        stream.accept("},\"replyBlocks\":[]}");
        stream.finish();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.path("kind").asText()).isEqualTo("recommend");
            assertThat(decision.path("nested").path("complete").asBoolean()).isTrue();
        });
    }

    @Test
    void neverPublishesAPartialReplyBlock() {
        List<JsonNode> decisions = new ArrayList<>();
        List<JsonNode> blocks = new ArrayList<>();
        RecommendationPublicationStream stream =
                new RecommendationPublicationStream(json, decisions::add, blocks::add);

        stream.accept("{\"decision\":{\"kind\":\"reply\"},\"replyBlocks\":[{\"text\":\"still");

        assertThat(decisions).hasSize(1);
        assertThat(blocks).isEmpty();

        assertFailure(FailureCode.TRUNCATED_JSON, stream::finish);
        assertThat(blocks).isEmpty();
    }

    @Test
    void buffersBlocksThatArriveBeforeDecisionAndReleasesThemInOrderAfterDecision() {
        List<String> events = new ArrayList<>();
        RecommendationPublicationStream stream = new RecommendationPublicationStream(
                json,
                decision -> events.add("decision:" + decision.path("kind").asText()),
                block -> events.add("block:" + block.path("index").asInt()));

        stream.accept("{\"replyBlocks\":[{\"index\":1},{\"index\":2}],");

        assertThat(events).isEmpty();

        stream.accept("\"decision\":{\"kind\":\"ready\"");
        assertThat(events).isEmpty();

        stream.accept("}}");
        stream.finish();

        assertThat(events).containsExactly("decision:ready", "block:1", "block:2");
    }

    @Test
    void rejectsUnknownAndDuplicateRootFieldsWithSpecificFailureCodes() {
        assertProtocolFailure(
                FailureCode.UNKNOWN_ROOT_FIELD,
                "{\"decision\":{},\"unexpected\":true,\"replyBlocks\":[]}");
        assertProtocolFailure(
                FailureCode.DUPLICATE_DECISION,
                "{\"decision\":{},\"decision\":{},\"replyBlocks\":[]}");
        assertProtocolFailure(
                FailureCode.DUPLICATE_REPLY_BLOCKS,
                "{\"decision\":{},\"replyBlocks\":[],\"replyBlocks\":[]}");
    }

    @Test
    void rejectsWrongRootShapesNonObjectBlocksAndTrailingData() {
        assertProtocolFailure(FailureCode.INVALID_ROOT, "[]");
        assertProtocolFailure(FailureCode.INVALID_DECISION, "{\"decision\":null,\"replyBlocks\":[]}");
        assertProtocolFailure(FailureCode.INVALID_REPLY_BLOCKS, "{\"decision\":{},\"replyBlocks\":{}}");
        assertProtocolFailure(FailureCode.INVALID_REPLY_BLOCK, "{\"decision\":{},\"replyBlocks\":[\"text\"]}");
        assertProtocolFailure(FailureCode.TRAILING_DATA, "{\"decision\":{},\"replyBlocks\":[]} true");
    }

    @Test
    void acceptsWhitespaceAfterTheSingleRootObjectButRejectsMalformedJson() {
        List<JsonNode> decisions = new ArrayList<>();
        RecommendationPublicationStream stream =
                new RecommendationPublicationStream(json, decisions::add, ignored -> {});

        stream.accept("{\"decision\":{},\"replyBlocks\":[]} \n\t");
        stream.finish();

        assertThat(decisions).hasSize(1);
        assertProtocolFailure(
                FailureCode.MALFORMED_JSON, "{\"decision\":{} \"replyBlocks\":[]}");
    }

    @Test
    void rejectsTruncationAndMissingRequiredRootMembers() {
        assertProtocolFailure(FailureCode.TRUNCATED_JSON, "{\"decision\":{},\"replyBlocks\":[{\"text\":\"cut");
        assertProtocolFailure(FailureCode.MISSING_DECISION, "{\"replyBlocks\":[]}");
        assertProtocolFailure(FailureCode.MISSING_REPLY_BLOCKS, "{\"decision\":{}}");
    }

    @Test
    void listenerFailureStopsBufferedPublicationAndAllLaterInput() {
        AtomicInteger decisionCalls = new AtomicInteger();
        List<JsonNode> blocks = new ArrayList<>();
        RecommendationPublicationStream stream = new RecommendationPublicationStream(
                json,
                ignored -> {
                    decisionCalls.incrementAndGet();
                    throw new IllegalStateException("downstream unavailable");
                },
                blocks::add);

        Failure failure = assertFailure(
                FailureCode.LISTENER_FAILURE,
                () -> stream.accept("{\"replyBlocks\":[{\"index\":1}],\"decision\":{\"kind\":\"ready\"}}"));

        assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(decisionCalls).hasValue(1);
        assertThat(blocks).isEmpty();
        assertFailure(FailureCode.LISTENER_FAILURE, () -> stream.accept(" "));
        assertFailure(FailureCode.LISTENER_FAILURE, stream::finish);
        assertThat(decisionCalls).hasValue(1);
        assertThat(blocks).isEmpty();
    }

    @Test
    void blockListenerFailurePreventsLaterBlockCallbacks() {
        List<Integer> attemptedBlocks = new ArrayList<>();
        RecommendationPublicationStream stream = new RecommendationPublicationStream(
                json,
                ignored -> {},
                block -> {
                    int index = block.path("index").asInt();
                    attemptedBlocks.add(index);
                    if (index == 1) throw new IllegalStateException("client disconnected");
                });

        assertFailure(
                FailureCode.LISTENER_FAILURE,
                () -> stream.accept("{\"decision\":{},\"replyBlocks\":[{\"index\":1},{\"index\":2}]}"));

        assertThat(attemptedBlocks).containsExactly(1);
    }

    @Test
    void enforcesOverallUtf8ByteAndReplyBlockLimits() {
        RecommendationPublicationStream byteLimited =
                new RecommendationPublicationStream(json, ignored -> {}, ignored -> {}, 32, 10);
        assertFailure(
                FailureCode.INPUT_LIMIT_EXCEEDED,
                () -> byteLimited.accept("{\"decision\":{\"text\":\"这段内容超过三十二字节\"},\"replyBlocks\":[]}"));

        RecommendationPublicationStream blockLimited =
                new RecommendationPublicationStream(json, ignored -> {}, ignored -> {}, 1_024, 2);
        assertFailure(
                FailureCode.BLOCK_LIMIT_EXCEEDED,
                () -> blockLimited.accept(
                        "{\"decision\":{},\"replyBlocks\":[{\"index\":1},{\"index\":2},{\"index\":3}]}"));
    }

    @Test
    void rejectsUnpairedJavaStringSurrogatesInsteadOfReplacingThem() {
        RecommendationPublicationStream trailingHigh =
                new RecommendationPublicationStream(json, ignored -> {}, ignored -> {});
        trailingHigh.accept("{\"decision\":{\"text\":\"");
        trailingHigh.accept("\uD83D");
        assertFailure(FailureCode.INVALID_UNICODE, trailingHigh::finish);

        RecommendationPublicationStream loneLow =
                new RecommendationPublicationStream(json, ignored -> {}, ignored -> {});
        assertFailure(FailureCode.INVALID_UNICODE, () -> loneLow.accept("\uDE00"));
    }

    private void assertProtocolFailure(FailureCode expected, String payload) {
        RecommendationPublicationStream stream =
                new RecommendationPublicationStream(json, ignored -> {}, ignored -> {});
        Throwable thrown = catchThrowable(() -> {
            stream.accept(payload);
            stream.finish();
        });

        assertThat(thrown).isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.code())
                .isEqualTo(expected));
    }

    private Failure assertFailure(FailureCode expected, ThrowingCallable operation) {
        Throwable thrown = catchThrowable(operation);
        assertThat(thrown).isInstanceOf(Failure.class);
        Failure failure = (Failure) thrown;
        assertThat(failure.code()).isEqualTo(expected);
        return failure;
    }
}
