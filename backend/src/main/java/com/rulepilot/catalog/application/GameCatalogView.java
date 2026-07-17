package com.rulepilot.catalog.application;

import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.util.List;

public record GameCatalogView(Game game, List<GameEdition> editions, List<Expansion> expansions) {

    public GameCatalogView {
        editions = List.copyOf(editions);
        expansions = List.copyOf(expansions);
    }
}
