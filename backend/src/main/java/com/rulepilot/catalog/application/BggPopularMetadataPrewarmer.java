package com.rulepilot.catalog.application;

import com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.Cohort;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Low-priority hydration of recommendation metadata for the most-ranked BGG games. */
@Component
@Profile("!test")
class BggPopularMetadataPrewarmer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggPopularMetadataPrewarmer.class);
    private static final int BGG_BATCH_SIZE = 20;

    private final BggRankedCatalogRepository rankedCatalog;
    private final BoardGameGeekCatalog bgg;
    private final BggMetadataLocalizationService localization;
    private final BggPopularMetadataPrewarmProgress progress;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    private final int targetGameCount;
    private final int metadataCohortSize;
    private final int translationCohortSize;
    private final Duration leaseDuration;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    BggPopularMetadataPrewarmer(
            BggRankedCatalogRepository rankedCatalog,
            BoardGameGeekCatalog bgg,
            BggMetadataLocalizationService localization,
            BggPopularMetadataPrewarmProgress progress,
            @Qualifier("bggPopularPrewarmExecutor") TaskExecutor executor,
            @Value("${rulepilot.bgg.cache.prewarm.enabled:true}") boolean enabled,
            @Value("${rulepilot.bgg.cache.prewarm.game-count:2000}") int targetGameCount,
            @Value("${rulepilot.bgg.cache.prewarm.cohort-size:500}") int metadataCohortSize,
            @Value("${rulepilot.bgg.cache.prewarm.translation-cohort-size:60}") int translationCohortSize,
            @Value("${rulepilot.bgg.cache.prewarm.lease-duration:PT30M}") Duration leaseDuration) {
        this(
                rankedCatalog,
                bgg,
                localization,
                progress,
                executor,
                Clock.systemUTC(),
                enabled,
                targetGameCount,
                metadataCohortSize,
                translationCohortSize,
                leaseDuration);
    }

    BggPopularMetadataPrewarmer(
            BggRankedCatalogRepository rankedCatalog,
            BoardGameGeekCatalog bgg,
            BggMetadataLocalizationService localization,
            BggPopularMetadataPrewarmProgress progress,
            TaskExecutor executor,
            Clock clock,
            boolean enabled,
            int targetGameCount,
            int metadataCohortSize,
            int translationCohortSize,
            Duration leaseDuration) {
        checkedConfiguration(targetGameCount, metadataCohortSize, translationCohortSize, leaseDuration);
        this.rankedCatalog = rankedCatalog;
        this.bgg = bgg;
        this.localization = localization;
        this.progress = progress;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.targetGameCount = targetGameCount;
        this.metadataCohortSize = metadataCohortSize;
        this.translationCohortSize = translationCohortSize;
        this.leaseDuration = leaseDuration;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        schedule();
    }

    @Scheduled(fixedDelayString = "${rulepilot.bgg.cache.prewarm.resume-delay:PT2H}")
    void resume() {
        schedule();
    }

    private void schedule() {
        if (!enabled || targetGameCount == 0 || !bgg.configured() || !running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    prewarm();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            LOGGER.warn("BGG popular metadata prewarm could not be scheduled");
        }
    }

    void prewarm() {
        BggRankedCatalog.Snapshot snapshot = rankedCatalog.findSnapshot().orElse(null);
        if (snapshot == null || snapshot.gameCount() == 0) return;
        int target = Math.min(targetGameCount, snapshot.gameCount());
        Cohort cohort = progress.claim(
                        snapshot.sha256(),
                        target,
                        metadataCohortSize,
                        translationCohortSize,
                        clock.instant(),
                        leaseDuration)
                .orElse(null);
        if (cohort == null) return;

        int metadataNext = hydrate(cohort.metadataStart(), cohort.metadataEnd());
        int translationNext = translate(cohort.translationStart(), cohort.translationEnd());
        try {
            progress.complete(cohort, metadataNext, translationNext, clock.instant());
            LOGGER.info(
                    "BGG popular metadata prewarm advanced details to {} / {} and translations to {} / {}",
                    metadataNext,
                    target,
                    translationNext,
                    target);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG popular metadata prewarm progress could not be persisted; the lease will be retried");
        }
    }

    private int hydrate(int start, int end) {
        int next = start;
        while (next < end) {
            List<Integer> ids = rankedIds(next, Math.min(BGG_BATCH_SIZE, end - next));
            if (ids.isEmpty()) return next;
            try {
                bgg.gameDetails(ids);
            } catch (RuntimeException exception) {
                LOGGER.warn("BGG popular metadata hydration paused at ranked offset {}", next);
                return next;
            }
            next += ids.size();
        }
        return next;
    }

    private int translate(int start, int end) {
        int next = start;
        while (next < end) {
            List<Integer> ids = rankedIds(next, Math.min(BGG_BATCH_SIZE, end - next));
            if (ids.isEmpty()) return next;
            Map<Integer, DiscoveryGame> details;
            try {
                details = bgg.gameDetails(ids).stream().collect(java.util.stream.Collectors.toMap(
                        DiscoveryGame::bggId,
                        game -> game,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new));
            } catch (RuntimeException exception) {
                LOGGER.warn("BGG popular metadata translation paused while reading ranked offset {}", next);
                return next;
            }
            for (Integer id : ids) {
                DiscoveryGame game = details.get(id);
                if (game != null && !localization.prewarm(game)) return next;
                next++;
            }
        }
        return next;
    }

    private List<Integer> rankedIds(int offset, int limit) {
        return rankedCatalog.findRankedRange(offset, limit).stream()
                .map(BggRankedCatalog.RankedGame::bggId)
                .distinct()
                .limit(limit)
                .toList();
    }

    private void checkedConfiguration(
            int targetGameCount,
            int metadataCohortSize,
            int translationCohortSize,
            Duration leaseDuration) {
        if (targetGameCount < 0 || targetGameCount > 5_000) {
            throw new IllegalArgumentException("BGG prewarm game count must be between 0 and 5000");
        }
        if (metadataCohortSize < 1 || metadataCohortSize > 500) {
            throw new IllegalArgumentException("BGG metadata prewarm cohort must be between 1 and 500 games");
        }
        if (translationCohortSize < 1 || translationCohortSize > 500) {
            throw new IllegalArgumentException("BGG translation prewarm cohort must be between 1 and 500 games");
        }
        if (leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(5)) < 0
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("BGG prewarm lease must be between five minutes and two hours");
        }
    }
}
