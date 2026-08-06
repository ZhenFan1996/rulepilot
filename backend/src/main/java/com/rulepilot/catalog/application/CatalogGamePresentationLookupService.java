package com.rulepilot.catalog.application;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class CatalogGamePresentationLookupService implements CatalogGamePresentationLookup {

    private final CatalogRepository catalog;

    CatalogGamePresentationLookupService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public java.util.Optional<Presentation> findByEdition(java.util.UUID editionId) {
        if (editionId == null) return java.util.Optional.empty();
        return catalog.findEdition(editionId).flatMap(edition -> catalog.findGame(edition.gameId()).flatMap(game -> catalog
                .findBggMetadata(game.id())
                .map(metadata -> new Presentation(
                        edition.id(),
                        game.name(),
                        edition.name(),
                        edition.language(),
                        edition.publicationYear(),
                        metadata.bggId(),
                        metadata.thumbnailUrl(),
                        metadata.minPlayers(),
                        metadata.maxPlayers(),
                        metadata.playingTimeMinutes(),
                        metadata.minimumAge(),
                        "https://boardgamegeek.com/boardgame/" + metadata.bggId()))));
    }
}
