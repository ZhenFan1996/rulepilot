package com.rulepilot.catalog.application;

import com.rulepilot.catalog.domain.BggGameMetadata;
import com.rulepilot.catalog.domain.Expansion;
import com.rulepilot.catalog.domain.Game;
import com.rulepilot.catalog.domain.GameEdition;
import java.util.List;
import java.util.Optional;

public record GameCatalogView(
        Game game,
        List<GameEdition> editions,
        List<Expansion> expansions,
        Optional<BggGameMetadata> bggMetadata) {

    public GameCatalogView {
        editions = List.copyOf(editions);
        expansions = List.copyOf(expansions);
        bggMetadata = bggMetadata == null ? Optional.empty() : bggMetadata;
    }
}
