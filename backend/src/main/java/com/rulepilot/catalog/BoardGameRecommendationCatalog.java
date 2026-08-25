package com.rulepilot.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Read-only catalog capability exposed to the independent recommendation module. */
public interface BoardGameRecommendationCatalog {

    CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum);

    List<Game> findGamesByIds(List<Integer> bggIds);

    /** Searches the ranked catalog with exact BGG taxonomy and relationship filters. */
    default CandidateSet searchGames(CatalogFilters filters) {
        BggGameType required = filters.types().size() == 1 ? filters.types().getFirst() : BggGameType.ALL;
        return findCandidates(required, filters.types(), filters.maximum());
    }

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

    record CandidateSet(int sourceCount, List<Game> games, boolean pageExhausted) {
        public CandidateSet(int sourceCount, List<Game> games) {
            this(sourceCount, games, true);
        }

        public CandidateSet {
            games = List.copyOf(games);
        }
    }

    record CatalogFilters(
            List<BggGameType> types,
            List<String> categories,
            List<String> mechanics,
            List<String> designers,
            List<String> publishers,
            List<String> families,
            Integer minimumPublicationYear,
            Integer maximumPublicationYear,
            BigDecimal minimumAverageRating,
            Integer minimumRatingsCount,
            String textQuery,
            CatalogSort sort,
            int maximum,
            int offset) {
        public CatalogFilters(
                List<BggGameType> types,
                List<String> categories,
                List<String> mechanics,
                List<String> designers,
                int maximum) {
            this(types, categories, mechanics, designers, maximum, 0);
        }

        public CatalogFilters(
                List<BggGameType> types,
                List<String> categories,
                List<String> mechanics,
                List<String> designers,
                int maximum,
                int offset) {
            this(
                    types,
                    categories,
                    mechanics,
                    designers,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    CatalogSort.RANK,
                    maximum,
                    offset);
        }

        public CatalogFilters {
            types = types == null ? List.of() : List.copyOf(types);
            categories = categories == null ? List.of() : List.copyOf(categories);
            mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
            designers = designers == null ? List.of() : List.copyOf(designers);
            publishers = publishers == null ? List.of() : List.copyOf(publishers);
            families = families == null ? List.of() : List.copyOf(families);
            textQuery = textQuery == null ? null : textQuery.strip().replaceAll("\\s+", " ");
            if (textQuery != null && textQuery.isBlank()) textQuery = null;
            sort = sort == null ? CatalogSort.RANK : sort;
            if (maximum < 1 || maximum > 20) {
                throw new IllegalArgumentException("BGG catalog filter maximum must be between 1 and 20");
            }
            if (offset < 0 || offset > 200) {
                throw new IllegalArgumentException("BGG catalog filter offset must be between 0 and 200");
            }
            if (minimumPublicationYear != null
                    && (minimumPublicationYear < 1 || minimumPublicationYear > 2100)
                    || maximumPublicationYear != null
                            && (maximumPublicationYear < 1 || maximumPublicationYear > 2100)
                    || minimumPublicationYear != null
                            && maximumPublicationYear != null
                            && minimumPublicationYear > maximumPublicationYear) {
                throw new IllegalArgumentException("BGG catalog publication-year filters are invalid");
            }
            if (minimumAverageRating != null
                    && (minimumAverageRating.compareTo(BigDecimal.ZERO) < 0
                            || minimumAverageRating.compareTo(BigDecimal.TEN) > 0)) {
                throw new IllegalArgumentException("BGG catalog rating filter is invalid");
            }
            if (minimumRatingsCount != null && (minimumRatingsCount < 0 || minimumRatingsCount > 100_000_000)) {
                throw new IllegalArgumentException("BGG catalog ratings-count filter is invalid");
            }
            if (textQuery != null && textQuery.length() > 240) {
                throw new IllegalArgumentException("BGG catalog text query is invalid");
            }
            if (sort == CatalogSort.RELEVANCE && textQuery == null) {
                throw new IllegalArgumentException("BGG catalog relevance sort requires a text query");
            }
        }
    }

    enum CatalogSort {
        RANK,
        RATING,
        POPULARITY,
        NEWEST,
        RELEVANCE
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
