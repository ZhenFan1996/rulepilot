package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.PublicContextEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Source;
import com.rulepilot.recommendation.ConstraintRange;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.CandidateComparison;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.ConversationRequest;
import com.rulepilot.recommendation.application.BoardGameRecommendationAgent.RecommendationProfile;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Mutable execution facts for one turn; {@link CatalogSearch} is the sole candidate-filter owner. */
final class RecommendationAgentState {

    final long startedAtNanos;
    final String modelConfigurationOwner;
    final RecommendationProfile profile;
    final Set<Integer> excludedIds;
    final Set<Integer> previouslyShownIds = new LinkedHashSet<>();
    final Map<Integer, Game> verified = new LinkedHashMap<>();
    final Set<Integer> freshVerifiedIds = new LinkedHashSet<>();
    final Set<Integer> comparisonSubjectIds = new LinkedHashSet<>();
    final Set<String> finalResponseEvidenceIds = new LinkedHashSet<>();
    final Set<String> finalResponsePublicEvidenceIds = new LinkedHashSet<>();
    final Map<String, PublicContextEvidence> publicContextEvidence = new LinkedHashMap<>();
    final List<String> actions = new ArrayList<>();
    PublicationSeed pendingPublicationSeed;
    CatalogSearch activeSearch;
    Research research = Research.empty();
    CandidateComparison comparison;
    List<Source> publicContextSources = List.of();
    boolean webResearchAvailable;
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
        this.startedAtNanos = startedAtNanos;
        this.modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                ? null
                : modelConfigurationOwner.strip();
        profile = request.profile();
        excludedIds = new LinkedHashSet<>(request.excludedBggIds());
        previouslyShownIds.addAll(request.shownBggIds());
        comparisonSubjectIds.addAll(request.shownBggIds());
        if (request.focusedBggId() != null) comparisonSubjectIds.add(request.focusedBggId());
        request.priorVerifiedGames().forEach(this::restoreVerified);
        webResearchAvailable = webResearchConfigured;
    }

    synchronized void addVerified(Game game) {
        if (game == null || game.ranking() == null || game.details() == null) return;
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
        if (game == null || game.ranking() == null || game.details() == null) return;
        verified.putIfAbsent(game.ranking().bggId(), game);
    }

    synchronized void beginCatalogSearch(CatalogSearch search) {
        activeSearch = search;
        pendingPublicationSeed = null;
        comparison = null;
    }

    synchronized void completeCatalogSearch(int catalogSourceCount, List<Game> games) {
        actions.add("SEARCH_BGG_CATALOG");
        sourceCount = Math.max(sourceCount, catalogSourceCount);
        games.forEach(game -> {
            addVerified(game);
            comparisonSubjectIds.add(game.ranking().bggId());
        });
    }

    RecommendationProfile selectionProfile() {
        return activeSearch == null ? RecommendationProfile.empty() : activeSearch.selectionProfile();
    }

    synchronized void recordCatalogCall() {
        catalogCalls++;
    }

    synchronized void recordAction(String action) {
        actions.add(action);
    }

    synchronized void recordSourceCount(int count) {
        sourceCount = Math.max(sourceCount, count);
    }

    void disableWebResearch(String code) {
        webResearchAvailable = false;
        webResearchFailureCode = code == null ? "" : code;
        actions.add("WEB_RESEARCH_DEGRADED:" + webResearchFailureCode);
    }

    long elapsedMs() {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    void recordModelCallElapsed(long callStartedAtNanos) {
        modelCallElapsedMs.add(Math.max(0, (System.nanoTime() - callStartedAtNanos) / 1_000_000));
    }

    boolean hasVerifiedPublicContext() {
        return !publicContextEvidence.isEmpty();
    }

    record CatalogSearch(
            List<BggGameType> includeTypes,
            List<BggGameType> excludeTypes,
            TitleFilter title,
            Integer players,
            Integer maxMinutes,
            ConstraintRange<BigDecimal> complexity,
            String evidenceId,
            RecommendationProfile selectionProfile) {
        CatalogSearch {
            includeTypes = includeTypes == null ? List.of() : List.copyOf(includeTypes);
            excludeTypes = excludeTypes == null ? List.of() : List.copyOf(excludeTypes);
            if (evidenceId == null || evidenceId.isBlank() || selectionProfile == null) {
                throw new IllegalArgumentException("catalog search contract is invalid");
            }
        }

        boolean matches(Game game) {
            if (game == null || game.ranking() == null || game.details() == null) return false;
            List<BggGameType> actualTypes = game.ranking().types();
            if (!includeTypes.isEmpty() && includeTypes.stream().noneMatch(actualTypes::contains)) return false;
            if (excludeTypes.stream().anyMatch(actualTypes::contains)) return false;
            return title == null || title.matches(game);
        }
    }

    record TitleFilter(TitleMatch match, String value) {
        TitleFilter {
            if (match == null || normalize(value).isEmpty()) {
                throw new IllegalArgumentException("title filter is invalid");
            }
            value = value.strip();
        }

        boolean matches(Game game) {
            String expected = normalize(value);
            return java.util.stream.Stream.of(
                            game.ranking().sourceName(),
                            game.details().name(),
                            game.details().officialChineseName())
                    .map(TitleFilter::normalize)
                    .anyMatch(actual -> match == TitleMatch.EXACT
                            ? actual.equals(expected)
                            : actual.contains(expected));
        }

        private static String normalize(String value) {
            if (value == null || value.isBlank()) return "";
            return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC)
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
        }
    }

    enum TitleMatch {
        EXACT,
        CONTAINS
    }

    record PublicationSeed(List<Integer> candidateBggIds) {
        PublicationSeed {
            candidateBggIds = candidateBggIds == null ? List.of() : List.copyOf(candidateBggIds);
            if (candidateBggIds.isEmpty()
                    || candidateBggIds.stream().anyMatch(id -> id == null || id <= 0)
                    || candidateBggIds.stream().distinct().count() != candidateBggIds.size()) {
                throw new IllegalArgumentException("recommendation publication seed is invalid");
            }
        }
    }
}
