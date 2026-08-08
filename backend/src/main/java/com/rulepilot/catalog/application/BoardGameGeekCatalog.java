package com.rulepilot.catalog.application;

import java.math.BigDecimal;
import java.util.List;

public interface BoardGameGeekCatalog {

    boolean configured();

    List<SearchResult> search(String query);

    List<GameMatch> exactMatches(String query);

    List<HotGame> hotGames();

    List<DiscoveryGame> hotGameDetails();

    List<DiscoveryGame> gameDetails(List<Integer> bggIds);

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
            String chineseName,
            Integer publicationYear,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
            BigDecimal averageRating,
            BigDecimal averageWeight,
            List<String> categories,
            List<String> mechanics,
            Integer minimumPlayTimeMinutes,
            Integer maximumPlayTimeMinutes,
            Integer minimumAge,
            Integer suggestedMinimumAge,
            String bestWith,
            String recommendedWith,
            Integer languageDependenceLevel,
            Integer weightVotes,
            List<String> families,
            List<String> designers,
            List<String> publishers,
            String description,
            String imageUrl) {

        public DiscoveryGame {
            chineseName = SimplifiedChineseText.normalize(chineseName);
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
            bestWith = bestWith == null ? "" : bestWith;
            recommendedWith = recommendedWith == null ? "" : recommendedWith;
            families = List.copyOf(families);
            designers = List.copyOf(designers);
            publishers = List.copyOf(publishers);
            description = description == null ? "" : description;
            imageUrl = imageUrl == null ? "" : imageUrl;
        }

        public DiscoveryGame(
                int rank,
                int bggId,
                String name,
                String chineseName,
                Integer publicationYear,
                String thumbnailUrl,
                Integer minPlayers,
                Integer maxPlayers,
                Integer playingTimeMinutes,
                BigDecimal averageRating,
                BigDecimal averageWeight,
                List<String> categories,
                List<String> mechanics,
                Integer minimumPlayTimeMinutes,
                Integer maximumPlayTimeMinutes,
                Integer minimumAge,
                Integer suggestedMinimumAge,
                String bestWith,
                String recommendedWith,
                Integer languageDependenceLevel,
                Integer weightVotes,
                List<String> families,
                List<String> designers,
                List<String> publishers) {
            this(
                    rank, bggId, name, chineseName, publicationYear, thumbnailUrl,
                    minPlayers, maxPlayers, playingTimeMinutes, averageRating, averageWeight,
                    categories, mechanics, minimumPlayTimeMinutes, maximumPlayTimeMinutes,
                    minimumAge, suggestedMinimumAge, bestWith, recommendedWith,
                    languageDependenceLevel, weightVotes, families, designers, publishers, "", "");
        }

        public DiscoveryGame(
                int rank,
                int bggId,
                String name,
                String chineseName,
                Integer publicationYear,
                String thumbnailUrl,
                Integer minPlayers,
                Integer maxPlayers,
                Integer playingTimeMinutes,
                BigDecimal averageRating,
                BigDecimal averageWeight,
                List<String> categories,
                List<String> mechanics) {
            this(
                    rank,
                    bggId,
                    name,
                    chineseName,
                    publicationYear,
                    thumbnailUrl,
                    minPlayers,
                    maxPlayers,
                    playingTimeMinutes,
                    averageRating,
                    averageWeight,
                    categories,
                    mechanics,
                    playingTimeMinutes,
                    playingTimeMinutes,
                    null,
                    null,
                    "",
                    "",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "");
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
            List<String> publishers,
            List<String> officialChineseNames) {
        public GameDetails {
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
            designers = List.copyOf(designers);
            publishers = List.copyOf(publishers);
            officialChineseNames = SimplifiedChineseText.normalize(officialChineseNames).stream()
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
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
                    List.of(),
                    List.of());
        }
    }
}
