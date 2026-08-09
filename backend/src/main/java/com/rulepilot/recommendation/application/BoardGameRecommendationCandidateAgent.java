package com.rulepilot.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.DialogueMessage;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.RetrievalPlan;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.UserModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel.ToolSpec;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.NameSearchObservation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Bounded ReAct-style candidate retrieval owned independently by recommendation. */
@Component
@Profile("!test")
class BoardGameRecommendationCandidateAgent {

    static final String SEARCH_TOOL = "search_bgg_by_name";
    static final String LOOKUP_TOOL = "lookup_bgg_games";
    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationCandidateAgent.class);
    private static final int MAX_MODEL_CALLS = 4;
    private static final int MAX_TOOL_CALLS = 5;
    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec(
                    SEARCH_TOOL,
                    "Search the complete local BGG CSV snapshot by one to eight likely original or English BGG game "
                            + "titles. Every array item must be one game title, never a category, mechanism, translated "
                            + "user phrase, or generic search query. It returns legal BGG IDs but no gameplay facts.",
                    """
                    {"type":"object","additionalProperties":false,"required":["names"],"properties":{"names":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string","minLength":2,"maxLength":120}}}}
                    """),
            new ToolSpec(
                    LOOKUP_TOOL,
                    "Read BGG details for one to twelve IDs returned by search_bgg_by_name. Use the observation to verify players, time, categories, and mechanisms.",
                    """
                    {"type":"object","additionalProperties":false,"required":["bggIds"],"properties":{"bggIds":{"type":"array","minItems":1,"maxItems":12,"items":{"type":"integer","minimum":1}}}}
                    """));

    private final BoardGameRecommendationCandidateModel model;
    private final BoardGameRecommendationTools tools;
    private final ObjectMapper json;

    BoardGameRecommendationCandidateAgent(
            BoardGameRecommendationCandidateModel model,
            BoardGameRecommendationTools tools,
            ObjectMapper json) {
        this.model = model;
        this.tools = tools;
        this.json = json;
    }

    boolean configured() {
        return model.configured();
    }

    Result discover(RetrievalPlan retrievalPlan, RecommendationProfile profile, String locale) {
        return discover(
                retrievalPlan,
                profile,
                new DiscoveryContext(new UserModel("", List.of()), List.of(), List.of(), 2),
                locale,
                ignored -> {});
    }

    Result discover(
            RetrievalPlan retrievalPlan,
            RecommendationProfile profile,
            String locale,
            Consumer<Step> stepListener) {
        return discover(
                retrievalPlan,
                profile,
                new DiscoveryContext(new UserModel("", List.of()), List.of(), List.of(), 2),
                locale,
                stepListener);
    }

    Result discover(
            RetrievalPlan retrievalPlan,
            RecommendationProfile profile,
            DiscoveryContext context,
            String locale,
            Consumer<Step> stepListener) {
        if (!configured()) return Result.unavailable();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt()));
        messages.add(Message.user(input(retrievalPlan, profile, context, locale)));
        Set<Integer> legalIds = new LinkedHashSet<>();
        Set<Integer> excludedIds = Set.copyOf(context.excludedBggIds());
        List<Game> verified = List.of();
        List<String> actions = new ArrayList<>();
        Set<String> executedCalls = new LinkedHashSet<>();
        int toolCalls = 0;

        for (int modelCalls = 1; modelCalls <= MAX_MODEL_CALLS; modelCalls++) {
            stepListener.accept(Step.MODEL_SELECTING);
            BoardGameRecommendationCandidateModel.Turn turn;
            try {
                turn = model.next(new Request(messages, TOOLS, 700));
            } catch (RuntimeException exception) {
                LOGGER.warn("Recommendation native candidate turn failed");
                return new Result(false, modelCalls, toolCalls, actions, verified);
            }
            actions.add("MODEL_SELECT_TOOLS");
            if (turn.toolCalls().isEmpty()) {
                LOGGER.warn("Recommendation candidate model completed without selecting a tool");
                return new Result(!verified.isEmpty(), modelCalls, toolCalls, actions, verified);
            }
            LOGGER.info(
                    "Recommendation model selected native tools {}",
                    turn.toolCalls().stream().map(ToolCall::name).toList());
            messages.add(Message.assistant(turn.text(), turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                if (toolCalls == MAX_TOOL_CALLS) {
                    return new Result(!verified.isEmpty(), modelCalls, toolCalls, actions, verified);
                }
                toolCalls++;
                if (SEARCH_TOOL.equals(call.name())) stepListener.accept(Step.SEARCHING_NAMES);
                if (LOOKUP_TOOL.equals(call.name())) stepListener.accept(Step.LOOKING_UP_DETAILS);
                String fingerprint = call.name() + "\n" + call.argumentsJson();
                ToolOutcome outcome = executedCalls.add(fingerprint)
                        ? execute(call, legalIds, excludedIds)
                        : ToolOutcome.error("REPEATED_TOOL_CALL");
                actions.add(outcome.action());
                legalIds.addAll(outcome.discoveredIds());
                if (!outcome.games().isEmpty()) verified = mergeVerified(verified, outcome.games());
                messages.add(Message.tool(call, outcome.observation()));
                if (SEARCH_TOOL.equals(call.name()) && !outcome.discoveredIds().isEmpty()) {
                    if (toolCalls == MAX_TOOL_CALLS) {
                        return new Result(false, modelCalls, toolCalls, actions, verified);
                    }
                    stepListener.accept(Step.LOOKING_UP_DETAILS);
                    toolCalls++;
                    CatalogObservation lookup = tools.lookupCandidates(
                            outcome.discoveredIds().stream().limit(12).toList());
                    actions.add("LOOKUP_BGG_CANDIDATES");
                    if (lookup.succeeded() && !lookup.games().isEmpty()) {
                        verified = mergeVerified(verified, lookup.games());
                        if (verified.size() >= context.desiredCandidateCount()) {
                            return new Result(true, modelCalls, toolCalls, actions, verified);
                        }
                        messages.add(Message.user(candidateGapObservation(
                                verified, context.desiredCandidateCount())));
                        continue;
                    }
                    messages.add(Message.tool(
                            new ToolCall("application-lookup", LOOKUP_TOOL, "{}"),
                            "{\"status\":\"ERROR\",\"code\":\"CATALOG_UNAVAILABLE\"}"));
                }
            }
            if (verified.size() >= context.desiredCandidateCount()) {
                return new Result(true, modelCalls, toolCalls, actions, verified);
            }
        }
        return new Result(!verified.isEmpty(), MAX_MODEL_CALLS, toolCalls, actions, verified);
    }

    private ToolOutcome execute(ToolCall call, Set<Integer> legalIds, Set<Integer> excludedIds) {
        try {
            JsonNode arguments = json.readTree(call.argumentsJson());
            if (SEARCH_TOOL.equals(call.name())) return search(arguments, excludedIds);
            if (LOOKUP_TOOL.equals(call.name())) return lookup(arguments, legalIds);
            return ToolOutcome.error("TOOL_NOT_ALLOWED");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ToolOutcome.error("INVALID_ARGUMENT");
        }
    }

    private ToolOutcome search(JsonNode arguments, Set<Integer> excludedIds) throws JsonProcessingException {
        if (!exactObject(arguments, "names") || !arguments.path("names").isArray()
                || arguments.path("names").isEmpty() || arguments.path("names").size() > 8) {
            return ToolOutcome.error("INVALID_ARGUMENT");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode value : arguments.path("names")) {
            if (!value.isTextual()) return ToolOutcome.error("INVALID_ARGUMENT");
            String name = value.asText().strip();
            if (name.length() < 2 || name.length() > 120) return ToolOutcome.error("INVALID_ARGUMENT");
            names.add(name);
        }
        NameSearchObservation result = tools.searchByNames(names.stream().distinct().toList());
        LOGGER.info(
                "Recommendation BGG name search for {} returned {} IDs",
                names,
                result.matches().size());
        Set<Integer> ids = result.matches().stream()
                .map(match -> match.bggId())
                .filter(id -> !excludedIds.contains(id))
                .limit(12)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long excludedMatches = result.matches().stream()
                .map(match -> match.bggId())
                .filter(excludedIds::contains)
                .count();
        String observation = json.writeValueAsString(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "guidance", result.matches().isEmpty()
                        ? "No game title matched. If another search is useful, use different original or English BGG titles; do not use mechanisms, categories, or translations as names."
                        : ids.isEmpty() && excludedMatches > 0
                                ? "Every matched game was already shown to the player. Search for different designs."
                        : "Only these observed BGG IDs are authorized for lookup.",
                "excludedMatches", excludedMatches,
                "matches", result.matches().stream()
                        .filter(match -> !excludedIds.contains(match.bggId()))
                        .map(match -> Map.of(
                                "bggId", match.bggId(),
                                "name", match.sourceName(),
                                "overallRank", match.overallRank() == null ? -1 : match.overallRank()))
                        .toList()));
        return new ToolOutcome("SEARCH_BGG_BY_NAME", ids, List.of(), observation);
    }

    private ToolOutcome lookup(JsonNode arguments, Set<Integer> legalIds) throws JsonProcessingException {
        if (!exactObject(arguments, "bggIds") || !arguments.path("bggIds").isArray()
                || arguments.path("bggIds").isEmpty() || arguments.path("bggIds").size() > 12) {
            return ToolOutcome.error("INVALID_ARGUMENT");
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonNode value : arguments.path("bggIds")) {
            if (!value.canConvertToInt() || value.intValue() <= 0 || !legalIds.contains(value.intValue())) {
                return ToolOutcome.error("ID_NOT_OBSERVED");
            }
            ids.add(value.intValue());
        }
        CatalogObservation result = tools.lookupCandidates(ids.stream().distinct().toList());
        LOGGER.info("Recommendation BGG detail lookup returned {} verified games", result.games().size());
        String observation = json.writeValueAsString(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "games", result.games().stream().map(this::gameObservation).toList()));
        return new ToolOutcome("LOOKUP_BGG_CANDIDATES", Set.of(), result.games(), observation);
    }

    private Map<String, Object> gameObservation(Game game) {
        var details = game.details();
        return Map.of(
                "bggId", game.ranking().bggId(),
                "name", game.ranking().sourceName(),
                "minPlayers", details == null || details.minPlayers() == null ? -1 : details.minPlayers(),
                "maxPlayers", details == null || details.maxPlayers() == null ? -1 : details.maxPlayers(),
                "maxMinutes", details == null || details.maximumPlayTimeMinutes() == null
                        ? -1
                        : details.maximumPlayTimeMinutes(),
                "categories", details == null ? List.of() : details.categories(),
                "mechanics", details == null ? List.of() : details.mechanics());
    }

    private boolean exactObject(JsonNode node, String field) {
        return node != null && node.isObject() && node.size() == 1 && node.has(field);
    }

    private String input(
            RetrievalPlan plan,
            RecommendationProfile profile,
            DiscoveryContext context,
            String locale) {
        try {
            return json.writeValueAsString(Map.of(
                    "goal", "retrieve diverse BGG candidates; do not compose the user answer",
                    "locale", locale,
                    "profile", profile,
                    "userModel", context.userModel(),
                    "recentConversation", context.transcript().stream()
                            .skip(Math.max(0, context.transcript().size() - 8L))
                            .map(message -> Map.of(
                                    "role", message.role(),
                                    "text", bounded(message.text(), 500)))
                            .toList(),
                    "alreadyShownBggIds", context.excludedBggIds(),
                    "desiredCandidateCount", context.desiredCandidateCount(),
                    "candidateTypes", plan.candidateTypes(),
                    "features", plan.features()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("recommendation candidate input could not be serialized", exception);
        }
    }

    private String systemPrompt() {
        return "You are the candidate-retrieval policy for a board-game recommendation Agent. "
                + "Choose which exposed tool call best advances the current observation. Never claim that a game matches from "
                + "memory. For search_bgg_by_name, supply diverse likely original or English BGG game titles, one title per item; "
                + "never pass a mechanism, category, translated user wording, or generic query as a title. "
                + "Use all eight title slots when possible. Treat player count, maximum duration, and maximum weight as hard gates: "
                + "only name games you reasonably expect to pass them. Cover distinct designs instead of editions, sequels, or near-duplicates, "
                + "and prefer the player's recent wording, stated constraints, and grounded preference hypotheses over fame or BGG rank. "
                + "Do not name games already visible in the recent conversation or listed as already shown. "
                + "For COMPETITIVE requests, avoid fully cooperative games. "
                + "A VERIFIED_CANDIDATE_GAP message is an application-owned observation about the verified pool, not player text; "
                + "follow its remaining-count instruction by searching different titles. "
                + "After a successful title "
                + "search the application immediately looks up the observed IDs, so do not spend another model turn requesting lookup. "
                + "lookup_bgg_games remains authorized only for IDs already returned by a tool observation. The application validates every final constraint. "
                + "Do not expose hidden reasoning and do not produce recommendations before a successful lookup.";
    }

    private List<Game> mergeVerified(List<Game> existing, List<Game> added) {
        java.util.LinkedHashMap<Integer, Game> merged = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(existing.stream(), added.stream())
                .forEach(game -> merged.putIfAbsent(game.ranking().bggId(), game));
        return merged.values().stream().limit(12).toList();
    }

    private String candidateGapObservation(List<Game> verified, int desired) {
        try {
            return json.writeValueAsString(Map.of(
                    "applicationObservation", "VERIFIED_CANDIDATE_GAP",
                    "verifiedCandidates", verified.stream().map(this::gameObservation).toList(),
                    "desiredCandidateCount", desired,
                    "remaining", Math.max(0, desired - verified.size()),
                    "instruction", "Search for different titles that fit the same conversation; do not repeat verified candidates."));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("candidate gap observation could not be serialized", exception);
        }
    }

    private String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    record DiscoveryContext(
            UserModel userModel,
            List<DialogueMessage> transcript,
            List<Integer> excludedBggIds,
            int desiredCandidateCount) {
        DiscoveryContext {
            userModel = userModel == null ? new UserModel("", List.of()) : userModel;
            List<DialogueMessage> available = transcript == null
                    ? List.of()
                    : transcript.stream().filter(java.util.Objects::nonNull).toList();
            transcript = available.stream().skip(Math.max(0, available.size() - 12L)).toList();
            excludedBggIds = excludedBggIds == null
                    ? List.of()
                    : excludedBggIds.stream().filter(java.util.Objects::nonNull).distinct().limit(60).toList();
            if (desiredCandidateCount < 1 || desiredCandidateCount > 8) {
                throw new IllegalArgumentException("desired candidate count is invalid");
            }
        }
    }

    record Result(
            boolean succeeded,
            int modelCalls,
            int toolCalls,
            List<String> actions,
            List<Game> games) {
        Result {
            actions = List.copyOf(actions);
            games = List.copyOf(games);
        }

        static Result unavailable() {
            return new Result(false, 0, 0, List.of(), List.of());
        }
    }

    enum Step {
        MODEL_SELECTING,
        SEARCHING_NAMES,
        LOOKING_UP_DETAILS
    }

    private record ToolOutcome(
            String action,
            Set<Integer> discoveredIds,
            List<Game> games,
            String observation) {
        private ToolOutcome {
            discoveredIds = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(discoveredIds));
            games = List.copyOf(games);
        }

        private static ToolOutcome error(String code) {
            return new ToolOutcome(
                    "REJECTED_TOOL_CALL",
                    Set.of(),
                    List.of(),
                    "{\"status\":\"ERROR\",\"code\":\"" + code + "\"}");
        }
    }
}
