package com.rulepilot.catalog;

import java.util.List;

/** Exposes attributed catalog candidates without treating catalog data as rule evidence. */
public interface BoardGameMetadataMatching {

    List<Candidate> findExactCandidates(String playerFacingTitle);

    record Candidate(
            int bggId,
            String name,
            Integer publicationYear,
            String coverUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            boolean normalizedTitleMatch) {

        public Candidate {
            if (bggId <= 0 || name == null || name.isBlank()) {
                throw new IllegalArgumentException("BGG metadata candidate is invalid");
            }
            name = name.strip();
            coverUrl = coverUrl == null ? "" : coverUrl.strip();
        }
    }
}
