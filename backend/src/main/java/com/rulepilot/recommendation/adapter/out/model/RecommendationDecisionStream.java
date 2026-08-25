package com.rulepilot.recommendation.adapter.out.model;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredOutput;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decodes one bounded first-decision envelope. The complete object is validated by field name before
 * player prose is released; JSON member order is never a routing or safety signal.
 */
final class RecommendationDecisionStream {

    private static final int MAX_INPUT_BYTES = 131_072;
    private static final int MAX_REPLY_DELTAS = 64;
    private static final int PREFERRED_MAX_REPLY_DELTA_CHARACTERS = 160;
    private static final int MAX_REPLY_CHARACTERS = 1_200;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final Set<String> ROOT_FIELDS = Set.of("mode", "action", "replyDeltas");
    private static final Set<String> ACTION_FIELDS = Set.of("name", "arguments");

    private final ObjectMapper json;
    private final Consumer<String> accumulatedReplyListener;
    private final Set<String> allowedActionNames;
    private final String schema;
    private final StringBuilder input = new StringBuilder();
    private final StringBuilder reply = new StringBuilder();
    private final List<String> replySnapshots = new ArrayList<>();

    private Mode mode;
    private JsonNode action;
    private int inputBytes;
    private char pendingHighSurrogate;
    private Failure terminalFailure;
    private boolean finished;
    private boolean replyPublished;

    RecommendationDecisionStream(
            ObjectMapper json,
            List<ToolSpec> actions,
            Consumer<String> accumulatedReplyListener) {
        this.json = Objects.requireNonNull(json, "json is required").copy();
        this.json.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
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
        this.schema = schema(this.json, actions);
    }

    StructuredOutput output() {
        // Dynamic action schemas contain provider-specific unsupported keywords. The application
        // owns exact decoding and action validation, so the portable wire contract is JSON_OBJECT.
        return new StructuredOutput("recommendation_decision", schema, false);
    }

    String instruction(boolean includeSchema) {
        String contract = """
                Return exactly one first-decision JSON envelope containing mode, action, and replyDeltas.
                Use mode REPLY only when the complete turn needs no retrieval, selectable cards, factual game lookup, or typed preference update. Then action must be null. Put the complete natural player-facing answer into consecutive replyDeltas; concatenating them unchanged is the final answer. Keep each delta short enough to stream promptly and include any desired spaces or line breaks inside the deltas.
                Otherwise use mode ACTION, choose exactly one allowed typed action, and use an empty replyDeltas array. Do not put a preface, progress narration, or player-facing prose around an action. Never expose hidden reasoning.
                """.strip();
        return includeSchema ? contract + "\nThe exact envelope and action schema is:\n" + schema : contract;
    }

    void accept(String delta) {
        requireActive();
        Objects.requireNonNull(delta, "delta is required");
        byte[] bytes = utf8(delta);
        if ((long) inputBytes + bytes.length > MAX_INPUT_BYTES) {
            throw stop(FailureCode.INPUT_LIMIT_EXCEEDED, "Recommendation decision exceeded its byte limit");
        }
        inputBytes += bytes.length;
        input.append(delta);
    }

    void finish() {
        if (finished) return;
        requireActive();
        if (pendingHighSurrogate != 0) {
            throw stop(FailureCode.INVALID_UNICODE, "Recommendation decision ended with an unmatched surrogate");
        }
        JsonNode root;
        try (JsonParser parser = json.getFactory().createParser(input.toString())) {
            root = json.readTree(parser);
            if (root == null) {
                throw stop(FailureCode.INVALID_ROOT, "Recommendation decision root is required");
            }
            if (parser.nextToken() != null) {
                throw stop(FailureCode.TRAILING_DATA, "Recommendation decision contained trailing data");
            }
        } catch (Failure failure) {
            throw failure;
        } catch (JsonEOFException exception) {
            throw stop(FailureCode.TRUNCATED_JSON, "Recommendation decision ended mid-value", exception);
        } catch (JsonParseException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation decision was not valid JSON", exception);
        } catch (IOException exception) {
            throw stop(FailureCode.MALFORMED_JSON, "Recommendation decision could not be decoded", exception);
        }
        decode(root);
        finished = true;
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

    void publishReply() {
        if (terminalFailure != null) throw terminalFailure;
        if (!finished) throw new IllegalStateException("recommendation decision stream is not finished");
        if (mode != Mode.REPLY || replyPublished) return;
        for (String snapshot : replySnapshots) {
            try {
                accumulatedReplyListener.accept(snapshot);
            } catch (RuntimeException exception) {
                throw stop(FailureCode.LISTENER_FAILURE, "Recommendation reply listener failed", exception);
            }
        }
        replyPublished = true;
    }

    private void decode(JsonNode root) {
        if (!root.isObject()) {
            throw stop(FailureCode.INVALID_ROOT, "Recommendation decision root must be an object");
        }
        Set<String> fieldNames = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(fieldNames::add);
        if (fieldNames.stream().anyMatch(field -> !ROOT_FIELDS.contains(field))) {
            throw stop(FailureCode.UNKNOWN_ROOT_FIELD, "Recommendation decision contained an unknown root field");
        }
        if (!fieldNames.equals(ROOT_FIELDS)) {
            throw stop(FailureCode.INVALID_ENVELOPE, "Recommendation decision must contain mode, action, and replyDeltas");
        }
        JsonNode modeNode = root.path("mode");
        if (!modeNode.isTextual()) {
            throw stop(FailureCode.INVALID_MODE, "Recommendation decision mode must be REPLY or ACTION");
        }
        try {
            mode = Mode.valueOf(modeNode.asText());
        } catch (IllegalArgumentException exception) {
            throw stop(FailureCode.INVALID_MODE, "Recommendation decision mode must be REPLY or ACTION", exception);
        }

        JsonNode replyDeltas = root.path("replyDeltas");
        if (!replyDeltas.isArray()) {
            throw stop(FailureCode.INVALID_REPLY_DELTAS, "replyDeltas must be an array");
        }
        action = root.path("action");
        if (mode == Mode.ACTION) {
            decodeAction(replyDeltas);
        } else {
            decodeReply(replyDeltas);
        }
    }

    private void decodeAction(JsonNode replyDeltas) {
        if (!replyDeltas.isEmpty()) {
            throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Action decisions cannot contain player-facing text");
        }
        if (!action.isObject()) {
            throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Action decision must contain one typed action");
        }
        Set<String> fieldNames = new LinkedHashSet<>();
        action.fieldNames().forEachRemaining(fieldNames::add);
        if (!fieldNames.equals(ACTION_FIELDS)
                || !action.path("name").isTextual()
                || !allowedActionNames.contains(action.path("name").asText())
                || !action.path("arguments").isObject()) {
            throw stop(FailureCode.INVALID_ACTION, "Recommendation action did not match an allowed typed action");
        }
    }

    private void decodeReply(JsonNode replyDeltas) {
        if (!action.isNull()) {
            throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Reply decision must use a null action");
        }
        if (replyDeltas.isEmpty()) {
            throw stop(FailureCode.EMPTY_REPLY, "Reply decision did not contain player-facing text");
        }
        if (replyDeltas.size() > MAX_REPLY_DELTAS) {
            throw stop(FailureCode.REPLY_LIMIT_EXCEEDED, "Recommendation reply exceeded its streaming limits");
        }
        for (JsonNode item : replyDeltas) {
            if (!item.isTextual()) {
                throw stop(FailureCode.INVALID_MODE_PAYLOAD, "Reply decisions require textual reply deltas");
            }
            String delta = item.asText();
            if (delta.isEmpty() || (long) reply.length() + delta.length() > MAX_REPLY_CHARACTERS) {
                throw stop(FailureCode.REPLY_LIMIT_EXCEEDED, "Recommendation reply exceeded its streaming limits");
            }
            reply.append(delta);
            replySnapshots.add(reply.toString());
        }
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
        return terminalFailure;
    }

    enum FailureCode {
        MALFORMED_JSON,
        TRUNCATED_JSON,
        INVALID_UNICODE,
        INVALID_ROOT,
        INVALID_ENVELOPE,
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

    private enum Mode {
        REPLY,
        ACTION
    }
}
