package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.SEARCH_TOOL;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_VERIFIED_GAMES;
import static com.rulepilot.recommendation.application.RecommendationReActLoop.MAX_REFERENCE_RESOLUTION_ATTEMPTS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Clarification;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ClarificationOption;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonAxis;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonCandidate;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ComparisonCell;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationResponse;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DecisionMode;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.HarnessTrace;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.Outcome;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.PreferenceField;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationReplyPart;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ReplyPartRole;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.DiscoveryObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ResearchObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.TitleHypothesis;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the eleven allow-listed recommendation actions over application-owned tools. */
final class RecommendationActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);
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
            Consumer<ProgressStage> progress) {
        try {
            JsonNode arguments = actionJson.readTree(call.argumentsJson());
            return switch (call.name()) {
                case REPLY_TOOL -> reply(arguments, state, request, locale);
                case ASK_TOOL -> ask(arguments, state, request, locale);
                case RESOLVE_TOOL -> resolve(arguments, state, request, locale, progress);
                case SEARCH_TOOL -> search(arguments, state, request, progress);
                case BROWSE_TOOL -> browse(arguments, state, request, progress);
                case DISCOVER_TOOL -> discover(arguments, state, request, locale, progress);
                case LOOKUP_TOOL -> lookup(arguments, state, progress);
                case RESEARCH_TOOL -> research(arguments, state, locale, progress);
                case COMPARE_TOOL -> compare(arguments, state, request, locale);
                case NO_MATCH_TOOL -> noMatch(arguments, state, locale);
                case RECOMMEND_TOOL -> recommend(arguments, state, request, locale, progress);
                default -> rejected(state, "TOOL_NOT_ALLOWED", "Choose one action from the supplied action list.");
            };
        } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
            state.actions.add("RUN_DEADLINE_EXCEEDED");
            return ActionOutcome.terminal(runtime.unavailable(state, locale, "RUN_DEADLINE_EXCEEDED"));
        } catch (JsonProcessingException | InvalidAction exception) {
            InvalidAction invalid = exception instanceof InvalidAction value ? value : null;
            String code = invalid == null ? "INVALID_JSON" : invalid.code;
            if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
            return rejected(
                    state,
                    code,
                    invalid != null && invalid.guidance != null
                            ? invalid.guidance
                            : invalidActionGuidance(code));
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation action {} failed ({})", call.name(), exception.getClass().getSimpleName());
            if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
            return rejected(state, "ACTION_UNAVAILABLE", "The action failed. Choose another useful action or respond transparently.");
        }
    }

    Optional<ConversationResponse> publishLocalExplicitTarget(
            String title,
            RecommendationAgentState state,
            String locale) {
        state.catalogCalls++;
        state.actionCalls++;
        state.referenceResolutionAttempts++;
        state.actions.add("RESOLVE_EXPLICIT_TARGET_LOCALLY");
        ReferenceObservation result;
        try {
            result = tools.resolveLocalReferenceTitle(title);
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Recommendation local explicit-target lookup was skipped ({})",
                    failure.getClass().getSimpleName());
            return Optional.empty();
        }
        if (!result.resolved()) return Optional.empty();

        Game selected = result.games().getFirst();
        state.observeCandidate(selected.ranking().bggId(), selected.ranking().sourceName());
        state.addVerified(selected);
        state.namedGamePurpose = NamedGamePurpose.TARGET_GAME;
        state.assignNamedGameRole(selected.ranking().bggId(), NamedGamePurpose.TARGET_GAME);
        state.actions.add("RECOMMEND_GAMES");

        List<RecommendedGame> games = selector.present(
                List.of(selected),
                state.profile,
                List.of(),
                runtime.chinese(locale),
                state.research);
        String name = visibleName(selected, runtime.chinese(locale));
        String playerReply = runtime.chinese(locale)
                ? "已找到《" + name + "》。可以直接继续查看规则书、生成讲解或进入答疑。"
                : "I found “" + name + "”. You can continue directly to its rulebook, guide, or questions.";
        ConversationResponse response = response(
                Outcome.RECOMMENDATIONS,
                playerReply,
                state,
                locale,
                null,
                games);
        return Optional.of(new ConversationResponse(
                response.outcome(),
                DecisionMode.MODEL_FAST_PATH,
                response.assistantMessage(),
                response.profile(),
                response.clarification(),
                response.sourceCount(),
                response.candidatesEvaluated(),
                response.userModel(),
                response.researchSources(),
                response.harness(),
                response.games(),
                response.comparison(),
                response.shortfall(),
                response.recommendationLead()));
    }

    private String visibleName(Game game, boolean chinese) {
        if (game.details() != null) {
            String localized = chinese ? game.details().officialChineseName() : game.details().name();
            if (localized != null && !localized.isBlank()) return localized.strip();
            if (game.details().name() != null && !game.details().name().isBlank()) {
                return game.details().name().strip();
            }
        }
        return game.ranking().sourceName();
    }

    private ActionOutcome reply(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("playerReply"), Set.of("referencedBggIds", "preferenceUpdates"));
        String playerReply = playerReply(arguments);
        evidenceReview.rejectInvalidHardPreferencesBeforeTerminalReply(arguments, state, request);
        evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        List<Integer> referencedIds = arguments.has("referencedBggIds")
                ? ids(arguments.path("referencedBggIds"), 0, 5)
                : List.of();
        if (referencedIds.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("REPLY_ID_NOT_VERIFIED");
        }
        if (referencedIds.stream().anyMatch(id ->
                !state.comparisonSubjectIds.contains(id) && !state.targetGameIds.contains(id))) {
            throw new InvalidAction("REPLY_RECOMMENDATION_REQUIRES_CARDS");
        }
        state.finalResponseGameIds.addAll(referencedIds);
        state.actions.add("REPLY_TO_USER");
        return ActionOutcome.terminal(response(
                Outcome.CONVERSATION,
                playerReply,
                state,
                locale,
                null,
                List.of()));
    }

    private ActionOutcome ask(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("question"), Set.of("options", "preferenceUpdates"));
        evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        String question = playerFacingText(arguments.path("question"));
        List<ClarificationOption> options = List.of();
        if (arguments.has("options")) {
            options = playerFacingStrings(arguments.path("options"), 2, 3).stream()
                    .map(option -> new ClarificationOption(option, option))
                    .toList();
        }
        state.actions.add("ASK_USER");
        return ActionOutcome.terminal(response(
                Outcome.NEEDS_CLARIFICATION,
                question,
                state,
                locale,
                new Clarification(PreferenceField.CONVERSATION, question, options),
                List.of()));
    }

    private ActionOutcome compare(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(
                arguments,
                Set.of("candidateBggIds", "subjects", "preferredBggId", "playerReply"),
                Set.of("internalEvidenceIds", "preferenceUpdates"));
        String playerReply = playerReply(arguments);
        evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        List<Integer> candidateIds = ids(arguments.path("candidateBggIds"), 2, 5);
        List<Game> games = candidateIds.stream().map(state.verified::get).toList();
        if (games.stream().anyMatch(Objects::isNull)) {
            throw new InvalidAction("COMPARISON_CANDIDATE_NOT_VERIFIED");
        }
        if (!state.comparisonSubjectIds.containsAll(candidateIds)) {
            throw new InvalidAction("COMPARISON_CANDIDATE_NOT_IN_CONVERSATION");
        }
        List<String> subjects = playerFacingStrings(arguments.path("subjects"), 1, 3);
        Integer preferredBggId = preferredComparisonId(arguments.path("preferredBggId"));
        if (preferredBggId != null && !candidateIds.contains(preferredBggId)) {
            throw new InvalidAction("COMPARISON_PREFERENCE_INVALID");
        }

        Map<String, CandidateObservation> availableEvidence = games.stream()
                .flatMap(game -> narrativeObservations(game, state.research).entrySet().stream())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<ComparisonCandidate> candidates = games.stream()
                .map(game -> new ComparisonCandidate(
                        game,
                        selector.fitClaims(game, state.profile, runtime.chinese(locale))))
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
        validateComparisonDecision(
                arguments,
                state,
                games,
                subjects,
                preferredBggId,
                availableEvidence,
                locale);
        state.finalResponseGameIds.addAll(candidateIds);
        state.comparison = new CandidateComparison(candidates, axes);
        state.actions.add("COMPARE_CANDIDATES");
        return ActionOutcome.terminal(response(
                Outcome.CONVERSATION,
                playerReply,
                state,
                locale,
                null,
                List.of()));
    }

    private void validateComparisonDecision(
            JsonNode arguments,
            RecommendationAgentState state,
            List<Game> games,
            List<String> subjects,
            Integer preferredBggId,
            Map<String, CandidateObservation> availableEvidence,
            String locale) {
        if (!arguments.has("internalEvidenceIds")) {
            throw new InvalidAction("COMPARISON_MESSAGE_INCOMPLETE");
        }
        List<String> internalEvidenceIds = strings(
                arguments.path("internalEvidenceIds"),
                1,
                availableEvidence.size(),
                3,
                80);
        List<CandidateObservation> messageEvidence = internalEvidenceIds.stream()
                .map(availableEvidence::get)
                .toList();
        if (messageEvidence.stream().anyMatch(Objects::isNull)) {
            throw new InvalidAction("COMPARISON_MESSAGE_EVIDENCE_NOT_GROUNDED");
        }
        if (messageEvidence.stream().anyMatch(observation -> !subjects.contains(observation.attribute()))) {
            throw new InvalidAction("COMPARISON_MESSAGE_EVIDENCE_OUTSIDE_AXES");
        }
        if (preferredBggId != null
                && messageEvidence.stream().noneMatch(observation -> observation.bggId() == preferredBggId)) {
            throw new InvalidAction("COMPARISON_PREFERENCE_EVIDENCE_MISSING");
        }
        state.finalResponseEvidenceIds.addAll(internalEvidenceIds);
        state.finalResponseDecisionFacts.put("preferredBggId", preferredBggId);
        state.finalResponseDecisionFacts.put("comparisonSubjects", List.copyOf(subjects));
    }

    private Integer preferredComparisonId(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            int value = node.intValue();
            if (value > 0) return value;
        }
        throw new InvalidAction("COMPARISON_PREFERENCE_INVALID");
    }

    private ActionOutcome noMatch(JsonNode arguments, RecommendationAgentState state, String locale) {
        requireObject(arguments, Set.of("relaxSubject", "playerReply"), Set.of());
        String playerReply = playerReply(arguments);
        String subject = text(arguments.path("relaxSubject"), 1, 40);
        if (!runtime.relaxableSubjects(state).contains(subject)) {
            throw new InvalidAction("NO_MATCH_RELAXATION_NOT_ACTIONABLE");
        }
        String constraint = evidenceReview.constraintLabel(state.profile, subject, locale);
        String option = runtime.chinese(locale)
                ? "暂时取消“" + constraint + "”这条明确条件，其他条件保持不变。"
                : "Temporarily remove the stated constraint “" + constraint + "” and keep every other constraint unchanged.";
        state.finalResponseDecisionFacts.put("relaxSubject", subject);
        state.finalResponseDecisionFacts.put("relaxedConstraint", constraint);
        state.actions.add("REPORT_NO_MATCH");
        Clarification clarification = new Clarification(
                PreferenceField.CONVERSATION,
                playerReply,
                List.of(new ClarificationOption(option, option)));
        return ActionOutcome.terminal(response(
                Outcome.NO_MATCH,
                playerReply,
                state,
                locale,
                clarification,
                List.of()));
    }

    private ActionOutcome resolve(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        return resolve(arguments, state, request, locale, progress, false);
    }

    private ActionOutcome resolve(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress,
            boolean localOnly) {
        requireObject(arguments, Set.of("title", "purpose", "evidence"), Set.of("preferenceUpdates"));
        String title = text(arguments.path("title"), 1, 160);
        String evidenceId = text(arguments.path("evidence"), 1, 16);
        evidenceReview.requireUserEvidence(evidenceId, request);
        NamedGamePurpose purpose = enumValue(
                NamedGamePurpose.class, arguments.path("purpose"), "NAMED_GAME_PURPOSE_INVALID");
        List<String> preferenceWarnings = evidenceReview.applyPreferenceUpdatesForRead(arguments, state, request);
        state.referenceResolutionAttempts++;
        progress.accept(ProgressStage.READING_GAME_DETAILS);
        state.catalogCalls++;
        ReferenceObservation result = runtime.withinDeadline(state, () -> localOnly
                ? tools.resolveLocalReferenceTitle(title)
                : tools.resolveReferenceTitle(title));
        state.actions.add("RESOLVE_BGG_REFERENCE");
        result.games().forEach(game -> {
            state.observeCandidate(game.ranking().bggId(), game.ranking().sourceName());
            if (game.details() != null) state.addVerified(game);
        });
        if (result.resolved()) {
            state.unresolvedPlayerTitle = false;
            state.namedGamePurpose = purpose;
            result.games().stream()
                    .map(game -> game.ranking().bggId())
                    .forEach(id -> state.assignNamedGameRole(id, purpose));
        } else {
            state.unresolvedPlayerTitle = true;
        }
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.resolved() ? "SUCCESS" : result.status().name(),
                "code", result.code(),
                "purpose", purpose.name(),
                "preferenceUpdateWarnings", preferenceWarnings,
                "guidance", result.resolved()
                        ? switch (purpose) {
                            case COMPARISON_REFERENCE ->
                                "The player-named comparison reference is verified. Continue the still-open comparison request now: inspect your own distinct candidate hypotheses, then recommend from verified facts. Do not stop merely to confirm the title. Persist later explicit preference corrections only from their cited user-message evidence; never infer a preference from these game facts.";
                            case TARGET_GAME ->
                                "The player explicitly chose this verified game as the target. Finish with recommend_games so the application can render the verified, selectable target card. Do not inspect unrelated candidates or stop with plain text. Persist later explicit preference corrections only from cited user-message evidence; never infer a preference from these game facts.";
                            case DISCUSSION_SUBJECT, IDENTITY_ONLY ->
                                "Use only the observed BGG facts below. Continue the declared purpose, and persist any later explicit preference correction only from cited user-message evidence; never infer it from these game facts.";
                        }
                        : state.referenceResolutionAttempts < MAX_REFERENCE_RESOLUTION_ATTEMPTS
                                ? "This player-authored span did not resolve as a game title. If the request may instead describe a creator/person alias, award, list, or another external relationship, use public discovery when available rather than asking the player to supply the answer. Otherwise resolve a materially different player-authored title correction, ask for a genuinely missing identity detail, or respond transparently."
                                : "The bounded exact reference-resolution attempts did not uniquely resolve a title. Ask for the missing identity detail or respond transparently; do not invent another variant.",
                "resolvedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome search(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("titles"), Set.of("preferenceUpdates", "contextualGroup"));
        requireReadPreferenceDecision(arguments, state);
        List<String> titles = strings(arguments.path("titles"), 1, 8, 2, 120);
        List<String> preferenceWarnings = evidenceReview.applyReadPreferenceDecisions(arguments, state, request);
        state.titleInspectionAttempted = true;
        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
        state.catalogCalls += 2;
        CatalogObservation result = runtime.withinDeadline(state, () -> tools.inspectTitles(titles));
        state.actions.add("SEARCH_BGG_BY_NAME");
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        result.games().forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "preferenceUpdateWarnings", preferenceWarnings,
                "guidance", result.games().isEmpty()
                        ? "The one bounded title-inspection attempt returned no match and is now complete. Use public discovery when available, make one broad catalog browse, ask only if needed, or respond transparently; do not inspect titles again in this run."
                        : "Title identity and bounded BGG details are already verified. Do not look them up again; compare runMemory and finish when the slate is useful.",
                "verifiedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome browse(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of(), Set.of("types", "limit", "preferenceUpdates", "contextualGroup"));
        requireReadPreferenceDecision(arguments, state);
        List<String> preferenceWarnings = evidenceReview.applyReadPreferenceDecisions(arguments, state, request);
        state.catalogBrowseAttempted = true;
        List<BggGameType> types = optionalGameTypeHints(arguments, state);
        int limit = arguments.has("limit")
                ? integer(arguments.path("limit"), 1, MAX_VERIFIED_GAMES, "LIMIT_OUT_OF_RANGE")
                : Math.min(properties.modelCandidateLimit(), MAX_VERIFIED_GAMES);
        int eligibilityLimit = limit;
        Set<Integer> unavailableCandidateIds = new LinkedHashSet<>(state.excludedIds);
        unavailableCandidateIds.addAll(state.previouslyShownIds);
        int requestedCandidateCount = Math.max(eligibilityLimit, properties.resultCount());
        int catalogLimit = Math.min(
                runtime.maximumRecommendationResults(),
                requestedCandidateCount
                        + Math.min(
                                Math.max(properties.resultCount(), unavailableCandidateIds.size()),
                                runtime.maximumRecommendationResults()));
        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
        state.catalogCalls++;
        CatalogObservation result = runtime.withinDeadline(
                state,
                () -> tools.searchCatalog(state.profile.type(), types, catalogLimit));
        state.actions.add("SEARCH_BGG_CATALOG");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        List<Game> eligible = result.succeeded()
                ? selector.eligible(result.games(), state.profile, unavailableCandidateIds, eligibilityLimit)
                : List.of();
        eligible.forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "preferenceUpdateWarnings", preferenceWarnings,
                "guidance", eligible.isEmpty()
                        ? "The one bounded catalog browse produced no hard-gate-eligible game and is now complete. Use a different available capability or finish transparently; do not browse again in this run."
                        : "These are broad catalog candidates and prove only their listed BGG observations. If any player-requested selection criterion is absent from those observations, public discovery remains available and must supply that evidence before recommendation. Otherwise compare the facts and finish; do not browse again.",
                "verifiedBggIds", eligible.stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome discover(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("query"), Set.of("types", "preferenceUpdates", "contextualGroup"));
        requireReadPreferenceDecision(arguments, state);
        List<String> preferenceWarnings = evidenceReview.applyReadPreferenceDecisions(arguments, state, request);
        state.discoveryAttempted = true;
        String query = text(arguments.path("query"), 3, 300);
        List<BggGameType> types = optionalGameTypeHints(arguments, state);
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES);
        state.webResearchCalls++;
        DiscoveryObservation result = runtime.withinDeadline(
                state,
                () -> tools.discoverCandidates(
                        new BoardGameRecommendationWebResearch.DiscoveryRequest(query, types, locale)));
        state.actions.add("DISCOVER_CANDIDATES");
        CandidateDiscovery discovery = result.result().orElse(null);
        if (discovery == null) {
            if (result.status() == ToolStatus.ERROR || result.status() == ToolStatus.UNAVAILABLE) {
                state.disableWebResearch(result.code());
            }
            return ActionOutcome.observation(runtime.observation(Map.of(
                    "status", result.status().name(),
                    "code", result.code(),
                    "preferenceUpdateWarnings", preferenceWarnings,
                    "guidance", state.webResearchAvailable
                            ? "Public discovery returned no attributed candidates. Choose another retrieval action or respond transparently."
                            : "Public web research is unavailable for the rest of this run. Use the BGG title, lookup, or catalog actions, or finish transparently; do not retry web research.")));
        }
        List<BoardGameRecommendationWebResearch.CandidateLead> leads = discovery.candidates().stream()
                .limit(6)
                .toList();
        List<TitleHypothesis> hypotheses = java.util.stream.IntStream.range(0, leads.size())
                .mapToObj(index -> new TitleHypothesis("discovery-" + (index + 1), leads.get(index).name()))
                .toList();
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        state.catalogCalls += 2;
        CatalogObservation inspection = runtime.withinDeadline(
                state,
                () -> tools.inspectTitleHypotheses(hypotheses));
        state.actions.add("SEARCH_BGG_BY_NAME");
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, inspection.sourceCount());
        inspection.games().forEach(state::addVerified);
        if (!inspection.games().isEmpty()) {
            state.discoveryProducedVerifiedGames = true;
            state.unresolvedPlayerTitle = false;
        }
        state.research = mergeResearch(state.research, discoveryEvidence(discovery, inspection));
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", inspection.succeeded() && !inspection.games().isEmpty() ? "SUCCESS" : "PARTIAL",
                "preferenceUpdateWarnings", preferenceWarnings,
                "guidance", inspection.games().isEmpty()
                        ? "Public search found source-backed title hypotheses, but none produced complete BGG details. Choose another retrieval action or respond transparently."
                        : "Public search supplied title hypotheses and the application already resolved and hydrated the matching BGG games. Do not search or look them up again; use the verified facts in runMemory.",
                "verifiedBggIds", inspection.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome lookup(JsonNode arguments, RecommendationAgentState state, Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("bggIds"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1, MAX_VERIFIED_GAMES);
        if (!state.legalIds.containsAll(ids)) throw new InvalidAction("ID_NOT_OBSERVED");
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        state.catalogCalls++;
        CatalogObservation result = runtime.withinDeadline(state, () -> tools.lookupCandidates(ids));
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        result.games().forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "guidance", result.games().isEmpty()
                        ? "No complete BGG details were returned. Try different observed candidates or respond transparently."
                        : "These bounded BGG facts are verified and may support comparison or final selection.",
                "verifiedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private void requireReadPreferenceDecision(JsonNode arguments, RecommendationAgentState state) {
        if (arguments.has("preferenceUpdates")) return;
        boolean alreadyCaptured = state.actions.stream().anyMatch(action -> Set.of(
                        "UPDATE_PREFERENCES",
                        "RECORD_CONTEXTUAL_PREFERENCE",
                        "IGNORED_REDUNDANT_PREFERENCE_UPDATE")
                .contains(action));
        if (!alreadyCaptured) throw new InvalidAction("REQUIRED_ARGUMENT_MISSING");
    }

    private ActionOutcome research(
            JsonNode arguments,
            RecommendationAgentState state,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("bggIds", "question"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1, 5);
        String question = text(arguments.path("question"), 1, 300);
        if (ids.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("GAME_NOT_VERIFIED");
        }
        state.researchAttempted = true;
        progress.accept(ProgressStage.RESEARCHING_GAME_FIT);
        state.webResearchCalls++;
        List<BoardGameRecommendationWebResearch.Candidate> candidates = ids.stream()
                .map(state.verified::get)
                .map(selector::researchCandidate)
                .toList();
        ResearchObservation result = runtime.withinDeadline(
                state,
                () -> tools.researchGameFit(candidates, locale, question));
        state.actions.add("RESEARCH_GAME_FIT");
        if (result.status() == ToolStatus.ERROR || result.status() == ToolStatus.UNAVAILABLE) {
            state.disableWebResearch(result.code());
        }
        Research added = result.result().orElse(Research.empty());
        state.research = mergeResearch(state.research, added);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.status().name(),
                "code", result.code(),
                "guidance", added.games().isEmpty()
                        ? state.webResearchAvailable
                                ? "No attributed experience evidence was returned. Do not invent it."
                                : "Public web research is unavailable for the rest of this run. Use verified BGG facts or finish transparently; do not retry web research."
                        : "Use these attributed observations as reported experience, distinct from BGG facts.",
                "researchedBggIds", added.games().stream().map(GameResearch::bggId).toList())));
    }

    private ActionOutcome recommend(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(
                arguments,
                Set.of("selections", "requestedCount", "reply"),
                Set.of("referenceBggIds", "preferenceUpdates"));
        evidenceReview.applyPreferenceUpdates(arguments, state, request);
        List<Integer> rawReferenceIds = arguments.has("referenceBggIds")
                ? ids(arguments.path("referenceBggIds"), 0, MAX_VERIFIED_GAMES)
                : List.of();
        if (rawReferenceIds.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("REFERENCE_ID_NOT_VERIFIED");
        }
        JsonNode selections = arguments.path("selections");
        if (!selections.isArray()) {
            throw new InvalidAction("SELECTIONS_ARRAY_REQUIRED");
        }
        int availableCount = runtime.recommendableIds(state).size();
        int requestedCount = integer(
                arguments.path("requestedCount"),
                1,
                state.maximumRecommendationResults,
                "SELECTION_COUNT_INVALID");
        int expectedSelectionCount = Math.min(requestedCount, availableCount);
        if (selections.isEmpty()
                || selections.size() > state.maximumRecommendationResults
                || selections.size() != expectedSelectionCount) {
            throw new InvalidAction("SELECTION_COUNT_INVALID");
        }
        List<Game> selected = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode selection : selections) {
            requireObject(
                    selection,
                    Set.of("bggId"),
                    Set.of());
            int id = integer(selection.path("bggId"), 1, Integer.MAX_VALUE, "BGG_ID_INVALID");
            if (!seen.add(id)) throw new InvalidAction("DUPLICATE_SELECTION");
            Game game = state.verified.get(id);
            if (game == null) throw new InvalidAction("FINAL_ID_NOT_VERIFIED");
            if (state.excludedIds.contains(id)) throw new InvalidAction("FINAL_ID_EXCLUDED");
            if (state.previouslyShownIds.contains(id) && !state.targetGameIds.contains(id)) {
                throw new InvalidAction("FINAL_ID_PREVIOUSLY_SHOWN");
            }
            if (state.comparisonReferenceIds.contains(id)) {
                throw new InvalidAction("FINAL_ID_IS_COMPARISON_REFERENCE");
            }
            if (!state.targetGameIds.contains(id) && !selector.eligible(game, state.profile)) {
                throw new InvalidAction("FINAL_ID_FAILS_HARD_GATES");
            }
            selected.add(game);
        }
        Set<Integer> selectedIds = selected.stream()
                .map(game -> game.ranking().bggId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, CandidateObservation> availableMessageEvidence = selected.stream()
                .flatMap(game -> narrativeObservations(game, state.research).entrySet().stream())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        RecommendationReply reply = recommendationReply(
                arguments.path("reply"),
                selected,
                availableMessageEvidence,
                state,
                runtime.chinese(locale));
        state.finalResponseGameIds.addAll(selectedIds);
        reply.parts().stream()
                .map(RecommendationReplyPart::claim)
                .flatMap(claim -> claim.evidence().stream())
                .map(CandidateObservation::id)
                .forEach(state.finalResponseEvidenceIds::add);
        List<Integer> referenceIds = java.util.stream.Stream.concat(
                        state.comparisonReferenceIds.stream(), rawReferenceIds.stream())
                .distinct()
                .filter(id -> !selectedIds.contains(id))
                .limit(2)
                .toList();
        RecommendationShortfall shortfall = availableCount < requestedCount
                ? new RecommendationShortfall(requestedCount, availableCount)
                : null;
        progress.accept(ProgressStage.COMPOSING_RESPONSE);
        if (shortfall != null) state.actions.add("RECOMMENDATION_AVAILABILITY_SHORTFALL");
        state.actions.add("RECOMMEND_GAMES");
        List<Game> references = referenceIds.stream().map(state.verified::get).toList();
        List<RecommendedGame> games = selector.present(
                selected,
                state.profile,
                references,
                runtime.chinese(locale),
                state.research).stream()
                .map(game -> new RecommendedGame(
                        game.game(),
                        game.matches(),
                        game.tradeoffs(),
                        game.reasons(),
                        game.claims(),
                        reply.parts().stream()
                                .filter(part -> part.claim().bggId() == game.game().ranking().bggId())
                                .toList()))
                .toList();
        return ActionOutcome.terminal(response(
                Outcome.RECOMMENDATIONS,
                reply.completeMessage(),
                state,
                locale,
                null,
                games,
                shortfall,
                reply.lead()));
    }

    private RecommendationReply recommendationReply(
            JsonNode node,
            List<Game> selected,
            Map<String, CandidateObservation> availableEvidence,
            RecommendationAgentState state,
            boolean chinese) {
        requireObject(node, Set.of("lead", "sections"), Set.of());
        String lead = playerFacingText(node.path("lead")).strip();
        if (lead.length() > 240) throw new InvalidAction("RECOMMENDATION_REPLY_LEAD_INVALID");
        JsonNode sections = node.path("sections");
        if (!sections.isArray() || sections.size() != selected.size()) {
            throw new InvalidAction("RECOMMENDATION_REPLY_SECTIONS_INVALID");
        }
        List<RecommendationReplyPart> parts = new ArrayList<>();
        Set<Integer> seenGames = new LinkedHashSet<>();
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            JsonNode section = sections.get(sectionIndex);
            requireObject(section, Set.of("bggId", "claims"), Set.of());
            int bggId = integer(section.path("bggId"), 1, Integer.MAX_VALUE, "BGG_ID_INVALID");
            int expectedBggId = selected.get(sectionIndex).ranking().bggId();
            if (bggId != expectedBggId || !seenGames.add(bggId)) {
                throw new InvalidAction("RECOMMENDATION_REPLY_SECTIONS_INVALID");
            }
            JsonNode claims = section.path("claims");
            if (!claims.isArray() || claims.isEmpty() || claims.size() > 4) {
                throw new InvalidAction("RECOMMENDATION_REPLY_CLAIMS_INVALID");
            }
            Set<String> claimKeys = new LinkedHashSet<>();
            boolean whyFitPresent = false;
            for (JsonNode claimNode : claims) {
                RecommendationReplyPart part = recommendationReplyPart(
                        claimNode,
                        bggId,
                        selected.get(sectionIndex),
                        availableEvidence,
                        state,
                        chinese);
                String evidenceId = part.claim().evidence().getFirst().id();
                if (!claimKeys.add(part.role() + "\n" + evidenceId)) {
                    throw new InvalidAction("RECOMMENDATION_REPLY_CLAIMS_INVALID");
                }
                if (part.role() == ReplyPartRole.WHY_FIT) whyFitPresent = true;
                parts.add(part);
            }
            if (!whyFitPresent) throw new InvalidAction("RECOMMENDATION_REPLY_WHY_FIT_REQUIRED");
        }
        String completeMessage = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(lead),
                        parts.stream().map(part -> part.claim().text()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        if (completeMessage.length() > 1_200) throw new InvalidAction("PLAYER_REPLY_TOO_LONG");
        return new RecommendationReply(lead, List.copyOf(parts), completeMessage);
    }

    private RecommendationReplyPart recommendationReplyPart(
            JsonNode node,
            int bggId,
            Game game,
            Map<String, CandidateObservation> availableEvidence,
            RecommendationAgentState state,
            boolean chinese) {
        requireObject(node, Set.of("role", "claimType", "subject", "evidenceId"), Set.of("text"));
        ReplyPartRole role = enumValue(
                ReplyPartRole.class,
                node.path("role"),
                "RECOMMENDATION_REPLY_ROLE_INVALID");
        CandidateClaim.Type type = enumValue(
                CandidateClaim.Type.class,
                node.path("claimType"),
                "RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID");
        if (!Set.of(
                        CandidateClaim.Type.CONSTRAINT_FIT,
                        CandidateClaim.Type.STRUCTURED_FACT,
                        CandidateClaim.Type.TAXONOMY_CLASSIFICATION,
                        CandidateClaim.Type.ATTRIBUTED_EXPERIENCE,
                        CandidateClaim.Type.RULE_PROCEDURE,
                        CandidateClaim.Type.PUBLISHER_DESCRIPTION)
                .contains(type)) {
            throw new InvalidAction("RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID");
        }
        String subject = text(node.path("subject"), 1, 80);
        String evidenceId = text(node.path("evidenceId"), 3, 80);
        CandidateObservation observation = availableEvidence.get(evidenceId);
        if (observation == null || observation.bggId() != bggId || !observation.attribute().equals(subject)) {
            throw new InvalidAction("RECOMMENDATION_MESSAGE_EVIDENCE_NOT_GROUNDED");
        }
        CandidateClaim claim;
        if (type == CandidateClaim.Type.CONSTRAINT_FIT) {
            if (node.has("text")) throw new InvalidAction("RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID");
            CandidateClaim fit = selector.fitClaims(game, state.profile, chinese).stream()
                    .filter(candidate -> candidate.subject().equals(subject))
                    .filter(candidate -> candidate.evidence().stream()
                            .anyMatch(evidence -> evidence.id().equals(evidenceId)))
                    .findFirst()
                    .orElseThrow(() -> new InvalidAction("RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID"));
            if (role == ReplyPartRole.WHY_FIT && fit.relation() != CandidateClaim.Relation.SATISFIED
                    || role == ReplyPartRole.TRADEOFF && fit.relation() != CandidateClaim.Relation.CONFLICT) {
                throw new InvalidAction("RECOMMENDATION_REPLY_ROLE_INVALID");
            }
            claim = new CandidateClaim(
                    bggId,
                    subject,
                    type,
                    fit.strength(),
                    fit.relation(),
                    fit.text(),
                    List.of(observation));
        } else {
            if (!observation.supports(type)) {
                throw new InvalidAction("RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID");
            }
            boolean modelTextAllowed = type == CandidateClaim.Type.PUBLISHER_DESCRIPTION
                    || type == CandidateClaim.Type.ATTRIBUTED_EXPERIENCE
                    || type == CandidateClaim.Type.RULE_PROCEDURE;
            if (modelTextAllowed != node.has("text")) {
                throw new InvalidAction("RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID");
            }
            String claimText = modelTextAllowed
                    ? text(node.path("text"), 1, 280)
                    : structuredObservationText(observation, chinese);
            claim = new CandidateClaim(
                    bggId,
                    subject,
                    type,
                    null,
                    CandidateClaim.Relation.OBSERVED,
                    claimText,
                    List.of(observation));
        }
        return new RecommendationReplyPart(role, claim);
    }

    private String structuredObservationText(CandidateObservation observation, boolean chinese) {
        String value = observation.value();
        return switch (observation.attribute()) {
            case "playerCount" -> localizedRange(
                    value,
                    chinese ? "BGG 标注人数：" : "BGG player count: ",
                    chinese ? " 人。" : " players.");
            case "durationMinutes" -> localizedRange(
                    value,
                    chinese ? "BGG 标注时长：" : "BGG duration: ",
                    chinese ? " 分钟。" : " minutes.");
            case "complexity" -> chinese
                    ? "BGG 标注复杂度：" + value + " / 5。"
                    : "BGG complexity: " + value + " / 5.";
            case "minimumAge" -> chinese
                    ? "BGG 标注年龄：" + value + " 岁以上。"
                    : "BGG minimum age: " + value + "+.";
            case "bestWith" -> chinese
                    ? "BGG 社区最佳人数标注：" + value + " 人。"
                    : "BGG community best-with listing: " + value + " players.";
            case "recommendedWith" -> chinese
                    ? "BGG 社区推荐人数标注：" + value + " 人。"
                    : "BGG community recommended-with listing: " + value + " players.";
            case "designers" -> chinese ? "设计者：" + value + "。" : "Designed by: " + value + ".";
            case "publishers" -> chinese ? "出版方：" + value + "。" : "Published by: " + value + ".";
            case "categories", "mechanics", "families", "bggType" -> chinese
                    ? "BGG 分类记录：" + value + "。"
                    : "BGG classification: " + value + ".";
            default -> chinese ? "BGG 记录：" + value + "。" : "BGG records: " + value + ".";
        };
    }

    private String localizedRange(String value, String prefix, String suffix) {
        int separator = value.indexOf("..");
        if (separator <= 0 || separator + 2 >= value.length()) return prefix + value + suffix;
        String minimum = value.substring(0, separator);
        String maximum = value.substring(separator + 2);
        return prefix + (minimum.equals(maximum) ? minimum : minimum + "–" + maximum) + suffix;
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


    private ActionOutcome rejected(RecommendationAgentState state, String code, String guidance) {
        state.actions.add("REJECTED_ACTION:" + code);
        return ActionOutcome.rejected(runtime.error(code, guidance));
    }

    private String invalidActionGuidance(String code) {
        return switch (code) {
            case "REPLY_RECOMMENDATION_REQUIRES_CARDS" ->
                "New candidate recommendations must use recommend_games so the UI can render verified cards.";
            case "SELECTIONS_ARRAY_REQUIRED" ->
                "selections must be a native JSON array of selection objects. Never quote or JSON-encode the array as a string.";
            case "RECOMMENDATION_SHORTFALL_REQUIRED" ->
                "Fewer hard-eligible candidates exist than the player requested. Return every available ID once and include the required shortfall object with the exact schema counts and one concrete allowed relaxation reply when offered.";
            case "RECOMMENDATION_SHORTFALL_UNEXPECTED" ->
                "Omit shortfall because the current hard-eligible pool can satisfy the requested count.";
            case "SHORTFALL_COUNT_INVALID" ->
                "Copy requestedCount and availableCount exactly from the current shortfall schema. Never pad or duplicate candidates.";
            case "SHORTFALL_RELAXATION_INVALID" ->
                "Use each allowed shortfall subject at most once and write one concrete direct player reply that relaxes only that bound.";
            case "INVALID_JSON" ->
                "Return a fresh action with valid JSON arguments and escape string content correctly.";
            case "PREFERENCE_EVIDENCE_NOT_GROUNDED" ->
                "Use the exact evidenceId shown beside the user-authored message that states this hard constraint, or continue without changing the typed profile.";
            case "PREFERENCE_NUMERIC_EVIDENCE_NOT_EXPLICIT" ->
                "Do not translate a qualitative complexity preference into a BGG number. Persist complexity only when the cited user text explicitly states that numeric BGG complexity or weight value.";
            case "PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT" ->
                "A persistent type or interaction filter requires the player to explicitly name that category in an affirmative statement. A companion, setting, mood, inferred audience, or rejected category is not categorical evidence; omit the typed update and keep that context in the natural decision instead.";
            case "PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID" ->
                "Use evidenceClassification DIRECT for an explicitly stated constraint. Only an exact player count strongly implied by a fully described whole group may use CONTEXTUAL_COMPLETE_GROUP; otherwise omit the typed update.";
            case "RECOMMENDATION_MESSAGE_EVIDENCE_NOT_GROUNDED" ->
                "Cite only observation IDs from the selected candidates in current runMemory. Keep those IDs out of player-facing prose.";
            case "RECOMMENDATION_MESSAGE_EVIDENCE_INCOMPLETE" ->
                "Cite at least one literal observation for every selected candidate. Rewrite any game-specific claim that is not supported by those observations instead of inferring an unreported experience.";
            case "RECOMMENDATION_REPLY_LEAD_INVALID" ->
                "Write one short reply.lead without candidate facts, fit superlatives, or play-experience claims.";
            case "RECOMMENDATION_REPLY_SECTIONS_INVALID" ->
                "Return exactly one reply section per selection, in the same order, with that selected bggId.";
            case "RECOMMENDATION_REPLY_CLAIMS_INVALID", "RECOMMENDATION_REPLY_WHY_FIT_REQUIRED" ->
                "Each selected game needs one to four distinct claim objects and at least one WHY_FIT claim.";
            case "RECOMMENDATION_REPLY_CLAIM_TYPE_INVALID", "RECOMMENDATION_REPLY_ROLE_INVALID" ->
                "Match each claimType and role to the single cited observation capability; taxonomy and publisher text cannot prove player experience.";
            case "FINAL_ID_FAILS_HARD_GATES", "FINAL_ID_IS_COMPARISON_REFERENCE" ->
                "Select only IDs listed in runMemory.recommendableBggIds; those IDs already satisfy the current typed hard gates.";
            case "NO_MATCH_RELAXATION_NOT_ACTIONABLE" ->
                "Choose exactly one relaxSubject from the current report_no_match schema; it must unlock a verified candidate while every other hard constraint stays unchanged.";
            default -> "Correct the action arguments using the supplied JSON schema and current runMemory.";
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
        return response(outcome, message, state, locale, clarification, games, shortfall, null);
    }

    private ConversationResponse response(
            Outcome outcome,
            String message,
            RecommendationAgentState state,
            String locale,
            Clarification clarification,
            List<RecommendedGame> games,
            RecommendationShortfall shortfall,
            String recommendationLead) {
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
                        state.elapsedMs()),
                games,
                state.comparison,
                shortfall,
                recommendationLead);
        return response;
    }

    Map<String, Object> gameObservation(Game game) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bggId", game.ranking().bggId());
        value.put("name", game.ranking().sourceName());
        putIfKnown(value, "year", game.ranking().publicationYear());
        if (game.details() == null) return value;
        var details = game.details();
        putIfText(value, "officialChineseName", details.officialChineseName());
        Map<String, List<String>> observations = selector.observations(game).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> List.of(observationKindCode(observation), observation.value()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        value.put("observations", observations);
        return value;
    }

    Map<String, Object> finalResponseGameObservation(
            Game game,
            Research research,
            Set<String> allowedEvidenceIds) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bggId", game.ranking().bggId());
        value.put("name", game.ranking().sourceName());
        putIfKnown(value, "year", game.ranking().publicationYear());
        if (game.details() != null) putIfText(value, "officialChineseName", game.details().officialChineseName());
        Map<String, List<String>> observations = narrativeObservations(game, research).entrySet().stream()
                .filter(entry -> allowedEvidenceIds.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.of(finalObservationKind(entry.getValue()), entry.getValue().value()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        value.put("observations", observations);
        return value;
    }

    private String observationKindCode(CandidateObservation observation) {
        return switch (observation.kind()) {
            case STRUCTURED_METADATA -> "M";
            case TAXONOMY -> "T";
            case ATTRIBUTED_REPORT -> "A";
            case RULEBOOK_FACT -> "R";
        };
    }

    private String finalObservationKind(CandidateObservation observation) {
        if ("publisherDescription".equals(observation.attribute())) {
            return "publisher_description";
        }
        return switch (observation.kind()) {
            case STRUCTURED_METADATA -> "verified_bgg_metadata";
            case TAXONOMY -> "bgg_taxonomy_label";
            case ATTRIBUTED_REPORT -> "attributed_public_report";
            case RULEBOOK_FACT -> "verified_rulebook_fact";
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
            if (sources.size() == 12) break;
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

    private Research discoveryEvidence(CandidateDiscovery discovery, CatalogObservation inspection) {
        if (discovery == null || discovery.sources().isEmpty() || inspection.games().isEmpty()) {
            return Research.empty();
        }
        List<Source> sources = discovery.sources().stream()
                .filter(this::credibleDiscoverySource)
                .toList();
        if (sources.isEmpty()) return Research.empty();
        Set<Integer> sourceIndexes = sources.stream()
                .map(Source::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Integer, Game> gamesById = inspection.games().stream()
                .collect(java.util.stream.Collectors.toMap(
                        game -> game.ranking().bggId(),
                        game -> game,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<String, Integer> resolvedIds = inspection.titleResolutions().stream()
                .collect(java.util.stream.Collectors.toMap(
                        BoardGameRecommendationTools.TitleResolution::correlationId,
                        BoardGameRecommendationTools.TitleResolution::bggId,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<BoardGameRecommendationWebResearch.CandidateLead> leads = discovery.candidates().stream()
                .limit(6)
                .toList();
        List<GameResearch> games = java.util.stream.IntStream.range(0, leads.size())
                .mapToObj(index -> Map.entry("discovery-" + (index + 1), leads.get(index)))
                .flatMap(entry -> java.util.Optional.ofNullable(resolvedIds.get(entry.getKey()))
                        .map(gamesById::get)
                        .map(game -> new GameResearch(
                                game.ranking().bggId(),
                                List.of(new Observation(
                                        entry.getValue().fitObservation(),
                                        entry.getValue().sourceIndexes().stream()
                                                .filter(sourceIndexes::contains)
                                                .toList()))))
                        .stream())
                .filter(game -> game.observations().stream()
                        .anyMatch(observation -> !observation.text().isBlank()
                                && !observation.sourceIndexes().isEmpty()))
                .toList();
        return games.isEmpty() ? Research.empty() : new Research(games, sources);
    }

    private boolean credibleDiscoverySource(Source source) {
        String domain = source == null || source.domain() == null
                ? ""
                : source.domain().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        return !Set.of(
                        "amazon.com",
                        "facebook.com",
                        "instagram.com",
                        "pinterest.com",
                        "tiktok.com",
                        "x.com")
                .contains(domain);
    }

    private void requireObject(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw new InvalidAction("ARGUMENT_OBJECT_REQUIRED");
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        java.util.Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new InvalidAction("UNEXPECTED_ARGUMENT");
        if (required.stream().anyMatch(field -> !node.has(field))) {
            throw new InvalidAction("REQUIRED_ARGUMENT_MISSING");
        }
    }

    private String text(JsonNode node, int minimum, int maximum) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText().strip();
        if (value.length() < minimum || value.length() > maximum) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private String playerFacingText(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("TEXT_ARGUMENT_REQUIRED");
        String value = node.asText();
        if (value.isBlank()) throw new InvalidAction("TEXT_LENGTH_INVALID");
        return value;
    }

    private String playerReply(JsonNode arguments) {
        String reply = playerFacingText(arguments.path("playerReply")).strip();
        if (reply.length() > 1_200) throw new InvalidAction("PLAYER_REPLY_TOO_LONG");
        return reply;
    }

    private List<String> playerFacingStrings(JsonNode node, int minimumItems, int maximumItems) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(playerFacingText(value));
        List<String> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return distinct;
    }

    private List<String> strings(JsonNode node, int minimumItems, int maximumItems, int minimumLength, int maximumLength) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(text(value, minimumLength, maximumLength));
        List<String> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return distinct;
    }

    private List<Integer> ids(JsonNode node, int minimumItems, int maximumItems) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction("ID_LIST_INVALID");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode value : node) values.add(integer(value, 1, Integer.MAX_VALUE, "BGG_ID_INVALID"));
        List<Integer> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction("DUPLICATE_LIST_VALUE");
        return distinct;
    }

    private int integer(JsonNode node, int minimum, int maximum, String code) {
        if (!node.canConvertToInt()) throw new InvalidAction(code);
        int value = node.intValue();
        if (value < minimum || value > maximum) throw new InvalidAction(code);
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
            Class<E> type, JsonNode node, int minimumItems, int maximumItems, String code) {
        if (!node.isArray() || node.size() < minimumItems || node.size() > maximumItems) {
            throw new InvalidAction(code);
        }
        List<E> values = new ArrayList<>();
        for (JsonNode value : node) values.add(enumValue(type, value, code));
        List<E> distinct = values.stream().distinct().toList();
        if (distinct.size() != values.size()) throw new InvalidAction(code);
        return distinct;
    }

    private List<BggGameType> optionalGameTypeHints(
            JsonNode arguments,
            RecommendationAgentState state) {
        if (!arguments.has("types")) return List.of();
        List<BggGameType> values = enumValues(
                BggGameType.class, arguments.path("types"), 0, 3, "GAME_TYPES_INVALID");
        if (values.contains(BggGameType.ALL)) throw new InvalidAction("GAME_TYPES_INVALID");
        return values;
    }

    record ActionOutcome(ConversationResponse response, String observation, boolean rejected) {
        static ActionOutcome terminal(ConversationResponse response) {
            return new ActionOutcome(response, "", false);
        }

        static ActionOutcome observation(String observation) {
            return new ActionOutcome(null, observation, false);
        }

        static ActionOutcome rejected(String observation) {
            return new ActionOutcome(null, observation, true);
        }
    }

    private record RecommendationReply(
            String lead,
            List<RecommendationReplyPart> parts,
            String completeMessage) {}

    static final class InvalidAction extends RuntimeException {
        final String code;
        final String guidance;

        InvalidAction(String code) {
            this(code, null);
        }

        InvalidAction(String code, String guidance) {
            super(code);
            this.code = code;
            this.guidance = guidance;
        }
    }
}
