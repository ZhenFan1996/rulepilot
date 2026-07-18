package com.rulepilot.catalog;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CatalogEditionLookup {

    Optional<EditionReference> findEdition(UUID editionId);

    record EditionReference(UUID id, UUID gameId, String name, String language, Set<UUID> compatibleExpansionIds) {
        public EditionReference {
            compatibleExpansionIds = Set.copyOf(compatibleExpansionIds);
        }
    }
}
