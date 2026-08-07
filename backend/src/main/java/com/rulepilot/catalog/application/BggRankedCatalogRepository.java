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

    void stage(UUID importId, List<RankedGame> games);

    void publish(UUID importId, Snapshot snapshot);
}
