package com.rulepilot.catalog;

import java.util.Collection;
import java.util.Map;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/** Exposes the minimal public catalog identity needed to decorate a public lesson. */
public interface PublicGameCoverLookup {

    Optional<Cover> findByEdition(UUID editionId);

    Map<UUID, Cover> findByEditions(Collection<UUID> editionIds);

    record Cover(String gameName, int bggId, String thumbnailUrl, String bggUrl) {
        public Cover {
            if (gameName == null || gameName.isBlank() || bggId <= 0) {
                throw new IllegalArgumentException("public game cover is invalid");
            }
            gameName = gameName.strip();
            thumbnailUrl = httpsUrl(thumbnailUrl, "thumbnail URL");
            bggUrl = httpsUrl(bggUrl, "BGG URL");
        }

        private static String httpsUrl(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
            URI uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(field + " must be a public HTTPS URL");
            }
            return uri.toASCIIString();
        }
    }
}
