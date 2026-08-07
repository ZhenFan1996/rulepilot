package com.rulepilot.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface BggRankedCatalog {

    Snapshot snapshot();

    Page find(Query query);

    enum Sort {
        HOT,
        RATING,
        RANK
    }

    enum GameType {
        ALL,
        ABSTRACT,
        CUSTOMIZABLE,
        CHILDREN,
        FAMILY,
        PARTY,
        STRATEGY,
        THEMATIC,
        WAR,
        EXPANSION
    }

    record Query(String search, GameType type, Sort sort, int page, int size, List<Integer> hotIds) {
        public Query {
            hotIds = List.copyOf(hotIds);
        }
    }

    record RankedGame(
            int bggId,
            String sourceName,
            Integer publicationYear,
            Integer overallRank,
            BigDecimal bayesAverage,
            BigDecimal averageRating,
            int usersRated,
            boolean expansion,
            Map<GameType, Integer> typeRanks) {
        public RankedGame {
            typeRanks = Map.copyOf(typeRanks);
        }

        public List<GameType> types() {
            return List.copyOf(typeRanks.keySet());
        }
    }

    record Page(long total, int page, int size, List<RankedGame> games) {
        public Page {
            games = List.copyOf(games);
        }
    }

    record Snapshot(Instant importedAt, LocalDate sourceDate, int gameCount, String sha256) {}
}
