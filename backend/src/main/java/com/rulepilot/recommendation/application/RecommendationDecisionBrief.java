package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Publishes the first model decision as a player-safe summary, never as hidden reasoning. */
final class RecommendationDecisionBrief {

    static final String FIELD = "decisionBrief";
    private static final Set<String> FIELDS = Set.of(
            "chosenAction",
            "understoodGoal",
            "constraints",
            "direction",
            "decisionFactors",
            "nextStep",
            "uncertainties");

    private final ObjectMapper json;

    RecommendationDecisionBrief(ObjectMapper json) {
        this.json = json;
    }

    StreamingPublisher streamingPublisher(
            String locale,
            Set<String> allowedActions,
            Consumer<String> listener) {
        return new StreamingPublisher("zh-CN".equals(locale), allowedActions, listener);
    }

    Optional<String> render(ToolCall call, String locale) {
        try {
            JsonNode root = json.readTree(call.argumentsJson());
            JsonNode value = root.path(FIELD);
            if (!root.isObject() || !value.isObject() || !exactFields(value)) return Optional.empty();
            String chosenAction = text(value.path("chosenAction"));
            if (!call.name().equals(chosenAction)) return Optional.empty();
            String understoodGoal = text(value.path("understoodGoal"));
            List<String> constraints = texts(value.path("constraints"), false);
            String direction = text(value.path("direction"));
            List<String> decisionFactors = texts(value.path("decisionFactors"), true);
            String nextStep = text(value.path("nextStep"));
            List<String> uncertainties = texts(value.path("uncertainties"), false);
            if (understoodGoal == null
                    || constraints == null
                    || direction == null
                    || decisionFactors == null
                    || nextStep == null
                    || uncertainties == null) {
                return Optional.empty();
            }
            return Optional.of(markdown(
                    "zh-CN".equals(locale),
                    understoodGoal,
                    constraints,
                    direction,
                    decisionFactors,
                    nextStep,
                    uncertainties));
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
        return value.asText().strip();
    }

    private List<String> texts(JsonNode value, boolean required) {
        if (!value.isArray()) return null;
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            String text = text(item);
            if (text == null) return null;
            values.add(text);
        }
        if (required && values.isEmpty()) return null;
        return List.copyOf(values);
    }

    private String markdown(
            boolean chinese,
            String goal,
            List<String> constraints,
            String direction,
            List<String> factors,
            String nextStep,
            List<String> uncertainties) {
        StringBuilder output = new StringBuilder();
        section(output, chinese ? "我对这次请求的判断" : "How I am reading this request", goal);
        listSection(output, chinese ? "我识别到的约束" : "Constraints I identified", constraints,
                chinese ? "暂时没有明确的硬约束。" : "No explicit hard constraint yet.");
        section(output, chinese ? "我准备优先走的方向" : "Direction I will take first", direction);
        listSection(output, chinese ? "影响这个选择的因素" : "Factors behind this choice", factors, null);
        section(output, chinese ? "下一步会核对什么" : "What I will check next", nextStep);
        listSection(output, chinese ? "目前还不确定的部分" : "What remains uncertain", uncertainties,
                chinese ? "目前没有需要提前声明的不确定项。" : "No uncertainty needs to be called out yet.");
        return output.toString().strip();
    }

    private void section(StringBuilder output, String heading, String value) {
        output.append("**").append(heading).append("**\n\n").append(value).append("\n\n");
    }

    private void listSection(
            StringBuilder output,
            String heading,
            List<String> values,
            String emptyValue) {
        output.append("**").append(heading).append("**\n\n");
        if (values.isEmpty()) {
            output.append(emptyValue);
        } else {
            values.forEach(value -> output.append("- ").append(value).append("\n"));
        }
        output.append("\n");
    }

    final class StreamingPublisher implements Consumer<String> {
        private final boolean chinese;
        private final Set<String> allowedActions;
        private final Consumer<String> listener;
        private String lastPublished = "";

        private StreamingPublisher(
                boolean chinese,
                Set<String> allowedActions,
                Consumer<String> listener) {
            this.chinese = chinese;
            this.allowedActions = Set.copyOf(allowedActions);
            this.listener = listener;
        }

        @Override
        public void accept(String accumulatedArguments) {
            PartialDecision parsed = PartialDecision.parse(accumulatedArguments);
            if (!parsed.valid
                    || !parsed.completeText("chosenAction")
                    || !allowedActions.contains(parsed.text("chosenAction"))) {
                return;
            }
            publish(partialMarkdown(parsed));
        }

        void finish(ToolCall call) {
            render(call, chinese ? "zh-CN" : "en").ifPresent(this::publish);
        }

        private void publish(String snapshot) {
            if (snapshot.isBlank() || snapshot.equals(lastPublished)) return;
            lastPublished = snapshot;
            listener.accept(snapshot);
        }

        private String partialMarkdown(PartialDecision decision) {
            StringBuilder output = new StringBuilder();
            partialSection(
                    output,
                    chinese ? "我对这次请求的判断" : "How I am reading this request",
                    decision.text("understoodGoal"));
            partialListSection(
                    output,
                    chinese ? "我识别到的约束" : "Constraints I identified",
                    decision.list("constraints"));
            partialSection(
                    output,
                    chinese ? "我准备优先走的方向" : "Direction I will take first",
                    decision.text("direction"));
            partialListSection(
                    output,
                    chinese ? "影响这个选择的因素" : "Factors behind this choice",
                    decision.list("decisionFactors"));
            partialSection(
                    output,
                    chinese ? "下一步会核对什么" : "What I will check next",
                    decision.text("nextStep"));
            partialListSection(
                    output,
                    chinese ? "目前还不确定的部分" : "What remains uncertain",
                    decision.list("uncertainties"));
            return output.toString().strip();
        }

        private void partialSection(StringBuilder output, String heading, String value) {
            if (value == null || value.isBlank()) return;
            section(output, heading, value);
        }

        private void partialListSection(StringBuilder output, String heading, List<String> values) {
            if (values == null || values.stream().allMatch(String::isBlank)) return;
            output.append("**").append(heading).append("**\n\n");
            values.stream()
                    .filter(value -> !value.isBlank())
                    .forEach(value -> output.append("- ").append(value).append("\n"));
            output.append("\n");
        }
    }

    /** Re-parses a small accumulated JSON prefix; only allow-listed decision fields can reach the UI. */
    private static final class PartialDecision {
        private static final Set<String> TEXT_FIELDS = Set.of(
                "chosenAction", "understoodGoal", "direction", "nextStep");
        private static final Set<String> LIST_FIELDS = Set.of(
                "constraints", "decisionFactors", "uncertainties");

        private final Map<String, Object> values = new LinkedHashMap<>();
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
                if ((!TEXT_FIELDS.contains(key.value) && !LIST_FIELDS.contains(key.value))
                        || decision.values.containsKey(key.value)
                        || !cursor.take(':')) {
                    return decision.invalidate();
                }
                if (TEXT_FIELDS.contains(key.value)) {
                    ParsedString value = cursor.string();
                    if (!value.valid) return decision.invalidate();
                    decision.values.put(key.value, value.value);
                    if (!value.complete) return decision;
                    decision.complete.add(key.value);
                } else {
                    ParsedList value = cursor.stringList();
                    if (!value.valid) return decision.invalidate();
                    decision.values.put(key.value, value.values);
                    if (!value.complete) return decision;
                    decision.complete.add(key.value);
                }
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
            Object value = values.get(field);
            return value instanceof String text ? text : null;
        }

        @SuppressWarnings("unchecked")
        private List<String> list(String field) {
            Object value = values.get(field);
            return value instanceof List<?> ? (List<String>) value : null;
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

        private ParsedList stringList() {
            if (!take('[')) return new ParsedList(List.of(), false, false);
            List<String> values = new ArrayList<>();
            while (hasMore()) {
                if (take(']')) return new ParsedList(List.copyOf(values), true, true);
                ParsedString value = string();
                if (!value.valid) return new ParsedList(List.copyOf(values), false, false);
                values.add(value.value);
                if (!value.complete) return new ParsedList(List.copyOf(values), false, true);
                if (take(',')) continue;
                if (take(']')) return new ParsedList(List.copyOf(values), true, true);
                if (!hasMore()) return new ParsedList(List.copyOf(values), false, true);
                return new ParsedList(List.copyOf(values), false, false);
            }
            return new ParsedList(List.copyOf(values), false, true);
        }
    }

    private record ParsedString(String value, boolean complete, boolean valid) {}

    private record ParsedList(List<String> values, boolean complete, boolean valid) {}
}
