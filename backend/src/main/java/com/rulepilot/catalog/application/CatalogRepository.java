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

    List<Expansion> findExpansions(UUID gameId);

    Map<UUID, Cover> findCoversByEditions(Collection<UUID> editionIds);
}
