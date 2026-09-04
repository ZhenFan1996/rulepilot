package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
}
