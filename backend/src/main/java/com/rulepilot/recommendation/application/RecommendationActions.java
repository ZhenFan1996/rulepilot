package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RECOMMEND_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.UPDATE_PREFERENCES_TOOL;

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
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocus;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressFocusKind;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ProgressStage;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationShortfall;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendedGame;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CatalogObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.DiscoveryObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ResearchObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.RecommendationAgentState.DiscoveryPurpose;
import com.rulepilot.recommendation.application.RecommendationAgentState.TitleConstraint;
import com.rulepilot.recommendation.application.RecommendationEvidenceReview.PreferenceUpdatePlan;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import com.rulepilot.recommendation.application.RecommendationAgentState.PublicationSeed;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the allow-listed recommendation actions over application-owned tools. */
final class RecommendationActions {

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
                case UPDATE_PREFERENCES_TOOL -> updatePreferences(arguments, state, request, locale);
                case ASK_TOOL -> ask(arguments, state, request, locale);
                case RESOLVE_TOOL -> resolve(arguments, state, request, locale, progress);
                case BROWSE_TOOL -> browse(arguments, state, request, progress);
                case DISCOVER_TOOL -> discover(arguments, state, request, locale, progress);
                case LOOKUP_TOOL -> lookup(arguments, state, progress);
                case RESEARCH_TOOL -> research(arguments, state, locale, progress);
                case COMPARE_TOOL -> compare(arguments, state, request, locale);
                case NO_MATCH_TOOL -> noMatch(arguments, state, locale);
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
            if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
            return rejectedContract(
                    state,
                    code,
                    invalid != null && invalid.guidance != null
                            ? invalid.guidance
                            : invalidActionGuidance(code),
                    invalid == null ? Map.of() : invalid.details);
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation action {} failed ({})", call.name(), exception.getClass().getSimpleName());
            if (!ASK_TOOL.equals(call.name())) state.clarificationBlockedByExecutionFailure = true;
            return rejectedUnavailable(
                    state,
                    "ACTION_UNAVAILABLE",
                    "The action failed. Choose another useful action or respond transparently.");
        }
    }

    private ActionOutcome updatePreferences(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("preferenceUpdates"), Set.of("playerReply"));
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
        String reply = arguments.has("playerReply") ? playerReply(arguments) : null;
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
        if (reply != null) {
            return ActionOutcome.terminal(response(
                    Outcome.CONVERSATION,
                    reply,
                    state,
                    locale,
                    null,
                    List.of()));
        }
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", "PREFERENCES_UPDATED",
                "currentProfile", evidenceReview.profileForAgent(state.profile))));
    }

    private ActionOutcome ask(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("question"), Set.of("options", "preferenceUpdates"));
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planClarificationPreferenceUpdates(arguments, state.profile, request);
        String question = playerFacingText(arguments.path("question"));
        List<ClarificationOption> options = List.of();
        if (arguments.has("options")) {
            options = playerFacingStrings(arguments.path("options"), 2, 3).stream()
                    .map(option -> new ClarificationOption(option, option))
                    .toList();
        }
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
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
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planOptionalPreferenceUpdates(arguments, state.profile, request);
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
                        selector.fitClaims(game, preferencePlan.profile(), runtime.chinese(locale))))
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
        List<String> internalEvidenceIds = validateComparisonDecision(
                arguments, subjects, preferredBggId, availableEvidence);
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
        state.finalResponseEvidenceIds.addAll(internalEvidenceIds);
        state.finalResponseDecisionFacts.put("preferredBggId", preferredBggId);
        state.finalResponseDecisionFacts.put("comparisonSubjects", List.copyOf(subjects));
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

    private List<String> validateComparisonDecision(
            JsonNode arguments,
            List<String> subjects,
            Integer preferredBggId,
            Map<String, CandidateObservation> availableEvidence) {
        if (!arguments.has("internalEvidenceIds")) {
            throw new InvalidAction("COMPARISON_MESSAGE_INCOMPLETE");
        }
        List<String> internalEvidenceIds = strings(arguments.path("internalEvidenceIds"), 1);
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
        return internalEvidenceIds;
    }

    private Integer preferredComparisonId(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            int value = node.intValue();
            if (value > 0) return value;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0 && Integer.toString(parsed).equals(value)) return parsed;
            } catch (NumberFormatException ignored) {
                // A decimal string is a documented provider transport form, not free-form prose.
            }
        }
        throw new InvalidAction("COMPARISON_PREFERENCE_INVALID");
    }

    private ActionOutcome noMatch(JsonNode arguments, RecommendationAgentState state, String locale) {
        requireObject(arguments, Set.of("relaxSubject", "playerReply"), Set.of());
        String playerReply = playerReply(arguments);
        String subject = text(arguments.path("relaxSubject"));
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
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        return resolve(arguments, state, request, locale, progress, false);
    }

    private ActionOutcome resolve(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress,
            boolean localOnly) {
        requireObject(
                arguments,
                Set.of("title", "purpose", "evidence"),
                Set.of("alternateTitles"));
        List<String> titles = referenceTitles(arguments);
        String evidenceId = referenceEvidence(arguments.path("evidence"));
        evidenceReview.requireUserEvidence(evidenceId, request);
        NamedGamePurpose purpose = enumValue(
                NamedGamePurpose.class, arguments.path("purpose"), "NAMED_GAME_PURPOSE_INVALID");
        progress.accept(ProgressStage.READING_GAME_DETAILS, null);
        boolean reusedVerifiedReference = false;
        ReferenceObservation result = verifiedReference(titles, state)
                .map(game -> new ReferenceObservation(ToolStatus.SUCCESS, List.of(game), ""))
                .orElse(null);
        if (result != null) reusedVerifiedReference = true;
        if (result == null) {
            for (String title : titles) {
                state.catalogCalls++;
                result = runtime.withinDeadline(state, () -> tools.resolveLocalReferenceTitle(title));
                if (result.resolved()) break;
            }
        }
        if (!localOnly && (result == null || !result.resolved())) {
            for (String title : titles) {
                state.catalogCalls++;
                result = runtime.withinDeadline(state, () -> tools.resolveReferenceTitle(title));
                if (result.resolved()) break;
            }
        }
        if (result == null) throw new InvalidAction("REFERENCE_TITLE_REQUIRED");
        if (result.resolved() && purpose == NamedGamePurpose.TARGET_GAME) {
            return resolvedTarget(
                    result.games().getFirst(),
                    result,
                    reusedVerifiedReference,
                    state);
        }
        commitReferenceOutcome(result, purpose, reusedVerifiedReference, state);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", result.resolved() ? "SUCCESS" : result.status().name(),
                "code", result.code(),
                "purpose", purpose.name(),
                "guidance", result.resolved()
                        ? switch (purpose) {
                            case COMPARISON_REFERENCE ->
                                "The player-named comparison reference is verified. Continue the still-open comparison request now: inspect your own distinct candidate hypotheses, then recommend from verified facts. Do not stop merely to confirm the title. Persist later explicit preference corrections only from their cited user-message evidence; never infer a preference from these game facts.";
                            case TARGET_GAME ->
                                "The player explicitly chose this verified game as the target. The target action itself returns its selectable card; do not inspect unrelated candidates. Persist later explicit preference corrections only from cited user-message evidence; never infer a preference from these game facts.";
                            case DISCUSSION_SUBJECT, IDENTITY_ONLY ->
                                "Use only the observed BGG facts below. Continue the declared purpose, and persist any later explicit preference correction only from cited user-message evidence; never infer it from these game facts.";
                        }
                        : "This player-authored span did not resolve as a game title. If the request may instead describe a creator/person alias, award, list, or another external relationship, use public discovery when available rather than asking the player to supply the answer. Otherwise resolve a materially different player-authored title correction, ask for a genuinely missing identity detail, or respond transparently.",
                "resolvedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList())));
    }

    private void commitReferenceOutcome(
            ReferenceObservation result,
            NamedGamePurpose purpose,
            boolean reusedVerifiedReference,
            RecommendationAgentState state) {
        state.referenceResolutionAttempts++;
        if (reusedVerifiedReference) state.actions.add("REUSE_VERIFIED_BGG_REFERENCE");
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
    }

    private ActionOutcome resolvedTarget(
            Game selected,
            ReferenceObservation result,
            boolean reusedVerifiedReference,
            RecommendationAgentState state) {
        int bggId = selected.ranking().bggId();
        if (state.excludedIds.contains(bggId)) throw new InvalidAction("FINAL_ID_EXCLUDED");
        if (state.titleConstraint != null && !state.titleConstraint.matches(selected)) {
            throw new InvalidAction("FINAL_TITLE_CONSTRAINT_MISMATCH");
        }
        commitReferenceOutcome(result, NamedGamePurpose.TARGET_GAME, reusedVerifiedReference, state);
        return preparePublication(
                runtime.observation(Map.of(
                        "status", "SUCCESS",
                        "code", result.code(),
                        "purpose", NamedGamePurpose.TARGET_GAME.name(),
                        "guidance", "The exact player-selected game is verified. Use recommend_games now so the complete reply and every card note are written from the observed facts below.",
                        "verifiedBggIds", List.of(bggId))),
                state,
                List.of(selected),
                1);
    }

    private Optional<Game> verifiedReference(List<String> titles, RecommendationAgentState state) {
        Set<String> typedIdentities = titles.stream()
                .map(this::normalizedReferenceIdentity)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Game> matches = state.verified.values().stream()
                .filter(game -> game.details() != null)
                .filter(game -> gameIdentityTitles(game).stream().anyMatch(typedIdentities::contains))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private Set<String> gameIdentityTitles(Game game) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        identities.add(normalizedReferenceIdentity(game.ranking().sourceName()));
        identities.add(normalizedReferenceIdentity(game.details().name()));
        identities.add(normalizedReferenceIdentity(game.details().officialChineseName()));
        identities.remove("");
        return identities;
    }

    private String normalizedReferenceIdentity(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private TitleConstraint titleConstraint(JsonNode arguments, ConversationRequest request) {
        if (!arguments.has("titleConstraint")) return null;
        if (!arguments.has("evidence")) {
            throw new InvalidAction("TITLE_CONSTRAINT_EVIDENCE_REQUIRED");
        }
        JsonNode constraint = arguments.path("titleConstraint");
        requireObject(constraint, Set.of("operator", "value"), Set.of());
        String operator = text(constraint.path("operator")).strip();
        if (!"CONTAINS".equals(operator)) throw new InvalidAction("TITLE_CONSTRAINT_OPERATOR_INVALID");
        String value = text(constraint.path("value")).strip();
        if (value.isEmpty()) throw new InvalidAction("TITLE_CONSTRAINT_VALUE_INVALID");
        String evidenceId = text(arguments.path("evidence")).strip();
        evidenceReview.requireCurrentTurnUserEvidence(
                evidenceId,
                request,
                "TITLE_CONSTRAINT_EVIDENCE_NOT_CURRENT");
        return new TitleConstraint(value, evidenceId);
    }

    private ActionOutcome browse(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(
                arguments,
                Set.of(),
                Set.of(
                        "purpose",
                        "types",
                        "categories",
                        "mechanics",
                        "designers",
                        "publishers",
                        "families",
                        "minimumPublicationYear",
                        "maximumPublicationYear",
                        "minimumAverageRating",
                        "minimumRatingsCount",
                        "textQuery",
                        "titleConstraint",
                        "evidence",
                        "sort",
                        "limit",
                        "requestedCount",
                        "offset",
                        "preferenceUpdates"));
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planOptionalPreferenceUpdates(arguments, state.profile, request);
        DiscoveryPurpose purpose = arguments.has("purpose")
                ? enumValue(DiscoveryPurpose.class, arguments.path("purpose"), "CATALOG_PURPOSE_INVALID")
                : DiscoveryPurpose.SELECTABLE_CARDS;
        List<BggGameType> requestedTypes = optionalGameTypeHints(arguments, state);
        List<BggGameType> types = preferencePlan.profile().type() == BggGameType.ALL
                ? requestedTypes
                : List.of(preferencePlan.profile().type());
        List<String> categories = optionalStrings(arguments, "categories");
        List<String> mechanics = optionalStrings(arguments, "mechanics");
        List<String> designers = optionalStrings(arguments, "designers");
        List<String> publishers = optionalStrings(arguments, "publishers");
        List<String> families = optionalStrings(arguments, "families");
        Integer minimumPublicationYear = arguments.has("minimumPublicationYear")
                ? integer(arguments.path("minimumPublicationYear"), 1, 2100, "PUBLICATION_YEAR_INVALID")
                : null;
        Integer maximumPublicationYear = arguments.has("maximumPublicationYear")
                ? integer(arguments.path("maximumPublicationYear"), 1, 2100, "PUBLICATION_YEAR_INVALID")
                : null;
        if (minimumPublicationYear != null
                && maximumPublicationYear != null
                && minimumPublicationYear > maximumPublicationYear) {
            throw new InvalidAction("PUBLICATION_YEAR_INVALID");
        }
        BigDecimal minimumAverageRating = arguments.has("minimumAverageRating")
                ? decimal(arguments.path("minimumAverageRating"), BigDecimal.ZERO, BigDecimal.TEN, "RATING_INVALID")
                : null;
        Integer minimumRatingsCount = arguments.has("minimumRatingsCount")
                ? integer(arguments.path("minimumRatingsCount"), 0, 100_000_000, "RATINGS_COUNT_INVALID")
                : null;
        TitleConstraint requestedTitleConstraint = titleConstraint(arguments, request);
        if (requestedTitleConstraint != null
                && state.titleConstraint != null
                && !state.titleConstraint.equals(requestedTitleConstraint)) {
            throw new InvalidAction("TITLE_CONSTRAINT_CONFLICT");
        }
        TitleConstraint activeTitleConstraint = requestedTitleConstraint == null
                ? state.titleConstraint
                : requestedTitleConstraint;
        String textQuery = arguments.has("textQuery")
                ? text(arguments.path("textQuery")).strip()
                : activeTitleConstraint == null ? null : activeTitleConstraint.value();
        CatalogSort sort = arguments.has("sort")
                ? enumValue(CatalogSort.class, arguments.path("sort"), "CATALOG_SORT_INVALID")
                : CatalogSort.RANK;
        if (sort == CatalogSort.RELEVANCE && textQuery == null) {
            throw new InvalidAction("CATALOG_TEXT_QUERY_REQUIRED");
        }
        int offset = arguments.has("offset")
                ? integer(arguments.path("offset"), 0, MAX_CATALOG_OFFSET, "CATALOG_OFFSET_INVALID")
                : 0;
        if (purpose == DiscoveryPurpose.IDENTITY_ONLY
                && (designers.size() != 1
                        || !categories.isEmpty()
                        || !mechanics.isEmpty()
                        || !publishers.isEmpty()
                        || !families.isEmpty()
                        || minimumPublicationYear != null
                        || maximumPublicationYear != null
                        || minimumAverageRating != null
                        || minimumRatingsCount != null
                        || textQuery != null
                        || activeTitleConstraint != null
                        || offset != 0)) {
            throw new InvalidAction(
                    "IDENTITY_CATALOG_QUERY_INVALID",
                    "For a creator identity check, supply exactly one designer and no category or mechanic filters.");
        }
        int limit = arguments.has("limit")
                ? integer(
                        arguments.path("limit"),
                        1,
                        properties.modelCandidateLimit(),
                        "LIMIT_OUT_OF_RANGE")
                : properties.modelCandidateLimit();
        int publicationCount = publicationCount(arguments, request);
        if (activeTitleConstraint != null) state.titleConstraint = activeTitleConstraint;
        int eligibilityLimit = purpose == DiscoveryPurpose.IDENTITY_ONLY
                ? limit
                : Math.max(limit, publicationCount);
        Set<Integer> unavailableCandidateIds = new LinkedHashSet<>(state.excludedIds);
        unavailableCandidateIds.addAll(state.previouslyShownIds);
        unavailableCandidateIds.addAll(state.comparisonReferenceIds);
        int catalogLimit = CATALOG_PAGE_SIZE;
        int selectionLimit = eligibilityLimit;
        progress.accept(
                ProgressStage.SEARCHING_BGG_CATALOG,
                browseFocus(categories, mechanics, designers, publishers, families));
        List<Game> eligible = List.of();
        Set<Integer> previousPageIds = Set.of();
        CatalogObservation result = null;
        int catalogSourceCount = 0;
        int pageOffset = offset;
        int scannedPages = 0;
        long catalogPageBudget = 1;
        boolean completedCatalogPage = false;
        while (eligible.size() < eligibilityLimit) {
            state.catalogCalls++;
            CatalogObservation page;
            try {
                int currentOffset = pageOffset;
                page = runtime.withinDeadline(
                        state,
                        () -> tools.searchCatalog(
                                types,
                                categories,
                                mechanics,
                                designers,
                                publishers,
                                families,
                                minimumPublicationYear,
                                maximumPublicationYear,
                                minimumAverageRating,
                                minimumRatingsCount,
                                textQuery,
                                sort,
                                catalogLimit,
                                currentOffset));
            } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
                if (eligible.isEmpty()) throw exception;
                state.actions.add("CATALOG_SCAN_STOPPED:TIME_BUDGET");
                break;
            }
            result = page;
            scannedPages++;
            catalogSourceCount = Math.max(catalogSourceCount, page.sourceCount());
            catalogPageBudget = catalogPageBudget(catalogSourceCount, offset, catalogLimit);
            if (!page.succeeded()) break;
            completedCatalogPage = true;
            LinkedHashMap<Integer, Game> selectionWindow = eligible.stream().collect(
                    java.util.stream.Collectors.toMap(
                            game -> game.ranking().bggId(),
                            game -> game,
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            LinkedHashSet<Integer> pageIds = new LinkedHashSet<>();
            page.games().forEach(game -> {
                int bggId = game.ranking().bggId();
                if (pageIds.add(bggId)
                        && (activeTitleConstraint == null || activeTitleConstraint.matches(game))) {
                    selectionWindow.putIfAbsent(bggId, game);
                }
            });
            eligible = selector.eligible(
                    List.copyOf(selectionWindow.values()),
                    preferencePlan.profile(),
                    unavailableCandidateIds,
                    selectionLimit);
            if (purpose == DiscoveryPurpose.IDENTITY_ONLY
                    || eligible.size() >= eligibilityLimit
                    || page.pageExhausted()
                    || page.games().isEmpty()
                    || pageIds.equals(previousPageIds)) {
                break;
            }
            previousPageIds = Set.copyOf(pageIds);
            if (scannedPages >= catalogPageBudget) {
                state.actions.add("CATALOG_SCAN_STOPPED:SOURCE_CARDINALITY");
                break;
            }
            long nextPageOffset = (long) pageOffset + catalogLimit;
            if (nextPageOffset > MAX_CATALOG_OFFSET) {
                state.actions.add("CATALOG_SCAN_STOPPED:OFFSET_RANGE");
                break;
            }
            pageOffset = (int) nextPageOffset;
        }
        CatalogObservation terminalResult = Objects.requireNonNull(result, "catalog scan must complete one page");
        Map<String, Object> appliedFilters = new LinkedHashMap<>();
        appliedFilters.put("types", types);
        appliedFilters.put("categories", categories);
        appliedFilters.put("mechanics", mechanics);
        appliedFilters.put("designers", designers);
        appliedFilters.put("publishers", publishers);
        appliedFilters.put("families", families);
        appliedFilters.put("minimumPublicationYear", minimumPublicationYear);
        appliedFilters.put("maximumPublicationYear", maximumPublicationYear);
        appliedFilters.put("minimumAverageRating", minimumAverageRating);
        appliedFilters.put("minimumRatingsCount", minimumRatingsCount);
        appliedFilters.put("textQuery", textQuery);
        appliedFilters.put(
                "titleConstraint",
                activeTitleConstraint == null
                        ? null
                        : Map.of(
                                "operator", "CONTAINS",
                                "value", activeTitleConstraint.value(),
                                "evidence", activeTitleConstraint.evidenceId()));
        appliedFilters.put("sort", sort);
        appliedFilters.put("offset", offset);
        appliedFilters.put("pagesScanned", scannedPages);
        appliedFilters.put("pageBudget", catalogPageBudget);
        String observation = runtime.observation(Map.of(
                "status", completedCatalogPage
                        ? terminalResult.succeeded() ? "SUCCESS" : "PARTIAL"
                        : "ERROR",
                "code", terminalResult.code(),
                "guidance", eligible.isEmpty()
                        ? purpose == DiscoveryPurpose.IDENTITY_ONLY && state.webResearchAvailable
                                ? "The local BGG catalog did not verify that creator identity. Treat the guessed name as disproved for this alias and use public discovery with the original user evidence; do not retry or publish the guess."
                                : "This exact BGG filter query produced no hard-gate-eligible game. If its filters came from a metaphor, mood, or subjective wish rather than literal player-supplied BGG labels, remove all of those inferred filters and browse one varied slate now. Otherwise use materially different verified filters, another capability, or finish transparently; never repeat the same query or guess titles."
                                : "These games match the supplied BGG filters and their listed observations are verified. Use recommend_games to write the complete recommendation and evidence-bound card notes, or make one materially different structured query only when the open request still needs it.",
                "appliedFilters", appliedFilters,
                "verifiedBggIds", eligible.stream().map(game -> game.ranking().bggId()).toList()));
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
        state.catalogBrowseAttempted = true;
        state.discoveryPurpose = purpose;
        state.actions.add("SEARCH_BGG_CATALOG");
        state.sourceCount = Math.max(state.sourceCount, catalogSourceCount);
        eligible.forEach(state::addVerified);
        if (purpose == DiscoveryPurpose.IDENTITY_ONLY) {
            return ActionOutcome.observation(observation);
        }
        return preparePublication(observation, state, eligible, publicationCount);
    }

    private long catalogPageBudget(int sourceCount, int offset, int pageSize) {
        long remainingRows = Math.max(0L, (long) sourceCount - offset);
        if (remainingRows == 0) return 1;
        return (remainingRows + pageSize - 1L) / pageSize;
    }

    private ActionOutcome discover(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(
                arguments,
                Set.of("evidence", "subject", "goal"),
                Set.of("types"));
        DiscoveryGoal goal = enumValue(
                DiscoveryGoal.class,
                arguments.path("goal"),
                "DISCOVERY_GOAL_INVALID");
        DiscoveryPurpose purpose = goal == DiscoveryGoal.SELECTABLE_CARDS
                ? DiscoveryPurpose.SELECTABLE_CARDS
                : DiscoveryPurpose.IDENTITY_ONLY;
        String evidenceId = text(arguments.path("evidence"));
        evidenceReview.requireUserEvidence(evidenceId, request);
        String query = evidenceReview.preferenceEvidence(request).get(evidenceId);
        String subject = text(arguments.path("subject")).strip();
        List<BggGameType> types = optionalGameTypeHints(arguments, state);
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES, null);
        state.publicContextEvidence.clear();
        state.publicContextSources = List.of();
        state.finalResponsePublicEvidenceIds.clear();
        state.discoveredCandidateLeads = List.of();
        state.webResearchCalls++;
        BoardGameRecommendationWebResearch.DiscoveryRequest discoveryRequest =
                new BoardGameRecommendationWebResearch.DiscoveryRequest(
                        query,
                        subject,
                        types,
                        locale,
                        goal);
        DiscoveryObservation result = runtime.withinDeadline(
                state,
                () -> tools.discoverCandidates(discoveryRequest));
        CandidateDiscovery discovery = result.result().orElse(null);
        if (discovery == null) {
            boolean webResearchAvailable = result.status() != ToolStatus.ERROR
                    && result.status() != ToolStatus.UNAVAILABLE;
            String guidance = purpose == DiscoveryPurpose.IDENTITY_ONLY
                    ? webResearchAvailable
                            ? "Public discovery returned no attributed fact. Use another capability only when it is materially relevant to the open request; otherwise answer transparently. Do not repeat this search."
                            : "Public web research is unavailable for the rest of this run. Use another capability only when it is materially relevant to the open request; otherwise answer transparently. Do not retry web research."
                    : webResearchAvailable
                            ? "Public discovery returned no attributed candidates. Choose another retrieval action or respond transparently."
                            : "Public web research is unavailable for the rest of this run. Use the BGG title, lookup, or catalog actions, or finish transparently; do not retry web research.";
            String observation = runtime.observation(Map.of(
                    "status", result.status().name(),
                    "code", result.code(),
                    "guidance", guidance));
            state.discoveryAttempted = true;
            state.discoveryPurpose = purpose;
            state.actions.add("DISCOVER_CANDIDATES");
            if (!webResearchAvailable) state.disableWebResearch(result.code());
            return ActionOutcome.observation(observation);
        }
        Set<Integer> sourceIndexes = discovery.sources().stream()
                .map(Source::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        state.publicContextSources = discovery.sources();
        state.sourceCount = Math.max(state.sourceCount, discovery.sources().size());
        List<PublicContextEvidence> publicContext = recordPublicContext(discovery, state);
        state.discoveredCandidateLeads = discovery.candidates().stream()
                .filter(lead -> !lead.name().isBlank())
                .filter(lead -> !lead.sourceIndexes().isEmpty())
                .filter(lead -> sourceIndexes.containsAll(lead.sourceIndexes()))
                .toList();
        if (!state.discoveredCandidateLeads.isEmpty()) {
            state.actions.add("DISCOVERY_CANDIDATE_LEADS_RECORDED");
        }
        Map<String, Object> observation = new LinkedHashMap<>();
        boolean useful = !publicContext.isEmpty() || !state.discoveredCandidateLeads.isEmpty();
        observation.put("status", useful ? "SUCCESS" : "PARTIAL");
        observation.put(
                "guidance",
                useful
                        ? "This public-search result is now visible to you. If it is sufficient, answer directly and concisely; the UI renders the verified source links separately, so name only the strongest sources the player actually needs instead of repeating a source-by-source dossier. If selectable BGG cards are still useful, choose a separate catalog action using only the returned candidate leads; public title leads are not yet verified BGG games."
                        : "Public search returned sources but no usable attributed fact or title lead. Choose a materially different retrieval action or answer transparently.");
        if (!publicContext.isEmpty()) {
            observation.put(
                    "publicContextEvidence",
                    publicContext.stream().map(this::publicContextObservation).toList());
        }
        if (!state.discoveredCandidateLeads.isEmpty()) {
            observation.put(
                    "candidateLeads",
                    state.discoveredCandidateLeads.stream()
                            .map(lead -> Map.of(
                                    "name", lead.name(),
                                    "fitObservation", lead.fitObservation(),
                                    "sourceIndexes", lead.sourceIndexes()))
                            .toList());
        }
        state.discoveryAttempted = true;
        state.discoveryPurpose = purpose;
        state.actions.add("DISCOVER_CANDIDATES");
        return ActionOutcome.observation(runtime.observation(observation));
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
        if (verified.isEmpty()) return List.of();
        state.publicContextEvidence.putAll(verified);
        state.publicContextSources = discovery.sources();
        state.sourceCount = Math.max(state.sourceCount, discovery.sources().size());
        state.actions.add("DISCOVERY_PUBLIC_CONTEXT_VERIFIED");
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

    private ActionOutcome lookup(
            JsonNode arguments,
            RecommendationAgentState state,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(arguments, Set.of("bggIds"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1);
        if (!state.legalIds.containsAll(ids)) throw new InvalidAction("ID_NOT_OBSERVED");
        progress.accept(
                ProgressStage.VERIFYING_BGG_CANDIDATES,
                new ProgressFocus(ProgressFocusKind.VERIFIED_GAME_COUNT, List.of(Integer.toString(ids.size()))));
        List<Game> loaded = new ArrayList<>();
        Set<Integer> loadedIds = new LinkedHashSet<>();
        ToolStatus status = ToolStatus.SUCCESS;
        String code = "";
        boolean completedBatch = false;
        int attemptedPages = 0;
        int completedPages = 0;
        long pageBudget = pageBudget(ids.size(), CATALOG_PAGE_SIZE);
        for (int start = 0; start < ids.size(); ) {
            int end = (int) Math.min(
                    (long) ids.size(),
                    (long) start + CATALOG_PAGE_SIZE);
            List<Integer> batch = List.copyOf(ids.subList(start, end));
            state.catalogCalls++;
            attemptedPages++;
            CatalogObservation result;
            try {
                result = runtime.withinDeadline(state, () -> tools.lookupCandidates(batch));
            } catch (RecommendationReActLoop.RunDeadlineExceeded exception) {
                if (!completedBatch) throw exception;
                status = loaded.isEmpty() ? ToolStatus.ERROR : ToolStatus.PARTIAL;
                code = "RUN_DEADLINE_EXCEEDED";
                state.actions.add("LOOKUP_STOPPED:TIME_BUDGET");
                break;
            }
            completedBatch = true;
            completedPages++;
            state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
            if (!result.succeeded()) {
                status = loaded.isEmpty() ? ToolStatus.ERROR : ToolStatus.PARTIAL;
                code = result.code();
                break;
            }
            result.games().forEach(game -> {
                if (loadedIds.add(game.ranking().bggId())) loaded.add(game);
            });
            start = end;
        }
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        loaded.forEach(state::addVerified);
        return ActionOutcome.observation(runtime.observation(Map.of(
                "status", status.name(),
                "code", code,
                "guidance", loaded.isEmpty()
                        ? "No complete BGG details were returned. Try different observed candidates or respond transparently."
                        : "These bounded BGG facts are verified and may support comparison or final selection.",
                "resourceBoundary", Map.of(
                        "pageSize", CATALOG_PAGE_SIZE,
                        "pageBudget", pageBudget,
                        "pagesAttempted", attemptedPages,
                        "pagesCompleted", completedPages),
                "verifiedBggIds", loaded.stream().map(game -> game.ranking().bggId()).toList())));
    }

    private ActionOutcome research(
            JsonNode arguments,
            RecommendationAgentState state,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(arguments, Set.of("bggIds", "question"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1);
        String question = text(arguments.path("question"));
        if (ids.stream().anyMatch(id -> !state.verified.containsKey(id))) {
            throw new InvalidAction("GAME_NOT_VERIFIED");
        }
        progress.accept(
                ProgressStage.RESEARCHING_GAME_FIT,
                new ProgressFocus(
                        ProgressFocusKind.RESEARCH_GAMES,
                        ids.stream()
                                .map(state.verified::get)
                                .map(game -> game.ranking().sourceName())
                                .limit(3)
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
        String observation = runtime.observation(Map.of(
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
                "researchedBggIds", List.copyOf(researchedIds)));
        state.researchAttempted = true;
        state.actions.add("RESEARCH_GAME_FIT");
        if (!webResearchAvailable) state.disableWebResearch(code);
        return ActionOutcome.observation(observation);
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
        state.actions.add("REJECTED_ACTION:" + code);
        return ActionOutcome.rejectedContract(runtime.error(code, guidance, details), code);
    }

    private ActionOutcome rejectedUnavailable(
            RecommendationAgentState state,
            String code,
            String guidance) {
        state.actions.add("REJECTED_ACTION:" + code);
        return ActionOutcome.rejectedUnavailable(runtime.error(code, guidance), code);
    }

    private String invalidActionGuidance(String code) {
        return switch (code) {
            case "REPLY_RECOMMENDATION_REQUIRES_CARDS" ->
                "New candidate recommendations require a candidate-producing typed read so the application can validate and render cards.";
            case "INVALID_JSON" ->
                "Return a fresh action with valid JSON arguments and escape string content correctly.";
            case "REFERENCE_TITLE_TYPE_INVALID" ->
                "title and every alternateTitles item must be a JSON string containing only one exact board-game title spelling from the cited user turn.";
            case "REFERENCE_TITLE_LENGTH_INVALID" ->
                "Copy one non-blank exact board-game title from the cited user turn; do not copy the surrounding request.";
            case "REFERENCE_ALTERNATES_INVALID" ->
                "alternateTitles must be a JSON array of distinct exact localized or original title spellings from the cited user turn.";
            case "REFERENCE_EVIDENCE_INVALID" ->
                "Use exactly one supplied user evidence ID such as U1 in evidence; do not copy the user message into that field.";
            case "RECOMMENDATION_STATE_REQUIRED" ->
                "recommend_games is available only after a card-producing read has returned verified candidates.";
            case "RECOMMENDATION_SELECTION_COUNT_INVALID" ->
                "Select exactly the count required by the current recommend_games schema: the requested count or every available verified candidate when there is a shortfall.";
            case "PREFERENCE_EVIDENCE_NOT_GROUNDED" ->
                "Use the exact evidenceId shown beside the user-authored message that states this hard constraint, or continue without changing the typed profile.";
            case "PREFERENCE_NUMERIC_EVIDENCE_NOT_EXPLICIT" ->
                "Do not translate a qualitative complexity preference into a BGG number. Persist complexity only when the cited user text explicitly states that numeric BGG complexity or weight value.";
            case "PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT" ->
                "A persistent type or interaction filter requires the player to explicitly name that category in an affirmative statement. A companion, setting, mood, inferred audience, or rejected category is not categorical evidence; omit the typed update and keep that context in the natural decision instead.";
            case "PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID" ->
                "Use DIRECT for a cited number. Use INFERRED_GROUP_MEMBER_COUNT only to count stated members when no total is given; otherwise omit the update.";
            case "RECOMMENDATION_EVIDENCE_REQUIRED", "RECOMMENDATION_EVIDENCE_NOT_GROUNDED" ->
                "For every selection, cite one or more observation IDs that belong to that same candidate in current turnState. Use them only as internalEvidenceIds and keep them out of player-facing prose.";
            case "RECOMMENDATION_REPLY_INVALID" ->
                "Write the complete locale-matched playerReply and useful card notes. The application publishes this prose unchanged.";
            case "FINAL_ID_FAILS_HARD_GATES", "FINAL_ID_IS_COMPARISON_REFERENCE" ->
                "Select only IDs listed in turnState.recommendableBggIds; those IDs already satisfy the current typed hard gates.";
            case "NO_MATCH_RELAXATION_NOT_ACTIONABLE" ->
                "Choose exactly one relaxSubject from the current report_no_match schema; it must unlock a verified candidate while every other hard constraint stays unchanged.";
            default -> "Correct the action arguments using the supplied JSON schema and current turnState.";
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

    Map<String, Object> gameObservation(Game game) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("bggId", game.ranking().bggId());
        value.put("name", game.ranking().sourceName());
        putIfKnown(value, "year", game.ranking().publicationYear());
        if (game.details() == null) return value;
        var details = game.details();
        putIfText(value, "officialChineseName", details.officialChineseName());
        List<CandidateObservation> gameObservations = selector.observations(game);
        Map<String, List<String>> observations = gameObservations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> List.of(observationKindCode(observation), observation.value()),
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

    private void requireObject(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw new InvalidAction("ARGUMENT_OBJECT_REQUIRED");
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        List<String> unexpected = new ArrayList<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unexpected.add(field);
        });
        if (!unexpected.isEmpty()) throw InvalidAction.unexpectedArguments(unexpected, allowed);
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

    private List<String> referenceTitles(JsonNode arguments) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        titles.add(referenceTitle(arguments.path("title")));
        if (!arguments.has("alternateTitles")) return List.copyOf(titles);
        JsonNode alternates = arguments.path("alternateTitles");
        if (!alternates.isArray()) {
            throw new InvalidAction("REFERENCE_ALTERNATES_INVALID");
        }
        for (JsonNode alternate : alternates) titles.add(referenceTitle(alternate));
        if (titles.size() != alternates.size() + 1) {
            throw new InvalidAction("REFERENCE_ALTERNATES_INVALID");
        }
        return List.copyOf(titles);
    }

    private String referenceTitle(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("REFERENCE_TITLE_TYPE_INVALID");
        String title = node.asText().strip();
        if (title.isEmpty()) {
            throw new InvalidAction("REFERENCE_TITLE_LENGTH_INVALID");
        }
        return title;
    }

    private String referenceEvidence(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("REFERENCE_EVIDENCE_INVALID");
        String evidence = node.asText().strip();
        if (evidence.isEmpty()) {
            throw new InvalidAction("REFERENCE_EVIDENCE_INVALID");
        }
        return evidence;
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

    private List<String> strings(JsonNode node, int minimumItems) {
        if (!node.isArray() || node.size() < minimumItems) {
            throw new InvalidAction("STRING_LIST_INVALID");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) values.add(text(value));
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

    private List<Integer> ids(JsonNode node, int minimumItems) {
        return ids(node, minimumItems, Integer.MAX_VALUE);
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
        if (distinct.size() != values.size()) throw new InvalidAction(code);
        return distinct;
    }

    private List<BggGameType> optionalGameTypeHints(
            JsonNode arguments,
            RecommendationAgentState state) {
        if (!arguments.has("types")) return List.of();
        List<BggGameType> values = enumValues(
                BggGameType.class, arguments.path("types"), 0, "GAME_TYPES_INVALID");
        if (values.contains(BggGameType.ALL)) throw new InvalidAction("GAME_TYPES_INVALID");
        return values;
    }

    private ProgressFocus browseFocus(
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            List<String> publishers,
            List<String> families) {
        if (!mechanics.isEmpty()) return focus(ProgressFocusKind.CATALOG_MECHANICS, mechanics);
        if (!categories.isEmpty()) return focus(ProgressFocusKind.CATALOG_CATEGORIES, categories);
        if (!families.isEmpty()) return focus(ProgressFocusKind.CATALOG_FAMILIES, families);
        if (!designers.isEmpty()) return focus(ProgressFocusKind.CATALOG_DESIGNERS, designers);
        if (!publishers.isEmpty()) return focus(ProgressFocusKind.CATALOG_PUBLISHERS, publishers);
        return null;
    }

    private ProgressFocus focus(ProgressFocusKind kind, List<String> values) {
        return new ProgressFocus(kind, values.stream().limit(3).toList());
    }

    private int publicationCount(JsonNode arguments, ConversationRequest request) {
        if (!arguments.has("requestedCount")) {
            return properties.resultCount();
        }
        int requestedCount = integer(
                arguments.path("requestedCount"),
                1,
                properties.modelCandidateLimit(),
                "REQUESTED_COUNT_OUT_OF_RANGE");
        if (!arguments.has("evidence")) {
            throw new InvalidAction(
                    "REQUESTED_COUNT_EVIDENCE_REQUIRED",
                    "An explicit requestedCount must cite the current user turn in evidence; omit requestedCount when the player stated no count.");
        }
        String evidenceId = text(arguments.path("evidence"));
        evidenceReview.requireCurrentTurnUserEvidence(evidenceId, request);
        return requestedCount;
    }

    private ActionOutcome preparePublication(
            String observation,
            RecommendationAgentState state,
            List<Game> candidates,
            int requestedCount) {
        Set<Integer> recommendable = new LinkedHashSet<>(runtime.recommendableIds(state));
        List<Integer> candidateIds = candidates.stream()
                .map(game -> game.ranking().bggId())
                .filter(recommendable::contains)
                .distinct()
                .toList();
        candidateIds = candidateIds.stream()
                .filter(id -> {
                    Game game = state.verified.get(id);
                    return game != null && !narrativeObservations(game, state.research).isEmpty();
                })
                .toList();
        if (!candidateIds.isEmpty()) {
            state.pendingPublicationSeed = new PublicationSeed(
                    candidateIds,
                    state.comparisonReferenceIds.stream().toList(),
                    requestedCount);
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

        static InvalidAction unexpectedArguments(
                java.util.Collection<String> unexpected,
                java.util.Collection<String> allowed) {
            List<String> unexpectedFields = unexpected.stream().distinct().sorted().toList();
            List<String> allowedFields = allowed.stream().distinct().sorted().toList();
            return new InvalidAction(
                    "UNEXPECTED_ARGUMENT",
                    "Remove the unsupported fields listed in unexpectedArguments from this object. "
                            + "Use only fields listed in allowedArguments at this object boundary; do not relocate "
                            + "a value unless the current action schema names its destination.",
                    Map.of(
                            "unexpectedArguments", unexpectedFields,
                            "allowedArguments", allowedFields));
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
