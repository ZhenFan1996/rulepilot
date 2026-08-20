package com.rulepilot.catalog.application;

import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BggRankedCatalogRepository {

    Optional<Snapshot> findSnapshot();

    Page find(Query query);

    default List<RankedGame> findExactName(String name) {
        return List.of();
    }

    default List<RankedGame> findRankedRange(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("BGG ranked range is invalid");
        }
        if (offset % limit != 0) return List.of();
        return find(new Query("", com.rulepilot.catalog.BggGameType.ALL, BggRankedCatalog.Sort.RANK,
                        offset / limit, limit, List.of()))
                .games();
    }

    default List<RankedGame> findByIds(List<Integer> bggIds) {
        return List.of();
    }

    void stage(UUID importId, List<RankedGame> games);

    void publish(UUID importId, Snapshot snapshot);
}
