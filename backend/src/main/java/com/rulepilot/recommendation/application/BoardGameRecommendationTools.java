package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CanonicalMetadataResult;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogMetadataCriterion;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.SelectionEligibility;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.WebResearchUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Allow-listed read capabilities for the recommendation ReAct Agent. Every result
 * becomes a bounded observation consumed by the next model step.
 */
@Component
@Profile("!test")
public class BoardGameRecommendationTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardGameRecommendationTools.class);

    private final BoardGameRecommendationCatalog catalog;
    private final BoardGameRecommendationWebResearch webResearch;

    public BoardGameRecommendationTools(
            BoardGameRecommendationCatalog catalog,
            BoardGameRecommendationWebResearch webResearch) {
        this.catalog = catalog;
        this.webResearch = webResearch;
    }

    CatalogObservation searchCatalog(
            List<BggGameType> types,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            int maximum) {
        return searchCatalog(
                types,
                categories,
                mechanics,
                designers,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                CatalogSort.RANK,
                maximum,
                0);
    }

    CanonicalMetadataObservation canonicalizeMetadata(List<CatalogMetadataCriterion> criteria) {
        try {
            CanonicalMetadataResult result = catalog.canonicalizeMetadata(criteria);
            if (!result.supported()) {
                return new CanonicalMetadataObservation(
                        ToolStatus.UNAVAILABLE,
                        CanonicalMetadataResult.unsupported(),
                        "CATALOG_CANONICALIZATION_UNAVAILABLE");
            }
            return new CanonicalMetadataObservation(ToolStatus.SUCCESS, result, "");
        } catch (IllegalArgumentException exception) {
            return new CanonicalMetadataObservation(
                    ToolStatus.ERROR,
                    CanonicalMetadataResult.unsupported(),
                    "INVALID_ARGUMENT");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation catalog canonical-metadata lookup failed");
            return new CanonicalMetadataObservation(
                    ToolStatus.ERROR,
                    CanonicalMetadataResult.unsupported(),
                    "CATALOG_UNAVAILABLE");
        }
    }

    CatalogObservation searchCatalog(
            List<BggGameType> types,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            List<String> publishers,
            List<String> families,
            Integer minimumPublicationYear,
            Integer maximumPublicationYear,
            java.math.BigDecimal minimumAverageRating,
            Integer minimumRatingsCount,
            String textQuery,
            CatalogSort sort,
            int maximum,
            int offset) {
        return searchCatalog(
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
                maximum,
                offset,
                SelectionEligibility.none());
    }

    CatalogObservation searchCatalog(
            List<BggGameType> types,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            List<String> publishers,
            List<String> families,
            Integer minimumPublicationYear,
            Integer maximumPublicationYear,
            java.math.BigDecimal minimumAverageRating,
            Integer minimumRatingsCount,
            String textQuery,
            CatalogSort sort,
            int maximum,
            int offset,
            SelectionEligibility eligibility) {
        try {
            BoardGameRecommendationCatalog.CandidateSet result = catalog.searchGames(
                    new BoardGameRecommendationCatalog.CatalogFilters(
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
                            maximum,
                            offset),
                    eligibility);
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    ToolName.SEARCH_BGG_CATALOG,
                    result.sourceCount(),
                    result.availableCount(),
                    result.games(),
                    List.of(),
                    "");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation catalog search tool failed");
            return CatalogObservation.error(ToolName.SEARCH_BGG_CATALOG, "CATALOG_UNAVAILABLE");
        }
    }

    CatalogObservation lookupGame(int bggId) {
        try {
            Optional<Game> game = catalog.findGameById(bggId);
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    ToolName.LOOKUP_BGG_GAME,
                    catalog.gameCount(),
                    game.isPresent() ? 1 : 0,
                    game.map(List::of).orElseGet(List::of),
                    List.of(),
                    "");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation BGG-ID detail tool failed");
            return CatalogObservation.error(ToolName.LOOKUP_BGG_GAME, "CATALOG_UNAVAILABLE");
        }
    }

    CatalogObservation lookupCandidates(List<Integer> bggIds) {
        return lookupGames(bggIds, ToolName.LOOKUP_BGG_CANDIDATES);
    }

    /**
     * Resolves title hypotheses and hydrates their BGG details as one Agent-facing read.
     * The two catalog operations are mechanically dependent and do not need another
     * model decision between them.
     */
    CatalogObservation inspectTitles(List<String> names) {
        List<TitleHypothesis> hypotheses = IntStream.range(0, names.size())
                .mapToObj(index -> new TitleHypothesis("title-" + (index + 1), names.get(index)))
                .toList();
        return inspectTitleHypotheses(hypotheses);
    }

    CatalogObservation inspectTitleHypotheses(List<TitleHypothesis> hypotheses) {
        try {
            List<TitleResolution> resolutions = hypotheses.stream()
                    .map(hypothesis -> catalog.searchByNames(List.of(hypothesis.title())).stream()
                            .findFirst()
                            .map(match -> new TitleResolution(hypothesis.correlationId(), match.bggId()))
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<Integer> ids = resolutions.stream()
                    .map(TitleResolution::bggId)
                    .distinct()
                    .toList();
            List<Game> games = ids.isEmpty() ? List.of() : catalog.findGamesByIds(ids);
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    ToolName.INSPECT_BGG_TITLES,
                    catalog.gameCount(),
                    games.size(),
                    games,
                    resolutions,
                    "");
        } catch (IllegalArgumentException exception) {
            return CatalogObservation.error(ToolName.INSPECT_BGG_TITLES, "INVALID_ARGUMENT");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation BGG title-inspection tool failed");
            return CatalogObservation.error(ToolName.INSPECT_BGG_TITLES, "CATALOG_UNAVAILABLE");
        }
    }

    NameSearchObservation searchByNames(List<String> names) {
        try {
            return new NameSearchObservation(
                    ToolStatus.SUCCESS,
                    catalog.searchByNames(names),
                    "");
        } catch (IllegalArgumentException exception) {
            return new NameSearchObservation(ToolStatus.ERROR, List.of(), "INVALID_ARGUMENT");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation BGG name-search tool failed");
            return new NameSearchObservation(ToolStatus.ERROR, List.of(), "CATALOG_UNAVAILABLE");
        }
    }

    ReferenceObservation resolveReferenceTitle(String title) {
        return resolveReferenceTitle(title, false);
    }

    ReferenceObservation resolveLocalReferenceTitle(String title) {
        return resolveReferenceTitle(title, true);
    }

    private ReferenceObservation resolveReferenceTitle(String title, boolean localOnly) {
        try {
            List<Game> games = localOnly
                    ? catalog.resolveLocalReferenceTitle(title)
                    : catalog.resolveReferenceTitle(title);
            boolean complete = games.size() == 1 && games.getFirst().details() != null;
            return new ReferenceObservation(
                    complete ? ToolStatus.SUCCESS : ToolStatus.PARTIAL,
                    games,
                    complete ? "" : games.isEmpty() ? "REFERENCE_NOT_FOUND" : "REFERENCE_DETAILS_UNAVAILABLE");
        } catch (IllegalArgumentException exception) {
            return new ReferenceObservation(ToolStatus.ERROR, List.of(), "INVALID_ARGUMENT");
        } catch (RuntimeException exception) {
            LOGGER.warn(localOnly
                    ? "Recommendation local reference-title resolution failed"
                    : "Recommendation reference-title resolution failed");
            return new ReferenceObservation(ToolStatus.ERROR, List.of(), "CATALOG_UNAVAILABLE");
        }
    }

    private CatalogObservation lookupGames(List<Integer> bggIds, ToolName tool) {
        try {
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    tool,
                    catalog.gameCount(),
                    bggIds.size(),
                    catalog.findGamesByIds(bggIds),
                    List.of(),
                    "");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation BGG-ID lookup tool failed");
            return CatalogObservation.error(tool, "CATALOG_UNAVAILABLE");
        }
    }

    DiscoveryObservation discoverCandidates(DiscoveryRequest request) {
        if (!webResearch.configured()) return DiscoveryObservation.unavailable();
        try {
            return webResearch.discover(request)
                    .map(value -> new DiscoveryObservation(ToolStatus.SUCCESS, value, ""))
                    .orElseGet(() -> new DiscoveryObservation(ToolStatus.PARTIAL, null, "NO_DISCOVERY_RESULT"));
        } catch (WebResearchUnavailableException exception) {
            LOGGER.warn("Recommendation candidate-discovery capability degraded ({})", exception.code());
            return new DiscoveryObservation(ToolStatus.ERROR, null, exception.code());
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation candidate-discovery tool failed");
            return new DiscoveryObservation(ToolStatus.ERROR, null, "DISCOVERY_UNAVAILABLE");
        }
    }

    void rememberVerifiedIdentity(
            DiscoveryRequest request,
            BoardGameRecommendationWebResearch.CandidateDiscovery discovery) {
        webResearch.rememberVerifiedIdentity(request, discovery);
    }

    ResearchObservation researchGameFit(List<Candidate> candidates, String locale, String question) {
        if (!webResearch.configured()) return ResearchObservation.unavailable();
        try {
            return webResearch.research(new BoardGameRecommendationWebResearch.Request(candidates, locale, question))
                    .map(value -> new ResearchObservation(ToolStatus.SUCCESS, value, ""))
                    .orElseGet(() -> new ResearchObservation(ToolStatus.PARTIAL, Research.empty(), "NO_RESEARCH_RESULT"));
        } catch (WebResearchUnavailableException exception) {
            LOGGER.warn("Recommendation game-fit research capability degraded ({})", exception.code());
            return new ResearchObservation(ToolStatus.ERROR, Research.empty(), exception.code());
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation game-fit research tool failed");
            return new ResearchObservation(ToolStatus.ERROR, Research.empty(), "RESEARCH_UNAVAILABLE");
        }
    }

    boolean webResearchConfigured() {
        return webResearch.configured();
    }

    int catalogGameCount() {
        return catalog.gameCount();
    }

    enum ToolName {
        SEARCH_BGG_CATALOG,
        LOOKUP_BGG_GAME,
        LOOKUP_BGG_CANDIDATES,
        INSPECT_BGG_TITLES,
        SEARCH_BGG_BY_NAME,
        RESOLVE_BGG_REFERENCE,
        DISCOVER_CANDIDATES,
        RESEARCH_GAME_FIT,
        RESEARCH_GAME_QUESTION
    }

    enum ToolStatus {
        SUCCESS,
        PARTIAL,
        ERROR,
        UNAVAILABLE
    }

    record CatalogObservation(
            ToolStatus status,
            ToolName tool,
            int sourceCount,
            int availableCount,
            List<Game> games,
            List<TitleResolution> titleResolutions,
            String code) {
        CatalogObservation {
            games = List.copyOf(games);
            titleResolutions = List.copyOf(titleResolutions);
        }

        static CatalogObservation error(ToolName tool, String code) {
            return new CatalogObservation(ToolStatus.ERROR, tool, 0, 0, List.of(), List.of(), code);
        }

        boolean succeeded() {
            return status == ToolStatus.SUCCESS;
        }
    }

    record CanonicalMetadataObservation(
            ToolStatus status,
            CanonicalMetadataResult result,
            String code) {
        CanonicalMetadataObservation {
            if (status == null || result == null || code == null) {
                throw new IllegalArgumentException("canonical metadata observation is invalid");
            }
        }

        boolean succeeded() {
            return status == ToolStatus.SUCCESS;
        }
    }

    record TitleHypothesis(String correlationId, String title) {
        TitleHypothesis {
            if (correlationId == null || correlationId.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("title hypothesis is invalid");
            }
        }
    }

    record TitleResolution(String correlationId, int bggId) {
        TitleResolution {
            if (correlationId == null || correlationId.isBlank() || bggId < 1) {
                throw new IllegalArgumentException("title resolution is invalid");
            }
        }
    }

    record DiscoveryObservation(ToolStatus status, CandidateDiscovery discovery, String code) {
        static DiscoveryObservation unavailable() {
            return new DiscoveryObservation(ToolStatus.UNAVAILABLE, null, "WEB_RESEARCH_DISABLED");
        }

        Optional<CandidateDiscovery> result() {
            return Optional.ofNullable(discovery);
        }
    }

    record NameSearchObservation(
            ToolStatus status,
            List<BoardGameRecommendationCatalog.Ranking> matches,
            String code) {
        NameSearchObservation {
            matches = List.copyOf(matches);
        }

        boolean succeeded() {
            return status == ToolStatus.SUCCESS;
        }
    }

    record ReferenceObservation(ToolStatus status, List<Game> games, String code) {
        ReferenceObservation {
            games = List.copyOf(games);
        }

        boolean resolved() {
            return status == ToolStatus.SUCCESS && games.size() == 1;
        }
    }

    record ResearchObservation(ToolStatus status, Research research, String code) {
        static ResearchObservation unavailable() {
            return new ResearchObservation(ToolStatus.UNAVAILABLE, Research.empty(), "WEB_RESEARCH_DISABLED");
        }

        Optional<Research> result() {
            return status == ToolStatus.SUCCESS ? Optional.of(research) : Optional.empty();
        }
    }
}
