package com.rulepilot.catalog.application;

import com.rulepilot.catalog.PublicGameCoverLookup.Cover;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CatalogRepository {

    Game save(Game game);

    GameEdition save(GameEdition edition);

    boolean confirmEditionLanguageIfUnknown(UUID editionId, String language);

    Expansion save(Expansion expansion);

    BggGameMetadata save(BggGameMetadata metadata);

    Optional<Game> findGame(UUID gameId);

    Optional<Game> findGameByName(String name);

    Optional<GameEdition> findEdition(UUID editionId);

    Optional<GameEdition> findEdition(UUID gameId, String name, String language);

    Optional<Game> findGameByBggId(int bggId);

    Optional<BggGameMetadata> findBggMetadata(UUID gameId);

    List<Game> findGames();

    List<GameEdition> findEditions(UUID gameId);

    default Map<UUID, List<GameEdition>> findEditionsByGames(Collection<UUID> gameIds) {
        if (gameIds == null || gameIds.isEmpty()) return Map.of();
        return gameIds.stream().distinct().collect(java.util.stream.Collectors.toMap(
                gameId -> gameId,
                this::findEditions,
                (first, ignored) -> first,
                java.util.LinkedHashMap::new));
    }

    List<Expansion> findExpansions(UUID gameId);

    default Map<UUID, List<Expansion>> findExpansionsByGames(Collection<UUID> gameIds) {
        if (gameIds == null || gameIds.isEmpty()) return Map.of();
        return gameIds.stream().distinct().collect(java.util.stream.Collectors.toMap(
                gameId -> gameId,
                this::findExpansions,
                (first, ignored) -> first,
                java.util.LinkedHashMap::new));
    }

    default Map<UUID, BggGameMetadata> findBggMetadataByGames(Collection<UUID> gameIds) {
        if (gameIds == null || gameIds.isEmpty()) return Map.of();
        var result = new java.util.LinkedHashMap<UUID, BggGameMetadata>();
        gameIds.stream().distinct().forEach(gameId -> findBggMetadata(gameId)
                .ifPresent(metadata -> result.put(gameId, metadata)));
        return Map.copyOf(result);
    }

    Map<UUID, Cover> findCoversByEditions(Collection<UUID> editionIds);
}
