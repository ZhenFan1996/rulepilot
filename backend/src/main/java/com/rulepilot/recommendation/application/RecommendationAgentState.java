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
    final Set<Integer> agentVisiblePriorIds = new LinkedHashSet<>();
    final Map<Integer, Game> verified = new LinkedHashMap<>();
    final Set<Integer> freshVerifiedIds = new LinkedHashSet<>();
    final Set<Integer> comparisonSubjectIds = new LinkedHashSet<>();
    final Set<String> finalResponseEvidenceIds = new LinkedHashSet<>();
    final Set<String> finalResponsePublicEvidenceIds = new LinkedHashSet<>();
    final Map<String, PublicContextEvidence> publicContextEvidence = new LinkedHashMap<>();
    final List<String> actions = new ArrayList<>();
    PublicationSeed pendingPublicationSeed;
    CatalogSearch activeSearch;
    List<String> catalogMechanics = List.of();
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
        agentVisiblePriorIds.addAll(request.shownBggIds());
        if (request.focusedBggId() != null) agentVisiblePriorIds.add(request.focusedBggId());
        comparisonSubjectIds.addAll(request.shownBggIds());
        if (request.focusedBggId() != null) comparisonSubjectIds.add(request.focusedBggId());
        request.priorVerifiedGames().forEach(this::restoreVerified);
        List<Integer> restoredShownCandidates = request.shownBggIds().stream()
                .filter(verified::containsKey)
                .filter(id -> !excludedIds.contains(id))
                .toList();
        if (!restoredShownCandidates.isEmpty()) {
            pendingPublicationSeed = new PublicationSeed(restoredShownCandidates);
        }
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
                                .filter(entry -> agentVisiblePriorIds.contains(entry.getKey()))
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

    synchronized void resolveCatalogFamilies(List<String> families) {
        if (activeSearch == null) throw new IllegalStateException("active catalog search is required");
        activeSearch = activeSearch.withResolvedFamilies(families);
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
            List<String> mechanics,
            List<String> excludedMechanics,
            TitleFilter title,
            List<TitleFilter> excludedTitles,
            Integer requestedCount,
            Integer players,
            Integer maxMinutes,
            Integer minimumPublicationYear,
            Integer maximumPublicationYear,
            Integer youngestPlayerAge,
            ConstraintRange<BigDecimal> complexity,
            String evidenceId,
            RecommendationProfile selectionProfile) {
        CatalogSearch {
            includeTypes = includeTypes == null ? List.of() : List.copyOf(includeTypes);
            excludeTypes = excludeTypes == null ? List.of() : List.copyOf(excludeTypes);
            mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
            excludedMechanics = excludedMechanics == null ? List.of() : List.copyOf(excludedMechanics);
            excludedTitles = excludedTitles == null ? List.of() : List.copyOf(excludedTitles);
            if (requestedCount != null && requestedCount < 0
                    || evidenceId == null
                    || evidenceId.isBlank()
                    || selectionProfile == null) {
                throw new IllegalArgumentException("catalog search contract is invalid");
            }
        }

        boolean matches(Game game) {
            if (game == null || game.ranking() == null || game.details() == null) return false;
            List<BggGameType> actualTypes = game.ranking().types();
            if (!includeTypes.isEmpty() && includeTypes.stream().noneMatch(actualTypes::contains)) return false;
            if (excludeTypes.stream().anyMatch(actualTypes::contains)) return false;
            if (!game.details().mechanics().containsAll(mechanics)) return false;
            if (excludedMechanics.stream().anyMatch(game.details().mechanics()::contains)) return false;
            if (excludedTitles.stream().anyMatch(excluded -> excluded.matches(game))) return false;
            Integer year = game.ranking().publicationYear();
            if (minimumPublicationYear != null && (year == null || year < minimumPublicationYear)) return false;
            if (maximumPublicationYear != null && (year == null || year > maximumPublicationYear)) return false;
            Integer minimumAge = game.details().minimumAge();
            if (youngestPlayerAge != null && (minimumAge == null || minimumAge > youngestPlayerAge)) return false;
            if (title == null) return true;
            return title.matches(game);
        }

        CatalogSearch withResolvedFamilies(List<String> families) {
            return new CatalogSearch(
                    includeTypes,
                    excludeTypes,
                    mechanics,
                    excludedMechanics,
                    title.withFamilies(families),
                    excludedTitles,
                    requestedCount,
                    players,
                    maxMinutes,
                    minimumPublicationYear,
                    maximumPublicationYear,
                    youngestPlayerAge,
                    complexity,
                    evidenceId,
                    selectionProfile);
        }
    }

    record TitleFilter(TitleMatch match, TitleScope scope, String value, List<String> families) {
        TitleFilter(TitleMatch match, TitleScope scope, String value) {
            this(match, scope, value, List.of());
        }

        TitleFilter withFamilies(List<String> resolved) {
            return new TitleFilter(match, scope, value, resolved);
        }

        TitleFilter {
            if (match == null || scope == null || normalize(value).isEmpty()) {
                throw new IllegalArgumentException("title filter is invalid");
            }
            if (scope == TitleScope.SERIES && match != TitleMatch.CONTAINS) {
                throw new IllegalArgumentException("series title filter must use CONTAINS");
            }
            value = value.strip();
            families = List.copyOf(families);
        }

        boolean matches(Game game) {
            if (scope == TitleScope.SERIES && game.details().families().stream()
                    .map(TitleFilter::normalize)
                    .anyMatch(actual -> families.stream().map(TitleFilter::normalize).anyMatch(actual::equals))) {
                return true;
            }
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

    enum TitleScope {
        TITLE,
        SERIES
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
