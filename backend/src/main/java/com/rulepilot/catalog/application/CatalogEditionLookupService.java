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
        return repository.findEdition(editionId)
                .map(edition -> new EditionReference(
                        edition.id(), edition.gameId(), edition.name(), edition.language()));
    }
}
