package com.rulepilot.catalog.application;

import com.rulepilot.catalog.application.BggRankedCatalog.GameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BggRankedCatalogService implements BggRankedCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggRankedCatalogService.class);
    private final BggRankedCatalogRepository repository;
    private final BoardGameGeekCatalog bgg;

    public BggRankedCatalogService(BggRankedCatalogRepository repository, BoardGameGeekCatalog bgg) {
        this.repository = repository;
        this.bgg = bgg;
    }

    @Override
    public Snapshot snapshot() {
        return repository.findSnapshot().orElse(null);
    }

    @Override
    public Page find(Query query) {
        return repository.find(query);
    }

    public BrowseResult browse(String search, GameType type, Sort sort, int page, int size) {
        return browse(search, type, sort, page, size, true);
    }

    public BrowseResult browse(
            String search, GameType type, Sort sort, int page, int size, boolean includeDetails) {
        String checkedSearch = checkedSearch(search);
        GameType checkedType = type == null ? GameType.ALL : type;
        Sort checkedSort = sort == null ? Sort.HOT : sort;
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("page must be between 0 and 10000");
        if (size < 1 || size > 20) throw new IllegalArgumentException("size must be between 1 and 20");

        List<HotGame> hotGames = checkedSort == Sort.HOT ? hotGames() : List.of();
        List<Integer> hotIds = hotGames.stream().map(HotGame::bggId).toList();
        Page ranked = repository.find(new Query(checkedSearch, checkedType, checkedSort, page, size, hotIds));
        Map<Integer, Integer> hotRanks = hotGames.stream().collect(java.util.stream.Collectors.toMap(
                HotGame::bggId,
                HotGame::rank,
                Math::min,
                LinkedHashMap::new));
        Map<Integer, DiscoveryGame> details = includeDetails ? details(ranked.games()) : Map.of();
        List<BrowseGame> games = ranked.games().stream()
                .map(game -> new BrowseGame(game, hotRanks.get(game.bggId()), details.get(game.bggId())))
                .toList();
        return new BrowseResult(repository.findSnapshot(), ranked.total(), page, size, checkedSort, checkedType, games);
    }

    public List<BrowseGame> browseIds(List<Integer> bggIds) {
        List<Integer> ids = bggIds == null
                ? List.of()
                : bggIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 5 || ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("focused BGG lookup requires one to five positive ids");
        }
        List<RankedGame> ranked = repository.findByIds(ids);
        Map<Integer, DiscoveryGame> details = details(ranked);
        return ranked.stream().map(game -> new BrowseGame(game, null, details.get(game.bggId()))).toList();
    }

    private List<HotGame> hotGames() {
        try {
            return bgg.hotGames();
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG hot ranking is unavailable; ranked catalog order will be used");
            return List.of();
        }
    }

    private Map<Integer, DiscoveryGame> details(List<RankedGame> games) {
        if (games.isEmpty() || !bgg.configured()) return Map.of();
        try {
            return bgg.gameDetails(games.stream().map(RankedGame::bggId).toList()).stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(DiscoveryGame::bggId, game -> game));
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG batch details are unavailable; serving the ranked CSV snapshot");
            return Map.of();
        }
    }

    private String checkedSearch(String search) {
        if (search == null || search.isBlank()) return "";
        String checked = search.strip().replaceAll("\\s+", " ");
        if (checked.length() < 2 || checked.length() > 120) {
            throw new IllegalArgumentException("search must contain 2 to 120 characters");
        }
        return checked;
    }

    public record BrowseResult(
            Optional<Snapshot> snapshot,
            long total,
            int page,
            int size,
            Sort sort,
            GameType type,
            List<BrowseGame> games) {
        public BrowseResult {
            snapshot = snapshot == null ? Optional.empty() : snapshot;
            games = List.copyOf(games);
        }
    }

    public record BrowseGame(RankedGame ranked, Integer hotRank, DiscoveryGame details) {}
}
