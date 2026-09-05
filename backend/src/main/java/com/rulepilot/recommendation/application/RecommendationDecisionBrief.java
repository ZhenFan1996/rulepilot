package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Publishes the first model decision as a player-safe summary, never as hidden reasoning. */
final class RecommendationDecisionBrief {

    static final String FIELD = "decisionBrief";
    private static final Set<String> FIELDS = Set.of("chosenAction", "message");

    private final ObjectMapper json;

    RecommendationDecisionBrief(ObjectMapper json) {
        this.json = json;
    }

    StreamingPublisher streamingPublisher(Set<String> allowedActions, Consumer<String> listener) {
        return new StreamingPublisher(allowedActions, listener);
    }

    Optional<String> render(ToolCall call) {
        try {
            JsonNode root = json.readTree(call.argumentsJson());
            JsonNode value = root.path(FIELD);
            if (!root.isObject() || !value.isObject() || !exactFields(value)) return Optional.empty();
            if (!call.name().equals(text(value.path("chosenAction")))) return Optional.empty();
            return Optional.ofNullable(text(value.path("message")));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    ToolCall withoutBrief(ToolCall call) {
        try {
            JsonNode root = json.readTree(call.argumentsJson());
            if (!root.isObject() || !root.has(FIELD)) return call;
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove(FIELD);
            return new ToolCall(call.id(), call.name(), json.writeValueAsString(root));
        } catch (JsonProcessingException exception) {
            return call;
        }
    }

    private boolean exactFields(JsonNode value) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields.equals(FIELDS);
    }

    private String text(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) return null;
        return value.asText();
    }

    final class StreamingPublisher implements Consumer<ToolCall> {
        private final Set<String> allowedActions;
        private final Consumer<String> listener;
        private String lastPublished = "";

        private StreamingPublisher(
                Set<String> allowedActions,
                Consumer<String> listener) {
            this.allowedActions = Set.copyOf(allowedActions);
            this.listener = listener;
        }

        @Override
        public void accept(ToolCall call) {
            PartialDecision parsed = PartialDecision.parse(call.argumentsJson());
            if (!parsed.valid
                    || !parsed.completeText("chosenAction")
                    || !allowedActions.contains(call.name())
                    || !call.name().equals(parsed.text("chosenAction"))) {
                return;
            }
            publish(parsed.text("message"));
        }

        void finish(ToolCall call) {
            if (allowedActions.contains(call.name())) {
                render(call).ifPresent(this::publish);
            }
        }

        private void publish(String snapshot) {
            if (snapshot == null || snapshot.isBlank() || snapshot.equals(lastPublished)) return;
            lastPublished = snapshot;
            listener.accept(snapshot);
        }
    }

    /** Re-parses a small accumulated JSON prefix; only allow-listed decision fields can reach the UI. */
    private static final class PartialDecision {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Set<String> complete = new LinkedHashSet<>();
        private boolean valid = true;

        private static PartialDecision parse(String source) {
            PartialDecision decision = new PartialDecision();
            if (source == null) return decision;
            Cursor cursor = new Cursor(source);
            if (!cursor.take('{')) return decision.invalidate();
            ParsedString outer = cursor.string();
            if (!outer.valid) return decision.invalidate();
            if (!outer.complete) return decision;
            if (!FIELD.equals(outer.value) || !cursor.take(':') || !cursor.take('{')) {
                return decision.invalidate();
            }
            while (cursor.hasMore()) {
                if (cursor.take('}')) return decision;
                ParsedString key = cursor.string();
                if (!key.valid) return decision.invalidate();
                if (!key.complete) return decision;
                if (!FIELDS.contains(key.value)
                        || decision.values.containsKey(key.value)
                        || !cursor.take(':')) {
                    return decision.invalidate();
                }
                ParsedString value = cursor.string();
                if (!value.valid) return decision.invalidate();
                decision.values.put(key.value, value.value);
                if (!value.complete) return decision;
                decision.complete.add(key.value);
                if (cursor.take(',')) continue;
                if (cursor.take('}')) return decision;
                if (!cursor.hasMore()) return decision;
                return decision.invalidate();
            }
            return decision;
        }

        private PartialDecision invalidate() {
            valid = false;
            return this;
        }

        private String text(String field) {
            return values.get(field);
        }

        private boolean completeText(String field) {
            return complete.contains(field) && text(field) != null && !text(field).isBlank();
        }
    }

    private static final class Cursor {
        private final String source;
        private int index;

        private Cursor(String source) {
            this.source = source;
        }

        private boolean hasMore() {
            whitespace();
            return index < source.length();
        }

        private boolean take(char expected) {
            whitespace();
            if (index >= source.length() || source.charAt(index) != expected) return false;
            index++;
            return true;
        }

        private void whitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private ParsedString string() {
            whitespace();
            if (index >= source.length()) return new ParsedString("", false, true);
            if (source.charAt(index++) != '"') return new ParsedString("", false, false);
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') return new ParsedString(value.toString(), true, true);
                if (character < 0x20) return new ParsedString(value.toString(), false, false);
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= source.length()) return new ParsedString(value.toString(), false, true);
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (source.length() - index < 4) {
                            return new ParsedString(value.toString(), false, true);
                        }
                        int codePoint = 0;
                        for (int offset = 0; offset < 4; offset++) {
                            int digit = Character.digit(source.charAt(index + offset), 16);
                            if (digit < 0) return new ParsedString(value.toString(), false, false);
                            codePoint = codePoint * 16 + digit;
                        }
                        index += 4;
                        value.append((char) codePoint);
                    }
                    default -> {
                        return new ParsedString(value.toString(), false, false);
                    }
                }
            }
            return new ParsedString(value.toString(), false, true);
        }
    }

    private record ParsedString(String value, boolean complete, boolean valid) {}
}
