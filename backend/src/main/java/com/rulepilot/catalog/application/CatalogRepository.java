package com.rulepilot.catalog.application;

import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogRepository {

    Game save(Game game);

    GameEdition save(GameEdition edition);

    Expansion save(Expansion expansion);

    Optional<Game> findGame(UUID gameId);

    Optional<GameEdition> findEdition(UUID editionId);

    List<Game> findGames();

    List<GameEdition> findEditions(UUID gameId);

    List<Expansion> findExpansions(UUID gameId);
}
