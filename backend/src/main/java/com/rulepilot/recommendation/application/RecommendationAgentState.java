package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateLead;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicContextEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mutable state for one recommendation conversation turn. */
final class RecommendationAgentState {

    final long startedAtNanos;
    final String modelConfigurationOwner;
    final RecommendationRunBudget budget;
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
    PublicationSeed pendingPublicationSeed;
    TitleConstraint titleConstraint;
    Research research = Research.empty();
    CandidateComparison comparison;
    final List<String> actions = new ArrayList<>();
    boolean webResearchAvailable;
    NamedGamePurpose namedGamePurpose;
    boolean unresolvedPlayerTitle;
    int referenceResolutionAttempts;
    boolean catalogBrowseAttempted;
    boolean discoveryAttempted;
    DiscoveryPurpose discoveryPurpose;
    List<CandidateLead> discoveredCandidateLeads = List.of();
    final Map<String, PublicContextEvidence> publicContextEvidence = new LinkedHashMap<>();
    List<Source> publicContextSources = List.of();
    boolean researchAttempted;
    boolean clarificationBlockedByExecutionFailure;
    String webResearchFailureCode = "";
    int modelCalls;
    final List<Long> modelCallElapsedMs = new ArrayList<>();
    int actionCalls;
    int catalogCalls;
    int webResearchCalls;
    int sourceCount;

    RecommendationAgentState(
            ConversationRequest request,
            long startedAtNanos,
            String modelConfigurationOwner,
            boolean webResearchConfigured) {
        this(
                request,
                startedAtNanos,
                modelConfigurationOwner,
                webResearchConfigured,
                BoardGameRecommendationProperties.DEFAULT_MAX_TOKENS);
    }

    RecommendationAgentState(
            ConversationRequest request,
            long startedAtNanos,
            String modelConfigurationOwner,
            boolean webResearchConfigured,
            int maxTokens) {
        this.startedAtNanos = startedAtNanos;
        this.modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                ? null
                : modelConfigurationOwner.strip();
        budget = new RecommendationRunBudget(maxTokens);
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
        if (game == null || game.details() == null) return;
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
        candidateNames.put(bggId, name == null ? "" : name);
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
        boolean selectionWorkObserved = catalogBrowseAttempted
                || discoveryAttempted
                || !verified.isEmpty()
                || !research.games().isEmpty();
        previouslyShownIds.clear();
        catalogBrowseAttempted = false;
        researchAttempted = false;
        research = Research.empty();
        pendingPublicationSeed = null;
        if (selectionWorkObserved) {
            actions.add("RECONSIDER_SELECTION_AFTER_PREFERENCE_UPDATE");
        }
    }

    long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    void recordModelCallElapsed(long startedAtNanos) {
        modelCallElapsedMs.add(Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000));
    }

    boolean hasVerifiedPublicContext() {
        return !publicContextEvidence.isEmpty();
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

    record TitleConstraint(String value, String evidenceId) {
        TitleConstraint {
            value = normalize(value);
            evidenceId = Objects.requireNonNull(evidenceId, "title constraint evidence is required").strip();
            if (value.isEmpty() || evidenceId.isEmpty()) {
                throw new IllegalArgumentException("title constraint is invalid");
            }
        }

        boolean matches(Game game) {
            if (game == null || game.ranking() == null || game.details() == null) return false;
            return java.util.stream.Stream.of(
                            game.ranking().sourceName(),
                            game.details().name(),
                            game.details().officialChineseName())
                    .map(TitleConstraint::normalize)
                    .anyMatch(title -> title.contains(value));
        }

        private static String normalize(String value) {
            if (value == null || value.isBlank()) return "";
            return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
        }
    }

    record PublicationSeed(
            List<Integer> candidateBggIds,
            List<Integer> referenceBggIds,
            int requestedCount) {
        PublicationSeed {
            candidateBggIds = candidateBggIds == null ? List.of() : List.copyOf(candidateBggIds);
            referenceBggIds = referenceBggIds == null ? List.of() : List.copyOf(referenceBggIds);
            if (candidateBggIds.isEmpty()
                    || candidateBggIds.stream().anyMatch(id -> id == null || id <= 0)
                    || candidateBggIds.stream().distinct().count() != candidateBggIds.size()
                    || referenceBggIds.stream().anyMatch(id -> id == null || id <= 0)
                    || referenceBggIds.stream().distinct().count() != referenceBggIds.size()
                    || requestedCount < 1) {
                throw new IllegalArgumentException("recommendation publication seed is invalid");
            }
        }

    }

}
