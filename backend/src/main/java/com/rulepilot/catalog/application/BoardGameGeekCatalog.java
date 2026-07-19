package com.rulepilot.catalog.application;

import java.util.List;

public interface BoardGameGeekCatalog {

    boolean configured();

    List<SearchResult> search(String query);

    List<HotGame> hotGames();

    GameDetails game(int bggId);

    record SearchResult(int bggId, String name, Integer publicationYear) {}

    record HotGame(int rank, int bggId, String name, Integer publicationYear, String thumbnailUrl) {}

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
