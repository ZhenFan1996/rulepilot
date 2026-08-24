package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Incrementally decodes the recommendation publication envelope without interpreting player-facing prose.
 * Complete blocks may arrive before the decision, but publication always starts with the decision.
 */
final class RecommendationPublicationStream {

    private static final int DEFAULT_MAX_INPUT_BYTES = 1_048_576;
    private static final int DEFAULT_MAX_REPLY_BLOCKS = 512;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final ObjectMapper json;
    private final Consumer<JsonNode> decisionListener;
    private final Consumer<JsonNode> replyBlockListener;
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private final int maxInputBytes;
    private final int maxReplyBlocks;
    private final List<JsonNode> blocksAwaitingDecision = new ArrayList<>();

    private ProtocolState state = ProtocolState.EXPECT_ROOT;
    private boolean decisionDeclared;
    private boolean replyBlocksDeclared;
    private boolean decisionPublished;
    private int inputBytes;
    private int replyBlockCount;
    private char pendingHighSurrogate;
    private TokenBuffer objectTokens;
    private CapturedObject capturedObject;
    private int capturedDepth;
    private Failure terminalFailure;
    private boolean finished;

    RecommendationPublicationStream(
            ObjectMapper json,
            Consumer<JsonNode> decisionListener,
            Consumer<JsonNode> replyBlockListener) {
        this(json, decisionListener, replyBlockListener, DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_REPLY_BLOCKS);
    }

    RecommendationPublicationStream(
            ObjectMapper json,
            Consumer<JsonNode> decisionListener,
            Consumer<JsonNode> replyBlockListener,
            int maxInputBytes,
            int maxReplyBlocks) {
        this.json = Objects.requireNonNull(json, "json is required");
        this.decisionListener = Objects.requireNonNull(decisionListener, "decisionListener is required");
        this.replyBlockListener = Objects.requireNonNull(replyBlockListener, "replyBlockListener is required");
        if (maxInputBytes < 1) throw new IllegalArgumentException("maxInputBytes must be positive");
        if (maxReplyBlocks < 1) throw new IllegalArgumentException("maxReplyBlocks must be positive");
        this.maxInputBytes = maxInputBytes;
        this.maxReplyBlocks = maxReplyBlocks;
        try {
            this.parser = json.getFactory().createNonBlockingByteArrayParser();
        } catch (IOException exception) {
            throw new IllegalArgumentException("ObjectMapper must support non-blocking JSON parsing", exception);
        }
        if (!(parser.getNonBlockingInputFeeder() instanceof ByteArrayFeeder byteArrayFeeder)) {
            closeParser();
            throw new IllegalArgumentException("ObjectMapper must provide a non-blocking byte-array feeder");
        }
        this.feeder = byteArrayFeeder;
    }

    void accept(String delta) {
        requireActive();
        Objects.requireNonNull(delta, "delta is required");
        if ((long) inputBytes + delta.length() > maxInputBytes) {
            throw stop(FailureCode.INPUT_LIMIT_EXCEEDED, "Recommendation publication input exceeded its byte limit");
        }
        byte[] bytes = utf8(delta);
        if (bytes.length == 0) return;
        if ((long) inputBytes + bytes.length > maxInputBytes) {
            throw stop(FailureCode.INPUT_LIMIT_EXCEEDED, "Recommendation publication input exceeded its byte limit");
        }
        inputBytes += bytes.length;
        try {
            if (!feeder.needMoreInput()) drainTokens();
            if (!feeder.needMoreInput()) {
                throw stop(FailureCode.MALFORMED_JSON, "Non-blocking JSON parser did not consume its input");
            }
            feeder.feedInput(bytes, 0, bytes.length);
            drainTokens();
        } catch (Failure failure) {
            throw failure;
        } catch (JsonEOFException exception) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation publication JSON ended mid-value", exception);
        } catch (JsonParseException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation publication was not valid JSON", exception);
        } catch (IOException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation publication JSON could not be decoded", exception);
        }
    }

    void finish() {
        if (finished) return;
        requireActive();
        if (pendingHighSurrogate != 0) {
            throw stop(FailureCode.INVALID_UNICODE, "Recommendation publication ended with an unmatched surrogate");
        }
        try {
            feeder.endOfInput();
            drainTokens();
        } catch (Failure failure) {
            throw failure;
        } catch (JsonEOFException exception) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation publication JSON ended mid-value", exception);
        } catch (JsonParseException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation publication was not valid JSON", exception);
        } catch (IOException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation publication JSON could not be decoded", exception);
        }
        if (state != ProtocolState.ROOT_COMPLETE) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation publication JSON was not closed");
        }
        if (!decisionDeclared) {
            throw stop(FailureCode.MISSING_DECISION, "Recommendation publication did not contain decision");
        }
        if (!replyBlocksDeclared) {
            throw stop(FailureCode.MISSING_REPLY_BLOCKS, "Recommendation publication did not contain replyBlocks");
        }
        finished = true;
        closeParser();
    }

    private byte[] utf8(String delta) {
        if (delta.isEmpty()) return EMPTY_BYTES;
        String value = delta;
        if (pendingHighSurrogate != 0) {
            if (!Character.isLowSurrogate(delta.charAt(0))) {
                throw stop(FailureCode.INVALID_UNICODE, "High surrogate was not followed by a low surrogate");
            }
            value = new StringBuilder(delta.length() + 1)
                    .append(pendingHighSurrogate)
                    .append(delta)
                    .toString();
            pendingHighSurrogate = 0;
        }
        int encodableLength = value.length();
        if (Character.isHighSurrogate(value.charAt(encodableLength - 1))) {
            pendingHighSurrogate = value.charAt(encodableLength - 1);
            encodableLength--;
        }
        for (int index = 0; index < encodableLength; index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= encodableLength || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw stop(FailureCode.INVALID_UNICODE, "High surrogate was not followed by a low surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw stop(FailureCode.INVALID_UNICODE, "Low surrogate did not follow a high surrogate");
            }
        }
        if (encodableLength == 0) return EMPTY_BYTES;
        return value.substring(0, encodableLength).getBytes(StandardCharsets.UTF_8);
    }

    private void drainTokens() throws IOException {
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == null || token == JsonToken.NOT_AVAILABLE) return;
            consume(token);
        }
    }

    private void consume(JsonToken token) throws IOException {
        if (objectTokens != null) {
            capture(token);
            return;
        }
        switch (state) {
            case EXPECT_ROOT -> startRoot(token);
            case EXPECT_ROOT_MEMBER -> consumeRootMember(token);
            case EXPECT_DECISION_OBJECT -> startDecision(token);
            case EXPECT_REPLY_BLOCK_ARRAY -> startReplyBlocks(token);
            case EXPECT_REPLY_BLOCK -> consumeReplyBlock(token);
            case ROOT_COMPLETE -> throw stop(
                    FailureCode.TRAILING_DATA, "Recommendation publication contained data after the root object");
        }
    }

    private void startRoot(JsonToken token) {
        if (token != JsonToken.START_OBJECT) {
            throw stop(FailureCode.INVALID_ROOT, "Recommendation publication root must be an object");
        }
        state = ProtocolState.EXPECT_ROOT_MEMBER;
    }

    private void consumeRootMember(JsonToken token) throws IOException {
        if (token == JsonToken.END_OBJECT) {
            state = ProtocolState.ROOT_COMPLETE;
            return;
        }
        if (token != JsonToken.FIELD_NAME) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation publication root member was malformed");
        }
        String field = parser.currentName();
        if ("decision".equals(field)) {
            if (decisionDeclared) {
                throw stop(FailureCode.DUPLICATE_DECISION, "Recommendation publication repeated decision");
            }
            decisionDeclared = true;
            state = ProtocolState.EXPECT_DECISION_OBJECT;
            return;
        }
        if ("replyBlocks".equals(field)) {
            if (replyBlocksDeclared) {
                throw stop(FailureCode.DUPLICATE_REPLY_BLOCKS, "Recommendation publication repeated replyBlocks");
            }
            replyBlocksDeclared = true;
            state = ProtocolState.EXPECT_REPLY_BLOCK_ARRAY;
            return;
        }
        throw stop(FailureCode.UNKNOWN_ROOT_FIELD, "Recommendation publication contained an unknown root field");
    }

    private void startDecision(JsonToken token) throws IOException {
        if (token != JsonToken.START_OBJECT) {
            throw stop(FailureCode.INVALID_DECISION, "Recommendation publication decision must be an object");
        }
        beginCapture(CapturedObject.DECISION);
    }

    private void startReplyBlocks(JsonToken token) {
        if (token != JsonToken.START_ARRAY) {
            throw stop(FailureCode.INVALID_REPLY_BLOCKS, "Recommendation publication replyBlocks must be an array");
        }
        state = ProtocolState.EXPECT_REPLY_BLOCK;
    }

    private void consumeReplyBlock(JsonToken token) throws IOException {
        if (token == JsonToken.END_ARRAY) {
            state = ProtocolState.EXPECT_ROOT_MEMBER;
            return;
        }
        if (token != JsonToken.START_OBJECT) {
            throw stop(FailureCode.INVALID_REPLY_BLOCK, "Every recommendation reply block must be an object");
        }
        replyBlockCount++;
        if (replyBlockCount > maxReplyBlocks) {
            throw stop(FailureCode.BLOCK_LIMIT_EXCEEDED, "Recommendation publication exceeded its reply block limit");
        }
        beginCapture(CapturedObject.REPLY_BLOCK);
    }

    private void beginCapture(CapturedObject target) throws IOException {
        objectTokens = new TokenBuffer(parser);
        objectTokens.copyCurrentEvent(parser);
        capturedObject = target;
        capturedDepth = 1;
    }

    private void capture(JsonToken token) throws IOException {
        objectTokens.copyCurrentEvent(parser);
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) capturedDepth++;
        if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) capturedDepth--;
        if (capturedDepth != 0) return;

        TokenBuffer completeTokens = objectTokens;
        CapturedObject completeObject = capturedObject;
        objectTokens = null;
        capturedObject = null;
        JsonNode completeNode = completeObject(completeTokens);
        if (completeObject == CapturedObject.DECISION) {
            state = ProtocolState.EXPECT_ROOT_MEMBER;
            publishDecision(completeNode);
        } else {
            state = ProtocolState.EXPECT_REPLY_BLOCK;
            publishOrBufferBlock(completeNode);
        }
    }

    private JsonNode completeObject(TokenBuffer tokens) throws IOException {
        try (tokens; JsonParser completeObjectParser = tokens.asParser(json)) {
            JsonNode completeNode = json.readTree(completeObjectParser);
            if (completeNode == null || !completeNode.isObject() || completeObjectParser.nextToken() != null) {
                throw stop(FailureCode.MALFORMED_JSON, "Completed publication object could not be materialized");
            }
            return completeNode;
        }
    }

    private void publishDecision(JsonNode decision) {
        notifyListener(decisionListener, decision);
        decisionPublished = true;
        for (JsonNode block : blocksAwaitingDecision) notifyListener(replyBlockListener, block);
        blocksAwaitingDecision.clear();
    }

    private void publishOrBufferBlock(JsonNode block) {
        if (!decisionPublished) {
            blocksAwaitingDecision.add(block);
            return;
        }
        notifyListener(replyBlockListener, block);
    }

    private void notifyListener(Consumer<JsonNode> listener, JsonNode value) {
        try {
            listener.accept(value);
        } catch (RuntimeException exception) {
            throw stop(FailureCode.LISTENER_FAILURE, "Recommendation publication listener failed", exception);
        }
    }

    private void requireActive() {
        if (terminalFailure != null) throw terminalFailure;
        if (finished) throw new IllegalStateException("Recommendation publication stream is already finished");
    }

    private Failure stop(FailureCode code, String message) {
        return stop(code, message, null);
    }

    private Failure stop(FailureCode code, String message, Throwable cause) {
        if (terminalFailure != null) return terminalFailure;
        terminalFailure = new Failure(code, message, cause);
        closeParser();
        return terminalFailure;
    }

    private void closeParser() {
        try {
            if (parser != null) parser.close();
        } catch (IOException ignored) {
            // The stream is already terminal; parser close cannot change the protocol outcome.
        }
    }

    enum FailureCode {
        MALFORMED_JSON,
        TRUNCATED_JSON,
        INVALID_UNICODE,
        INVALID_ROOT,
        UNKNOWN_ROOT_FIELD,
        DUPLICATE_DECISION,
        DUPLICATE_REPLY_BLOCKS,
        INVALID_DECISION,
        INVALID_REPLY_BLOCKS,
        INVALID_REPLY_BLOCK,
        TRAILING_DATA,
        MISSING_DECISION,
        MISSING_REPLY_BLOCKS,
        INPUT_LIMIT_EXCEEDED,
        BLOCK_LIMIT_EXCEEDED,
        LISTENER_FAILURE
    }

    static final class Failure extends RuntimeException {

        private final FailureCode code;

        private Failure(FailureCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        FailureCode code() {
            return code;
        }
    }

    private enum ProtocolState {
        EXPECT_ROOT,
        EXPECT_ROOT_MEMBER,
        EXPECT_DECISION_OBJECT,
        EXPECT_REPLY_BLOCK_ARRAY,
        EXPECT_REPLY_BLOCK,
        ROOT_COMPLETE
    }

    private enum CapturedObject {
        DECISION,
        REPLY_BLOCK
    }
}
