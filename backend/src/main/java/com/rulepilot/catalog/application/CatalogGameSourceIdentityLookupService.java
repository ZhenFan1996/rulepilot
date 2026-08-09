package com.rulepilot.catalog.application;

import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class CatalogGameSourceIdentityLookupService implements CatalogGameSourceIdentityLookup {

    private final BoardGameGeekCatalog bgg;

    CatalogGameSourceIdentityLookupService(BoardGameGeekCatalog bgg) {
        this.bgg = bgg;
    }

    @Override
    public Optional<Identity> findByBggId(int bggId) {
        if (bggId <= 0 || !bgg.configured()) return Optional.empty();
        try {
            var details = bgg.game(bggId);
            var names = new ArrayList<String>();
            names.add(details.name());
            names.addAll(details.officialChineseNames());
            return Optional.of(new Identity(details.name(), names, details.publishers()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
