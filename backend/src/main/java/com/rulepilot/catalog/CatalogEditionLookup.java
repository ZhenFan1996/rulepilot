package com.rulepilot.catalog;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CatalogEditionLookup {

    Optional<EditionReference> findEdition(UUID editionId);

    record EditionReference(
            UUID id,
            UUID gameId,
            String gameName,
            String name,
            String language,
            Set<UUID> compatibleExpansionIds) {
        public EditionReference {
            if (id == null || gameId == null || gameName == null || gameName.isBlank()
                    || name == null || name.isBlank() || language == null || language.isBlank()) {
                throw new IllegalArgumentException("catalog edition identity is invalid");
            }
            gameName = gameName.strip();
            name = name.strip();
            language = language.strip();
            compatibleExpansionIds = Set.copyOf(compatibleExpansionIds);
        }
    }
}
