package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicContextEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonAxis;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonCandidate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonCell;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocusKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.DiscoveryObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ResearchObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.RecommendationAgentState.CatalogSearch;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import com.rulepilot.recommendation.application.RecommendationAgentState.TitleFilter;
import com.rulepilot.recommendation.application.RecommendationAgentState.TitleMatch;
import com.rulepilot.recommendation.application.RecommendationAgentState.TitleScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the allow-listed recommendation actions over application-owned tools. */
final class RecommendationActions {

    static final int MODEL_PUBLISHER_DESCRIPTION_MAX_CODE_POINTS = 1_200;

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);
    /** One storage page; larger slates are read page-by-page until exhaustion or the run deadline. */
    private static final int CATALOG_PAGE_SIZE = BoardGameRecommendationCatalog.MAX_SEARCH_PAGE_SIZE;
    private static final int MAX_CATALOG_OFFSET = Integer.MAX_VALUE - CATALOG_PAGE_SIZE;
    private final BoardGameRecommendationTools tools;
    private final BoardGameRecommendationSelector selector;
    private final BoardGameRecommendationProperties properties;
    private final ObjectMapper actionJson;
    private final RecommendationEvidenceReview evidenceReview;
    private final RecommendationReActLoop runtime;

    RecommendationActions(
            BoardGameRecommendationTools tools,
            BoardGameRecommendationSelector selector,
            BoardGameRecommendationProperties properties,
            ObjectMapper json,
            RecommendationEvidenceReview evidenceReview,
            RecommendationReActLoop runtime) {
        this.tools = tools;
        this.selector = selector;
        this.properties = properties;
        this.actionJson = json.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.evidenceReview = evidenceReview;
        this.runtime = runtime;
    }

    ActionOutcome execute(
            ToolCall call,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        if (RECOMMEND_TOOL.equals(call.name())) {
            return ActionOutcome.publication(call.argumentsJson());
        }
        try {
            JsonNode arguments = actionJson.readTree(call.argumentsJson());
            return switch (call.name()) {
                case SEARCH_TOOL -> search(arguments, state, request, locale, progress);
                case DISCOVER_TOOL -> discover(arguments, state, request, locale, progress);
                case RESEARCH_TOOL -> research(arguments, state, locale, progress);
                case COMPARE_TOOL -> compare(arguments, state, locale);
                default -> rejectedContract(
                        state,
                        "TOOL_NOT_ALLOWED",
                        "Choose one action from the supplied action list.",
                        Map.of());
            };
        } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
            throw exception;
        } catch (JsonProcessingException | InvalidAction exception) {
            InvalidAction invalid = exception instanceof InvalidAction value ? value : null;
            String code = invalid == null ? "INVALID_JSON" : invalid.code;
            return rejectedContract(
                    state,
                    code,
                    invalid != null && invalid.guidance != null
                            ? invalid.guidance
                            : invalidActionGuidance(code),
                    invalid == null ? Map.of() : invalid.details);
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation action {} failed ({})", call.name(), exception.getClass().getSimpleName());
            return rejectedUnavailable(
                    state,
                    "ACTION_UNAVAILABLE",
                    "The action failed. Choose another useful action or respond transparently.");
        }
    }

    private ActionOutcome compare(
            JsonNode arguments,
            RecommendationAgentState state,
            String locale) {
        requireObject(
                arguments,
                Set.of("candidateBggIds", "subjects"));
        List<Integer> candidateIds = uniqueIds(arguments.path("candidateBggIds"), 2);
        List<Game> games = candidateIds.stream().map(state.verified::get).toList();
        if (games.stream().anyMatch(Objects::isNull)) {
            throw new InvalidAction("COMPARISON_CANDIDATE_NOT_VERIFIED");
        }
        if (!state.comparisonSubjectIds.containsAll(candidateIds)) {
            throw new InvalidAction("COMPARISON_CANDIDATE_NOT_IN_CONVERSATION");
        }
        List<String> subjects = playerFacingStrings(arguments.path("subjects"), 1);

        Map<String, CandidateObservation> availableEvidence = games.stream()
                .flatMap(game -> narrativeObservations(game, state.research).entrySet().stream())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Set<String> availableSubjects = availableEvidence.values().stream()
                .map(CandidateObservation::attribute)
                .collect(java.util.stream.Collectors.toSet());
        if (!availableSubjects.containsAll(subjects)) {
            throw new InvalidAction("COMPARISON_SUBJECT_INVALID");
        }
        List<ComparisonCandidate> candidates = games.stream()
                .map(game -> new ComparisonCandidate(
                        game,
                        selector.fitClaims(game, state.selectionProfile(), runtime.chinese(locale))))
                .toList();
        List<ComparisonAxis> axes = subjects.stream()
                .map(subject -> new ComparisonAxis(
                        subject,
                        games.stream()
                                .map(game -> new ComparisonCell(
                                        game.ranking().bggId(),
                                        narrativeObservations(game, state.research).values().stream()
                                                .filter(observation -> observation.attribute().equals(subject))
                                                .findFirst()
                                                .orElse(null)))
                                .toList()))
                .toList();
        state.comparison = new CandidateComparison(candidates, axes);
        state.actions.add("COMPARE_CANDIDATES");
        return ActionOutcome.observation(comparisonObservation(candidateIds, axes));
    }

    private String comparisonObservation(List<Integer> candidateIds, List<ComparisonAxis> axes) {
        List<Map<String, Object>> observedAxes = axes.stream().map(axis -> Map.<String, Object>of(
                "subject", axis.subject(),
                "cells", axis.cells().stream().map(cell -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("bggId", cell.bggId());
                    CandidateObservation observation = cell.observation();
                    if (observation == null) {
                        value.put("status", "UNKNOWN");
                    } else {
                        value.put("status", "OBSERVED");
                        value.put("evidenceId", observation.id());
                        value.put("kind", observation.kind().name());
                        value.put("value", observation.value());
                    }
                    return Map.copyOf(value);
                }).toList())).toList();
        return runtime.observation(Map.of(
                "status", "SUCCESS",
                "code", "COMPARISON_OBSERVED",
                "candidateBggIds", candidateIds,
                "axes", observedAxes,
                "guidance", "Observe this comparison, then decide again whether to recommend, read more, or answer naturally."));
    }

    private ActionOutcome search(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(
                arguments,
                Set.of("evidence", "includeTypes", "excludeTypes"));
        String evidenceId = text(arguments.path("evidence"));
        evidenceReview.requireCurrentTurnUserEvidence(evidenceId, request);
        String evidenceText = evidenceReview.evidenceText(evidenceId, request);
        int evidenceTurn = evidenceReview.evidenceTurn(evidenceId, request);
        List<BggGameType> includeTypes = gameTypes(arguments.path("includeTypes"));
        List<BggGameType> excludeTypes = gameTypes(arguments.path("excludeTypes"));
        Integer requestedCount = arguments.has("requestedCount")
                ? integer(
                        arguments.path("requestedCount"),
                        1,
                        Integer.MAX_VALUE,
                        "RESULT_COUNT_OUT_OF_RANGE")
                : null;
        List<String> mechanics = arguments.has("requiredMechanics")
                ? boundedTaxonomy(arguments.path("requiredMechanics"))
                : List.of();
        BoardGameRecommendationAgent.InteractionPreference requiredInteraction = arguments.has("requiredInteraction")
                ? enumValue(
                        BoardGameRecommendationAgent.InteractionPreference.class,
                        arguments.path("requiredInteraction"),
                        "INTERACTION_MODE_INVALID")
                : BoardGameRecommendationAgent.InteractionPreference.ANY;
        if (requiredInteraction == BoardGameRecommendationAgent.InteractionPreference.COMPETITIVE) {
            throw new InvalidAction(
                    "INTERACTION_MODE_INVALID",
                    "Competitive intensity is not a positive BGG taxonomy gate; use experienceQuestion when it matters.");
        }
        List<String> catalogMechanics = java.util.stream.Stream.concat(
                        mechanics.stream(), requiredInteractionMechanics(requiredInteraction).stream())
                .distinct()
                .toList();
        if (includeTypes.stream().anyMatch(excludeTypes::contains)) {
            throw new InvalidAction(
                    "SEARCH_TYPE_CONFLICT",
                    "The same BGG product type cannot be both included and excluded.");
        }
        TitleFilter title = arguments.has("requiredTitle")
                ? titleFilter(arguments.path("requiredTitle"))
                : null;
        Integer players = arguments.has("players")
                ? integer(arguments.path("players"), 1, 20, "PLAYERS_OUT_OF_RANGE")
                : null;
        Integer maxMinutes = arguments.has("maxMinutes")
                ? integer(arguments.path("maxMinutes"), 5, 1_440, "DURATION_OUT_OF_RANGE")
                : null;
        ConstraintRange<BigDecimal> complexity = arguments.has("complexity")
                ? complexityConstraint(arguments.path("complexity"), evidenceText, evidenceTurn)
                : null;
        String experienceQuestion = arguments.has("experienceQuestion")
                ? experienceQuestion(arguments.path("experienceQuestion"))
                : null;
        String descriptionQuery = arguments.has("descriptionQuery")
                ? descriptionQuery(arguments.path("descriptionQuery"))
                : null;
        if (title != null && descriptionQuery != null) {
            throw new InvalidAction(
                    "DESCRIPTION_QUERY_WITH_TITLE",
                    "For a named-title lookup, keep requiredTitle and omit descriptionQuery; the verified title's description remains available after lookup.");
        }
        RecommendationProfile selectionProfile = new RecommendationProfile(
                players == null
                        ? null
                        : ConstraintRange.hard(players, players, evidenceText, evidenceTurn),
                maxMinutes == null
                        ? null
                        : ConstraintRange.hard(null, maxMinutes, evidenceText, evidenceTurn),
                complexity,
                includeTypes.size() == 1 ? includeTypes.getFirst() : BggGameType.ALL,
                requiredInteraction);
        CatalogSearch search = new CatalogSearch(
                includeTypes,
                excludeTypes,
                catalogMechanics,
                title,
                List.of(),
                requestedCount,
                players,
                maxMinutes,
                complexity,
                evidenceId,
                selectionProfile);
        state.beginCatalogSearch(search);

        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG, null);
        Set<Integer> unavailable = new LinkedHashSet<>(state.excludedIds);
        if (title == null || title.match() != TitleMatch.EXACT) {
            unavailable.addAll(state.previouslyShownIds);
        }
        CatalogScan scan = scanCatalog(
                search,
                includeTypes,
                catalogMechanics,
                List.of(),
                title == null ? descriptionQuery : title.value(),
                title == null && descriptionQuery == null ? CatalogSort.RANK : CatalogSort.RELEVANCE,
                selectionProfile,
                unavailable,
                state);
        List<String> resolvedFamilies = title != null && title.scope() == TitleScope.SERIES
                ? canonicalGameFamilies(scan.candidates())
                : List.of();
        if (!resolvedFamilies.isEmpty()) {
            state.resolveCatalogFamilies(resolvedFamilies);
            search = state.activeSearch;
            progress.accept(
                    ProgressStage.SEARCHING_BGG_CATALOG,
                    new ProgressFocus(ProgressFocusKind.CATALOG_FAMILIES, resolvedFamilies));
            CatalogScan familyScan = scanCatalog(
                    search,
                    includeTypes,
                    catalogMechanics,
                    resolvedFamilies,
                    null,
                    CatalogSort.RANK,
                    selectionProfile,
                    unavailable,
                    state);
            if (!familyScan.candidates().isEmpty()) {
                scan = familyScan.withSourceCount(Math.max(scan.sourceCount(), familyScan.sourceCount()));
            }
        }

        CatalogObservation terminal = scan.terminal();
        List<Game> candidates = scan.candidates();
        state.completeCatalogSearch(scan.sourceCount(), candidates);
        Map<String, Object> appliedContract = new LinkedHashMap<>();
        appliedContract.put("evidence", evidenceId);
        appliedContract.put("includeTypes", includeTypes);
        appliedContract.put("excludeTypes", excludeTypes);
        if (requestedCount != null) appliedContract.put("requestedCount", requestedCount);
        if (!mechanics.isEmpty()) appliedContract.put("requiredMechanics", mechanics);
        appliedContract.put("requiredInteraction", requiredInteraction);
        if (title != null) {
            appliedContract.put(
                    "requiredTitle",
                    Map.of("match", title.match(), "scope", title.scope(), "value", title.value()));
            if (!resolvedFamilies.isEmpty()) appliedContract.put("resolvedFamilies", resolvedFamilies);
        }
        if (players != null) appliedContract.put("players", players);
        if (maxMinutes != null) appliedContract.put("maxMinutes", maxMinutes);
        if (complexity != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (complexity.minimum() != null) range.put("minimum", complexity.minimum());
            if (complexity.maximum() != null) range.put("maximum", complexity.maximum());
            appliedContract.put("complexity", range);
        }
        if (experienceQuestion != null) appliedContract.put("experienceQuestion", experienceQuestion);
        if (descriptionQuery != null) appliedContract.put("descriptionQuery", descriptionQuery);
        List<Integer> verifiedIds = candidates.stream()
                .map(game -> game.ranking().bggId())
                .toList();
        Map<String, Object> experienceResearch = null;
        if (experienceQuestion != null && !verifiedIds.isEmpty()) {
            List<Integer> researchIds = verifiedIds.stream()
                    .limit(properties.resultCount())
                    .toList();
            experienceResearch = state.webResearchAvailable
                    ? researchCandidates(researchIds, experienceQuestion, state, locale, progress)
                    : Map.of(
                            "status", ToolStatus.UNAVAILABLE.name(),
                            "code", "RESEARCH_NOT_AVAILABLE",
                            "guidance",
                                    "No attributed experience read is available. Use verified structured facts and state any remaining experience uncertainty; do not invent it.",
                            "researchedBggIds", List.of());
        }
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put(
                "status",
                scan.completedPage() ? terminal.succeeded() ? "SUCCESS" : "PARTIAL" : "ERROR");
        observation.put("code", terminal.code());
        observation.put("appliedSearchContract", appliedContract);
        observation.put("verifiedCandidateBggIds", verifiedIds);
        if (experienceResearch != null) observation.put("experienceResearch", experienceResearch);
        observation.put("canTerminateNow", !verifiedIds.isEmpty());
        observation.put(
                "guidance",
                verifiedIds.isEmpty()
                        ? "No verified candidate matched this complete current-turn search contract. Finish transparently without naming an unverified game; do not search again in this turn."
                        : experienceResearch == null
                                ? "These candidate IDs are verified. Decide whether the available observations are enough for recommend_games or one genuinely distinct available read would materially improve the answer."
                                : "The catalog and the one bounded experience read have settled. Finish with recommend_games from the returned evidence, and state any experience dimension that remains unknown instead of opening another research stage.");
        if (!verifiedIds.isEmpty()) {
            observation.put(
                    "terminalAction",
                    Map.of(
                            "name", RECOMMEND_TOOL,
                            "verifiedCandidateBggIds", verifiedIds));
        }
        return preparePublication(runtime.observation(observation), state, candidates);
    }

    private CatalogScan scanCatalog(
            CatalogSearch search,
            List<BggGameType> includeTypes,
            List<String> catalogMechanics,
            List<String> families,
            String textQuery,
            CatalogSort sort,
            RecommendationProfile selectionProfile,
            Set<Integer> unavailable,
            RecommendationAgentState state) {
        LinkedHashMap<Integer, Game> eligible = new LinkedHashMap<>();
        Set<Integer> previousPageIds = Set.of();
        CatalogObservation lastPage = null;
        int sourceCount = 0;
        int offset = 0;
        int pagesScanned = 0;
        long pageBudget = 1;
        boolean completedPage = false;
        int candidateWindowSize = properties.modelCandidateLimit();
        while (eligible.size() < candidateWindowSize) {
            state.recordCatalogCall();
            int currentOffset = offset;
            CatalogObservation page;
            try {
                page = runtime.withinDeadline(
                        state,
                        () -> tools.searchCatalog(
                                includeTypes,
                                List.of(),
                                catalogMechanics,
                                List.of(),
                                List.of(),
                                families,
                                null,
                                null,
                                null,
                                null,
                                textQuery,
                                sort,
                                CATALOG_PAGE_SIZE,
                                currentOffset));
            } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
                if (eligible.isEmpty()) throw exception;
                state.recordAction("CATALOG_SCAN_STOPPED:TIME_BUDGET");
                break;
            }
            lastPage = page;
            pagesScanned++;
            sourceCount = Math.max(sourceCount, page.sourceCount());
            pageBudget = catalogPageBudget(sourceCount, 0, CATALOG_PAGE_SIZE);
            if (!page.succeeded()) break;
            completedPage = true;
            LinkedHashSet<Integer> pageIds = new LinkedHashSet<>();
            for (Game game : page.games()) {
                int bggId = game.ranking().bggId();
                pageIds.add(bggId);
                if (unavailable.contains(bggId)
                        || !search.matches(game)
                        || !selector.eligible(game, selectionProfile)) {
                    continue;
                }
                eligible.putIfAbsent(bggId, game);
                if (eligible.size() == candidateWindowSize) break;
            }
            if (eligible.size() >= candidateWindowSize
                    || page.pageExhausted()
                    || page.games().isEmpty()
                    || pageIds.equals(previousPageIds)
                    || pagesScanned >= pageBudget) {
                break;
            }
            previousPageIds = Set.copyOf(pageIds);
            if ((long) offset + CATALOG_PAGE_SIZE > MAX_CATALOG_OFFSET) break;
            offset += CATALOG_PAGE_SIZE;
        }
        return new CatalogScan(
                Objects.requireNonNull(lastPage, "catalog search must attempt one page"),
                sourceCount,
                List.copyOf(eligible.values()),
                completedPage);
    }

    private List<String> canonicalGameFamilies(List<Game> games) {
        if (games.isEmpty()) return List.of();
        LinkedHashSet<String> common = new LinkedHashSet<>(gameFamilies(games.getFirst()));
        for (Game game : games.subList(1, games.size())) common.retainAll(gameFamilies(game));
        return List.copyOf(common);
    }

    private List<String> gameFamilies(Game game) {
        return game.details().families().stream()
                .filter(family -> family.startsWith("Game: "))
                .distinct()
                .toList();
    }

    private record CatalogScan(
            CatalogObservation terminal, int sourceCount, List<Game> candidates, boolean completedPage) {
        CatalogScan withSourceCount(int count) {
            return new CatalogScan(terminal, count, candidates, completedPage);
        }
    }

    private List<String> requiredInteractionMechanics(
            BoardGameRecommendationAgent.InteractionPreference interaction) {
        return switch (interaction) {
            case COOPERATIVE -> List.of("Cooperative Game");
            case TEAM -> List.of("Team-Based Game");
            case ANY -> List.of();
            case COMPETITIVE -> throw new IllegalArgumentException("competitive interaction is not a positive gate");
        };
    }

    private ConstraintRange<BigDecimal> complexityConstraint(
            JsonNode node,
            String evidenceText,
            int evidenceTurn) {
        requireObject(node, Set.of());
        if (!node.has("minimum") && !node.has("maximum")) {
            throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
        }
        BigDecimal minimum = node.has("minimum")
                ? decimal(node.path("minimum"), BigDecimal.ZERO, new BigDecimal("5"), "WEIGHT_OUT_OF_RANGE")
                : null;
        BigDecimal maximum = node.has("maximum")
                ? decimal(node.path("maximum"), BigDecimal.ZERO, new BigDecimal("5"), "WEIGHT_OUT_OF_RANGE")
                : null;
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InvalidAction("WEIGHT_OUT_OF_RANGE");
        }
        return ConstraintRange.hard(minimum, maximum, evidenceText, evidenceTurn);
    }

    private TitleFilter titleFilter(JsonNode node) {
        requireObject(node, Set.of("match", "scope", "value"));
        TitleMatch match = enumValue(TitleMatch.class, node.path("match"), "TITLE_MATCH_INVALID");
        TitleScope scope = enumValue(TitleScope.class, node.path("scope"), "TITLE_SCOPE_INVALID");
        if (scope == TitleScope.SERIES && match != TitleMatch.CONTAINS) {
            throw new InvalidAction("TITLE_SCOPE_INVALID", "A named series must use CONTAINS title matching.");
        }
        return new TitleFilter(match, scope, text(node.path("value")));
    }

    private List<BggGameType> gameTypes(JsonNode node) {
        List<BggGameType> types = enumValues(
                BggGameType.class,
                node,
                0,
                "GAME_TYPES_INVALID");
        if (types.size() != node.size() || types.contains(BggGameType.ALL)) {
            throw new InvalidAction("GAME_TYPES_INVALID");
        }
        return types;
    }

    private long catalogPageBudget(int sourceCount, int offset, int pageSize) {
        long remainingRows = Math.max(0L, (long) sourceCount - offset);
        return remainingRows == 0 ? 1 : (remainingRows + pageSize - 1L) / pageSize;
    }

    private ActionOutcome discover(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(arguments, Set.of("evidence", "subject"));
        String evidenceId = text(arguments.path("evidence"));
        evidenceReview.requireCurrentTurnUserEvidence(
                evidenceId,
                request,
                "DISCOVERY_EVIDENCE_NOT_CURRENT");
        String query = evidenceReview.evidenceText(evidenceId, request);
        String subject = text(arguments.path("subject"));
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES, null);
        state.publicContextEvidence.clear();
        state.publicContextSources = List.of();
        state.finalResponsePublicEvidenceIds.clear();
        state.webResearchCalls++;
        BoardGameRecommendationWebResearch.DiscoveryRequest discoveryRequest =
                new BoardGameRecommendationWebResearch.DiscoveryRequest(
                        query,
                        subject,
                        List.of(),
                        locale,
                        DiscoveryGoal.IDENTITY_ONLY);
        DiscoveryObservation result = runtime.withinDeadline(
                state,
                () -> tools.discoverCandidates(discoveryRequest));
        CandidateDiscovery discovery = result.result().orElse(null);
        if (discovery == null) {
            boolean available = result.status() != ToolStatus.ERROR
                    && result.status() != ToolStatus.UNAVAILABLE;
            state.actions.add("DISCOVER_PUBLIC_RELATIONSHIP");
            if (!available) state.disableWebResearch(result.code());
            return ActionOutcome.observation(runtime.observation(Map.of(
                    "status", result.status().name(),
                    "code", result.code(),
                    "guidance", available
                            ? "No attributed relationship was found. Finish transparently or choose another genuinely distinct capability."
                            : "Public relationship discovery is unavailable for this run.")));
        }
        state.publicContextSources = discovery.sources();
        state.recordSourceCount(discovery.sources().size());
        List<PublicContextEvidence> publicContext = recordPublicContext(discovery, state);
        state.actions.add("DISCOVER_PUBLIC_RELATIONSHIP");
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", publicContext.isEmpty() ? "PARTIAL" : "SUCCESS",
                "guidance", publicContext.isEmpty()
                        ? "Sources returned no attributable relationship fact."
                        : "Use only these attributed relationship facts, or finish naturally.",
                "publicContextEvidence", publicContext.stream()
                        .map(this::publicContextObservation)
                        .toList())));
    }

    private List<PublicContextEvidence> recordPublicContext(
            CandidateDiscovery discovery,
            RecommendationAgentState state) {
        Set<Integer> sourceIndexes = discovery.sources().stream()
                .map(Source::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        LinkedHashMap<String, PublicContextEvidence> verified = new LinkedHashMap<>();
        discovery.publicContext().stream()
                .filter(Objects::nonNull)
                .filter(evidence -> sourceIndexes.containsAll(evidence.sourceIndexes()))
                .forEach(evidence -> verified.putIfAbsent(evidence.id(), evidence));
        state.publicContextEvidence.putAll(verified);
        state.publicContextSources = discovery.sources();
        if (!verified.isEmpty()) state.actions.add("DISCOVERY_PUBLIC_CONTEXT_VERIFIED");
        return List.copyOf(verified.values());
    }

    Map<String, Object> publicContextObservation(PublicContextEvidence evidence) {
        return Map.of(
                "id", evidence.id(),
                "subjectKind", evidence.subjectKind().name(),
                "subject", evidence.subject(),
                "relation", evidence.relation(),
                "object", evidence.object(),
                "statement", evidence.statement(),
                "sourceIndexes", evidence.sourceIndexes());
    }

    private ActionOutcome research(
            JsonNode arguments,
            RecommendationAgentState state,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(arguments, Set.of("bggIds", "question"));
        List<Integer> ids = ids(arguments.path("bggIds"), 1);
        String question = text(arguments.path("question"));
        if (ids.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("GAME_NOT_VERIFIED");
        }
        return ActionOutcome.observation(runtime.observation(
                researchCandidates(ids, question, state, locale, progress)));
    }

    private Map<String, Object> researchCandidates(
            List<Integer> ids,
            String question,
            RecommendationAgentState state,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        progress.accept(
                ProgressStage.RESEARCHING_GAME_FIT,
                new ProgressFocus(
                        ProgressFocusKind.RESEARCH_GAMES,
                        ids.stream()
                                .map(state.verified::get)
                                .map(game -> game.ranking().sourceName())
                                .toList()));
        ToolStatus status = ToolStatus.SUCCESS;
        String code = "";
        boolean completedBatch = false;
        boolean webResearchAvailable = true;
        LinkedHashSet<Integer> researchedIds = new LinkedHashSet<>();
        int researchPageSize = properties.modelCandidateLimit();
        int attemptedPages = 0;
        int completedPages = 0;
        long pageBudget = pageBudget(ids.size(), researchPageSize);
        for (int start = 0; start < ids.size(); ) {
            int end = (int) Math.min(
                    (long) ids.size(),
                    (long) start + researchPageSize);
            List<BoardGameRecommendationWebResearch.Candidate> candidates = ids.subList(start, end).stream()
                    .map(state.verified::get)
                    .map(selector::researchCandidate)
                    .toList();
            state.webResearchCalls++;
            attemptedPages++;
            ResearchObservation result;
            try {
                result = runtime.withinDeadline(
                        state,
                        () -> tools.researchGameFit(candidates, locale, question));
            } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
                if (!completedBatch) throw exception;
                status = researchedIds.isEmpty() ? ToolStatus.ERROR : ToolStatus.PARTIAL;
                code = "RUN_DEADLINE_EXCEEDED";
                state.actions.add("RESEARCH_STOPPED:TIME_BUDGET");
                break;
            }
            completedBatch = true;
            completedPages++;
            Research added = result.result().orElse(Research.empty());
            added.games().stream().map(GameResearch::bggId).forEach(researchedIds::add);
            if (!added.games().isEmpty() || !added.sources().isEmpty()) {
                state.comparison = null;
            }
            state.research = mergeResearch(state.research, added);
            boolean batchAvailable = result.status() != ToolStatus.ERROR
                    && result.status() != ToolStatus.UNAVAILABLE;
            if (!batchAvailable) {
                webResearchAvailable = false;
                status = researchedIds.isEmpty() ? result.status() : ToolStatus.PARTIAL;
                code = result.code();
                break;
            }
            if (result.status() != ToolStatus.SUCCESS) {
                status = ToolStatus.PARTIAL;
                code = result.code();
            }
            start = end;
        }
        Map<String, Object> observation = Map.of(
                "status", status.name(),
                "code", code,
                "guidance", researchedIds.isEmpty()
                        ? webResearchAvailable
                                ? "No attributed experience evidence was returned. Do not invent it."
                                : "Public web research is unavailable for the rest of this run. Use verified BGG facts or finish transparently; do not retry web research."
                        : "Use these attributed observations as reported experience, distinct from BGG facts.",
                "resourceBoundary", Map.of(
                        "pageSize", researchPageSize,
                        "pageBudget", pageBudget,
                        "pagesAttempted", attemptedPages,
                        "pagesCompleted", completedPages),
                "researchedBggIds", List.copyOf(researchedIds));
        state.actions.add("RESEARCH_GAME_FIT");
        if (!webResearchAvailable) state.disableWebResearch(code);
        return observation;
    }

    Map<String, CandidateObservation> narrativeObservations(Game game, Research research) {
        LinkedHashMap<String, CandidateObservation> observations = selector.observations(game).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> observation,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        observations.putAll(researchObservations(game.ranking().bggId(), research));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observations));
    }

    Map<String, CandidateObservation> researchObservations(int bggId, Research research) {
        LinkedHashMap<String, CandidateObservation> observations = new LinkedHashMap<>();
        if (research == null) return Map.of();
        research.games().stream()
                .filter(game -> game.bggId() == bggId)
                .findFirst()
                .ifPresent(game -> {
                    int ordinal = 0;
                    for (Observation observation : game.observations()) {
                        if (observation == null
                                || observation.text() == null
                                || observation.text().isBlank()
                                || observation.sourceIndexes().isEmpty()) {
                            continue;
                        }
                        String id = "R" + bggId + ":" + (++ordinal);
                        observations.put(id, new CandidateObservation(
                                id,
                                bggId,
                                CandidateObservation.Kind.ATTRIBUTED_REPORT,
                                "reportedExperience",
                                observation.text(),
                                observation.sourceIndexes()));
                    }
                });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observations));
    }

    private ActionOutcome rejectedContract(
            RecommendationAgentState state,
            String code,
            String guidance,
            Map<String, ?> details) {
        state.recordAction("REJECTED_ACTION:" + code);
        return ActionOutcome.rejectedContract(runtime.error(code, guidance, details), code);
    }

    private ActionOutcome rejectedUnavailable(
            RecommendationAgentState state,
            String code,
            String guidance) {
        state.recordAction("REJECTED_ACTION:" + code);
        return ActionOutcome.rejectedUnavailable(runtime.error(code, guidance), code);
    }

    private String invalidActionGuidance(String code) {
        return switch (code) {
            case "INVALID_JSON" ->
                "Return a fresh action with valid JSON arguments and correctly escaped strings.";
            case "SEARCH_EVIDENCE_NOT_CURRENT", "DISCOVERY_EVIDENCE_NOT_CURRENT" ->
                "Use the evidence ID attached to the current user turn.";
            case "SEARCH_TYPE_CONFLICT" ->
                "Remove every BGG product type that appears in both includeTypes and excludeTypes.";
            case "RECOMMENDATION_EVIDENCE_REQUIRED", "RECOMMENDATION_EVIDENCE_NOT_GROUNDED" ->
                "Cite observation IDs owned by that same verified candidate.";
            case "RECOMMENDATION_REPLY_INVALID" ->
                "Submit the complete locale-matched playerReply and whyFit values.";
            default -> "Correct the typed arguments using the current action schema and observations.";
        };
    }

    private ConversationResponse response(
            Outcome outcome,
            String message,
            RecommendationAgentState state,
            String locale,
            Clarification clarification,
            List<RecommendedGame> games) {
        return response(outcome, message, state, locale, clarification, games, null);
    }

    private ConversationResponse response(
            Outcome outcome,
            String message,
            RecommendationAgentState state,
            String locale,
            Clarification clarification,
            List<RecommendedGame> games,
            RecommendationShortfall shortfall) {
        ConversationResponse response = new ConversationResponse(
                outcome,
                DecisionMode.MODEL_ASSISTED,
                message,
                state.profile,
                clarification,
                state.sourceCount,
                state.verified.size(),
                evidenceReview.userModelView(state, locale),
                runtime.responseSources(state, games),
                new HarnessTrace(
                        state.modelCalls,
                        state.catalogCalls,
                        state.webResearchCalls,
                        false,
                        state.actions,
                        state.elapsedMs(),
                        state.modelCallElapsedMs),
                games,
                state.comparison,
                shortfall);
        return response;
    }

    Map<String, Object> gameObservation(Game game, boolean includePublisherDescription) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bggId", game.ranking().bggId());
        value.put("name", game.ranking().sourceName());
        putIfKnown(value, "year", game.ranking().publicationYear());
        if (game.details() == null) return value;
        var details = game.details();
        putIfText(value, "officialChineseName", details.officialChineseName());
        List<CandidateObservation> gameObservations = selector.observations(game).stream()
                .filter(observation -> includePublisherDescription
                        || !"publisherDescription".equals(observation.attribute()))
                .toList();
        Map<String, List<String>> observations = gameObservations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> List.of(
                                observationKindCode(observation),
                                modelObservationValue(observation)),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        value.put("observations", observations);
        return value;
    }

    private String modelObservationValue(CandidateObservation observation) {
        String value = observation.value();
        if (!"publisherDescription".equals(observation.attribute())
                || value.codePointCount(0, value.length()) <= MODEL_PUBLISHER_DESCRIPTION_MAX_CODE_POINTS) {
            return value;
        }
        int retainedCodePoints = MODEL_PUBLISHER_DESCRIPTION_MAX_CODE_POINTS - 1;
        int end = value.offsetByCodePoints(0, retainedCodePoints);
        return value.substring(0, end).stripTrailing() + "…";
    }

    private String observationKindCode(CandidateObservation observation) {
        return switch (observation.kind()) {
            case STRUCTURED_METADATA -> "M";
            case TAXONOMY -> "T";
            case ATTRIBUTED_REPORT -> "A";
            case RULEBOOK_FACT -> "R";
        };
    }

    private void putIfKnown(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    List<Map<String, Object>> sourceObservations(List<Source> sources) {
        return sources.stream()
                .map(source -> Map.<String, Object>of(
                        "index", source.index(),
                        "title", source.title() == null ? "" : source.title(),
                        "domain", source.domain() == null ? "" : source.domain()))
                .toList();
    }

    private Research mergeResearch(Research current, Research added) {
        if (added == null || added.sources().isEmpty()) return current;
        List<Source> sources = new ArrayList<>(current.sources());
        Map<Integer, Integer> remapped = new LinkedHashMap<>();
        for (Source source : added.sources()) {
            int index = sources.size() + 1;
            sources.add(new Source(index, source.title(), source.url(), source.domain()));
            remapped.put(source.index(), index);
        }
        Map<Integer, List<Observation>> observations = new LinkedHashMap<>();
        current.games().forEach(game -> observations
                .computeIfAbsent(game.bggId(), ignored -> new ArrayList<>())
                .addAll(game.observations()));
        added.games().forEach(game -> game.observations().stream()
                .filter(observation -> remapped.keySet().containsAll(observation.sourceIndexes()))
                .map(observation -> new Observation(
                        observation.text(),
                        observation.sourceIndexes().stream().map(remapped::get).toList()))
                .forEach(observation -> observations
                        .computeIfAbsent(game.bggId(), ignored -> new ArrayList<>())
                        .add(observation)));
        List<GameResearch> games = observations.entrySet().stream()
                .map(entry -> new GameResearch(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new Research(games, List.copyOf(sources));
    }

    private long pageBudget(int itemCount, int pageSize) {
        return ((long) itemCount + pageSize - 1L) / pageSize;
    }

    private void requireObject(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject()) throw new InvalidAction("ARGUMENT_OBJECT_REQUIRED");
        if (required.stream().anyMatch(field -> !node.has(field))) {
            throw new InvalidAction("REQUIRED_ARGUMENT_MISSING");
        }
    }

    private String text(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText().strip();
        if (value.isEmpty()) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private String experienceQuestion(JsonNode node) {
        String value = text(node);
        if (value.codePointCount(0, value.length()) > 500) {
            throw new InvalidAction("EXPERIENCE_QUESTION_INVALID");
        }
        return value;
    }

    private String descriptionQuery(JsonNode node) {
        String value = text(node);
        if (value.codePointCount(0, value.length()) > 200) {
            throw new InvalidAction("DESCRIPTION_QUERY_INVALID");
        }
        return value;
    }

    private String playerFacingText(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText();
        if (value.isBlank()) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private String playerReply(JsonNode arguments) {
        return playerFacingText(arguments.path("playerReply"));
    }

    private List<String> playerFacingStrings(JsonNode node, int minimumItems) {
        if (!node.isArray() || node.size() < minimumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(playerFacingText(value));
        List<String> distinct = values.stream().distinct().toList();
        if (distinct.size() < minimumItems) throw new InvalidAction("STRING_LIST_INVALID");
        return distinct;
    }

    private List<String> boundedTaxonomy(JsonNode node) {
        List<String> values = strings(node, 0);
        if (values.size() > 8
                || values.stream().anyMatch(value -> value.codePointCount(0, value.length()) > 80)) {
            throw new InvalidAction("CATALOG_TAXONOMY_INVALID");
        }
        return values;
    }

    private List<String> strings(JsonNode node, int minimumItems) {
        if (!node.isArray() || node.size() < minimumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(text(value));
        List<String> distinct = values.stream().distinct().toList();
        if (distinct.size() < minimumItems) throw new InvalidAction("STRING_LIST_INVALID");
        return distinct;
    }

    private List<Integer> ids(JsonNode node, int minimumItems) {
        if (!node.isArray() || node.size() < minimumItems) {
            throw new InvalidAction("ID_LIST_INVALID");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode value : node) values.add(integer(value, 1, Integer.MAX_VALUE, "BGG_ID_INVALID"));
        List<Integer> distinct = values.stream().distinct().toList();
        if (distinct.size() < minimumItems) throw new InvalidAction("ID_LIST_INVALID");
        return distinct;
    }

    private List<Integer> uniqueIds(JsonNode node, int minimumItems) {
        List<Integer> values = ids(node, minimumItems);
        if (values.size() != node.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return values;
    }

    private int integer(JsonNode node, int minimum, int maximum, String code) {
        if (!node.isNumber()
                || !node.canConvertToInt()
                || node.decimalValue().stripTrailingZeros().scale() > 0) {
            throw InvalidAction.integerRange(code, minimum, maximum);
        }
        int value = node.intValue();
        if (value < minimum || value > maximum) {
            throw InvalidAction.integerRange(code, minimum, maximum);
        }
        return value;
    }

    private BigDecimal decimal(JsonNode node, BigDecimal minimum, BigDecimal maximum, String code) {
        if (!node.isNumber()) throw new InvalidAction(code);
        BigDecimal value = node.decimalValue();
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new InvalidAction(code);
        }
        return value;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String code) {
        if (!node.isTextual()) throw new InvalidAction(code);
        try {
            return Enum.valueOf(type, node.asText());
        } catch (IllegalArgumentException exception) {
            throw new InvalidAction(code);
        }
    }

    private <E extends Enum<E>> List<E> enumValues(
            Class<E> type, JsonNode node, int minimumItems, String code) {
        if (!node.isArray() || node.size() < minimumItems) {
            throw new InvalidAction(code);
        }
        List<E> values = new ArrayList<>();
        for (JsonNode value : node) values.add(enumValue(type, value, code));
        List<E> distinct = values.stream().distinct().toList();
        if (distinct.size() < minimumItems) throw new InvalidAction(code);
        return distinct;
    }

    private ActionOutcome preparePublication(
            String observation,
            RecommendationAgentState state,
            List<Game> candidates) {
        CatalogSearch search = Objects.requireNonNull(state.activeSearch, "active catalog search is required");
        boolean exactTitle = search.title() != null && search.title().match() == TitleMatch.EXACT;
        List<Integer> candidateIds = candidates.stream()
                .filter(search::matches)
                .filter(game -> selector.eligible(game, search.selectionProfile()))
                .map(game -> game.ranking().bggId())
                .filter(id -> !state.excludedIds.contains(id))
                .filter(id -> exactTitle || !state.previouslyShownIds.contains(id))
                .distinct()
                .filter(id -> {
                    Game game = state.verified.get(id);
                    return game != null && !narrativeObservations(game, state.research).isEmpty();
                })
                .toList();
        if (!candidateIds.isEmpty()) {
            state.pendingPublicationSeed = new PublicationSeed(candidateIds);
            state.actions.add("PREPARE_RECOMMENDATION");
        }
        return ActionOutcome.observation(observation);
    }

    private List<String> optionalStrings(JsonNode arguments, String field) {
        return arguments.has(field)
                ? strings(arguments.path(field), 0)
                : List.of();
    }

    record ActionOutcome(
            ConversationResponse response,
            String observation,
            boolean rejected,
            boolean settledRead,
            String publicationArgumentsJson,
            RejectionKind rejectionKind,
            String rejectionCode) {
        static ActionOutcome terminal(ConversationResponse response) {
            return new ActionOutcome(response, "", false, false, null, RejectionKind.NONE, null);
        }

        static ActionOutcome terminalRead(ConversationResponse response) {
            return new ActionOutcome(response, "", false, true, null, RejectionKind.NONE, null);
        }

        static ActionOutcome observation(String observation) {
            return new ActionOutcome(null, observation, false, true, null, RejectionKind.NONE, null);
        }

        static ActionOutcome publication(String argumentsJson) {
            return new ActionOutcome(
                    null,
                    "",
                    false,
                    false,
                    argumentsJson,
                    RejectionKind.NONE,
                    null);
        }

        static ActionOutcome rejectedContract(String observation, String code) {
            return new ActionOutcome(
                    null,
                    observation,
                    true,
                    false,
                    null,
                    RejectionKind.DETERMINISTIC_CONTRACT,
                    code);
        }

        static ActionOutcome rejectedUnavailable(String observation, String code) {
            return new ActionOutcome(
                    null,
                    observation,
                    true,
                    false,
                    null,
                    RejectionKind.TRANSIENT_UNAVAILABLE,
                    code);
        }

        boolean deterministicContractRejection() {
            return rejectionKind == RejectionKind.DETERMINISTIC_CONTRACT;
        }
    }

    enum RejectionKind {
        NONE,
        DETERMINISTIC_CONTRACT,
        TRANSIENT_UNAVAILABLE
    }

    static final class InvalidAction extends RuntimeException {
        final String code;
        final String guidance;
        final Map<String, ?> details;

        InvalidAction(String code) {
            this(code, null, Map.of());
        }

        InvalidAction(String code, String guidance) {
            this(code, guidance, Map.of());
        }

        InvalidAction(String code, String guidance, Map<String, ?> details) {
            super(code);
            this.code = code;
            this.guidance = guidance;
            this.details = details == null ? Map.of() : Map.copyOf(details);
        }

        static InvalidAction integerRange(String code, int minimum, int maximum) {
            return new InvalidAction(
                    code,
                    "Use a JSON integer within allowedRange, matching the current action schema.",
                    Map.of(
                            "allowedRange",
                            Map.of(
                                    "type", "integer",
                                    "minimum", minimum,
                                    "maximum", maximum)));
        }
    }
}
