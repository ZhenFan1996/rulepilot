package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BggRankedCatalogService implements BggRankedCatalog, BoardGameRecommendationCatalog {

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

    public BrowseResult browse(String search, BggGameType type, Sort sort, int page, int size) {
        return browse(search, type, sort, page, size, true);
    }

    public BrowseResult browse(
            String search, BggGameType type, Sort sort, int page, int size, boolean includeDetails) {
        String checkedSearch = checkedSearch(search);
        BggGameType checkedType = type == null ? BggGameType.ALL : type;
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

    /**
     * Generates one bounded recommendation set from multiple BGG ranking channels and
     * enriches the union in a single details batch.
     */
    public BrowseResult recommendationCandidates(
            BggGameType requiredType, List<BggGameType> suggestedTypes, int size) {
        BggGameType checkedRequiredType = requiredType == null ? BggGameType.ALL : requiredType;
        if (size < 3 || size > 20) {
            throw new IllegalArgumentException("recommendation candidate size must be between 3 and 20");
        }
        List<BggGameType> channels = recommendationChannels(checkedRequiredType, suggestedTypes);
        List<List<RankedGame>> rankedChannels = channels.stream()
                .map(type -> repository.find(new Query("", type, Sort.RANK, 0, size, List.of())).games())
                .toList();
        List<RankedGame> ranked = roundRobin(rankedChannels, size);
        Map<Integer, DiscoveryGame> details = details(ranked);
        List<BrowseGame> games = ranked.stream()
                .map(game -> new BrowseGame(game, null, details.get(game.bggId())))
                .toList();
        Optional<Snapshot> snapshot = repository.findSnapshot();
        long total = snapshot.map(Snapshot::gameCount).orElse(0);
        return new BrowseResult(
                snapshot, total, 0, size, Sort.RANK, checkedRequiredType, games);
    }

    public List<BrowseGame> browseIds(List<Integer> bggIds) {
        List<Integer> ids = bggIds == null
                ? List.of()
                : bggIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 12 || ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("BGG lookup requires one to twelve positive ids");
        }
        List<RankedGame> ranked = repository.findByIds(ids);
        Map<Integer, DiscoveryGame> details = details(ranked);
        return ranked.stream().map(game -> new BrowseGame(game, null, details.get(game.bggId()))).toList();
    }

    @Override
    public CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum) {
        BrowseResult result = recommendationCandidates(requiredType, suggestedTypes, maximum);
        return new CandidateSet(
                result.snapshot().map(Snapshot::gameCount).orElse(0),
                result.games().stream().map(this::recommendationGame).toList());
    }

    @Override
    public List<BoardGameRecommendationCatalog.Game> findGamesByIds(List<Integer> bggIds) {
        return browseIds(bggIds).stream().map(this::recommendationGame).toList();
    }

    @Override
    public Optional<BoardGameRecommendationCatalog.Game> findGameById(int bggId) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        Optional<RankedGame> ranked = repository.findByIds(List.of(bggId)).stream().findFirst();
        if (ranked.isEmpty()) return Optional.empty();
        if (bgg.configured()) {
            try {
                return Optional.of(recommendationGame(ranked.orElseThrow(), bgg.game(bggId)));
            } catch (RuntimeException exception) {
                LOGGER.warn("Full BGG game detail is unavailable; using the cached recommendation summary");
            }
        }
        DiscoveryGame details = details(List.of(ranked.orElseThrow())).get(bggId);
        return Optional.of(recommendationGame(new BrowseGame(ranked.orElseThrow(), null, details)));
    }

    @Override
    public int gameCount() {
        return repository.findSnapshot().map(Snapshot::gameCount).orElse(0);
    }

    private BoardGameRecommendationCatalog.Game recommendationGame(BrowseGame game) {
        RankedGame ranked = game.ranked();
        DiscoveryGame details = game.details();
        BoardGameRecommendationCatalog.Details publicDetails = details == null
                ? null
                : new BoardGameRecommendationCatalog.Details(
                        details.name(),
                        details.chineseName(),
                        details.thumbnailUrl(),
                        details.minPlayers(),
                        details.maxPlayers(),
                        details.playingTimeMinutes(),
                        details.averageWeight(),
                        details.categories(),
                        details.mechanics(),
                        details.minimumPlayTimeMinutes(),
                        details.maximumPlayTimeMinutes(),
                        details.minimumAge(),
                        details.suggestedMinimumAge(),
                        details.bestWith(),
                        details.recommendedWith(),
                        details.languageDependenceLevel(),
                        details.weightVotes(),
                        details.families(),
                        details.designers(),
                        details.publishers());
        return new BoardGameRecommendationCatalog.Game(
                new BoardGameRecommendationCatalog.Ranking(
                        ranked.bggId(),
                        ranked.sourceName(),
                        ranked.publicationYear(),
                        ranked.overallRank(),
                        ranked.bayesAverage(),
                        ranked.averageRating(),
                        ranked.usersRated()),
                publicDetails);
    }

    private BoardGameRecommendationCatalog.Game recommendationGame(RankedGame ranked, GameDetails details) {
        String officialChineseName = details.officialChineseNames().isEmpty()
                ? ""
                : details.officialChineseNames().getFirst();
        return new BoardGameRecommendationCatalog.Game(
                recommendationRanking(ranked),
                new BoardGameRecommendationCatalog.Details(
                        details.name(),
                        officialChineseName,
                        details.thumbnailUrl(),
                        details.minPlayers(),
                        details.maxPlayers(),
                        details.playingTimeMinutes(),
                        details.averageWeight(),
                        details.categories(),
                        details.mechanics(),
                        details.playingTimeMinutes(),
                        details.playingTimeMinutes(),
                        details.minimumAge(),
                        null,
                        "",
                        "",
                        null,
                        null,
                        List.of(),
                        details.designers(),
                        details.publishers(),
                        details.description(),
                        details.imageUrl()));
    }

    private BoardGameRecommendationCatalog.Ranking recommendationRanking(RankedGame ranked) {
        return new BoardGameRecommendationCatalog.Ranking(
                ranked.bggId(),
                ranked.sourceName(),
                ranked.publicationYear(),
                ranked.overallRank(),
                ranked.bayesAverage(),
                ranked.averageRating(),
                ranked.usersRated());
    }

    private List<BggGameType> recommendationChannels(BggGameType requiredType, List<BggGameType> suggestedTypes) {
        if (requiredType != BggGameType.ALL) return List.of(requiredType);
        LinkedHashSet<BggGameType> channels = new LinkedHashSet<>();
        channels.add(BggGameType.ALL);
        if (suggestedTypes != null) {
            suggestedTypes.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(type -> type != BggGameType.ALL && type != BggGameType.EXPANSION)
                    .limit(2)
                    .forEach(channels::add);
        }
        return List.copyOf(channels);
    }

    private List<RankedGame> roundRobin(List<List<RankedGame>> channels, int maximum) {
        LinkedHashMap<Integer, RankedGame> selected = new LinkedHashMap<>();
        for (int position = 0; selected.size() < maximum; position++) {
            boolean found = false;
            for (List<RankedGame> channel : channels) {
                if (position >= channel.size()) continue;
                found = true;
                RankedGame game = channel.get(position);
                selected.putIfAbsent(game.bggId(), game);
                if (selected.size() == maximum) break;
            }
            if (!found) break;
        }
        return List.copyOf(selected.values());
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
            BggGameType type,
            List<BrowseGame> games) {
        public BrowseResult {
            snapshot = snapshot == null ? Optional.empty() : snapshot;
            games = List.copyOf(games);
        }
    }

    public record BrowseGame(RankedGame ranked, Integer hotRank, DiscoveryGame details) {}
}
