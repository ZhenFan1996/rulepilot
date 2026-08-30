package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressPhase;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** Canonicalizes actions and rejects batches whose calls would compete for the one turn state. */
final class RecommendationReadBatchExecutor {

    private static final Set<String> READ_ACTIONS = Set.of(SEARCH_TOOL, DISCOVER_TOOL, RESEARCH_TOOL);
    private final ObjectMapper actionJson;

    RecommendationReadBatchExecutor(
            RecommendationActions ignoredActions,
            RecommendationReActLoop ignoredRuntime,
            ExecutorService ignoredExecutor,
            ObjectMapper json) {
        actionJson = json.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    Compatibility compatibility(List<ToolCall> calls, List<ToolSpec> ignoredAvailableActions) {
        List<Map<String, String>> issues = calls.stream()
                .map(call -> Map.of(
                        "callId", call.id(),
                        "action", call.name(),
                        "code", "SHARED_TURN_STATE"))
                .toList();
        return new Compatibility(issues);
    }

    List<RecommendationActions.ActionOutcome> execute(
            List<ToolCall> calls,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            ProgressListener progress) {
        throw new IllegalStateException("shared-state recommendation action batches cannot execute");
    }

    boolean readOnly(String action) {
        return READ_ACTIONS.contains(action);
    }

    String fingerprint(ToolCall call) {
        try {
            JsonNode arguments = strictArguments(call);
            return call.name() + "\n" + actionJson.writeValueAsString(canonicalJson(arguments));
        } catch (JsonProcessingException exception) {
            return call.name() + "\n" + call.argumentsJson();
        }
    }

    JsonNode strictArguments(ToolCall call) throws JsonProcessingException {
        return actionJson.readTree(call.argumentsJson());
    }

    private JsonNode canonicalJson(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = actionJson.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> canonical.set(field, canonicalJson(value.path(field))));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = actionJson.createArrayNode();
            value.forEach(element -> canonical.add(canonicalJson(element)));
            return canonical;
        }
        return value.deepCopy();
    }

    record Compatibility(List<Map<String, String>> issues) {
        Compatibility {
            issues = List.copyOf(issues);
        }

        boolean compatible() {
            return issues.isEmpty();
        }
    }

    @FunctionalInterface
    interface ProgressListener {
        void accept(String action, ProgressStage stage, ProgressPhase phase, ProgressFocus focus);
    }
}
