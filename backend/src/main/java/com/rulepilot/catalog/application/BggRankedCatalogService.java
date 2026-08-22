package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.BoardGameRecommendationCatalog;
import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.catalog.BoardGameRecommendationCatalog.CandidateSet;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BggRankedCatalogService
        implements BggRankedCatalog, BoardGameRecommendationCatalog, CatalogGameSelectionLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggRankedCatalogService.class);
    private final BggRankedCatalogRepository repository;
    private final BoardGameGeekCatalog bgg;
    private final BggMetadataCache metadataCache;

    @Autowired
    public BggRankedCatalogService(
            BggRankedCatalogRepository repository,
            BoardGameGeekCatalog bgg,
            BggMetadataCache metadataCache) {
        this.repository = repository;
        this.bgg = bgg;
        this.metadataCache = metadataCache;
    }

    BggRankedCatalogService(BggRankedCatalogRepository repository, BoardGameGeekCatalog bgg) {
        this(repository, bgg, null);
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

        List<HotGame> hotGames = checkedSort == Sort.HOT
                ? includeDetails ? hotGames() : storedHotGames()
                : List.of();
        List<Integer> hotIds = hotGames.stream().map(HotGame::bggId).toList();
        Page ranked = repository.find(new Query(checkedSearch, checkedType, checkedSort, page, size, hotIds));
        Map<Integer, Integer> hotRanks = hotGames.stream().collect(java.util.stream.Collectors.toMap(
                HotGame::bggId,
                HotGame::rank,
                Math::min,
                LinkedHashMap::new));
        Map<Integer, DiscoveryGame> details = includeDetails ? details(ranked.games()) : storedDetails(ranked.games());
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
        return detailedGames(ids);
    }

    public List<BrowseGame> coverDetails(List<Integer> bggIds) {
        List<Integer> ids = bggIds == null
                ? List.of()
                : bggIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("BGG cover lookup requires one to twenty positive ids");
        }
        return detailedGames(ids);
    }

    private List<BrowseGame> detailedGames(List<Integer> ids) {
        List<RankedGame> ranked = repository.findByIds(ids);
        Map<Integer, DiscoveryGame> details = details(ranked);
        return ranked.stream().map(game -> new BrowseGame(game, null, details.get(game.bggId()))).toList();
    }

    @Override
    public Optional<GameSelection> find(int bggId) {
        if (bggId <= 0) throw new IllegalArgumentException("BGG id must be positive");
        Optional<GameSelection> local = repository.findSelectionsByIds(List.of(bggId)).stream()
                .findFirst()
                .map(this::selectionGame);
        if (local.isPresent()) return local;
        return browseIds(List.of(bggId)).stream().findFirst().map(game -> {
            DiscoveryGame details = game.details();
            return new GameSelection(
                    bggId,
                    game.ranked().sourceName(),
                    details == null ? "" : details.chineseName(),
                    game.ranked().publicationYear(),
                    details == null ? "" : details.thumbnailUrl(),
                    details == null ? "" : details.imageUrl());
        });
    }

    @Override
    public List<GameSelection> findAll(List<Integer> bggIds) {
        List<Integer> ids = bggIds == null
                ? List.of()
                : bggIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(id -> id > 0)
                        .distinct()
                        .limit(20)
                        .toList();
        if (ids.isEmpty()) return List.of();
        return repository.findSelectionsByIds(ids).stream().map(this::selectionGame).toList();
    }

    @Override
    public List<GameSelection> search(String query, int maximum) {
        String checked = checkedSearch(query);
        if (checked.isBlank()) return List.of();
        if (maximum < 1 || maximum > 20) {
            throw new IllegalArgumentException("identity search maximum must be between 1 and 20");
        }
        return repository.searchSelections(checked, maximum).stream().map(this::selectionGame).toList();
    }

    private GameSelection selectionGame(BggRankedCatalogRepository.SelectionCandidate game) {
        return new GameSelection(
                game.bggId(),
                game.sourceName(),
                game.chineseName(),
                game.publicationYear(),
                game.thumbnailUrl(),
                game.imageUrl());
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
    public List<BoardGameRecommendationCatalog.Ranking> searchByNames(List<String> names) {
        List<String> checked = names == null
                ? List.of()
                : names.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(this::checkedSearch)
                        .distinct()
                        .toList();
        if (checked.isEmpty() || checked.size() > 8) {
            throw new IllegalArgumentException("BGG name search requires one to eight names");
        }
        LinkedHashMap<Integer, RankedGame> matches = new LinkedHashMap<>();
        for (String name : checked) {
            List<RankedGame> exact = localExactMatches(name);
            if (exact.size() == 1) {
                RankedGame game = exact.getFirst();
                matches.putIfAbsent(game.bggId(), game);
                continue;
            }
            if (exact.size() > 1) continue;
            repository.find(new Query(name, BggGameType.ALL, Sort.RANK, 0, 5, List.of())).games().stream()
                    .findFirst()
                    .ifPresent(game -> matches.putIfAbsent(game.bggId(), game));
        }
        return matches.values().stream().map(this::recommendationRanking).toList();
    }

    @Override
    public List<BoardGameRecommendationCatalog.Game> resolveReferenceTitle(String title) {
        String checked = checkedSearch(title);
        List<String> aliases = explicitReferenceAliases(checked);
        LinkedHashMap<Integer, RankedGame> localExact = localReferenceMatches(aliases);
        if (localExact.size() > 1) return List.of();
        if (localExact.size() == 1) {
            int bggId = localExact.keySet().iterator().next();
            return findGameById(bggId).map(List::of).orElseGet(List::of);
        }
        if (!bgg.configured()) return List.of();

        LinkedHashSet<Integer> exactIds = new LinkedHashSet<>();
        for (String alias : aliases) {
            String normalizedAlias = normalizedTitle(alias);
            bgg.search(alias).stream()
                    .filter(result -> normalizedTitle(result.name()).equals(normalizedAlias))
                    .map(BoardGameGeekCatalog.SearchResult::bggId)
                    .limit(2)
                    .forEach(exactIds::add);
            if (exactIds.size() > 1) return List.of();
        }
        if (exactIds.size() != 1) return List.of();
        int bggId = exactIds.iterator().next();
        Optional<RankedGame> ranked = repository.findByIds(List.of(bggId)).stream().findFirst();
        GameDetails details = bgg.game(bggId);
        return List.of(ranked
                .map(value -> recommendationGame(value, details))
                .orElseGet(() -> recommendationGame(details)));
    }

    @Override
    public List<BoardGameRecommendationCatalog.Game> resolveLocalReferenceTitle(String title) {
        String checked = checkedSearch(title);
        LinkedHashMap<Integer, RankedGame> localExact = localReferenceMatches(explicitReferenceAliases(checked));
        if (localExact.size() != 1) return List.of();
        RankedGame ranked = localExact.values().iterator().next();
        DiscoveryGame cached = storedDetails(List.of(ranked)).get(ranked.bggId());
        if (cached != null) {
            return List.of(recommendationGame(new BrowseGame(ranked, null, cached)));
        }
        Optional<BggRankedCatalogRepository.SelectionCandidate> selection = repository
                .findSelectionsByIds(List.of(ranked.bggId()))
                .stream()
                .findFirst();
        return List.of(selection
                .map(value -> recommendationGame(ranked, value))
                .orElseGet(() -> recommendationGame(ranked, (BggRankedCatalogRepository.SelectionCandidate) null)));
    }

    private LinkedHashMap<Integer, RankedGame> localReferenceMatches(List<String> aliases) {
        LinkedHashMap<Integer, RankedGame> matches = new LinkedHashMap<>();
        repository.findExactNames(aliases).forEach(match ->
                matches.putIfAbsent(match.game().bggId(), match.game()));
        return matches;
    }

    private List<RankedGame> localExactMatches(String name) {
        return repository.findExactName(name);
    }

    /**
     * A player may write one title as an explicit localized/canonical pair. Each locally exact
     * alias must point to one BGG identity; a canonical local match avoids a redundant remote
     * search for the localized spelling.
     */
    private List<String> explicitReferenceAliases(String title) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        StringBuilder outside = new StringBuilder();
        for (int index = 0; index < title.length(); index++) {
            char current = title.charAt(index);
            char closing = current == '(' ? ')' : current == '（' ? '）' : 0;
            if (closing == 0) {
                outside.append(current);
                continue;
            }
            int end = title.indexOf(closing, index + 1);
            if (end < 0) {
                outside.append(current);
                continue;
            }
            addReferenceAlias(aliases, title.substring(index + 1, end));
            outside.append(' ');
            index = end;
        }
        addReferenceAlias(aliases, outside.toString());
        addReferenceAlias(aliases, title);
        return List.copyOf(aliases);
    }

    private void addReferenceAlias(LinkedHashSet<String> aliases, String value) {
        String checked = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (!checked.isBlank() && checked.length() <= 120) aliases.add(checked);
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
                        details.publishers(),
                        details.description(),
                        details.imageUrl());
        return new BoardGameRecommendationCatalog.Game(
                new BoardGameRecommendationCatalog.Ranking(
                        ranked.bggId(),
                        ranked.sourceName(),
                        ranked.publicationYear(),
                        ranked.overallRank(),
                        ranked.bayesAverage(),
                        ranked.averageRating(),
                        ranked.usersRated(),
                        ranked.types()),
                publicDetails);
    }

    private BoardGameRecommendationCatalog.Game recommendationGame(RankedGame ranked, GameDetails details) {
        return new BoardGameRecommendationCatalog.Game(
                recommendationRanking(ranked),
                recommendationDetails(details));
    }

    private BoardGameRecommendationCatalog.Game recommendationGame(
            RankedGame ranked,
            BggRankedCatalogRepository.SelectionCandidate selection) {
        String name = selection == null || selection.sourceName().isBlank()
                ? ranked.sourceName()
                : selection.sourceName();
        String chineseName = selection == null ? "" : selection.chineseName();
        String thumbnailUrl = selection == null ? "" : selection.thumbnailUrl();
        String imageUrl = selection == null ? "" : selection.imageUrl();
        return new BoardGameRecommendationCatalog.Game(
                recommendationRanking(ranked),
                new BoardGameRecommendationCatalog.Details(
                        name,
                        chineseName,
                        thumbnailUrl,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        "",
                        "",
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "",
                        imageUrl));
    }

    private BoardGameRecommendationCatalog.Game recommendationGame(GameDetails details) {
        return new BoardGameRecommendationCatalog.Game(
                new BoardGameRecommendationCatalog.Ranking(
                        details.bggId(),
                        details.name(),
                        details.publicationYear(),
                        null,
                        details.averageRating(),
                        details.averageRating(),
                        0),
                recommendationDetails(details));
    }

    private BoardGameRecommendationCatalog.Details recommendationDetails(GameDetails details) {
        String officialChineseName = details.officialChineseNames().isEmpty()
                ? ""
                : details.officialChineseNames().getFirst();
        return new BoardGameRecommendationCatalog.Details(
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
                details.imageUrl());
    }

    private BoardGameRecommendationCatalog.Ranking recommendationRanking(RankedGame ranked) {
        return new BoardGameRecommendationCatalog.Ranking(
                ranked.bggId(),
                ranked.sourceName(),
                ranked.publicationYear(),
                ranked.overallRank(),
                ranked.bayesAverage(),
                ranked.averageRating(),
                ranked.usersRated(),
                ranked.types());
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

    private List<HotGame> storedHotGames() {
        if (metadataCache == null) return List.of();
        try {
            return metadataCache.hotGames(Instant.now()).map(BggMetadataCache.Cached::value).orElseGet(List::of);
        } catch (RuntimeException exception) {
            LOGGER.warn("Stored BGG hot ranking is unavailable; using the ranked catalog order");
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

    private Map<Integer, DiscoveryGame> storedDetails(List<RankedGame> games) {
        if (games.isEmpty() || metadataCache == null) return Map.of();
        try {
            return metadataCache.discoveryGames(
                            games.stream().map(RankedGame::bggId).toList(), Instant.now())
                    .entrySet()
                    .stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> entry.getValue().value()));
        } catch (RuntimeException exception) {
            LOGGER.warn("Stored BGG details are unavailable; serving the ranked catalog without blocking");
            return Map.of();
        }
    }

    private String checkedSearch(String search) {
        if (search == null || search.isBlank()) return "";
        String checked = search.strip().replaceAll("\\s+", " ");
        if (checked.length() > 120) {
            throw new IllegalArgumentException("search must contain at most 120 characters");
        }
        return checked;
    }

    private String normalizedTitle(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
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
