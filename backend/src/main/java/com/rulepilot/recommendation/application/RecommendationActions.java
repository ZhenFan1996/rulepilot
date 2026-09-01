package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL;
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
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataStatus;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataValue;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataCriterion;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataDimension;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.SelectionEligibility;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.RelationshipKind;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.ResolvedRelationship;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.CandidateClaim;
import com.rulepilot.recommendation.CandidateObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CatalogSelectionCriterion;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CatalogSelectionDimension;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CatalogSelectionIntent;
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
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.CanonicalMetadataObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.DiscoveryObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ReferenceObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ResearchObservation;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.TitleHypothesis;
import com.rulepilot.recommendation.application.BoardGameRecommendationTools.ToolStatus;
import com.rulepilot.recommendation.application.RecommendationEvidenceReview.PreferenceUpdatePlan;
import com.rulepilot.recommendation.application.RecommendationEvidenceReview.UserEvidence;
import com.rulepilot.recommendation.application.RecommendationAgentState.NamedGamePurpose;
import com.rulepilot.recommendation.application.RecommendationAgentState.DiscoveryPurpose;
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
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the eleven allow-listed recommendation actions over application-owned tools. */
final class RecommendationActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);
    private static final int MAX_PUBLISHER_DESCRIPTION_CONTEXT_CODE_POINTS = 800;
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
                case IDENTITY_REPLY_TOOL -> identityReply(arguments, state, locale);
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
            state.actions.add("REJECTED_ACTION:ACTION_UNAVAILABLE");
            return ActionOutcome.executionRejected(runtime.error(
                    "ACTION_UNAVAILABLE",
                    "The action failed. Choose another useful action or respond transparently."));
        }
    }

    private ActionOutcome reply(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale) {
        requireObject(arguments, Set.of("playerReply"), Set.of("referencedBggIds", "preferenceUpdates"));
        String playerReply = playerReply(arguments);
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
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
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
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

    private ActionOutcome identityReply(
            JsonNode arguments,
            RecommendationAgentState state,
            String locale) {
        IdentityStatus status = enumValue(
                IdentityStatus.class,
                arguments.path("status"),
                "IDENTITY_STATUS_INVALID");
        if (status == IdentityStatus.VERIFIED) {
            requireObject(
                    arguments,
                    Set.of("status", "entityKind", "entityNames", "playerReply"),
                    Set.of());
            IdentityKind kind = enumValue(
                    IdentityKind.class,
                    arguments.path("entityKind"),
                    "IDENTITY_KIND_INVALID");
            List<String> names = strings(arguments.path("entityNames"), 1, 4, 1, 160);
            if (!state.hasVerifiedIdentity()
                    || !kind.name().equals(state.discoveredRelationshipKind.name())
                    || !names.equals(state.discoveredRelationshipNames)) {
                throw new InvalidAction("IDENTITY_NOT_VERIFIED");
            }
        } else {
            List<Integer> expectedContextIds = state.verifiedIdentityContextIds();
            Set<String> required = expectedContextIds.isEmpty()
                    ? Set.of("status", "playerReply")
                    : Set.of("status", "contextBggIds", "playerReply");
            requireObject(arguments, required, Set.of());
            List<Integer> contextIds = expectedContextIds.isEmpty()
                    ? List.of()
                    : ids(arguments.path("contextBggIds"), expectedContextIds.size(), expectedContextIds.size());
            if (state.hasVerifiedIdentity()
                    || state.discoveryPurpose != DiscoveryPurpose.IDENTITY_ONLY
                    || !state.discoveryAttempted
                    || !new LinkedHashSet<>(contextIds).equals(new LinkedHashSet<>(expectedContextIds))) {
                throw new InvalidAction("IDENTITY_UNRESOLVED_STATE_INVALID");
            }
        }
        String playerReply = playerReply(arguments);
        state.actions.add(status == IdentityStatus.VERIFIED
                ? "REPLY_TO_USER"
                : "REPLY_TO_USER:IDENTITY_UNRESOLVED");
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
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
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
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
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
                        selector.fitClaims(
                                game,
                                preferencePlan.profile(),
                                state.catalogSelectionIntent,
                                runtime.chinese(locale))))
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
        return internalEvidenceIds;
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
        requireObject(
                arguments,
                Set.of("title", "purpose", "evidence"),
                Set.of("alternateTitles", "playerReply", "reason"));
        List<String> titles = referenceTitles(arguments);
        String evidenceId = referenceEvidence(arguments.path("evidence"));
        evidenceReview.requireUserEvidence(evidenceId, request);
        NamedGamePurpose purpose = enumValue(
                NamedGamePurpose.class, arguments.path("purpose"), "NAMED_GAME_PURPOSE_INVALID");
        TargetCompletion completion = targetCompletion(arguments, purpose);
        progress.accept(ProgressStage.READING_GAME_DETAILS);
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
                    completion,
                    result,
                    reusedVerifiedReference,
                    state,
                    locale,
                    progress);
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
                                "The player explicitly chose this verified game as the target. Finish with recommend_games so the application can render the verified, selectable target card. Do not inspect unrelated candidates or stop with plain text. Persist later explicit preference corrections only from cited user-message evidence; never infer a preference from these game facts.";
                            case DISCUSSION_SUBJECT, IDENTITY_ONLY ->
                                "Use only the observed BGG facts below. Continue the declared purpose, and persist any later explicit preference correction only from cited user-message evidence; never infer it from these game facts.";
                        }
                        : state.referenceResolutionAttempts < MAX_REFERENCE_RESOLUTION_ATTEMPTS
                                ? "This player-authored span did not resolve as a game title. If the request may instead describe a creator/person alias, award, list, or another external relationship, use public discovery when available rather than asking the player to supply the answer. Otherwise resolve a materially different player-authored title correction, ask for a genuinely missing identity detail, or respond transparently."
                                : "The bounded exact reference-resolution attempts did not uniquely resolve a title. Ask for the missing identity detail or respond transparently; do not invent another variant.",
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
            TargetCompletion completion,
            ReferenceObservation result,
            boolean reusedVerifiedReference,
            RecommendationAgentState state,
            String locale,
            Consumer<ProgressStage> progress) {
        int bggId = selected.ranking().bggId();
        if (state.excludedIds.contains(bggId)) throw new InvalidAction("FINAL_ID_EXCLUDED");
        commitReferenceOutcome(result, NamedGamePurpose.TARGET_GAME, reusedVerifiedReference, state);
        RecommendationReplyPart reason = modelRecommendationPart(
                bggId,
                ReplyPartRole.WHY_FIT,
                completion.reason());
        String completeMessage = completion.playerReply() + "\n\n" + completion.reason();
        state.finalResponseGameIds.add(bggId);
        progress.accept(ProgressStage.COMPOSING_RESPONSE);
        state.actions.add("RECOMMEND_GAMES");
        List<RecommendedGame> games = selector.present(
                List.of(selected),
                state.profile,
                List.of(),
                runtime.chinese(locale),
                state.research).stream()
                .map(game -> new RecommendedGame(
                        game.game(),
                        game.matches(),
                        game.tradeoffs(),
                        game.reasons(),
                        game.claims(),
                        List.of(reason)))
                .toList();
        return ActionOutcome.terminalRead(response(
                Outcome.RECOMMENDATIONS,
                completeMessage,
                state,
                locale,
                null,
                games,
                null,
                completion.playerReply()));
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

    private ActionOutcome search(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            Consumer<ProgressStage> progress) {
        requireObject(arguments, Set.of("titles"), Set.of("preferenceUpdates"));
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
        List<String> titles = strings(arguments.path("titles"), 1, 8, 2, 120);
        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
        state.catalogCalls += 2;
        CatalogObservation result = runtime.withinDeadline(state, () -> tools.inspectTitles(titles));
        String observation = runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "guidance", result.games().isEmpty()
                        ? "The one bounded title-inspection attempt returned no match and is now complete. Use public discovery when available, make one broad catalog browse, ask only if needed, or respond transparently; do not inspect titles again in this run."
                        : "Title identity and bounded BGG details are already verified. Do not look them up again; compare turnState and finish when the slate is useful.",
                "verifiedBggIds", result.games().stream().map(game -> game.ranking().bggId()).toList()));
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
        state.titleInspectionAttempted = true;
        state.actions.add("SEARCH_BGG_BY_NAME");
        state.actions.add("LOOKUP_BGG_CANDIDATES");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        result.games().forEach(state::addVerified);
        return ActionOutcome.observation(observation);
    }

    private ActionOutcome browse(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            Consumer<ProgressStage> progress) {
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
                        "sort",
                        "limit",
                        "offset",
                        "preferenceUpdates",
                        "catalogIntentUpdate"));
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
        DiscoveryPurpose purpose = arguments.has("purpose")
                ? enumValue(DiscoveryPurpose.class, arguments.path("purpose"), "CATALOG_PURPOSE_INVALID")
                : DiscoveryPurpose.SELECTABLE_CARDS;
        CatalogIntentPlan catalogIntentPlan = planCatalogIntent(arguments, state, request);
        if (purpose == DiscoveryPurpose.IDENTITY_ONLY && catalogIntentPlan.updatePresent()) {
            throw new InvalidAction("IDENTITY_CATALOG_INTENT_INVALID");
        }
        CatalogSelectionIntent requestedIntent = purpose == DiscoveryPurpose.SELECTABLE_CARDS
                ? catalogIntentPlan.intent()
                : CatalogSelectionIntent.empty();
        progress.accept(ProgressStage.SEARCHING_BGG_CATALOG);
        CatalogSelectionIntent queryIntent = requestedIntent;
        if (catalogIntentPlan.requiresCanonicalization()) {
            state.catalogCalls++;
            CanonicalMetadataObservation canonicalObservation = runtime.withinDeadline(
                    state,
                    () -> tools.canonicalizeMetadata(requestedIntent.requiredCriteria().stream()
                            .map(RecommendationActions::catalogMetadataCriterion)
                            .toList()));
            queryIntent = canonicalCatalogIntent(requestedIntent, canonicalObservation);
        }
        List<BggGameType> requestedTypes = optionalGameTypeHints(arguments, state);
        List<BggGameType> types = preferencePlan.profile().type() == BggGameType.ALL
                ? requestedTypes
                : List.of(preferencePlan.profile().type());
        List<String> categories = mergedCatalogFilters(
                optionalStrings(arguments, "categories", 5, 120),
                queryIntent,
                CatalogSelectionDimension.CATEGORY);
        List<String> mechanics = mergedCatalogFilters(
                optionalStrings(arguments, "mechanics", 5, 120),
                queryIntent,
                CatalogSelectionDimension.MECHANIC);
        List<String> designers = mergedCatalogFilters(
                optionalStrings(arguments, "designers", 3, 120),
                queryIntent,
                CatalogSelectionDimension.DESIGNER);
        List<String> publishers = mergedCatalogFilters(
                optionalStrings(arguments, "publishers", 5, 120),
                queryIntent,
                CatalogSelectionDimension.PUBLISHER);
        List<String> families = mergedCatalogFilters(
                optionalStrings(arguments, "families", 5, 120),
                queryIntent,
                CatalogSelectionDimension.FAMILY);
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
        String textQuery = arguments.has("textQuery")
                ? text(arguments.path("textQuery"), 1, 240).strip()
                : null;
        CatalogSort sort = arguments.has("sort")
                ? enumValue(CatalogSort.class, arguments.path("sort"), "CATALOG_SORT_INVALID")
                : CatalogSort.RANK;
        if (sort == CatalogSort.RELEVANCE && textQuery == null) {
            throw new InvalidAction("CATALOG_TEXT_QUERY_REQUIRED");
        }
        int offset = arguments.has("offset")
                ? integer(arguments.path("offset"), 0, 200, "CATALOG_OFFSET_INVALID")
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
                        || offset != 0)) {
            throw new InvalidAction(
                    "IDENTITY_CATALOG_QUERY_INVALID",
                    "For a creator identity check, supply exactly one designer and no category or mechanic filters.");
        }
        int limit = arguments.has("limit")
                ? integer(arguments.path("limit"), 1, MAX_VERIFIED_GAMES, "LIMIT_OUT_OF_RANGE")
                : Math.min(properties.modelCandidateLimit(), MAX_VERIFIED_GAMES);
        int eligibilityLimit = purpose == DiscoveryPurpose.SELECTABLE_CARDS
                ? Math.min(MAX_VERIFIED_GAMES, Math.max(limit, properties.resultCount()))
                : limit;
        Set<Integer> unavailableCandidateIds = new LinkedHashSet<>(state.excludedIds);
        // A comparison reference is evidence for the decision, never a selectable recommendation candidate.
        // Its ownership role survives preference changes and must be excluded before the catalog applies LIMIT.
        unavailableCandidateIds.addAll(state.comparisonReferenceIds);
        boolean selectionViewChanges = preferencePlan.profileUpdated()
                || !state.sameCatalogSelection(queryIntent);
        if (!selectionViewChanges) unavailableCandidateIds.addAll(state.previouslyShownIds);
        SelectionEligibility selectionEligibility = selectionEligibility(
                preferencePlan.profile(), unavailableCandidateIds);
        // The catalog applies the same deterministic hard gates before LIMIT and returns that exact DISCOVERY
        // payload. The selector below repeats them at the publication boundary, while the model sees only the
        // bounded eligible slate it needs for this turn.
        int catalogLimit = eligibilityLimit;
        state.catalogCalls++;
        CatalogObservation result = runtime.withinDeadline(
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
                        offset,
                        selectionEligibility));
        List<Game> eligible = result.succeeded()
                ? selector.eligible(
                        result.games(),
                        preferencePlan.profile(),
                        queryIntent,
                        unavailableCandidateIds,
                        eligibilityLimit)
                : List.of();
        List<String> verifiedIdentityNames = purpose == DiscoveryPurpose.IDENTITY_ONLY && !designers.isEmpty()
                ? result.games().stream()
                    .filter(game -> game.details() != null)
                    .filter(game -> designers.stream().allMatch(expected -> game.details().designers().stream()
                            .anyMatch(actual -> actual.equalsIgnoreCase(expected))))
                    .findFirst()
                    .map(game -> designers.stream()
                            .map(expected -> game.details().designers().stream()
                                    .filter(actual -> actual.equalsIgnoreCase(expected))
                                    .findFirst()
                                    .orElseThrow())
                            .toList())
                    .orElse(List.of())
                : List.of();
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
        appliedFilters.put("sort", sort);
        appliedFilters.put("offset", offset);
        appliedFilters.put(
                "requiredSelectionCriteria",
                queryIntent.requiredCriteria().stream()
                        .map(criterion -> Map.of(
                                "dimension", criterion.dimension().name(),
                                "value", criterion.value()))
                        .toList());
        String observation = runtime.observation(Map.of(
                "status", result.succeeded() ? "SUCCESS" : "ERROR",
                "code", result.code(),
                "guidance", eligible.isEmpty()
                        ? purpose == DiscoveryPurpose.IDENTITY_ONLY && state.webResearchAvailable
                                ? "The local BGG catalog did not verify that creator identity. Treat the guessed name as disproved for this alias and use public discovery with the original user evidence; do not retry or publish the guess."
                                : "This exact BGG filter query produced no hard-gate-eligible game. If its filters came from a metaphor, mood, or subjective wish rather than literal player-supplied BGG labels, remove all of those inferred filters and browse one varied slate now. Otherwise use materially different verified filters, another capability, or finish transparently; never repeat the same query or guess titles."
                        : "These games match the supplied BGG filters and their listed observations are verified. Finish when the slate is useful, or make one materially different structured query only when the open request still needs it.",
                "appliedFilters", appliedFilters,
                "availableCount", result.availableCount(),
                "verifiedBggIds", eligible.stream().map(game -> game.ranking().bggId()).toList()));
        if (result.succeeded()) {
            evidenceReview.commitPreferenceUpdates(preferencePlan, state);
            if (catalogIntentPlan.updatePresent()) {
                state.replaceCatalogSelectionIntent(queryIntent);
            }
            if (purpose == DiscoveryPurpose.SELECTABLE_CARDS) {
                // Preference and catalog-intent commits can deliberately clear derived selection state. Record
                // the authoritative count only after those resets so the final publication guard sees this read.
                state.lastSelectableAvailableCount = result.availableCount();
            }
        }
        state.catalogBrowseAttempted = true;
        state.discoveryPurpose = purpose;
        state.actions.add("SEARCH_BGG_CATALOG");
        state.sourceCount = Math.max(state.sourceCount, result.sourceCount());
        eligible.forEach(state::addVerified);
        if (!verifiedIdentityNames.isEmpty()) {
            state.discoveredRelationshipKind = designers.size() > 1
                    ? RelationshipKind.DESIGNER_GROUP
                    : RelationshipKind.DESIGNER;
            state.discoveredRelationshipNames = verifiedIdentityNames;
            state.actions.add("CATALOG_IDENTITY_VERIFIED");
        }
        return ActionOutcome.observation(observation);
    }

    private SelectionEligibility selectionEligibility(
            BoardGameRecommendationAgent.RecommendationProfile profile,
            Set<Integer> unavailableBggIds) {
        var playerCount = profile.playerCount() != null && profile.playerCount().hard()
                ? profile.playerCount()
                : null;
        var duration = profile.durationMinutes() != null && profile.durationMinutes().hard()
                ? profile.durationMinutes()
                : null;
        var complexity = profile.complexity() != null && profile.complexity().hard()
                ? profile.complexity()
                : null;
        return new SelectionEligibility(
                playerCount == null ? null : playerCount.minimum(),
                playerCount == null ? null : playerCount.maximum(),
                duration == null ? null : duration.minimum(),
                duration == null ? null : duration.maximum(),
                complexity == null ? null : complexity.minimum(),
                complexity == null ? null : complexity.maximum(),
                List.copyOf(unavailableBggIds));
    }

    private ActionOutcome discover(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(
                arguments,
                Set.of("evidence", "subject", "afterIdentity"),
                Set.of("types"));
        AfterIdentity afterIdentity = enumValue(
                AfterIdentity.class,
                arguments.path("afterIdentity"),
                "AFTER_IDENTITY_INVALID");
        DiscoveryPurpose purpose = afterIdentity == AfterIdentity.RECOMMEND_WITH_CARDS
                ? DiscoveryPurpose.SELECTABLE_CARDS
                : DiscoveryPurpose.IDENTITY_ONLY;
        String evidenceId = text(arguments.path("evidence"), 1, 16);
        evidenceReview.requireUserEvidence(evidenceId, request);
        String query = evidenceReview.preferenceEvidence(request).get(evidenceId);
        String subject = text(arguments.path("subject"), 1, 80).strip();
        List<BggGameType> types = optionalGameTypeHints(arguments, state);
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES);
        state.webResearchCalls++;
        BoardGameRecommendationWebResearch.DiscoveryRequest discoveryRequest =
                new BoardGameRecommendationWebResearch.DiscoveryRequest(
                        query,
                        subject,
                        types,
                        locale,
                        DiscoveryGoal.IDENTITY_ONLY);
        DiscoveryObservation result = runtime.withinDeadline(
                state,
                () -> tools.discoverCandidates(discoveryRequest));
        CandidateDiscovery discovery = result.result().orElse(null);
        if (discovery == null) {
            boolean webResearchAvailable = result.status() != ToolStatus.ERROR
                    && result.status() != ToolStatus.UNAVAILABLE;
            String observation = runtime.observation(Map.of(
                    "status", result.status().name(),
                    "code", result.code(),
                    "guidance", webResearchAvailable
                            ? "Public discovery returned no attributed candidates. Choose another retrieval action or respond transparently."
                            : "Public web research is unavailable for the rest of this run. Use the BGG title, lookup, or catalog actions, or finish transparently; do not retry web research."));
            state.discoveryAttempted = true;
            state.discoveryPurpose = purpose;
            state.actions.add("DISCOVER_CANDIDATES");
            if (!webResearchAvailable) state.disableWebResearch(result.code());
            return ActionOutcome.observation(observation);
        }
        List<BoardGameRecommendationWebResearch.CandidateLead> leads = discovery.candidates().stream()
                .limit(6)
                .toList();
        List<TitleHypothesis> hypotheses = java.util.stream.IntStream.range(0, leads.size())
                .mapToObj(index -> new TitleHypothesis("discovery-" + (index + 1), leads.get(index).name()))
                .toList();
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES);
        CandidateDiscovery canonicalDiscovery = discovery;
        CatalogObservation inspection = null;
        ResolvedRelationship proposedRelationship = discovery.relationship();
        if (proposedRelationship != null
                && (proposedRelationship.kind() == RelationshipKind.DESIGNER
                        || proposedRelationship.kind() == RelationshipKind.DESIGNER_GROUP)) {
            List<String> proposedNames = proposedRelationship.entityNames();
            inspection = searchDesignerGames(proposedNames, state);
            if (!inspection.games().isEmpty()) {
                state.actions.add("SEARCH_BGG_CATALOG");
            }
        }
        if (inspection == null || inspection.games().isEmpty()) {
            state.catalogCalls += 2;
            inspection = runtime.withinDeadline(
                    state,
                    () -> tools.inspectTitleHypotheses(hypotheses));
            state.actions.add("SEARCH_BGG_BY_NAME");
            state.actions.add("LOOKUP_BGG_CANDIDATES");
            canonicalDiscovery = discovery;
        }
        state.sourceCount = Math.max(state.sourceCount, inspection.sourceCount());
        inspection.games().forEach(state::addVerified);
        ResolvedRelationship verifiedRelationship = verifiedRelationship(canonicalDiscovery, inspection, state);
        if (verifiedRelationship != null) {
            state.discoveredRelationshipKind = verifiedRelationship.kind();
            state.discoveredRelationshipNames = verifiedRelationship.entityNames();
            tools.rememberVerifiedIdentity(discoveryRequest, canonicalDiscovery);
        }
        if (!inspection.games().isEmpty()) {
            state.discoveryProducedVerifiedGames = true;
            state.unresolvedPlayerTitle = false;
        }
        state.research = mergeResearch(state.research, discoveryEvidence(canonicalDiscovery, inspection));
        String observation = runtime.observation(Map.of(
                "status", inspection.succeeded() && !inspection.games().isEmpty() ? "SUCCESS" : "PARTIAL",
                "guidance", inspection.games().isEmpty()
                        ? "Public search found source-backed title hypotheses, but none produced complete BGG details. Choose another retrieval action or respond transparently."
                        : purpose == DiscoveryPurpose.SELECTABLE_CARDS
                                ? "The relationship and representative BGG games are verified. The declared remaining goal requires selectable cards, so finish with recommend_games; browse the local catalog once more only if this verified slate cannot answer the request."
                                : "The external relationship and representative BGG facts are verified. Naming the identity is the declared complete goal, so answer it naturally without turning the response into cardless recommendations.",
                "verifiedBggIds", inspection.games().stream().map(game -> game.ranking().bggId()).toList()));
        state.discoveryAttempted = true;
        state.discoveryPurpose = purpose;
        state.actions.add("DISCOVER_CANDIDATES");
        return ActionOutcome.observation(observation);
    }

    private CatalogIntentPlan planCatalogIntent(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request) {
        if (!arguments.has("catalogIntentUpdate")) {
            return CatalogIntentPlan.unchanged(state.catalogSelectionIntent);
        }
        JsonNode update = arguments.path("catalogIntentUpdate");
        requireObject(update, Set.of("operation"), Set.of("evidence", "criteria"));
        CatalogIntentOperation operation = enumValue(
                CatalogIntentOperation.class,
                update.path("operation"),
                "CATALOG_INTENT_OPERATION_INVALID");
        if (operation == CatalogIntentOperation.CLEAR) {
            if (!update.has("evidence") || update.has("criteria")) {
                throw new InvalidAction("CATALOG_INTENT_CLEAR_INVALID");
            }
            evidenceReview.userEvidence(text(update.path("evidence"), 1, 16), request);
            return new CatalogIntentPlan(CatalogSelectionIntent.empty(), true, false);
        }
        if (update.has("evidence") || !update.has("criteria") || !update.path("criteria").isArray()) {
            throw new InvalidAction("CATALOG_INTENT_CRITERIA_REQUIRED");
        }
        JsonNode criteria = update.path("criteria");
        if (criteria.isEmpty() || criteria.size() > 8) {
            throw new InvalidAction("CATALOG_INTENT_CRITERIA_INVALID");
        }
        List<CatalogSelectionCriterion> parsed = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode criterion : criteria) {
            requireObject(criterion, Set.of("dimension", "value", "evidence"), Set.of());
            CatalogSelectionDimension dimension = enumValue(
                    CatalogSelectionDimension.class,
                    criterion.path("dimension"),
                    "CATALOG_INTENT_DIMENSION_INVALID");
            String value = text(criterion.path("value"), 1, 120);
            UserEvidence evidence = evidenceReview.userEvidence(
                    text(criterion.path("evidence"), 1, 16),
                    request);
            if (!unique.add(dimension.name() + "\u0000" + value.toLowerCase(Locale.ROOT))) {
                throw new InvalidAction("CATALOG_INTENT_CRITERIA_INVALID");
            }
            parsed.add(new CatalogSelectionCriterion(
                    dimension,
                    value,
                    evidence.text(),
                    evidence.turn()));
        }
        return new CatalogIntentPlan(new CatalogSelectionIntent(parsed), true, true);
    }

    private List<String> mergedCatalogFilters(
            List<String> explicitFilters,
            CatalogSelectionIntent intent,
            CatalogSelectionDimension dimension) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        explicitFilters.forEach(value -> values.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        intent.requiredCriteria().stream()
                .filter(criterion -> criterion.dimension() == dimension)
                .map(CatalogSelectionCriterion::value)
                .forEach(value -> values.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        int maximum = dimension == CatalogSelectionDimension.DESIGNER ? 3 : 5;
        if (values.size() > maximum) throw new InvalidAction("CATALOG_FILTERS_TOO_MANY");
        return List.copyOf(values.values());
    }

    private CatalogSelectionIntent canonicalCatalogIntent(
            CatalogSelectionIntent requested,
            CanonicalMetadataObservation observation) {
        if (!observation.succeeded()) {
            throw new InvalidAction(
                    "CATALOG_INTENT_VALIDATION_UNAVAILABLE",
                    "The BGG taxonomy lookup is unavailable, so the exact selection direction cannot be committed safely. Finish transparently or try later; never silently relax it.");
        }
        List<CanonicalMetadataValue> values = observation.result().values();
        if (values.size() != requested.requiredCriteria().size()) {
            throw new InvalidAction("CATALOG_INTENT_VALIDATION_UNAVAILABLE");
        }
        List<CatalogSelectionCriterion> canonical = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            CatalogSelectionCriterion criterion = requested.requiredCriteria().get(index);
            CanonicalMetadataValue value = values.get(index);
            if (value.dimension() != catalogMetadataDimension(criterion.dimension())
                    || !value.requestedValue().equals(criterion.value())) {
                throw new InvalidAction("CATALOG_INTENT_VALIDATION_UNAVAILABLE");
            }
            if (value.status() == CanonicalMetadataStatus.NOT_FOUND) {
                throw new InvalidAction(
                        "CATALOG_INTENT_NOT_CANONICAL",
                        "An exact BGG selection criterion is not a canonical catalog value. Correct the typed value or finish transparently; never silently relax it.");
            }
            if (value.status() == CanonicalMetadataStatus.AMBIGUOUS) {
                throw new InvalidAction(
                        "CATALOG_INTENT_AMBIGUOUS",
                        "An exact BGG selection criterion maps to more than one authoritative spelling. Correct it or finish transparently; never guess.");
            }
            canonical.add(new CatalogSelectionCriterion(
                    criterion.dimension(),
                    value.canonicalValue(),
                    criterion.sourceText(),
                    criterion.confirmedTurn()));
        }
        return new CatalogSelectionIntent(canonical);
    }

    private static CatalogMetadataCriterion catalogMetadataCriterion(CatalogSelectionCriterion criterion) {
        return new CatalogMetadataCriterion(
                catalogMetadataDimension(criterion.dimension()),
                criterion.value());
    }

    private static CatalogMetadataDimension catalogMetadataDimension(CatalogSelectionDimension dimension) {
        return switch (dimension) {
            case CATEGORY -> CatalogMetadataDimension.CATEGORY;
            case MECHANIC -> CatalogMetadataDimension.MECHANIC;
            case FAMILY -> CatalogMetadataDimension.FAMILY;
            case DESIGNER -> CatalogMetadataDimension.DESIGNER;
            case PUBLISHER -> CatalogMetadataDimension.PUBLISHER;
        };
    }

    private CatalogObservation searchDesignerGames(
            List<String> designers, RecommendationAgentState state) {
        state.catalogCalls++;
        return runtime.withinDeadline(
                state,
                () -> tools.searchCatalog(
                        List.of(),
                        List.of(),
                        List.of(),
                        designers,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        CatalogSort.RANK,
                        MAX_VERIFIED_GAMES,
                        0));
    }

    private ResolvedRelationship verifiedRelationship(
            CandidateDiscovery discovery,
            CatalogObservation inspection,
            RecommendationAgentState state) {
        ResolvedRelationship relationship = discovery.relationship();
        if (relationship == null || relationship.kind() == RelationshipKind.OTHER) {
            state.actions.add("DISCOVERY_RELATIONSHIP_REJECTED:MISSING_OR_OTHER");
            return null;
        }
        Set<Integer> sourceIndexes = discovery.sources().stream()
                .map(Source::index)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!sourceIndexes.containsAll(relationship.sourceIndexes())) {
            state.actions.add("DISCOVERY_RELATIONSHIP_REJECTED:SOURCE");
            return null;
        }
        List<String> canonicalNames = switch (relationship.kind()) {
            case DESIGNER -> inspection.games().stream()
                    .filter(game -> game.details() != null)
                    .flatMap(game -> game.details().designers().stream())
                    .filter(name -> name.equalsIgnoreCase(relationship.entityNames().getFirst()))
                    .findFirst()
                    .map(List::of)
                    .orElse(null);
            case DESIGNER_GROUP -> inspection.games().stream()
                    .filter(game -> game.details() != null)
                    .map(game -> relationship.entityNames().stream()
                            .map(expected -> game.details().designers().stream()
                                    .filter(actual -> actual.equalsIgnoreCase(expected))
                                    .findFirst()
                                    .orElse(null))
                            .toList())
                    .filter(names -> names.stream().noneMatch(Objects::isNull))
                    .findFirst()
                    .orElse(null);
            case GAME -> inspection.games().stream()
                    .flatMap(game -> java.util.stream.Stream.of(
                            game.ranking().sourceName(),
                            game.details() == null ? null : game.details().name(),
                            game.details() == null ? null : game.details().officialChineseName()))
                    .filter(Objects::nonNull)
                    .filter(name -> name.equalsIgnoreCase(relationship.entityNames().getFirst()))
                    .findFirst()
                    .map(List::of)
                    .orElse(null);
            case OTHER -> null;
        };
        if (canonicalNames == null) {
            state.actions.add("DISCOVERY_RELATIONSHIP_REJECTED:BGG_ENTITY");
            return null;
        }
        state.actions.add("DISCOVERY_RELATIONSHIP_VERIFIED");
        return new ResolvedRelationship(
                relationship.kind(), canonicalNames, relationship.sourceIndexes());
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
        progress.accept(ProgressStage.RESEARCHING_GAME_FIT);
        state.webResearchCalls++;
        List<BoardGameRecommendationWebResearch.Candidate> candidates = ids.stream()
                .map(state.verified::get)
                .map(selector::researchCandidate)
                .toList();
        ResearchObservation result = runtime.withinDeadline(
                state,
                () -> tools.researchGameFit(candidates, locale, question));
        Research added = result.result().orElse(Research.empty());
        Research merged = mergeResearch(state.research, added);
        boolean webResearchAvailable = result.status() != ToolStatus.ERROR
                && result.status() != ToolStatus.UNAVAILABLE;
        String observation = runtime.observation(Map.of(
                "status", result.status().name(),
                "code", result.code(),
                "guidance", added.games().isEmpty()
                        ? webResearchAvailable
                                ? "No attributed experience evidence was returned. Do not invent it."
                                : "Public web research is unavailable for the rest of this run. Use verified BGG facts or finish transparently; do not retry web research."
                        : "Use these attributed observations as reported experience, distinct from BGG facts.",
                "researchedBggIds", added.games().stream().map(GameResearch::bggId).toList()));
        state.researchAttempted = true;
        state.actions.add("RESEARCH_GAME_FIT");
        if (!webResearchAvailable) state.disableWebResearch(result.code());
        state.research = merged;
        return ActionOutcome.observation(observation);
    }

    private ActionOutcome recommend(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            Consumer<ProgressStage> progress) {
        requireObject(
                arguments,
                Set.of("selections", "requestedCount", "playerReply"),
                Set.of("referenceBggIds"));
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
        if (availableCount < requestedCount && state.lastSelectableAvailableCount > availableCount) {
            throw new InvalidAction(
                    "MORE_VERIFIED_CANDIDATES_AVAILABLE",
                    "The catalog reports more eligible games than are currently verified. Browse from the beginning of the current filtered result before claiming a shortfall.");
        }
        if (selections.isEmpty()
                || selections.size() > state.maximumRecommendationResults
                || selections.size() != expectedSelectionCount) {
            throw new InvalidAction("SELECTION_COUNT_INVALID");
        }
        List<Game> selected = new ArrayList<>();
        List<RecommendationReplyPart> replyParts = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode selection : selections) {
            requireObject(
                    selection,
                    Set.of("bggId", "reason"),
                    Set.of("tradeoff"));
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
            if (!state.targetGameIds.contains(id)
                    && !selector.eligible(game, state.profile, state.catalogSelectionIntent)) {
                throw new InvalidAction("FINAL_ID_FAILS_HARD_GATES");
            }
            selected.add(game);
            replyParts.add(modelRecommendationPart(
                    id,
                    ReplyPartRole.WHY_FIT,
                    text(selection.path("reason"), 1, 280)));
            if (selection.has("tradeoff")) {
                replyParts.add(modelRecommendationPart(
                        id,
                        ReplyPartRole.TRADEOFF,
                        text(selection.path("tradeoff"), 1, 220)));
            }
        }
        Set<Integer> selectedIds = selected.stream()
                .map(game -> game.ranking().bggId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String lead = playerFacingText(arguments.path("playerReply")).strip();
        if (lead.codePointCount(0, lead.length()) > 500) {
            throw new InvalidAction("RECOMMENDATION_REPLY_LEAD_INVALID");
        }
        String completeMessage = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(lead),
                        replyParts.stream().map(part -> part.claim().text()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        RecommendationReply reply = new RecommendationReply(lead, List.copyOf(replyParts), completeMessage);
        state.finalResponseGameIds.addAll(selectedIds);
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
                state.catalogSelectionIntent,
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

    private RecommendationReplyPart modelRecommendationPart(
            int bggId,
            ReplyPartRole role,
            String text) {
        return new RecommendationReplyPart(
                role,
                new CandidateClaim(
                        bggId,
                        "recommendationJudgment",
                        CandidateClaim.Type.PREFERENCE_INFERENCE,
                        null,
                        CandidateClaim.Relation.UNKNOWN,
                        text,
                        List.of()));
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
            case "REFERENCE_TITLE_TYPE_INVALID" ->
                "title and every alternateTitles item must be a JSON string containing only one exact board-game title spelling from the cited user turn.";
            case "REFERENCE_TITLE_LENGTH_INVALID" ->
                "Copy only the exact board-game title substring from the cited user turn into title; do not copy the surrounding request. Each title must contain 1 to 160 characters.";
            case "REFERENCE_ALTERNATES_INVALID" ->
                "alternateTitles must be a JSON array with at most two distinct exact localized or original title spellings from the cited user turn.";
            case "REFERENCE_EVIDENCE_INVALID" ->
                "Use exactly one supplied user evidence ID such as U1 in evidence; do not copy the user message into that field.";
            case "TARGET_COMPLETION_REQUIRED" ->
                "A TARGET_GAME resolve must include both playerReply and reason in this same action so the verified target card can be published immediately without another model turn.";
            case "TARGET_REPLY_INVALID" ->
                "For TARGET_GAME, playerReply must be a nonblank locale-matched natural lead of at most 500 characters.";
            case "TARGET_REASON_INVALID" ->
                "For TARGET_GAME, reason must be one nonblank natural explanation of at most 280 characters.";
            case "PREFERENCE_EVIDENCE_NOT_GROUNDED" ->
                "Use the exact evidenceId shown beside the user-authored message that states this hard constraint, or continue without changing the typed profile.";
            case "PREFERENCE_NUMERIC_EVIDENCE_NOT_EXPLICIT" ->
                "Do not translate a qualitative complexity preference into a BGG number. Persist complexity only when the cited user text explicitly states that numeric BGG complexity or weight value.";
            case "PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT" ->
                "A persistent type or interaction filter requires the player to explicitly name that category in an affirmative statement. A companion, setting, mood, inferred audience, or rejected category is not categorical evidence; omit the typed update and keep that context in the natural decision instead.";
            case "PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID" ->
                "Use evidenceClassification DIRECT for an explicitly stated constraint. Only an exact player count strongly implied by a fully described whole group may use CONTEXTUAL_COMPLETE_GROUP; otherwise omit the typed update.";
            case "RECOMMENDATION_MESSAGE_EVIDENCE_NOT_GROUNDED" ->
                "Cite only observation IDs from the selected candidates in current turnState. Keep those IDs out of player-facing prose.";
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
        List<CandidateObservation> gameObservations = selector.observations(game);
        Map<String, List<String>> observations = gameObservations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        CandidateObservation::id,
                        observation -> List.of(observationKindCode(observation), modelContextValue(observation)),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        value.put("observations", observations);
        List<String> truncatedObservationIds = gameObservations.stream()
                .filter(this::truncatedInModelContext)
                .map(CandidateObservation::id)
                .toList();
        if (!truncatedObservationIds.isEmpty()) {
            value.put("truncatedObservationIds", truncatedObservationIds);
        }
        return value;
    }

    private String modelContextValue(CandidateObservation observation) {
        String value = observation.value();
        if (!truncatedInModelContext(observation)) return value;
        int end = value.offsetByCodePoints(0, MAX_PUBLISHER_DESCRIPTION_CONTEXT_CODE_POINTS);
        return value.substring(0, end).stripTrailing() + "…";
    }

    private boolean truncatedInModelContext(CandidateObservation observation) {
        return "publisherDescription".equals(observation.attribute())
                && observation.value().codePointCount(0, observation.value().length())
                        > MAX_PUBLISHER_DESCRIPTION_CONTEXT_CODE_POINTS;
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
        List<Source> sources = discovery.sources();
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

    private List<String> referenceTitles(JsonNode arguments) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        titles.add(referenceTitle(arguments.path("title")));
        if (!arguments.has("alternateTitles")) return List.copyOf(titles);
        JsonNode alternates = arguments.path("alternateTitles");
        if (!alternates.isArray() || alternates.size() > 2) {
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
        if (title.isEmpty() || title.length() > 160) {
            throw new InvalidAction("REFERENCE_TITLE_LENGTH_INVALID");
        }
        return title;
    }

    private String referenceEvidence(JsonNode node) {
        if (!node.isTextual()) throw new InvalidAction("REFERENCE_EVIDENCE_INVALID");
        String evidence = node.asText().strip();
        if (evidence.isEmpty() || evidence.length() > 16) {
            throw new InvalidAction("REFERENCE_EVIDENCE_INVALID");
        }
        return evidence;
    }

    private TargetCompletion targetCompletion(JsonNode arguments, NamedGamePurpose purpose) {
        if (purpose != NamedGamePurpose.TARGET_GAME) return null;
        if (!arguments.has("playerReply") || !arguments.has("reason")) {
            throw new InvalidAction("TARGET_COMPLETION_REQUIRED");
        }
        JsonNode replyNode = arguments.path("playerReply");
        if (!replyNode.isTextual()) throw new InvalidAction("TARGET_REPLY_INVALID");
        String playerReply = replyNode.asText().strip();
        if (playerReply.isEmpty() || playerReply.codePointCount(0, playerReply.length()) > 500) {
            throw new InvalidAction("TARGET_REPLY_INVALID");
        }
        JsonNode reasonNode = arguments.path("reason");
        if (!reasonNode.isTextual()) throw new InvalidAction("TARGET_REASON_INVALID");
        String reason = reasonNode.asText().strip();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 280) {
            throw new InvalidAction("TARGET_REASON_INVALID");
        }
        return new TargetCompletion(playerReply, reason);
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

    private List<String> optionalStrings(
            JsonNode arguments,
            String field,
            int maximumItems,
            int maximumLength) {
        return arguments.has(field)
                ? strings(arguments.path(field), 0, maximumItems, 1, maximumLength)
                : List.of();
    }

    record ActionOutcome(
            ConversationResponse response,
            String observation,
            boolean rejected,
            boolean settledRead,
            boolean argumentsAccepted) {
        static ActionOutcome terminal(ConversationResponse response) {
            return new ActionOutcome(response, "", false, false, true);
        }

        static ActionOutcome terminalRead(ConversationResponse response) {
            return new ActionOutcome(response, "", false, true, true);
        }

        static ActionOutcome observation(String observation) {
            return new ActionOutcome(null, observation, false, true, true);
        }

        static ActionOutcome rejected(String observation) {
            return new ActionOutcome(null, observation, true, false, false);
        }

        static ActionOutcome executionRejected(String observation) {
            return new ActionOutcome(null, observation, true, false, true);
        }
    }

    private record RecommendationReply(
            String lead,
            List<RecommendationReplyPart> parts,
            String completeMessage) {}

    private record TargetCompletion(String playerReply, String reason) {}

    private record CatalogIntentPlan(
            CatalogSelectionIntent intent,
            boolean updatePresent,
            boolean requiresCanonicalization) {
        private static CatalogIntentPlan unchanged(CatalogSelectionIntent current) {
            return new CatalogIntentPlan(current, false, false);
        }
    }

    private enum CatalogIntentOperation {
        REPLACE,
        CLEAR
    }

    private enum IdentityKind {
        DESIGNER,
        DESIGNER_GROUP,
        GAME
    }

    private enum IdentityStatus {
        VERIFIED,
        UNRESOLVED
    }

    private enum AfterIdentity {
        REPLY_WITH_IDENTITY,
        RECOMMEND_WITH_CARDS
    }

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
