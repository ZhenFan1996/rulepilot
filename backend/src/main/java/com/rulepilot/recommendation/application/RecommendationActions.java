package com.rulepilot.recommendation.application;

import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ASK_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.BROWSE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.COMPARE_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.DISCOVER_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.IDENTITY_REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.LOOKUP_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.NO_MATCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.REPLY_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESEARCH_TOOL;
import static com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RESOLVE_TOOL;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_PLAYER_LEAD_CODE_POINTS;
import static com.rulepilot.recommendation.application.RecommendationAgentState.MAX_VERIFIED_GAMES;
import static com.rulepilot.recommendation.application.RecommendationReActLoop.MAX_REFERENCE_RESOLUTION_ATTEMPTS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryGoal;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.GameResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Observation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicContextEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.RelationshipKind;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.ResolvedRelationship;
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
import com.rulepilot.recommendation.application.RecommendationAgentState.CandidateUse;
import com.rulepilot.recommendation.application.RecommendationAgentState.DiscoveryPurpose;
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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements the ten allow-listed recommendation actions over application-owned tools. */
final class RecommendationActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationAgent.class);
    private static final int MAX_PUBLISHER_DESCRIPTION_CONTEXT_CODE_POINTS = 800;
    private static final int MAX_LOCAL_CATALOG_SCAN_RESULTS = 20;
    private static final long READY_TEACHING_LOOKUP_TIMEOUT_MILLIS = 1_000;
    private static final int MAX_LOCAL_CATALOG_SCAN_OFFSET = 200;
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
        try {
            JsonNode arguments = actionJson.readTree(call.argumentsJson());
            return switch (call.name()) {
                case REPLY_TOOL -> reply(arguments, state, request, locale);
                case IDENTITY_REPLY_TOOL -> identityReply(arguments, state, locale);
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
            Set<String> required = new LinkedHashSet<>(
                    Set.of("status", "entityKind", "entityNames", "playerReply"));
            if (state.hasVerifiedPublicContext()) required.add("publicEvidenceIds");
            requireObject(
                    arguments,
                    required,
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
            if (state.hasVerifiedPublicContext()) selectPublicEvidence(arguments, state);
        } else if (status == IdentityStatus.SOURCED_CONTEXT) {
            requireObject(
                    arguments,
                    Set.of("status", "publicEvidenceIds", "playerReply"),
                    Set.of());
            if (state.hasVerifiedIdentity()
                    || !state.hasVerifiedPublicContext()
                    || state.discoveryPurpose != DiscoveryPurpose.IDENTITY_ONLY
                    || !state.discoveryAttempted) {
                throw new InvalidAction("PUBLIC_CONTEXT_STATE_INVALID");
            }
            selectPublicEvidence(arguments, state);
        } else {
            List<Integer> expectedContextIds = state.verifiedIdentityContextIds();
            Set<String> required = expectedContextIds.isEmpty()
                    ? Set.of("status")
                    : Set.of("status", "contextBggIds");
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
        String playerReply = status == IdentityStatus.UNRESOLVED
                ? unresolvedIdentityReply(state, locale)
                : playerReply(arguments);
        if (status == IdentityStatus.UNRESOLVED) {
            state.actions.add(state.webResearchFailureCode.isBlank()
                    ? "IDENTITY_UNRESOLVED:INSUFFICIENT_PUBLIC_EVIDENCE"
                    : "IDENTITY_UNRESOLVED:PUBLIC_RESEARCH_UNAVAILABLE");
        }
        state.actions.add(status == IdentityStatus.VERIFIED || status == IdentityStatus.SOURCED_CONTEXT
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

    private String unresolvedIdentityReply(RecommendationAgentState state, String locale) {
        boolean hasVerifiedGameContext = !state.verifiedIdentityContextIds().isEmpty();
        if (runtime.chinese(locale)) {
            if (!state.webResearchFailureCode.isBlank()) {
                return hasVerifiedGameContext
                        ? "公开资料查询这次没有完成，所以我现在不能可靠确认这个称呼指的是谁。我能确认你提到的游戏条目，但它不足以证明这个称呼的身份关系；这一步可以稍后重试。"
                        : "公开资料查询这次没有完成，所以我现在不能可靠确认这个称呼指的是谁。这不是你的问题有问题；这一步可以稍后重试。";
            }
            return hasVerifiedGameContext
                    ? "我查过公开资料，但没有找到足够证据确认这个称呼指的是谁。我能确认你提到的游戏条目，但它不足以证明这个称呼的身份关系；为了不凭记忆乱猜，我先停在这里。"
                    : "我查过公开资料，但没有找到足够证据确认这个称呼指的是谁。为了不凭记忆乱猜，我先停在这里；你可以补充更精确的名称或一条来源。";
        }
        if (!state.webResearchFailureCode.isBlank()) {
            return hasVerifiedGameContext
                    ? "The public-source lookup did not complete, so I cannot reliably identify this name right now. I could verify the game record you mentioned, but that does not prove the identity relationship; this step can be retried later."
                    : "The public-source lookup did not complete, so I cannot reliably identify this name right now. Your question is valid; this step can be retried later.";
        }
        return hasVerifiedGameContext
                ? "I checked public sources but did not find enough evidence to identify this name. I could verify the game record you mentioned, but that does not prove the identity relationship, so I will not guess from memory."
                : "I checked public sources but did not find enough evidence to identify this name. I will not guess from memory; a more precise name or one source would help narrow it down.";
    }

    private void selectPublicEvidence(JsonNode arguments, RecommendationAgentState state) {
        List<String> selected = strings(arguments.path("publicEvidenceIds"), 1, 4, 1, 16);
        if (selected.stream().anyMatch(id -> !state.publicContextEvidence.containsKey(id))) {
            throw new InvalidAction("PUBLIC_CONTEXT_EVIDENCE_NOT_VERIFIED");
        }
        state.finalResponsePublicEvidenceIds.addAll(selected);
        state.actions.add("CITE_PUBLIC_CONTEXT");
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
                Set.of(
                        "alternateTitles",
                        "playerReply",
                        "continuationGoal",
                        "continuationEvidence",
                        "learningGoal"));
        List<String> titles = referenceTitles(arguments);
        String evidenceId = referenceEvidence(arguments.path("evidence"));
        evidenceReview.requireUserEvidence(evidenceId, request);
        NamedGamePurpose purpose = enumValue(
                NamedGamePurpose.class, arguments.path("purpose"), "NAMED_GAME_PURPOSE_INVALID");
        TargetCompletion completion = targetCompletion(arguments, purpose);
        recordTeachingContinuation(
                arguments,
                state,
                request,
                purpose == NamedGamePurpose.TARGET_GAME);
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
                                "The player explicitly chose this verified game as the target. The target action itself returns its selectable card; do not inspect unrelated candidates. Persist later explicit preference corrections only from cited user-message evidence; never infer a preference from these game facts.";
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
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        int bggId = selected.ranking().bggId();
        if (state.excludedIds.contains(bggId)) throw new InvalidAction("FINAL_ID_EXCLUDED");
        commitReferenceOutcome(result, NamedGamePurpose.TARGET_GAME, reusedVerifiedReference, state);
        if (state.teachingContinuationRequested) {
            observeTeachingContinuations(
                    state,
                    BoardGameRecommendationTools.ToolName.LOOKUP_READY_TEACHING_CONTINUATIONS,
                    List.of(selected),
                    () -> tools.lookupReadyTeachingContinuations(List.of(selected)));
        }
        state.finalResponseGameIds.add(bggId);
        progress.accept(ProgressStage.COMPOSING_RESPONSE, null);
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
                        List.of(),
                        RecommendationContinuationProjection.card(
                                state.teachingContinuations.get(bggId))))
                .toList();
        return ActionOutcome.terminalRead(response(
                Outcome.RECOMMENDATIONS,
                completion.playerReply(),
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

    private void recordTeachingContinuation(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            boolean allowedForAction) {
        boolean declared = arguments.has("continuationGoal")
                || arguments.has("continuationEvidence")
                || arguments.has("learningGoal");
        if (!declared) return;
        if (!allowedForAction) {
            throw new InvalidAction("TEACHING_CONTINUATION_TARGET_REQUIRED");
        }
        if (!arguments.has("continuationGoal") || !arguments.has("continuationEvidence")) {
            throw new InvalidAction("TEACHING_CONTINUATION_EVIDENCE_REQUIRED");
        }
        enumValue(
                TeachingContinuationGoal.class,
                arguments.path("continuationGoal"),
                "TEACHING_CONTINUATION_GOAL_INVALID");
        String evidenceId = text(arguments.path("continuationEvidence"), 1, 32);
        evidenceReview.requireUserEvidence(evidenceId, request);
        String learningGoal = arguments.has("learningGoal")
                ? text(arguments.path("learningGoal"), 1, 400)
                : "";
        if (state.teachingContinuationRequested
                && !Objects.equals(state.teachingLearningGoal, learningGoal)) {
            throw new InvalidAction("TEACHING_CONTINUATION_CONFLICT");
        }
        state.teachingContinuationRequested = true;
        state.teachingLearningGoal = learningGoal;
        state.actions.add("TEACHING_CONTINUATION_REQUESTED");
    }

    private CatalogObservation observeTeachingContinuations(
            RecommendationAgentState state,
            BoardGameRecommendationTools.ToolName toolName,
            List<Game> scope,
            Supplier<CatalogObservation> lookup) {
        List<Integer> scopeIds = scope.stream()
                .map(game -> game.ranking().bggId())
                .distinct()
                .toList();
        state.catalogCalls++;
        CatalogObservation result;
        try {
            result = runtime.withinOptionalDeadline(
                    state,
                    READY_TEACHING_LOOKUP_TIMEOUT_MILLIS,
                    lookup);
        } catch (RecommendationReActLoop.OptionalCapabilityTimeout timeout) {
            result = CatalogObservation.error(toolName, "READY_TEACHING_CATALOG_TIMEOUT");
        }
        boolean hasReadyContinuation = false;
        if (result.succeeded()) {
            hasReadyContinuation = state.recordSuccessfulTeachingContinuationLookup(
                    scopeIds, result.teachingContinuations());
        } else if (result.status() == BoardGameRecommendationTools.ToolStatus.PARTIAL) {
            hasReadyContinuation = state.recordPartialTeachingContinuationLookup(
                    scopeIds, result.teachingContinuations());
        } else {
            state.recordUnavailableTeachingContinuationLookup(scopeIds);
        }
        boolean usableLookup = result.succeeded()
                || result.status() == BoardGameRecommendationTools.ToolStatus.PARTIAL;
        state.actions.add(usableLookup
                ? hasReadyContinuation
                        ? "TEACHING_CONTINUATION_READY"
                        : "TEACHING_CONTINUATION_NOT_FOUND"
                : "TEACHING_CONTINUATION_LOOKUP_UNAVAILABLE");
        return result;
    }

    private ActionOutcome browse(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(
                arguments,
                Set.of("requestedCount"),
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
                        "requestedCount",
                        "requestedCountBasis",
                        "continuationGoal",
                        "continuationEvidence",
                        "learningGoal",
                        "playerLead",
                        "offset",
                        "preferenceUpdates",
                        "candidateUse"));
        PreferenceUpdatePlan preferencePlan =
                evidenceReview.planPreferenceUpdates(arguments, state.profile, request);
        DiscoveryPurpose purpose = arguments.has("purpose")
                ? enumValue(DiscoveryPurpose.class, arguments.path("purpose"), "CATALOG_PURPOSE_INVALID")
                : DiscoveryPurpose.SELECTABLE_CARDS;
        CandidateUse use = candidateUse(
                arguments,
                purpose == DiscoveryPurpose.SELECTABLE_CARDS
                        ? CandidateUse.PUBLISH_CARDS
                        : CandidateUse.CONTINUE_REACT,
                purpose == DiscoveryPurpose.SELECTABLE_CARDS);
        List<BggGameType> requestedTypes = optionalGameTypeHints(arguments, state);
        List<BggGameType> types = preferencePlan.profile().type() == BggGameType.ALL
                ? requestedTypes
                : List.of(preferencePlan.profile().type());
        List<String> categories = optionalStrings(arguments, "categories", 5, 120);
        List<String> mechanics = optionalStrings(arguments, "mechanics", 5, 120);
        List<String> designers = optionalStrings(arguments, "designers", 3, 120);
        List<String> publishers = optionalStrings(arguments, "publishers", 5, 120);
        List<String> families = optionalStrings(arguments, "families", 5, 120);
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
        int publicationCount = publicationCount(arguments, request);
        recordTeachingContinuation(arguments, state, request, true);
        boolean continuationRequested = state.teachingContinuationRequested;
        int eligibilityLimit = use == CandidateUse.CONTINUE_REACT
                ? limit
                : Math.max(limit, publicationCount);
        Set<Integer> unavailableCandidateIds = new LinkedHashSet<>(state.excludedIds);
        unavailableCandidateIds.addAll(state.previouslyShownIds);
        unavailableCandidateIds.addAll(state.comparisonReferenceIds);
        int requestedCandidateCount = Math.max(eligibilityLimit, properties.resultCount());
        int boundedPlanningWindow = Math.min(
                MAX_LOCAL_CATALOG_SCAN_RESULTS,
                requestedCandidateCount
                        + Math.min(
                                Math.max(properties.resultCount(), unavailableCandidateIds.size()),
                                MAX_LOCAL_CATALOG_SCAN_RESULTS));
        int catalogLimit = use == CandidateUse.CONTINUE_REACT
                ? boundedPlanningWindow
                : MAX_LOCAL_CATALOG_SCAN_RESULTS;
        int selectionLimit = continuationRequested
                ? MAX_LOCAL_CATALOG_SCAN_RESULTS
                : eligibilityLimit;
        progress.accept(
                ProgressStage.SEARCHING_BGG_CATALOG,
                browseFocus(categories, mechanics, designers, publishers, families));
        List<Game> scannedGames = new ArrayList<>();
        Set<Integer> scannedIds = new LinkedHashSet<>();
        List<Game> eligible = List.of();
        CatalogObservation result = null;
        int catalogSourceCount = 0;
        int pageOffset = offset;
        int scannedPages = 0;
        boolean scanBudgetReached = false;
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
            if (!page.succeeded()) break;
            completedCatalogPage = true;
            int distinctBefore = scannedIds.size();
            page.games().forEach(game -> {
                if (scannedIds.add(game.ranking().bggId())) scannedGames.add(game);
            });
            eligible = selector.eligible(
                    scannedGames,
                    preferencePlan.profile(),
                    unavailableCandidateIds,
                    selectionLimit);
            if (use == CandidateUse.CONTINUE_REACT
                    || purpose == DiscoveryPurpose.IDENTITY_ONLY
                    || eligible.size() >= eligibilityLimit
                    || page.pageExhausted()
                    || page.games().isEmpty()
                    || scannedIds.size() == distinctBefore) {
                break;
            }
            if (pageOffset > MAX_LOCAL_CATALOG_SCAN_OFFSET - catalogLimit) {
                scanBudgetReached = true;
                state.actions.add("CATALOG_SCAN_STOPPED:ROW_BUDGET");
                break;
            }
            pageOffset += catalogLimit;
        }
        CatalogObservation terminalResult = Objects.requireNonNull(result, "catalog scan must complete one page");
        if (continuationRequested && !eligible.isEmpty()) {
            List<Game> candidatePool = eligible;
            observeTeachingContinuations(
                    state,
                    BoardGameRecommendationTools.ToolName.LOOKUP_READY_TEACHING_CONTINUATIONS,
                    candidatePool,
                    () -> tools.lookupReadyTeachingContinuations(candidatePool));
            eligible = java.util.stream.Stream.concat(
                            candidatePool.stream().filter(game -> state.teachingContinuations.containsKey(
                                    game.ranking().bggId())),
                            candidatePool.stream().filter(game -> !state.teachingContinuations.containsKey(
                                    game.ranking().bggId())))
                    .limit(eligibilityLimit)
                    .toList();
        }
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
        appliedFilters.put("pagesScanned", scannedPages);
        appliedFilters.put("scanBudgetReached", scanBudgetReached);
        String observation = runtime.observation(Map.of(
                "status", completedCatalogPage
                        ? terminalResult.succeeded() ? "SUCCESS" : "PARTIAL"
                        : "ERROR",
                "code", terminalResult.code(),
                "guidance", eligible.isEmpty()
                        ? purpose == DiscoveryPurpose.IDENTITY_ONLY && state.webResearchAvailable
                                ? "The local BGG catalog did not verify that creator identity. Treat the guessed name as disproved for this alias and use public discovery with the original user evidence; do not retry or publish the guess."
                                : "This exact BGG filter query produced no hard-gate-eligible game. If its filters came from a metaphor, mood, or subjective wish rather than literal player-supplied BGG labels, remove all of those inferred filters and browse one varied slate now. Otherwise use materially different verified filters, another capability, or finish transparently; never repeat the same query or guess titles."
                        : "These games match the supplied BGG filters and their listed observations are verified. Finish when the slate is useful, or make one materially different structured query only when the open request still needs it.",
                "appliedFilters", appliedFilters,
                "teachingContinuationRequested", continuationRequested,
                "readyTeachingBggIds", eligible.stream()
                        .map(game -> game.ranking().bggId())
                        .filter(state.teachingContinuations::containsKey)
                        .toList(),
                "verifiedBggIds", eligible.stream().map(game -> game.ranking().bggId()).toList()));
        evidenceReview.commitPreferenceUpdates(preferencePlan, state);
        state.catalogBrowseAttempted = true;
        state.discoveryPurpose = purpose;
        state.actions.add("SEARCH_BGG_CATALOG");
        state.sourceCount = Math.max(state.sourceCount, catalogSourceCount);
        eligible.forEach(state::addVerified);
        if (!verifiedIdentityNames.isEmpty()) {
            state.discoveredRelationshipKind = designers.size() > 1
                    ? RelationshipKind.DESIGNER_GROUP
                    : RelationshipKind.DESIGNER;
            state.discoveredRelationshipNames = verifiedIdentityNames;
            state.actions.add("CATALOG_IDENTITY_VERIFIED");
        }
        return candidateObservation(
                observation,
                state,
                eligible,
                use,
                publicationCount,
                playerLead(arguments));
    }

    private ActionOutcome discover(
            JsonNode arguments,
            RecommendationAgentState state,
            ConversationRequest request,
            String locale,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(
                arguments,
                Set.of("evidence", "subject", "afterIdentity", "requestedCount"),
                Set.of(
                        "types",
                        "candidateUse",
                        "requestedCount",
                        "requestedCountBasis",
                        "playerLead"));
        AfterIdentity afterIdentity = enumValue(
                AfterIdentity.class,
                arguments.path("afterIdentity"),
                "AFTER_IDENTITY_INVALID");
        DiscoveryPurpose purpose = afterIdentity == AfterIdentity.RECOMMEND_WITH_CARDS
                ? DiscoveryPurpose.SELECTABLE_CARDS
                : DiscoveryPurpose.IDENTITY_ONLY;
        CandidateUse use = candidateUse(
                arguments,
                purpose == DiscoveryPurpose.SELECTABLE_CARDS
                        ? CandidateUse.PUBLISH_CARDS
                        : CandidateUse.CONTINUE_REACT,
                purpose == DiscoveryPurpose.SELECTABLE_CARDS);
        int requestedCount = publicationCount(arguments, request);
        String evidenceId = text(arguments.path("evidence"), 1, 16);
        evidenceReview.requireUserEvidence(evidenceId, request);
        String query = evidenceReview.preferenceEvidence(request).get(evidenceId);
        String subject = text(arguments.path("subject"), 1, 80).strip();
        List<BggGameType> types = optionalGameTypeHints(arguments, state);
        progress.accept(ProgressStage.DISCOVERING_CANDIDATES, null);
        state.publicContextEvidence.clear();
        state.publicContextSources = List.of();
        state.finalResponsePublicEvidenceIds.clear();
        state.webResearchCalls++;
        BoardGameRecommendationWebResearch.DiscoveryRequest discoveryRequest =
                new BoardGameRecommendationWebResearch.DiscoveryRequest(
                        query,
                        subject,
                        types,
                        locale,
                        purpose == DiscoveryPurpose.SELECTABLE_CARDS
                                ? DiscoveryGoal.SELECTABLE_CARDS
                                : DiscoveryGoal.IDENTITY_ONLY);
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
        List<PublicContextEvidence> publicContext = recordPublicContext(discovery, state);
        List<BoardGameRecommendationWebResearch.CandidateLead> leads = discovery.candidates().stream()
                .limit(6)
                .toList();
        List<TitleHypothesis> hypotheses = java.util.stream.IntStream.range(0, leads.size())
                .mapToObj(index -> new TitleHypothesis("discovery-" + (index + 1), leads.get(index).name()))
                .toList();
        progress.accept(ProgressStage.VERIFYING_BGG_CANDIDATES, null);
        CandidateDiscovery canonicalDiscovery = discovery;
        CatalogObservation inspection = null;
        ResolvedRelationship proposedRelationship = discovery.relationship();
        if (purpose == DiscoveryPurpose.IDENTITY_ONLY
                && !publicContext.isEmpty()
                && (proposedRelationship == null || proposedRelationship.kind() == RelationshipKind.OTHER)) {
            state.discoveryAttempted = true;
            state.discoveryPurpose = purpose;
            state.actions.add("DISCOVER_CANDIDATES");
            return ActionOutcome.observation(runtime.observation(Map.of(
                    "status", "SUCCESS",
                    "guidance", "The public relationship is source-backed and needs no BGG canonicalization. Finish with SOURCED_CONTEXT, select only supplied public evidence ids, and keep every public factual clause within those statements.",
                    "publicContextEvidence", publicContext.stream()
                            .map(this::publicContextObservation)
                            .toList())));
        }
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
                                ? "The relationship and representative BGG games are verified. Follow the validated candidateUse: publish this slate when it answers the request, research it once when requested, or browse the local catalog when these games are only identity carriers."
                                : "The external relationship and representative BGG facts are verified. Naming the identity is the declared complete goal, so answer it naturally without turning the response into cardless recommendations.",
                "verifiedBggIds", inspection.games().stream().map(game -> game.ranking().bggId()).toList()));
        state.discoveryAttempted = true;
        state.discoveryPurpose = purpose;
        state.actions.add("DISCOVER_CANDIDATES");
        return candidateObservation(
                observation,
                state,
                inspection.games(),
                use,
                requestedCount,
                playerLead(arguments));
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
                .limit(4)
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

    private ActionOutcome lookup(
            JsonNode arguments,
            RecommendationAgentState state,
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(arguments, Set.of("bggIds"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1, MAX_VERIFIED_GAMES);
        if (!state.legalIds.containsAll(ids)) throw new InvalidAction("ID_NOT_OBSERVED");
        progress.accept(
                ProgressStage.VERIFYING_BGG_CANDIDATES,
                new ProgressFocus(ProgressFocusKind.VERIFIED_GAME_COUNT, List.of(Integer.toString(ids.size()))));
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
            BiConsumer<ProgressStage, ProgressFocus> progress) {
        requireObject(arguments, Set.of("bggIds", "question"), Set.of());
        List<Integer> ids = ids(arguments.path("bggIds"), 1, 5);
        String question = text(arguments.path("question"), 1, 300);
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
            case "SELECTIONS_ARRAY_REQUIRED" ->
                "selections must be a native JSON array of selection objects. Never quote or JSON-encode the array as a string.";
            case "RECOMMENDATION_SHORTFALL_REQUIRED" ->
                "The verified hard-eligible set is smaller than the player requested. Return every verified ID once and include the required shortfall object with the exact schema counts and one concrete allowed relaxation reply when offered. Never claim the whole catalog is exhausted.";
            case "RECOMMENDATION_SHORTFALL_UNEXPECTED" ->
                "Omit shortfall because the current verified hard-eligible set can satisfy the requested count.";
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
                "A TARGET_GAME resolve must include the complete natural playerReply in this same action so the verified target card can be published immediately without another model turn.";
            case "TARGET_REPLY_INVALID" ->
                "For TARGET_GAME, playerReply must be a complete nonblank locale-matched natural answer of at most 1200 characters.";
            case "PREFERENCE_EVIDENCE_NOT_GROUNDED" ->
                "Use the exact evidenceId shown beside the user-authored message that states this hard constraint, or continue without changing the typed profile.";
            case "PREFERENCE_NUMERIC_EVIDENCE_NOT_EXPLICIT" ->
                "Do not translate a qualitative complexity preference into a BGG number. Persist complexity only when the cited user text explicitly states that numeric BGG complexity or weight value.";
            case "PREFERENCE_CATEGORICAL_EVIDENCE_NOT_EXPLICIT" ->
                "A persistent type or interaction filter requires the player to explicitly name that category in an affirmative statement. A companion, setting, mood, inferred audience, or rejected category is not categorical evidence; omit the typed update and keep that context in the natural decision instead.";
            case "PREFERENCE_EVIDENCE_CLASSIFICATION_INVALID" ->
                "Use DIRECT for a cited number. Use INFERRED_GROUP_MEMBER_COUNT only to count stated members when no total is given; otherwise omit the update.";
            case "RECOMMENDATION_EVIDENCE_REQUIRED", "RECOMMENDATION_EVIDENCE_NOT_GROUNDED" ->
                "For every selection, cite one to eight observation IDs that belong to that same candidate in current turnState. Use them only as internalEvidenceIds and keep them out of player-facing prose.";
            case "RECOMMENDATION_REPLY_INVALID" ->
                "Write one complete, locale-matched natural playerReply of at most 1200 characters. The application will publish it unchanged and render card annotations separately.";
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
        return response(
                outcome,
                DecisionMode.MODEL_ASSISTED,
                message,
                state,
                locale,
                clarification,
                games,
                shortfall,
                recommendationLead);
    }

    private ConversationResponse response(
            Outcome outcome,
            DecisionMode mode,
            String message,
            RecommendationAgentState state,
            String locale,
            Clarification clarification,
            List<RecommendedGame> games,
            RecommendationShortfall shortfall,
            String recommendationLead) {
        ConversationResponse response = new ConversationResponse(
                outcome,
                mode,
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
                recommendationLead,
                RecommendationContinuationProjection.response(state, games));
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
        List<String> unexpected = new ArrayList<>();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unexpected.add(field);
        });
        if (!unexpected.isEmpty()) throw InvalidAction.unexpectedArguments(unexpected, allowed);
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
        if (!arguments.has("playerReply")) {
            throw new InvalidAction("TARGET_COMPLETION_REQUIRED");
        }
        JsonNode replyNode = arguments.path("playerReply");
        if (!replyNode.isTextual()) throw new InvalidAction("TARGET_REPLY_INVALID");
        String playerReply = replyNode.asText().strip();
        if (playerReply.isEmpty() || playerReply.codePointCount(0, playerReply.length()) > 1200) {
            throw new InvalidAction("TARGET_REPLY_INVALID");
        }
        return new TargetCompletion(playerReply);
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

    private CandidateUse candidateUse(
            JsonNode arguments,
            CandidateUse defaultUse,
            boolean selectableCards) {
        CandidateUse use = arguments.has("candidateUse")
                ? enumValue(CandidateUse.class, arguments.path("candidateUse"), "CANDIDATE_USE_INVALID")
                : defaultUse;
        if (!selectableCards && use != CandidateUse.CONTINUE_REACT) {
            throw new InvalidAction("CANDIDATE_USE_INVALID");
        }
        return use;
    }

    private int publicationCount(JsonNode arguments, ConversationRequest request) {
        if (!arguments.has("requestedCountBasis")) {
            throw new InvalidAction("REQUESTED_COUNT_BASIS_REQUIRED");
        }
        int requestedCount = integer(
                arguments.path("requestedCount"),
                1,
                runtime.maximumRecommendationResults(),
                "REQUESTED_COUNT_OUT_OF_RANGE");
        String basis = text(arguments.path("requestedCountBasis"), 1, 32);
        if ("PRODUCT_DEFAULT".equals(basis)) {
            if (requestedCount != properties.resultCount()) {
                throw new InvalidAction("REQUESTED_COUNT_DEFAULT_INVALID");
            }
            return requestedCount;
        }
        evidenceReview.requireCurrentTurnUserEvidence(basis, request);
        return requestedCount;
    }

    private String playerLead(JsonNode arguments) {
        JsonNode value = arguments.path("playerLead");
        if (!value.isTextual()) return "";
        String lead = value.asText().strip();
        int length = lead.codePointCount(0, lead.length());
        return length >= 1 && length <= MAX_PLAYER_LEAD_CODE_POINTS ? lead : "";
    }

    private ActionOutcome candidateObservation(
            String observation,
            RecommendationAgentState state,
            List<Game> candidates,
            CandidateUse use,
            int requestedCount,
            String playerLead) {
        if (use == CandidateUse.CONTINUE_REACT) return ActionOutcome.observation(observation);
        Set<Integer> recommendable = new LinkedHashSet<>(runtime.recommendableIds(state));
        List<Integer> candidateIds = candidates.stream()
                .map(game -> game.ranking().bggId())
                .filter(recommendable::contains)
                .distinct()
                .toList();
        if (candidateIds.isEmpty()) return ActionOutcome.observation(observation);
        return ActionOutcome.publication(
                observation,
                new PublicationSeed(
                        candidateIds,
                        state.comparisonReferenceIds.stream().toList(),
                        use,
                        requestedCount,
                        playerLead));
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
            PublicationSeed publicationSeed,
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

        static ActionOutcome publication(String observation, PublicationSeed publicationSeed) {
            return new ActionOutcome(
                    null,
                    observation,
                    false,
                    true,
                    publicationSeed,
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

    private record TargetCompletion(String playerReply) {}

    private enum IdentityKind {
        DESIGNER,
        DESIGNER_GROUP,
        GAME
    }

    private enum IdentityStatus {
        VERIFIED,
        SOURCED_CONTEXT,
        UNRESOLVED
    }

    private enum AfterIdentity {
        REPLY_WITH_IDENTITY,
        RECOMMEND_WITH_CARDS
    }

    private enum TeachingContinuationGoal {
        GUIDE_AND_RULE_QA
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
    }
}
