package com.rulepilot.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Read-only catalog capability exposed to the independent recommendation module. */
public interface BoardGameRecommendationCatalog {

    CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum);

    List<Game> findGamesByIds(List<Integer> bggIds);

    default List<Ranking> searchByNames(List<String> names) {
        return List.of();
    }

    default List<Game> resolveReferenceTitle(String title) {
        return searchByNames(List.of(title)).stream()
                .map(Ranking::bggId)
                .findFirst()
                .map(this::findGameById)
                .flatMap(value -> value)
                .map(List::of)
                .orElseGet(List::of);
    }

    /**
     * Resolves an exact player-selected title from already stored catalog evidence only.
     * Implementations must not perform a remote metadata call on this latency-sensitive path.
     */
    default List<Game> resolveLocalReferenceTitle(String title) {
        return List.of();
    }

    default Optional<Game> findGameById(int bggId) {
        return findGamesByIds(List.of(bggId)).stream().findFirst();
    }

    int gameCount();

    record CandidateSet(int sourceCount, List<Game> games) {
        public CandidateSet {
            games = List.copyOf(games);
        }
    }

    record Game(Ranking ranking, Details details) {}

    record Ranking(
            int bggId,
            String sourceName,
            Integer publicationYear,
            Integer overallRank,
            BigDecimal bayesAverage,
            BigDecimal averageRating,
            int usersRated,
            List<BggGameType> types) {
        public Ranking {
            types = types == null ? List.of() : List.copyOf(types);
        }

        public Ranking(
                int bggId,
                String sourceName,
                Integer publicationYear,
                Integer overallRank,
                BigDecimal bayesAverage,
                BigDecimal averageRating,
                int usersRated) {
            this(
                    bggId,
                    sourceName,
                    publicationYear,
                    overallRank,
                    bayesAverage,
                    averageRating,
                    usersRated,
                    List.of());
        }
    }

    record Details(
            String name,
            String officialChineseName,
            String thumbnailUrl,
            Integer minPlayers,
            Integer maxPlayers,
            Integer playingTimeMinutes,
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
        public Details(
                String name,
                String officialChineseName,
                String thumbnailUrl,
                Integer minPlayers,
                Integer maxPlayers,
                Integer playingTimeMinutes,
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
                    name,
                    officialChineseName,
                    thumbnailUrl,
                    minPlayers,
                    maxPlayers,
                    playingTimeMinutes,
                    averageWeight,
                    categories,
                    mechanics,
                    minimumPlayTimeMinutes,
                    maximumPlayTimeMinutes,
                    minimumAge,
                    suggestedMinimumAge,
                    bestWith,
                    recommendedWith,
                    languageDependenceLevel,
                    weightVotes,
                    families,
                    designers,
                    publishers,
                    "",
                    "");
        }

        public Details {
            officialChineseName = officialChineseName == null ? "" : officialChineseName;
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
    }
}
