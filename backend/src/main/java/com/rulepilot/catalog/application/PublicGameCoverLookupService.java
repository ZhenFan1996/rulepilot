package com.rulepilot.catalog.application;

import com.rulepilot.catalog.PublicGameCoverLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class PublicGameCoverLookupService implements PublicGameCoverLookup {

    private final CatalogRepository catalog;

    PublicGameCoverLookupService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public java.util.Optional<Cover> findByEdition(java.util.UUID editionId) {
        return catalog.findEdition(editionId).flatMap(edition -> catalog.findGame(edition.gameId()).flatMap(game -> catalog
                .findBggMetadata(game.id())
                .filter(metadata -> !metadata.thumbnailUrl().isBlank())
                .map(metadata -> new Cover(
                        game.name(),
                        metadata.bggId(),
                        metadata.thumbnailUrl(),
                        "https://boardgamegeek.com/boardgame/" + metadata.bggId()))));
    }

    @Override
    public Map<UUID, Cover> findByEditions(Collection<UUID> editionIds) {
        if (editionIds == null || editionIds.isEmpty()) return Map.of();
        return catalog.findCoversByEditions(editionIds);
    }
}
