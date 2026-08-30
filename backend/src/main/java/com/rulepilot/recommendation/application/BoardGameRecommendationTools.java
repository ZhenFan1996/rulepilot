package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CatalogSort;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.WebResearchUnavailableException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
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
                            offset));
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    ToolName.SEARCH_BGG_CATALOG,
                    result.sourceCount(),
                    result.games(),
                    "",
                    result.pageExhausted());
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation catalog search tool failed");
            return CatalogObservation.error(ToolName.SEARCH_BGG_CATALOG, "CATALOG_UNAVAILABLE");
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
        SEARCH_BGG_CATALOG
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
            List<Game> games,
            String code,
            boolean pageExhausted) {

        CatalogObservation {
            games = List.copyOf(games);
        }

        static CatalogObservation error(ToolName tool, String code) {
            return new CatalogObservation(ToolStatus.ERROR, tool, 0, List.of(), code, true);
        }

        boolean succeeded() {
            return status == ToolStatus.SUCCESS;
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

    record ResearchObservation(ToolStatus status, Research research, String code) {
        static ResearchObservation unavailable() {
            return new ResearchObservation(ToolStatus.UNAVAILABLE, Research.empty(), "WEB_RESEARCH_DISABLED");
        }

        Optional<Research> result() {
            return status == ToolStatus.SUCCESS ? Optional.of(research) : Optional.empty();
        }
    }
}
