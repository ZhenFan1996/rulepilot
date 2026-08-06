package com.rulepilot.catalog.application;

import java.math.BigDecimal;
import java.util.List;

public interface BoardGameGeekCatalog {

    boolean configured();

    List<SearchResult> search(String query);

    List<GameMatch> exactMatches(String query);

    List<HotGame> hotGames();

    List<DiscoveryGame> hotGameDetails();

    GameDetails game(int bggId);

    record SearchResult(int bggId, String name, Integer publicationYear) {}

    record GameMatch(
            int bggId,
            String name,
            Integer publicationYear,
            String coverUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge) {}

    record HotGame(int rank, int bggId, String name, Integer publicationYear, String thumbnailUrl) {}

    record DiscoveryGame(
            int rank,
            int bggId,
            String name,
            Integer publicationYear,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            BigDecimal averageRating,
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics) {

        public DiscoveryGame {
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
        }
    }

    record GameDetails(
            int bggId,
            String name,
            String description,
            String thumbnailUrl,
            Integer publicationYear,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            Integer minimumAge,
            String imageUrl,
            BigDecimal averageRating,
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            List<String> publishers) {
        public GameDetails {
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
            designers = List.copyOf(designers);
            publishers = List.copyOf(publishers);
        }

        public GameDetails(
                int bggId,
                String name,
                String description,
                String thumbnailUrl,
                Integer publicationYear,
                Integer minPlayers,
                Integer maxPlayers,
                Integer playingTimeMinutes,
                Integer minimumAge) {
            this(
                    bggId,
                    name,
                    description,
                    thumbnailUrl,
                    publicationYear,
                    minPlayers,
                    maxPlayers,
                    playingTimeMinutes,
                    minimumAge,
                    "",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }
    }
}
