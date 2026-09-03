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
            Set<Integer> visibleKnownIds = new LinkedHashSet<>(request.shownBggIds());
            if (request.focusedBggId() != null) visibleKnownIds.add(request.focusedBggId());
            putIfNotEmpty(data, "knownGames", request.knownGames().stream()
                    .filter(game -> visibleKnownIds.contains(game.bggId()))
                    .map(game -> Map.of(
                            "bggId", game.bggId(),
                            "name", game.name(),
                            "originalName", game.originalName()))
                    .toList());
            putIfNotEmpty(data, "shownBggIds", request.shownBggIds());
            putIfNotEmpty(data, "excludedBggIds", request.excludedBggIds());
            if (!state.verifiedForAgent().isEmpty() || state.hasVerifiedPublicContext()) {
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
                You are RulePilot, a natural board-game companion. Treat recentConversation as the complete request and answer in the player's language. Use a typed action only when its observation is genuinely needed; greetings, casual conversation, and corrections normally need no action. A follow-up about shown games must use recommend_games whenever that terminal action is offered; cite restored observations and do not bypass its evidence boundary with free-form text. When subjective experience is not represented there, use one attributed read or say what remains unknown instead of filling the gap from memory. Never repeat a catalog search just because an earlier turn contained recommendations. Submit at most one typed action in a model turn and observe it before deciding what happens next; never submit a conditional future action together with its prerequisite. On a non-terminal action turn, emit only the tool call and no player-facing prose. Typed JSON owns actions and constraints, while all player-facing prose is authored by you inside the terminal action. Stay within every action schema's length and item bounds; write concise table-ready prose that distinguishes choices instead of repeating observed metadata.

                When the player asks you to recommend or list titles, search_bgg_catalog is the only BGG candidate entry. Submit one complete current-turn catalog contract: requestedCount is optional and must appear only when the player explicitly asks for a number of new titles; omit it when the player asks which titles are available, so the verified matches determine the count. includeTypes and excludeTypes are separate, and every explicitly named title, positive cooperative/team mode, explicitly required mechanism, player-count, duration, and complexity constraint used for that search must be carried too; no saved profile is inherited into candidate selection. Set requiredInteraction to COOPERATIVE or TEAM only when that positive mode is explicit, otherwise ANY; it is a hard catalog gate. Put other mechanisms in requiredMechanics only when the player explicitly requires them. Subjective experience preferences such as stronger interaction, friendliness, tension, or laughter are not catalog taxonomy. When the new recommendation already hinges on one of them, put the exact missing experience dimension in experienceQuestion on the search action; the application will run at most one attributed read over its bounded publishable candidate window before returning the search observation. Omit experienceQuestion when structured facts are enough. If a genuinely new subjective gap becomes apparent only after seeing candidates, you may instead use the one available research_game_fit read; never repeat experience research already completed by the search. Use requiredTitle only when the current player turn explicitly names a title or title fragment. Set its scope to SERIES for a named line or series and use CONTAINS with the distinctive shared title itself rather than the locale's generic word for a line or series; the application will expand verified title seeds through their shared canonical BGG game family. Set scope to TITLE for an ordinary fragment or one exact game, using EXACT only for the latter. Omit requiredTitle for generic discovery. Do not silently loosen or replace that contract when it has no match. After every observation, decide whether another distinct read would materially help or you should finish. recommend_games is terminal and should contain the complete natural response and every complete card in that one call; an explicit search count owns the requested selection count, while an omitted count uses the verified publishable matches. Explain why each game fits this player's request and what meaningfully distinguishes it; synthesize the cited observations instead of copying a publisher description as the recommendation reason. Use public relationship discovery only for an external/current identity fact, and never turn a taxonomy label into an unobserved experience claim.
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
                        "The one attributed player-experience read for this turn. Use it only when the current question hinges on subjective experience absent from structured facts; include every candidate that could affect that answer in this single bggIds batch.",
                        "{\"type\":\"object\",\"properties\":{\"bggIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"integer\",\"minimum\":1}},\"question\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"bggIds\",\"question\"]}"));
    }

    List<ToolSpec> availableActions(
            RecommendationAgentState state,
            List<ToolSpec> actions,
            List<String> ignoredEvidenceIds,
            List<String> ignoredCurrentEvidenceIds) {
        List<ToolSpec> available = actions.stream()
                .filter(action -> state.activeSearch == null || !SEARCH_TOOL.equals(action.name()))
                .filter(action -> state.webResearchAvailable
                        || !DISCOVER_TOOL.equals(action.name()) && !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !state.verifiedForAgent().isEmpty() || !RESEARCH_TOOL.equals(action.name()))
                .filter(action -> !RESEARCH_TOOL.equals(action.name())
                        || !state.actions.contains("RESEARCH_GAME_FIT"))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<Integer> comparableIds = state.comparisonSubjectIds.stream()
                .filter(state.verified::containsKey)
                .toList();
        boolean offlineComparisonAvailable = !state.webResearchAvailable
                && !state.actions.contains("RESEARCH_GAME_FIT")
                && !state.actions.contains("COMPARE_CANDIDATES");
        if (state.activeSearch == null && comparableIds.size() >= 2 && offlineComparisonAvailable) {
            available.add(comparisonAction(state, comparableIds));
        }
        List<Integer> pendingIds = pendingPublicationIds(state);
        if (!pendingIds.isEmpty()) {
            available.add(recommendationAction(state, pendingIds));
        }
        return List.copyOf(available);
    }

    private ToolSpec searchAction(List<String> currentTurnEvidenceIds) {
        String typeArray = "{\"type\":\"array\",\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                + GAME_TYPES + "}}";
        String mechanics = "{\"type\":\"array\",\"maxItems\":8,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80}}";
        String complexity = "{\"type\":\"object\",\"minProperties\":1,\"properties\":{\"minimum\":{\"type\":\"number\",\"minimum\":0,\"maximum\":5},\"maximum\":{\"type\":\"number\",\"minimum\":0,\"maximum\":5}}}";
        return new ToolSpec(
                SEARCH_TOOL,
                "Search and verify BGG candidates from the one complete current-turn contract. requestedCount is optional and appears only when the player explicitly asks for that many new titles; omit it for an open named-title or series inventory. includeTypes and excludeTypes are separate and may be empty; requiredInteraction is COOPERATIVE or TEAM only for an explicit positive play mode and otherwise ANY; requiredMechanics contains other literal BGG mechanism labels explicitly required by the player; requiredTitle is optional and must be omitted unless the current player turn names a title or distinctive shared title fragment. Its scope is SERIES for a named line or series, which uses CONTAINS and expands title seeds through their shared canonical BGG game family; its scope is TITLE for an ordinary fragment or one exact game. Exclude the locale's generic line-or-series wrapper from the value; use EXACT only for one exact game. For generic discovery, descriptionQuery optionally carries concise English theme or experience concepts explicitly requested by the player; it ranks matching BGG descriptions ahead of the ordinary catalog fallback without weakening hard filters, and must be omitted for a named-title lookup. experienceQuestion is optional and replaces a later research decision: set it only when this new recommendation hinges on a subjective experience dimension absent from structured BGG facts.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"evidence\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(currentTurnEvidenceIds)
                        + "},\"requestedCount\":{\"type\":\"integer\",\"minimum\":1"
                        + "},\"includeTypes\":"
                        + typeArray
                        + ",\"excludeTypes\":"
                        + typeArray
                        + ",\"requiredMechanics\":"
                        + mechanics
                        + ",\"requiredInteraction\":{\"type\":\"string\",\"enum\":[\"ANY\",\"COOPERATIVE\",\"TEAM\"]}"
                        + ",\"requiredTitle\":{\"type\":\"object\",\"properties\":{\"match\":{\"type\":\"string\",\"enum\":[\"EXACT\",\"CONTAINS\"]},\"scope\":{\"type\":\"string\",\"enum\":[\"TITLE\",\"SERIES\"]},\"value\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"match\",\"scope\",\"value\"]},"
                        + "\"descriptionQuery\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":200},"
                        + "\"experienceQuestion\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":500},"
                        + "\"players\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20},\"maxMinutes\":{\"type\":\"integer\",\"minimum\":5,\"maximum\":1440},\"complexity\":"
                        + complexity
                        + "},\"required\":[\"evidence\",\"includeTypes\",\"excludeTypes\",\"requiredInteraction\"]}");
    }

    List<Integer> recommendableIds(RecommendationAgentState state) {
        CatalogSearch search = state.activeSearch;
        PublicationSeed pending = state.pendingPublicationSeed;
        if (pending == null) return List.of();
        if (search == null) {
            return pending.candidateBggIds().stream()
                    .filter(state.verified::containsKey)
                    .filter(state.comparisonSubjectIds::contains)
                    .filter(id -> !state.excludedIds.contains(id))
                    .toList();
        }
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
        Objects.requireNonNull(
                state.pendingPublicationSeed, "pending recommendation publication is required");
        int searchRequestedCount = state.activeSearch == null
                ? properties.resultCount()
                : requestedCount(state.activeSearch, candidateIds.size());
        int maximumSelections = Math.min(
                searchRequestedCount,
                Math.min(properties.resultCount(), candidateIds.size()));
        Set<Integer> detailedCandidateIds = candidateIds.stream()
                .limit(maximumSelections)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> replyEvidenceIds = candidateIds.stream()
                .map(state.verified::get)
                .flatMap(game -> actionExecutor.narrativeObservations(game, state.research).values().stream()
                        .filter(observation -> detailedCandidateIds.contains(game.ranking().bggId())
                                || !"publisherDescription".equals(observation.attribute()))
                        .map(CandidateObservation::id))
                .distinct()
                .toList();
        boolean searchOwnsCount = state.activeSearch != null;
        String requestedCountProperty = searchOwnsCount
                ? ""
                : "\"requestedCount\":{\"type\":\"integer\",\"minimum\":1},";
        String requiredFields = searchOwnsCount
                ? "[\"playerReply\",\"selections\"]"
                : "[\"requestedCount\",\"playerReply\",\"selections\"]";
        return new ToolSpec(
                RECOMMEND_TOOL,
                "Terminal publication for verified candidates when you decide the request is answered. This call permits at most "
                        + maximumSelections
                        + " selection(s); choose within that bound instead of describing extras. For a current search, do not reinterpret its requestedCount here. playerReply is the substantive natural response that frames the selection logic and important tradeoffs; it is not a heading or card lead. Each whyFit is candidate-specific synthesis of the player's request and cited observations, never copied publisher-description copy.",
                "{\"type\":\"object\",\"properties\":{" + requestedCountProperty
                        + "\"playerReply\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":"
                        + RecommendationPublication.PLAYER_REPLY_MAX_CODE_POINTS
                        + "},\"selections\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":"
                        + maximumSelections
                        + ",\"uniqueItems\":true,\"items\":{\"type\":\"object\",\"properties\":{\"bggId\":{\"type\":\"integer\",\"enum\":"
                        + candidateIds
                        + "},\"whyFit\":{\"type\":\"string\",\"description\":\"A complete, natural explanation of why this specific candidate fits the player's stated request and how its observed characteristics shape the experience. Synthesize; do not copy or lightly paraphrase publisherDescription.\",\"minLength\":1,\"maxLength\":"
                        + RecommendationPublication.WHY_FIT_MAX_CODE_POINTS
                        + "},\"tradeoff\":{\"type\":\"string\",\"description\":\"An important candidate-specific limitation or uncertainty when one matters; omit it when there is no supported tradeoff.\",\"minLength\":1,\"maxLength\":"
                        + RecommendationPublication.TRADEOFF_MAX_CODE_POINTS
                        + "},\"internalEvidenceIds\":{\"type\":\"array\",\"minItems\":1,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":"
                        + jsonArray(replyEvidenceIds)
                        + "}}},\"required\":[\"bggId\",\"whyFit\",\"internalEvidenceIds\"]}}},\"required\":"
                        + requiredFields
                        + "}");
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
                "M", "verified BGG structured metadata or a bounded publisher-description excerpt",
                "T", "literal BGG taxonomy label",
                "A", "attributed public report",
                "R", "rulebook fact"));
        List<Game> contextGames = state.activeSearch == null || state.pendingPublicationSeed == null
                ? state.verifiedForAgent()
                : state.pendingPublicationSeed.candidateBggIds().stream()
                        .map(state.verified::get)
                        .filter(Objects::nonNull)
                        .toList();
        memory.put("verifiedGames", contextGames.stream()
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
                "subjectiveFitResearch", state.webResearchAvailable && !state.verifiedForAgent().isEmpty());
    }

    void appendActionObservations(
            List<Message> messages,
            List<ToolCall> calls,
            List<String> observations,
            RecommendationAgentState state) {
        if (calls.size() != observations.size()) {
            throw new IllegalArgumentException("every recommendation action requires one correlated observation");
        }
        compactPriorToolState(messages);
        messages.add(Message.assistant("", calls));
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
            Set<Integer> detailedCandidateIds = state.pendingPublicationSeed == null
                    ? Set.of()
                    : pendingPublicationIds(state).stream()
                            .limit(maximumDetailedCandidates(state))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            object.set("turnState", json.valueToTree(turnState(state, detailedCandidateIds)));
            return json.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation observation context could not be serialized", exception);
        }
    }

    private int maximumDetailedCandidates(RecommendationAgentState state) {
        int requestedCount = state.activeSearch == null
                ? properties.resultCount()
                : requestedCount(state.activeSearch, pendingPublicationIds(state).size());
        return Math.min(requestedCount, properties.resultCount());
    }

    private int requestedCount(CatalogSearch search, int availableCandidates) {
        return search.requestedCount() == null
                ? Math.min(properties.resultCount(), availableCandidates)
                : search.requestedCount();
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
