package com.rulepilot.catalog;

import java.math.BigDecimal;
import java.util.List;

/** Read-only catalog capability exposed to the independent recommendation module. */
public interface BoardGameRecommendationCatalog {

    CandidateSet findCandidates(BggGameType requiredType, List<BggGameType> suggestedTypes, int maximum);

    List<Game> findGamesByIds(List<Integer> bggIds);

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
            int usersRated) {}

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
            List<String> publishers) {
        public Details {
            officialChineseName = officialChineseName == null ? "" : officialChineseName;
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
            bestWith = bestWith == null ? "" : bestWith;
            recommendedWith = recommendedWith == null ? "" : recommendedWith;
            families = List.copyOf(families);
            designers = List.copyOf(designers);
            publishers = List.copyOf(publishers);
        }
    }
}
