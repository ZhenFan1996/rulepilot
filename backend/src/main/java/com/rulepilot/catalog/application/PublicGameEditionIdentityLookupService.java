package com.rulepilot.catalog.application;

import com.rulepilot.catalog.PublicGameEditionIdentityLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
class PublicGameEditionIdentityLookupService implements PublicGameEditionIdentityLookup {

    private final CatalogRepository catalog;

    PublicGameEditionIdentityLookupService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public Map<UUID, Integer> findBggIds(Collection<UUID> editionIds) {
        return catalog.findBggIdsByEditions(editionIds);
    }
}
