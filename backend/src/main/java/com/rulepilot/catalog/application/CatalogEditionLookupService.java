package com.rulepilot.catalog.application;

import com.rulepilot.catalog.CatalogEditionLookup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class CatalogEditionLookupService implements CatalogEditionLookup {

    private final CatalogRepository repository;

    CatalogEditionLookupService(CatalogRepository repository) {
        this.repository = repository;
    }

    @Override
    public java.util.Optional<EditionReference> findEdition(java.util.UUID editionId) {
        return repository.findEdition(editionId).flatMap(edition -> repository.findGame(edition.gameId())
                .map(game -> new EditionReference(
                        edition.id(), edition.gameId(), game.name(), edition.name(), edition.language(),
                        repository.findExpansions(edition.gameId()).stream()
                                .filter(expansion -> expansion.compatibleEditionIds().contains(edition.id()))
                                .map(com.rulepilot.catalog.domain.Expansion::id)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()))));
    }
}
