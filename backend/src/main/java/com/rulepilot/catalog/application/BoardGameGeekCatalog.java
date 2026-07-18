package com.rulepilot.catalog.application;

import java.util.List;

public interface BoardGameGeekCatalog {

    boolean configured();

    List<SearchResult> search(String query);

    GameDetails game(int bggId);

    record SearchResult(int bggId, String name, Integer publicationYear) {}

    record GameDetails(
            int bggId,
            String name,
            String description,
            String thumbnailUrl,
            Integer publicationYear,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge) {}
}
