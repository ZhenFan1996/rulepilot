package com.rulepilot.recommendation.adapter.out.model;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredOutput;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decodes the first ReAct decision behind a mode barrier. Player text is released only after the
 * envelope has declared {@code REPLY} and a null action; typed action arguments are never sent to
 * the player-facing listener.
 */
final class RecommendationDecisionStream {

    private static final int MAX_INPUT_BYTES = 131_072;
    private static final int MAX_REPLY_DELTAS = 64;
    private static final int PREFERRED_MAX_REPLY_DELTA_CHARACTERS = 160;
    private static final int MAX_REPLY_CHARACTERS = 1_200;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final ObjectMapper json;
    private final Consumer<String> accumulatedReplyListener;
    private final Set<String> allowedActionNames;
    private final String schema;
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private final StringBuilder reply = new StringBuilder();

    private ProtocolState state = ProtocolState.EXPECT_ROOT;
    private Mode mode;
    private JsonNode action;
    private TokenBuffer actionTokens;
    private int actionDepth;
    private int replyDeltaCount;
    private int inputBytes;
    private char pendingHighSurrogate;
    private Failure terminalFailure;
    private boolean finished;

    RecommendationDecisionStream(
            ObjectMapper json,
            List<ToolSpec> actions,
            Consumer<String> accumulatedReplyListener) {
        this.json = Objects.requireNonNull(json, "json is required");
        Objects.requireNonNull(actions, "actions are required");
        if (actions.isEmpty()) throw new IllegalArgumentException("at least one decision action is required");
        this.accumulatedReplyListener =
                Objects.requireNonNull(accumulatedReplyListener, "reply listener is required");
        this.allowedActionNames = actions.stream()
                .map(ToolSpec::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (allowedActionNames.size() != actions.size()) {
            throw new IllegalArgumentException("decision action names must be unique");
        }
        this.schema = schema(json, actions);
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

    StructuredOutput output() {
        // Dynamic action schemas contain provider-specific unsupported keywords. The application
        // owns exact decoding and action validation, so the portable wire contract is JSON_OBJECT.
        return new StructuredOutput("recommendation_decision", schema, false);
    }

    String instruction(boolean includeSchema) {
        String contract = """
                Return exactly one first-decision JSON envelope. Write root members in this exact order: mode, action, replyDeltas.
                Use mode REPLY only when the complete turn needs no retrieval, selectable cards, factual game lookup, or typed preference update. Then action must be null. Put the complete natural player-facing answer into consecutive replyDeltas; concatenating them unchanged is the final answer. Keep each delta short enough to stream promptly and include any desired spaces or line breaks inside the deltas.
                Otherwise use mode ACTION, choose exactly one allowed typed action, and use an empty replyDeltas array. Do not put a preface, progress narration, or player-facing prose around an action. Never expose hidden reasoning.
                """.strip();
        return includeSchema ? contract + "\nThe exact envelope and action schema is:\n" + schema : contract;
    }

    void accept(String delta) {
        requireActive();
        Objects.requireNonNull(delta, "delta is required");
        byte[] bytes = utf8(delta);
        if (bytes.length == 0) return;
        if ((long) inputBytes + bytes.length > MAX_INPUT_BYTES) {
            throw stop(FailureCode.INPUT_LIMIT_EXCEEDED, "Recommendation decision exceeded its byte limit");
        }
        inputBytes += bytes.length;
        try {
            if (!feeder.needMoreInput()) drainTokens();
            if (!feeder.needMoreInput()) {
                throw stop(FailureCode.MALFORMED_JSON, "Non-blocking decision parser did not consume its input");
            }
            feeder.feedInput(bytes, 0, bytes.length);
            drainTokens();
        } catch (Failure failure) {
            throw failure;
        } catch (JsonEOFException exception) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation decision ended mid-value", exception);
        } catch (JsonParseException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation decision was not valid JSON", exception);
        } catch (IOException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation decision could not be decoded", exception);
        }
    }

    void finish() {
        if (finished) return;
        requireActive();
        if (pendingHighSurrogate != 0) {
            throw stop(FailureCode.INVALID_UNICODE, "Recommendation decision ended with an unmatched surrogate");
        }
        try {
            feeder.endOfInput();
            drainTokens();
        } catch (Failure failure) {
            throw failure;
        } catch (JsonEOFException exception) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation decision ended mid-value", exception);
        } catch (JsonParseException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation decision was not valid JSON", exception);
        } catch (IOException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation decision could not be decoded", exception);
        }
        if (state != ProtocolState.COMPLETE) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation decision JSON was not closed");
        }
        if (mode == Mode.REPLY && replyDeltaCount == 0) {
            throw stop(FailureCode.EMPTY_REPLY, "Reply decision did not contain player-facing text");
        }
        finished = true;
        closeParser();
    }

    Turn turn(CompletionStatus completionStatus) {
        if (!finished) throw new IllegalStateException("recommendation decision stream is not finished");
        if (mode == Mode.REPLY) return new Turn(reply.toString(), List.of(), completionStatus);
        String name = action.path("name").asText();
        try {
            String arguments = json.writeValueAsString(action.path("arguments"));
            return new Turn("", List.of(new ToolCall("decision-action-1", name, arguments)), completionStatus);
        } catch (IOException exception) {
            throw new IllegalStateException("typed recommendation action could not be serialized", exception);
        }
    }

    private void drainTokens() throws IOException {
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == null || token == JsonToken.NOT_AVAILABLE) return;
            consume(token);
        }
    }

    private void consume(JsonToken token) throws IOException {
        if (actionTokens != null) {
            captureAction(token);
            return;
        }
        switch (state) {
            case EXPECT_ROOT -> requireRoot(token);
            case EXPECT_MODE_FIELD -> requireField(token, "mode", ProtocolState.EXPECT_MODE);
            case EXPECT_MODE -> readMode(token);
            case EXPECT_ACTION_FIELD -> requireField(token, "action", ProtocolState.EXPECT_ACTION);
            case EXPECT_ACTION -> readAction(token);
            case EXPECT_REPLY_DELTAS_FIELD ->
                requireField(token, "replyDeltas", ProtocolState.EXPECT_REPLY_DELTAS);
            case EXPECT_REPLY_DELTAS -> requireReplyArray(token);
            case EXPECT_REPLY_DELTA -> readReplyDelta(token);
            case EXPECT_ROOT_END -> requireRootEnd(token);
            case COMPLETE -> throw stop(FailureCode.TRAILING_DATA, "Recommendation decision contained trailing data");
        }
    }

    private void requireRoot(JsonToken token) {
        if (token != JsonToken.START_OBJECT) {
            throw stop(FailureCode.INVALID_ROOT, "Recommendation decision root must be an object");
        }
        state = ProtocolState.EXPECT_MODE_FIELD;
    }

    private void requireField(JsonToken token, String expected, ProtocolState next) throws IOException {
        if (token != JsonToken.FIELD_NAME || !expected.equals(parser.currentName())) {
            throw stop(
                    FailureCode.INVALID_FIELD_ORDER,
                    "Recommendation decision must declare mode, action, and replyDeltas in order");
        }
        state = next;
    }

    private void readMode(JsonToken token) throws IOException {
        if (token != JsonToken.VALUE_STRING) {
            throw stop(FailureCode.INVALID_MODE, "Recommendation decision mode must be REPLY or ACTION");
        }
        try {
            mode = Mode.valueOf(parser.getText());
        } catch (IllegalArgumentException exception) {
            throw stop(FailureCode.INVALID_MODE, "Recommendation decision mode must be REPLY or ACTION", exception);
        }
        state = ProtocolState.EXPECT_ACTION_FIELD;
    }

    private void readAction(JsonToken token) throws IOException {
        if (token == JsonToken.VALUE_NULL) {
            if (mode != Mode.REPLY) {
                throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Action decision must contain one typed action");
            }
            state = ProtocolState.EXPECT_REPLY_DELTAS_FIELD;
            return;
        }
        if (token != JsonToken.START_OBJECT || mode != Mode.ACTION) {
            throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Reply decision must use a null action");
        }
        actionTokens = new TokenBuffer(parser);
        actionTokens.copyCurrentEvent(parser);
        actionDepth = 1;
    }

    private void captureAction(JsonToken token) throws IOException {
        actionTokens.copyCurrentEvent(parser);
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) actionDepth++;
        if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) actionDepth--;
        if (actionDepth != 0) return;

        TokenBuffer complete = actionTokens;
        actionTokens = null;
        try (complete; JsonParser actionParser = complete.asParser(json)) {
            action = json.readTree(actionParser);
            if (action == null || !action.isObject() || actionParser.nextToken() != null) {
                throw stop(FailureCode.INVALID_ACTION, "Recommendation action must be one object");
            }
        }
        if (action.size() != 2
                || !action.has("name")
                || !action.path("name").isTextual()
                || !allowedActionNames.contains(action.path("name").asText())
                || !action.has("arguments")
                || !action.path("arguments").isObject()) {
            throw stop(FailureCode.INVALID_ACTION, "Recommendation action did not match an allowed typed action");
        }
        state = ProtocolState.EXPECT_REPLY_DELTAS_FIELD;
    }

    private void requireReplyArray(JsonToken token) {
        if (token != JsonToken.START_ARRAY) {
            throw stop(FailureCode.INVALID_REPLY_DELTAS, "replyDeltas must be an array");
        }
        state = ProtocolState.EXPECT_REPLY_DELTA;
    }

    private void readReplyDelta(JsonToken token) throws IOException {
        if (token == JsonToken.END_ARRAY) {
            state = ProtocolState.EXPECT_ROOT_END;
            return;
        }
        if (mode != Mode.REPLY || token != JsonToken.VALUE_STRING) {
            throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Action decisions cannot contain player-facing text");
        }
        String delta = parser.getText();
        replyDeltaCount++;
        if (replyDeltaCount > MAX_REPLY_DELTAS
                || delta.isEmpty()
                || (long) reply.length() + delta.length() > MAX_REPLY_CHARACTERS) {
            throw stop(FailureCode.REPLY_LIMIT_EXCEEDED, "Recommendation reply exceeded its streaming limits");
        }
        reply.append(delta);
        try {
            accumulatedReplyListener.accept(reply.toString());
        } catch (RuntimeException exception) {
            throw stop(FailureCode.LISTENER_FAILURE, "Recommendation reply listener failed", exception);
        }
    }

    private void requireRootEnd(JsonToken token) {
        if (token != JsonToken.END_OBJECT) {
            throw stop(FailureCode.UNKNOWN_ROOT_FIELD, "Recommendation decision contained an unknown root field");
        }
        state = ProtocolState.COMPLETE;
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

    private static String schema(ObjectMapper json, List<ToolSpec> actions) {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");

        ObjectNode mode = properties.putObject("mode");
        mode.put("type", "string");
        mode.putArray("enum").add("REPLY").add("ACTION");
        mode.put("description", "REPLY for a complete no-read conversational answer; ACTION otherwise.");

        ObjectNode action = properties.putObject("action");
        action.put("description", "One allowed typed action for ACTION mode; null for REPLY mode.");
        ArrayNode actionAlternatives = action.putArray("anyOf");
        for (ToolSpec spec : actions) {
            ObjectNode variant = actionAlternatives.addObject();
            variant.put("type", "object");
            variant.put("additionalProperties", false);
            variant.put("description", spec.description());
            ObjectNode variantProperties = variant.putObject("properties");
            ObjectNode name = variantProperties.putObject("name");
            name.put("type", "string");
            name.putArray("enum").add(spec.name());
            JsonNode arguments;
            try {
                arguments = json.readTree(spec.inputSchema());
            } catch (IOException exception) {
                throw new IllegalArgumentException("recommendation action schema was not valid JSON", exception);
            }
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("recommendation action schema root must be an object");
            }
            variantProperties.set("arguments", arguments);
            variant.putArray("required").add("name").add("arguments");
        }
        actionAlternatives.addObject().put("type", "null");

        ObjectNode replyDeltas = properties.putObject("replyDeltas");
        replyDeltas.put("type", "array");
        replyDeltas.put("maxItems", MAX_REPLY_DELTAS);
        replyDeltas.put(
                "description",
                "Exact consecutive text fragments. Concatenate unchanged. Empty for ACTION mode.");
        ObjectNode item = replyDeltas.putObject("items");
        item.put("type", "string");
        item.put("minLength", 1);
        // This encourages useful transport granularity. Providers without native schema enforcement
        // may still return one longer fragment; the decoder accepts it when the bounded whole reply is valid.
        item.put("maxLength", PREFERRED_MAX_REPLY_DELTA_CHARACTERS);

        root.putArray("required").add("mode").add("action").add("replyDeltas");
        try {
            return json.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("recommendation decision schema could not be serialized", exception);
        }
    }

    private void requireActive() {
        if (terminalFailure != null) throw terminalFailure;
        if (finished) throw new IllegalStateException("recommendation decision stream is already finished");
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
            // A terminal protocol outcome is already known.
        }
    }

    enum FailureCode {
        MALFORMED_JSON,
        TRUNCATED_JSON,
        INVALID_UNICODE,
        INVALID_ROOT,
        INVALID_FIELD_ORDER,
        INVALID_MODE,
        INVALID_MODE_PAYLOAD,
        INVALID_ACTION,
        INVALID_REPLY_DELTAS,
        UNKNOWN_ROOT_FIELD,
        TRAILING_DATA,
        EMPTY_REPLY,
        INPUT_LIMIT_EXCEEDED,
        REPLY_LIMIT_EXCEEDED,
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
        EXPECT_MODE_FIELD,
        EXPECT_MODE,
        EXPECT_ACTION_FIELD,
        EXPECT_ACTION,
        EXPECT_REPLY_DELTAS_FIELD,
        EXPECT_REPLY_DELTAS,
        EXPECT_REPLY_DELTA,
        EXPECT_ROOT_END,
        COMPLETE
    }

    private enum Mode {
        REPLY,
        ACTION
    }
}
