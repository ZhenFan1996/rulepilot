package com.rulepilot.catalog;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/** Read-only catalog decoration for player-facing surfaces; never a rule-evidence input. */
public interface CatalogGamePresentationLookup {

    Optional<Presentation> findByEdition(UUID editionId);

    record Presentation(
            UUID editionId,
            String gameName,
            String editionName,
            String language,
            Integer publicationYear,
            int bggId,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            String bggUrl) {
        public Presentation {
            if (editionId == null || gameName == null || gameName.isBlank()
                    || editionName == null || editionName.isBlank() || language == null || language.isBlank()
                    || bggId <= 0) {
                throw new IllegalArgumentException("catalog game presentation is invalid");
            }
            gameName = gameName.strip();
            editionName = editionName.strip();
            language = language.strip();
            thumbnailUrl = optionalHttpsUrl(thumbnailUrl, "thumbnail URL");
            bggUrl = requiredHttpsUrl(bggUrl, "BGG URL");
        }

        private static String optionalHttpsUrl(String value, String field) {
            return value == null || value.isBlank() ? "" : requiredHttpsUrl(value, field);
        }

        private static String requiredHttpsUrl(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
            URI uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(field + " must be a public HTTPS URL");
            }
            return uri.toASCIIString();
        }
    }
}
