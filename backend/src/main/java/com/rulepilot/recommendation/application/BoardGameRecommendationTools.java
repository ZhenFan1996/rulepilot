package com.rulepilot.recommendation.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.Game;
import com.rulepilot.recommendation.BoardGameRecommendationAdvisor.Candidate;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.CandidateDiscovery;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.DiscoveryRequest;
import com.rulepilot.recommendation.BoardGameRecommendationWebResearch.Research;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Allow-listed read tools for the recommendation Agent. The planner selects which
 * tools are useful; every result is a bounded typed observation consumed by the
 * next model step rather than trusted as a final recommendation.
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

    CatalogObservation searchCatalog(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
        try {
            BoardGameRecommendationCatalog.CandidateSet result =
                    catalog.findCandidates(requiredType, suggestedTypes, maximum);
            return new CatalogObservation(
                    ToolStatus.SUCCESS, ToolName.SEARCH_BGG_CATALOG, result.sourceCount(), result.games(), "");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation catalog search tool failed");
            return CatalogObservation.error(ToolName.SEARCH_BGG_CATALOG, "CATALOG_UNAVAILABLE");
        }
    }

    CatalogObservation lookupGame(int bggId) {
        try {
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    ToolName.LOOKUP_BGG_GAME,
                    catalog.gameCount(),
                    catalog.findGameById(bggId).map(List::of).orElseGet(List::of),
                    "");
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation BGG-ID detail tool failed");
            return CatalogObservation.error(ToolName.LOOKUP_BGG_GAME, "CATALOG_UNAVAILABLE");
        }
    }

    CatalogObservation lookupCandidates(List<Integer> bggIds) {
        return lookupGames(bggIds, ToolName.LOOKUP_BGG_CANDIDATES);
    }

    private CatalogObservation lookupGames(List<Integer> bggIds, ToolName tool) {
        try {
            return new CatalogObservation(
                    ToolStatus.SUCCESS,
                    tool,
                    catalog.gameCount(),
                    catalog.findGamesByIds(bggIds),
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
        } catch (RuntimeException exception) {
            LOGGER.warn("Recommendation game-fit research tool failed");
            return new ResearchObservation(ToolStatus.ERROR, Research.empty(), "RESEARCH_UNAVAILABLE");
        }
    }

    boolean webResearchConfigured() {
        return webResearch.configured();
    }

    enum ToolName {
        SEARCH_BGG_CATALOG,
        LOOKUP_BGG_GAME,
        LOOKUP_BGG_CANDIDATES,
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
            List<Game> games,
            String code) {
        CatalogObservation {
            games = List.copyOf(games);
        }

        static CatalogObservation error(ToolName tool, String code) {
            return new CatalogObservation(ToolStatus.ERROR, tool, 0, List.of(), code);
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
