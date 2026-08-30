package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DialogueMessage;
import com.rulepilot.recommendation.application.RecommendationAgentState.CatalogSearch;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import com.rulepilot.recommendation.application.RecommendationAgentState.TitleMatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns the small model-visible state machine and every dynamic recommendation schema. */
final class RecommendationToolCatalog {

    private static final String GAME_TYPES =
            "[\"ABSTRACT\",\"CUSTOMIZABLE\",\"CHILDREN\",\"FAMILY\",\"PARTY\",\"STRATEGY\",\"THEMATIC\",\"WAR\",\"EXPANSION\"]";

    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationProperties properties;
    private final ObjectMapper json;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationActions actionExecutor;

    RecommendationToolCatalog(
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json,
            RecommendationEvidenceReview evidenceReview,
            RecommendationActions actionExecutor) {
        this.selector = selector;
        this.properties = properties;
        this.json = json;
        this.evidenceReview = evidenceReview;
        this.actionExecutor = actionExecutor;
    }

    String agentInput(ConversationRequest request, RecommendationAgentState state, String locale) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("locale", locale);
            data.put("recentConversation", conversationEvidence(request));
            if (request.focusedBggId() != null) data.put("focusedBggId", request.focusedBggId());
            putIfNotEmpty(data, "knownGames", request.knownGames().stream()
                    .map(game -> Map.of(
                            "bggId", game.bggId(),
                            "name", game.name(),
                            "originalName", game.originalName()))
                    .toList());
            putIfNotEmpty(data, "shownBggIds", request.shownBggIds());
            putIfNotEmpty(data, "excludedBggIds", request.excludedBggIds());
            if (!state.verified.isEmpty() || state.hasVerifiedPublicContext()) {
                Set<Integer> focusedIds = request.focusedBggId() == null
                        ? Set.of()
                        : Set.of(request.focusedBggId());
                data.put("restoredTurnState", turnState(state, focusedIds));
            }
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation Agent input could not be serialized", exception);
        }
    }

    private List<Map<String, String>> conversationEvidence(ConversationRequest request) {
        Map<String, String> indexed = evidenceReview.preferenceEvidence(request);
        int userIndex = 0;
        List<Map<String, String>> conversation = new ArrayList<>();
        for (DialogueMessage message : request.transcript()) {
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("role", message.role());
            turn.put("text", message.text());
            if ("user".equals(message.role())) turn.put("evidenceId", "U" + (++userIndex));
            conversation.add(Map.copyOf(turn));
        }
        if (userIndex != indexed.size()) {
            throw new IllegalStateException("recommendation evidence indexing is inconsistent");
        }
        return List.copyOf(conversation);
    }

    static String systemPrompt() {
        return """
                You are RulePilot, a natural board-game companion. Treat recentConversation as the complete request and answer in the player's language. Use a typed action only when its observation is genuinely needed; otherwise answer directly in this model turn. Greetings, casual conversation, corrections, and follow-up discussion about already shown games normally need no action. Never repeat a catalog search just because an earlier turn contained recommendations. Typed JSON owns actions and constraints, while all player-facing prose is authored by you.

                When the player asks you to recommend titles, search_bgg_catalog is the only BGG candidate entry. Its one current-turn contract explicitly separates included and excluded BGG product types and carries every title, player-count, duration, and complexity constraint used for that search; no saved profile is inherited into candidate selection. An exact named game uses the same title field with EXACT. After every observation, decide for yourself whether the request is answerable, another available read would materially help, or you should finish transparently. recommend_games is terminal and should contain the complete natural response and every complete card in that one call. Explain why each game fits this player's request and what meaningfully distinguishes it; synthesize the cited observations instead of copying a publisher description as the recommendation reason. Use public relationship discovery only for an external/current identity fact. When player experience matters, use research_game_fit and keep it attributed; never turn a catalog taxonomy or mechanism label into an unobserved experience claim. Never guess a title or factual game detail.
                """;
    }

    List<ToolSpec> actions(List<String> ignoredEvidenceIds, List<String> currentTurnEvidenceIds) {
        return List.of(
                searchAction(currentTurnEvidenceIds),
                new ToolSpec(
                        DISCOVER_TOOL,
                        "Read an attributed public relationship or current identity fact that the BGG catalog does not own.",
                        "{\"type\":\"object\",\"properties\":{\"evidence\":{\"type\":\"string\",\"enum\":"
                                + jsonArray(currentTurnEvidenceIds)
                                + "},\"subject\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"evidence\",\"subject\"]}"),
                new ToolSpec(
                        RESEARCH_TOOL,
                        "Read attributed player-reported experience for already verified candidate IDs.",
                        "{\"type\":\"object\",\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"question\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"bggIds\",\"question\"]}"));
    }

    List<ToolSpec> availableActions(
            RecommendationAgentState state,
            List<ToolSpec> actions,
            List<String> ignoredEvidenceIds,
            List<String> ignoredCurrentEvidenceIds) {
        List<ToolSpec> available = actions.stream()
                .filter(action -> state.webResearchAvailable
                        || !DISCOVER_TOOL.equals(action.name()) && !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.verified.isEmpty() || !RESEARCH_TOOL.equals(action.name()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<Integer> comparableIds = state.comparisonSubjectIds.stream()
                .filter(state.verified::containsKey)
                .toList();
        if (comparableIds.size() >= 2) available.add(comparisonAction(state, comparableIds));
        List<Integer> pendingIds = pendingPublicationIds(state);
        if (!pendingIds.isEmpty()) available.add(recommendationAction(state, pendingIds));
        return List.copyOf(available);
    }

    private ToolSpec searchAction(List<String> currentTurnEvidenceIds) {
        String typeArray = "{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                + GAME_TYPES + "}}";
        String complexity = "{\"type\":\"object\",\"minProperties\":1,\"properties\":{\"minimum\":{\"type\":\"number\",\"minimum\":0,\"maximum\":5},\"maximum\":{\"type\":\"number\",\"minimum\":0,\"maximum\":5}}}";
        return new ToolSpec(
                SEARCH_TOOL,
                "Search and verify BGG candidates from one current-turn contract. includeTypes and excludeTypes are separate and may be empty. title supports EXACT or CONTAINS.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"evidence\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(currentTurnEvidenceIds)
                        + "},\"includeTypes\":"
                        + typeArray
                        + ",\"excludeTypes\":"
                        + typeArray
                        + ",\"title\":{\"type\":\"object\",\"properties\":{\"match\":{\"type\":\"string\",\"enum\":[\"EXACT\",\"CONTAINS\"]},\"value\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"match\",\"value\"]},"
                        + "\"players\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},\"maxMinutes\":{\"type\":\"integer\",\"minimum\":5,\"maximum\":1440},\"complexity\":"
                        + complexity
                        + "},\"required\":[\"evidence\",\"includeTypes\",\"excludeTypes\"]}");
    }

    List<Integer> recommendableIds(RecommendationAgentState state) {
        CatalogSearch search = state.activeSearch;
        PublicationSeed pending = state.pendingPublicationSeed;
        if (search == null || pending == null) return List.of();
        boolean exactTitle = search.title() != null && search.title().match() == TitleMatch.EXACT;
        return pending.candidateBggIds().stream()
                .filter(state.verified::containsKey)
                .filter(id -> !state.excludedIds.contains(id))
                .filter(id -> exactTitle || !state.previouslyShownIds.contains(id))
                .filter(id -> search.matches(state.verified.get(id)))
                .filter(id -> selector.eligible(state.verified.get(id), search.selectionProfile()))
                .toList();
    }

    private List<Integer> pendingPublicationIds(RecommendationAgentState state) {
        if (state.pendingPublicationSeed == null) return List.of();
        LinkedHashSet<Integer> eligible = new LinkedHashSet<>(recommendableIds(state));
        return state.pendingPublicationSeed.candidateBggIds().stream()
                .filter(eligible::contains)
                .filter(id -> !actionExecutor.narrativeObservations(
                                state.verified.get(id), state.research)
                        .isEmpty())
                .toList();
    }

    private ToolSpec recommendationAction(RecommendationAgentState state, List<Integer> candidateIds) {
        PublicationSeed pending = Objects.requireNonNull(
                state.pendingPublicationSeed, "pending recommendation publication is required");
        int maximumSelections = candidateIds.size();
        List<String> replyEvidenceIds = candidateIds.stream()
                .map(state.verified::get)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).keySet().stream())
                .distinct()
                .toList();
        String candidateSchemas = candidateIds.stream()
                .map(id -> recommendationSelectionSchema(state, id))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return new ToolSpec(
                RECOMMEND_TOOL,
                "Terminal publication for verified candidates when you decide the request is answered. playerReply is the substantive natural response that frames the selection logic and important tradeoffs; it is not a heading or card lead. Each whyFit is candidate-specific synthesis of the player's request and cited observations, never copied publisher-description copy.",
                "{\"type\":\"object\",\"properties\":{\"requestedCount\":{\"type\":\"integer\",\"minimum\":1},\"playerReply\":{\"type\":\"string\",\"minLength\":1},\"playerReplyEvidenceIds\":{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(replyEvidenceIds)
                        + "}},\"selections\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":"
                        + maximumSelections
                        + ",\"uniqueItems\":true,\"items\":{\"oneOf\":"
                        + candidateSchemas
                        + "}}},\"required\":[\"requestedCount\",\"playerReply\",\"playerReplyEvidenceIds\",\"selections\"]}");
    }

    private String recommendationSelectionSchema(RecommendationAgentState state, int bggId) {
        Game game = Objects.requireNonNull(state.verified.get(bggId));
        List<String> evidenceIds = actionExecutor.narrativeObservations(game, state.research)
                .keySet()
                .stream()
                .toList();
        return "{\"type\":\"object\",\"properties\":{\"bggId\":{\"type\":\"integer\",\"enum\":["
                + bggId
                + "]},\"whyFit\":{\"type\":\"string\",\"description\":\"A complete, natural explanation of why this specific candidate fits the player's stated request and how its observed characteristics shape the experience. Synthesize; do not copy or lightly paraphrase publisherDescription.\",\"minLength\":1},\"tradeoff\":{\"type\":\"string\",\"description\":\"An important candidate-specific limitation or uncertainty when one matters; omit it when there is no supported tradeoff.\",\"minLength\":1},\"internalEvidenceIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                + jsonArray(evidenceIds)
                + "}}},\"required\":[\"bggId\",\"whyFit\",\"internalEvidenceIds\"]}";
    }

    private ToolSpec comparisonAction(RecommendationAgentState state, List<Integer> comparableIds) {
        LinkedHashSet<String> subjects = new LinkedHashSet<>();
        comparableIds.stream()
                .map(state.verified::get)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).values().stream())
                .map(CandidateObservation::attribute)
                .forEach(subjects::add);
        return new ToolSpec(
                COMPARE_TOOL,
                "Read a structured comparison observation for at least two verified conversation candidates. This is not terminal: after observing it, decide again whether to recommend, read more, or answer naturally.",
                "{\"type\":\"object\",\"properties\":{\"candidateBggIds\":{\"type\":\"array\",\"minItems\":2,\"uniqueItems\":true,\"items\":{\"type\":\"integer\",\"enum\":"
                        + comparableIds
                        + "}},\"subjects\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(List.copyOf(subjects))
                        + "}}},\"required\":[\"candidateBggIds\",\"subjects\"]}");
    }

    private Map<String, Object> turnState(
            RecommendationAgentState state,
            Set<Integer> detailedGameIds) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("observationLegend", Map.of(
                "M", "verified BGG structured metadata or complete publisher description",
                "T", "literal BGG taxonomy label",
                "A", "attributed public report",
                "R", "rulebook fact"));
        memory.put("verifiedGames", state.verifiedForAgent().stream()
                .map(game -> actionExecutor.gameObservation(
                        game,
                        detailedGameIds.contains(game.ranking().bggId())))
                .toList());
        memory.put("recommendableBggIds", recommendableIds(state));
        if (state.pendingPublicationSeed != null) {
            memory.put("pendingRecommendation", Map.of(
                    "verifiedCandidateBggIds", pendingPublicationIds(state),
                    "terminalAction", RECOMMEND_TOOL));
        }
        putIfNotEmpty(memory, "publicContextEvidence", state.publicContextEvidence.values().stream()
                .map(actionExecutor::publicContextObservation)
                .toList());
        putIfNotEmpty(memory, "publicContextSources", actionExecutor.sourceObservations(state.publicContextSources));
        putIfNotEmpty(memory, "researchEvidence", state.research.games().stream()
                .map(game -> Map.of(
                        "bggId", game.bggId(),
                        "observations", actionExecutor.researchObservations(game.bggId(), state.research).values().stream()
                                .map(item -> Map.of(
                                        "id", item.id(),
                                        "attribute", item.attribute(),
                                        "kind", item.kind().name(),
                                        "text", item.value(),
                                        "sourceIndexes", item.sourceIndexes()))
                                .toList()))
                .toList());
        putIfNotEmpty(memory, "researchSources", actionExecutor.sourceObservations(state.research.sources()));
        memory.put("actionsTaken", List.copyOf(state.actions));
        if (!state.webResearchAvailable && !state.webResearchFailureCode.isBlank()) {
            memory.put("webResearchFailureCode", state.webResearchFailureCode);
        }
        return memory;
    }

    private Map<String, Boolean> availableCapabilities(RecommendationAgentState state) {
        return Map.of(
                "publicRelationship", state.webResearchAvailable,
                "subjectiveFitResearch", state.webResearchAvailable && !state.verified.isEmpty());
    }

    void appendActionObservations(
            List<Message> messages,
            String assistantText,
            List<ToolCall> calls,
            List<String> observations,
            RecommendationAgentState state) {
        if (calls.size() != observations.size()) {
            throw new IllegalArgumentException("every recommendation action requires one correlated observation");
        }
        compactPriorToolState(messages);
        messages.add(Message.assistant(assistantText, calls));
        for (int index = 0; index < calls.size(); index++) {
            String observation = index == calls.size() - 1
                    ? contextualObservation(observations.get(index), state)
                    : observations.get(index);
            messages.add(Message.tool(calls.get(index), observation));
        }
    }

    private String contextualObservation(String observation, RecommendationAgentState state) {
        try {
            JsonNode parsed = json.readTree(observation);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalStateException("recommendation observation must be a JSON object");
            }
            object.set("availableCapabilities", json.valueToTree(availableCapabilities(state)));
            Set<Integer> currentSearchIds = state.pendingPublicationSeed == null
                    ? Set.of()
                    : Set.copyOf(state.pendingPublicationSeed.candidateBggIds());
            object.set("turnState", json.valueToTree(turnState(state, currentSearchIds)));
            return json.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation context could not be serialized", exception);
        }
    }

    private void compactPriorToolState(List<Message> messages) {
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message.role() != BoardGameRecommendationModel.Role.TOOL) continue;
            try {
                JsonNode parsed = json.readTree(message.content());
                if (!(parsed instanceof ObjectNode object)) continue;
                object.remove(List.of("availableCapabilities", "turnState"));
                messages.set(index, new Message(
                        BoardGameRecommendationModel.Role.TOOL,
                        json.writeValueAsString(object),
                        List.of(),
                        message.toolCallId(),
                        message.toolName()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("recommendation tool observation could not be compacted", exception);
            }
        }
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private void putIfNotEmpty(Map<String, Object> target, String field, List<?> values) {
        if (!values.isEmpty()) target.put(field, values);
    }
}
