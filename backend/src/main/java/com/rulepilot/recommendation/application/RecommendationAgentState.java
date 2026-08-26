package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.RelationshipKind;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicContextEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mutable state for one bounded recommendation conversation turn. */
final class RecommendationAgentState {

    static final int MAX_VERIFIED_GAMES = 8;
    static final int MAX_OBSERVED_CANDIDATES = 16;
    static final int MAX_PLAYER_LEAD_CODE_POINTS = 480;

    final long startedAtNanos;
    final String modelConfigurationOwner;
    final int maximumRecommendationResults;
    RecommendationProfile profile;
    final Set<Integer> excludedIds;
    final Set<Integer> previouslyShownIds = new LinkedHashSet<>();
    final Set<Integer> legalIds = new LinkedHashSet<>();
    final Map<Integer, String> candidateNames = new LinkedHashMap<>();
    final Map<Integer, Game> verified = new LinkedHashMap<>();
    final Set<Integer> freshVerifiedIds = new LinkedHashSet<>();
    final Map<String, ContextualPreference> contextualPreferences = new LinkedHashMap<>();
    final Set<Integer> targetGameIds = new LinkedHashSet<>();
    final Set<Integer> comparisonReferenceIds = new LinkedHashSet<>();
    final Set<Integer> comparisonSubjectIds = new LinkedHashSet<>();
    final Set<Integer> finalResponseGameIds = new LinkedHashSet<>();
    final Set<String> finalResponseEvidenceIds = new LinkedHashSet<>();
    final Set<String> finalResponsePublicEvidenceIds = new LinkedHashSet<>();
    final Map<String, Object> finalResponseDecisionFacts = new LinkedHashMap<>();
    final Map<Integer, Continuation> teachingContinuations = new LinkedHashMap<>();
    final Set<Integer> teachingContinuationQueriedIds = new LinkedHashSet<>();
    final Set<Integer> teachingContinuationUnavailableIds = new LinkedHashSet<>();
    Research research = Research.empty();
    CandidateComparison comparison;
    final List<String> actions = new ArrayList<>();
    boolean webResearchAvailable;
    NamedGamePurpose namedGamePurpose;
    boolean unresolvedPlayerTitle;
    int referenceResolutionAttempts;
    boolean catalogBrowseAttempted;
    boolean teachingContinuationRequested;
    String teachingLearningGoal = "";
    boolean discoveryAttempted;
    boolean discoveryProducedVerifiedGames;
    DiscoveryPurpose discoveryPurpose;
    RelationshipKind discoveredRelationshipKind;
    List<String> discoveredRelationshipNames = List.of();
    final Map<String, PublicContextEvidence> publicContextEvidence = new LinkedHashMap<>();
    List<Source> publicContextSources = List.of();
    boolean researchAttempted;
    boolean clarificationBlockedByExecutionFailure;
    String webResearchFailureCode = "";
    int modelCalls;
    int actionCalls;
    int catalogCalls;
    int webResearchCalls;
    int sourceCount;

    RecommendationAgentState(
            ConversationRequest request,
            long startedAtNanos,
            String modelConfigurationOwner,
            boolean webResearchConfigured,
            int maximumRecommendationResults) {
        this.startedAtNanos = startedAtNanos;
        this.modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                ? null
                : modelConfigurationOwner.strip();
        this.maximumRecommendationResults = maximumRecommendationResults;
        profile = request.profile();
        excludedIds = new LinkedHashSet<>(request.excludedBggIds());
        previouslyShownIds.addAll(request.shownBggIds());
        comparisonSubjectIds.addAll(request.shownBggIds());
        if (request.focusedBggId() != null) comparisonSubjectIds.add(request.focusedBggId());
        webResearchAvailable = webResearchConfigured;
        request.knownGames().forEach(game -> observeCandidate(
                game.bggId(), game.originalName().isBlank() ? game.name() : game.originalName()));
        request.priorVerifiedGames().forEach(this::restoreVerified);
        legalIds.addAll(request.shownBggIds());
        if (request.focusedBggId() != null) legalIds.add(request.focusedBggId());
    }

    void addVerified(Game game) {
        if (game == null || game.details() == null) return;
        observeCandidate(game.ranking().bggId(), game.ranking().sourceName());
        int bggId = game.ranking().bggId();
        if (!verified.containsKey(bggId) && verified.size() >= MAX_VERIFIED_GAMES) {
            Integer oldestRestored = null;
            for (Integer candidateId : verified.keySet()) {
                if (!freshVerifiedIds.contains(candidateId)) oldestRestored = candidateId;
            }
            if (oldestRestored == null) {
                oldestRestored = verified.keySet().iterator().next();
            }
            if (oldestRestored != null) {
                verified.remove(oldestRestored);
                freshVerifiedIds.remove(oldestRestored);
            }
        }
        verified.put(bggId, game);
        freshVerifiedIds.add(bggId);
    }

    List<Game> verifiedForAgent() {
        return java.util.stream.Stream.concat(
                        freshVerifiedIds.stream().map(verified::get),
                        verified.entrySet().stream()
                                .filter(entry -> !freshVerifiedIds.contains(entry.getKey()))
                                .map(Map.Entry::getValue))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private void restoreVerified(Game game) {
        if (game == null || game.details() == null || verified.size() >= MAX_VERIFIED_GAMES) return;
        observeCandidate(game.ranking().bggId(), game.ranking().sourceName());
        verified.putIfAbsent(game.ranking().bggId(), game);
    }

    void assignNamedGameRole(int bggId, NamedGamePurpose purpose) {
        targetGameIds.remove(bggId);
        comparisonReferenceIds.remove(bggId);
        if (purpose == NamedGamePurpose.TARGET_GAME) targetGameIds.add(bggId);
        if (purpose == NamedGamePurpose.COMPARISON_REFERENCE) comparisonReferenceIds.add(bggId);
        if (purpose == NamedGamePurpose.DISCUSSION_SUBJECT || purpose == NamedGamePurpose.TARGET_GAME) {
            comparisonSubjectIds.add(bggId);
        }
    }

    void observeCandidate(int bggId, String name) {
        legalIds.add(bggId);
        if (candidateNames.containsKey(bggId) || candidateNames.size() < MAX_OBSERVED_CANDIDATES) {
            candidateNames.put(bggId, name == null ? "" : name);
        }
    }

    void disableWebResearch(String code) {
        webResearchAvailable = false;
        webResearchFailureCode = code == null ? "" : code;
        actions.add("WEB_RESEARCH_DEGRADED:" + webResearchFailureCode);
    }

    boolean recordSuccessfulTeachingContinuationLookup(
            List<Integer> queriedBggIds,
            Map<Integer, Continuation> observedContinuations) {
        Set<Integer> scope = queriedBggIds.stream()
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        teachingContinuations.keySet().removeIf(scope::contains);
        observedContinuations.forEach((bggId, continuation) -> {
            if (scope.contains(bggId) && continuation != null && continuation.bggId() == bggId) {
                teachingContinuations.put(bggId, continuation);
            }
        });
        teachingContinuationQueriedIds.addAll(scope);
        teachingContinuationUnavailableIds.removeAll(scope);
        return scope.stream().anyMatch(teachingContinuations::containsKey);
    }

    boolean recordPartialTeachingContinuationLookup(
            List<Integer> queriedBggIds,
            Map<Integer, Continuation> observedContinuations) {
        Set<Integer> scope = queriedBggIds.stream()
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        observedContinuations.forEach((bggId, continuation) -> {
            if (scope.contains(bggId) && continuation != null && continuation.bggId() == bggId) {
                teachingContinuations.put(bggId, continuation);
            }
        });
        scope.forEach(bggId -> {
            if (teachingContinuations.containsKey(bggId)) {
                teachingContinuationQueriedIds.add(bggId);
                teachingContinuationUnavailableIds.remove(bggId);
            } else {
                teachingContinuationUnavailableIds.add(bggId);
            }
        });
        return scope.stream().anyMatch(teachingContinuations::containsKey);
    }

    void recordUnavailableTeachingContinuationLookup(List<Integer> queriedBggIds) {
        queriedBggIds.stream()
                .filter(Objects::nonNull)
                .filter(bggId -> !teachingContinuations.containsKey(bggId))
                .forEach(teachingContinuationUnavailableIds::add);
    }

    void reconsiderSelectionAfterPreferenceUpdate() {
        // Verified BGG facts remain valid, but every selection/retrieval decision derived from the old
        // profile is provisional. Previously shown cards become eligible again under the corrected profile;
        // only explicit exclusions remain excluded. Reopen bounded candidate reads and discard fit research
        // whose question may have been framed around the superseded preference set.
        boolean selectionWorkObserved = catalogBrowseAttempted
                || discoveryAttempted
                || !verified.isEmpty()
                || !research.games().isEmpty();
        previouslyShownIds.clear();
        catalogBrowseAttempted = false;
        researchAttempted = false;
        research = Research.empty();
        if (selectionWorkObserved) {
            actions.add("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE");
        }
    }

    long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    boolean hasVerifiedIdentity() {
        return discoveredRelationshipKind != null && !discoveredRelationshipNames.isEmpty();
    }

    boolean hasVerifiedPublicContext() {
        return !publicContextEvidence.isEmpty();
    }

    List<Integer> verifiedIdentityContextIds() {
        return java.util.stream.Stream.concat(
                        comparisonSubjectIds.stream(),
                        comparisonReferenceIds.stream())
                .distinct()
                .filter(id -> {
                    Game game = verified.get(id);
                    return game != null
                            && game.details() != null
                            && !game.details().designers().isEmpty();
                })
                .toList();
    }

    record ContextualPreference(
            String field,
            String value,
            String evidenceId,
            String evidenceText,
            String reason) {}

    enum NamedGamePurpose {
        TARGET_GAME,
        COMPARISON_REFERENCE,
        DISCUSSION_SUBJECT,
        IDENTITY_ONLY
    }

    enum DiscoveryPurpose {
        IDENTITY_ONLY,
        SELECTABLE_CARDS
    }

    enum CandidateUse {
        PUBLISH_CARDS,
        RESEARCH_THEN_PUBLISH,
        CONTINUE_REACT
    }

    record PublicationSeed(
            List<Integer> candidateBggIds,
            List<Integer> referenceBggIds,
            CandidateUse candidateUse,
            int requestedCount,
            String playerLead) {
        PublicationSeed(
                List<Integer> candidateBggIds,
                List<Integer> referenceBggIds,
                CandidateUse candidateUse,
                int requestedCount) {
            this(candidateBggIds, referenceBggIds, candidateUse, requestedCount, "");
        }

        PublicationSeed {
            candidateBggIds = candidateBggIds == null ? List.of() : List.copyOf(candidateBggIds);
            referenceBggIds = referenceBggIds == null ? List.of() : List.copyOf(referenceBggIds);
            Objects.requireNonNull(candidateUse, "candidateUse is required");
            playerLead = boundedPlayerLead(playerLead);
            if (candidateBggIds.isEmpty()
                    || candidateBggIds.stream().anyMatch(id -> id == null || id <= 0)
                    || candidateBggIds.stream().distinct().count() != candidateBggIds.size()
                    || referenceBggIds.stream().anyMatch(id -> id == null || id <= 0)
                    || referenceBggIds.stream().distinct().count() != referenceBggIds.size()
                    || requestedCount < 1
                    || requestedCount > MAX_VERIFIED_GAMES) {
                throw new IllegalArgumentException("recommendation publication seed is invalid");
            }
        }

        private static String boundedPlayerLead(String value) {
            if (value == null) return "";
            String checked = value.strip();
            int length = checked.codePointCount(0, checked.length());
            return length >= 1 && length <= MAX_PLAYER_LEAD_CODE_POINTS ? checked : "";
        }
    }
}
