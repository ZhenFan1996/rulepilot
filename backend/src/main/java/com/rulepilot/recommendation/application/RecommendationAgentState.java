package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mutable state for one bounded recommendation conversation turn. */
final class RecommendationAgentState {

    static final int MAX_VERIFIED_GAMES = 8;
    static final int MAX_OBSERVED_CANDIDATES = 16;

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
    final Map<String, Object> finalResponseDecisionFacts = new LinkedHashMap<>();
    Research research = Research.empty();
    CandidateComparison comparison;
    final List<String> actions = new ArrayList<>();
    boolean webResearchAvailable;
    NamedGamePurpose namedGamePurpose;
    boolean unresolvedPlayerTitle;
    int referenceResolutionAttempts;
    boolean titleInspectionAttempted;
    boolean catalogBrowseAttempted;
    boolean discoveryAttempted;
    boolean discoveryProducedVerifiedGames;
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

    void reconsiderSelectionAfterPreferenceUpdate() {
        // Verified BGG facts remain valid, but every selection/retrieval decision derived from the old
        // profile is provisional. Previously shown cards become eligible again under the corrected profile;
        // only explicit exclusions remain excluded. Reopen bounded candidate reads and discard fit research
        // whose question may have been framed around the superseded preference set.
        boolean selectionWorkObserved = titleInspectionAttempted
                || catalogBrowseAttempted
                || discoveryAttempted
                || !verified.isEmpty()
                || !research.games().isEmpty();
        previouslyShownIds.clear();
        titleInspectionAttempted = false;
        catalogBrowseAttempted = false;
        discoveryAttempted = false;
        discoveryProducedVerifiedGames = false;
        researchAttempted = false;
        research = Research.empty();
        if (selectionWorkObserved) {
            actions.add("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE");
        }
    }

    long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
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
}
