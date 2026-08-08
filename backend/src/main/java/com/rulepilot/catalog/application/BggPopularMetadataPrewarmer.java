package com.rulepilot.catalog.application;

import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.Sort;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** Low-priority hydration of recommendation metadata for the most-ranked BGG games. */
@Component
@Profile("!test")
class BggPopularMetadataPrewarmer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggPopularMetadataPrewarmer.class);
    private static final int BGG_BATCH_SIZE = 20;

    private final BggRankedCatalogRepository rankedCatalog;
    private final BoardGameGeekCatalog bgg;
    private final TaskExecutor executor;
    private final boolean enabled;
    private final int gameCount;
    private final AtomicBoolean started = new AtomicBoolean();

    BggPopularMetadataPrewarmer(
            BggRankedCatalogRepository rankedCatalog,
            BoardGameGeekCatalog bgg,
            @Qualifier("bggPopularPrewarmExecutor") TaskExecutor executor,
            @Value("${rulepilot.bgg.cache.prewarm.enabled:true}") boolean enabled,
            @Value("${rulepilot.bgg.cache.prewarm.game-count:500}") int gameCount) {
        if (gameCount < 0 || gameCount > 5_000) {
            throw new IllegalArgumentException("BGG prewarm game count must be between 0 and 5000");
        }
        this.rankedCatalog = rankedCatalog;
        this.bgg = bgg;
        this.executor = executor;
        this.enabled = enabled;
        this.gameCount = gameCount;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled || gameCount == 0 || !bgg.configured() || !started.compareAndSet(false, true)) return;
        try {
            executor.execute(this::prewarm);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG popular metadata prewarm could not be scheduled");
        }
    }

    void prewarm() {
        int hydrated = 0;
        for (int page = 0; hydrated < gameCount; page++) {
            int batchSize = Math.min(BGG_BATCH_SIZE, gameCount - hydrated);
            List<Integer> ids = rankedCatalog
                    .find(new Query("", BggGameType.ALL, Sort.RANK, page, BGG_BATCH_SIZE, List.of()))
                    .games()
                    .stream()
                    .map(BggRankedCatalog.RankedGame::bggId)
                    .distinct()
                    .limit(batchSize)
                    .toList();
            if (ids.isEmpty()) break;
            try {
                bgg.gameDetails(ids);
            } catch (RuntimeException exception) {
                LOGGER.warn("BGG popular metadata prewarm stopped after {} games", hydrated);
                return;
            }
            hydrated += ids.size();
            if (ids.size() < batchSize) break;
        }
        LOGGER.info("BGG popular metadata prewarm checked {} ranked games", hydrated);
    }
}
