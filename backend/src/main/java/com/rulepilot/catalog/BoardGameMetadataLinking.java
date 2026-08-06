package com.rulepilot.catalog;

import java.util.UUID;

/** Persists one player-confirmed BGG catalog identity without exposing catalog repositories. */
public interface BoardGameMetadataLinking {

    Link confirm(int bggId);

    record Link(
            UUID gameId,
            UUID editionId,
            int bggId,
            String gameName,
            String coverUrl,
            boolean alreadyImported) {

        public Link {
            if (gameId == null || editionId == null || bggId <= 0 || gameName == null || gameName.isBlank()) {
                throw new IllegalArgumentException("confirmed BGG link is invalid");
            }
            gameName = gameName.strip();
            coverUrl = coverUrl == null ? "" : coverUrl.strip();
        }
    }
}
