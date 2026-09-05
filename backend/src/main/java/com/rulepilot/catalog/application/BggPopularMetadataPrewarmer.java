package com.rulepilot.catalog.application;

import static com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.MAX_COHORT_SIZE;
import static com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.MAX_TARGET_COUNT;

import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.CatalogCoverImages.Asset;
import com.rulepilot.catalog.CatalogCoverImages.Retryable;
import com.rulepilot.catalog.CatalogCoverImages.Variant;
import com.rulepilot.catalog.application.BggCatalogCoverPrewarmProgress.CoverCohort;
import com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.Cohort;
import com.rulepilot.catalog.BggMetadataTranslation.PrewarmResult;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Prioritizes hot-game translations before resuming ranked metadata and cover hydration. */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "rulepilot.runtime.worker-enabled", havingValue = "true")
class BggPopularMetadataPrewarmer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggPopularMetadataPrewarmer.class);
    private static final int BGG_BATCH_SIZE = 20;

    private final BggRankedCatalogRepository rankedCatalog;
    private final BoardGameGeekCatalog bgg;
    private final BggMetadataCache cache;
    private final BggMetadataLocalizationService localization;
    private final BggPopularMetadataPrewarmProgress progress;
    private final BggCatalogCoverPrewarmProgress coverProgress;
    private final TaskExecutor executor;
    private final CatalogCoverImages coverImages;
    private final TaskExecutor coverExecutor;
    private final Clock clock;
    private final boolean enabled;
    private final int targetGameCount;
    private final int metadataCohortSize;
    private final Duration leaseDuration;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    BggPopularMetadataPrewarmer(
            BggRankedCatalogRepository rankedCatalog,
            BoardGameGeekCatalog bgg,
            BggMetadataCache cache,
            BggMetadataLocalizationService localization,
            BggPopularMetadataPrewarmProgress progress,
            BggCatalogCoverPrewarmProgress coverProgress,
            @Qualifier("bggPopularPrewarmExecutor") TaskExecutor executor,
            CatalogCoverImages coverImages,
            @Qualifier("bggCoverPrewarmExecutor") TaskExecutor coverExecutor,
            @Value("${rulepilot.bgg.cache.prewarm.enabled:true}") boolean enabled,
            @Value("${rulepilot.bgg.cache.prewarm.game-count:10000}") int targetGameCount,
            @Value("${rulepilot.bgg.cache.prewarm.cohort-size:500}") int metadataCohortSize,
            @Value("${rulepilot.bgg.cache.prewarm.lease-duration:PT30M}") Duration leaseDuration) {
        this(
                rankedCatalog,
                bgg,
                cache,
                localization,
                progress,
                coverProgress,
                executor,
                coverImages,
                coverExecutor,
                Clock.systemUTC(),
                enabled,
                targetGameCount,
                metadataCohortSize,
                leaseDuration);
    }

    BggPopularMetadataPrewarmer(
            BggRankedCatalogRepository rankedCatalog,
            BoardGameGeekCatalog bgg,
            BggMetadataCache cache,
            BggMetadataLocalizationService localization,
            BggPopularMetadataPrewarmProgress progress,
            BggCatalogCoverPrewarmProgress coverProgress,
            TaskExecutor executor,
            CatalogCoverImages coverImages,
            TaskExecutor coverExecutor,
            Clock clock,
            boolean enabled,
            int targetGameCount,
            int metadataCohortSize,
            Duration leaseDuration) {
        checkedConfiguration(targetGameCount, metadataCohortSize, leaseDuration);
        this.rankedCatalog = rankedCatalog;
        this.bgg = bgg;
        this.cache = cache;
        this.localization = localization;
        this.progress = progress;
        this.coverProgress = coverProgress;
        this.executor = executor;
        this.coverImages = coverImages;
        this.coverExecutor = coverExecutor;
        this.clock = clock;
        this.enabled = enabled;
        this.targetGameCount = targetGameCount;
        this.metadataCohortSize = metadataCohortSize;
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(
            initialDelayString = "${rulepilot.bgg.cache.prewarm.initial-delay:PT5M}",
            fixedDelayString = "${rulepilot.bgg.cache.prewarm.resume-delay:PT1H}")
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
        int target = snapshot == null ? 0 : Math.min(targetGameCount, snapshot.gameCount());
        String snapshotIdentity = snapshot == null ? "0".repeat(64) : snapshot.sha256();
        Cohort cohort = progress.claim(
                        snapshotIdentity,
                        target,
                        metadataCohortSize,
                        clock.instant(),
                        leaseDuration)
                .orElse(null);
        if (cohort != null) prewarmMetadata(cohort, target, clock.instant().plus(leaseDuration));
        try {
            if (target > 0) prewarmCovers(snapshotIdentity, target);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG catalog cover prewarm could not be claimed");
        }
    }

    private void prewarmMetadata(Cohort cohort, int target, java.time.Instant deadline) {
        boolean canTranslate = translateHotGames(deadline);
        int metadataNext = clock.instant().isBefore(deadline)
                ? hydrate(cohort.metadataStart(), cohort.metadataEnd()) : cohort.metadataStart();
        if (canTranslate) {
            try {
                translateCachedSources(deadline);
            } catch (RuntimeException exception) {
                LOGGER.warn("BGG translation source refresh unavailable; completed metadata remains usable");
            }
        }
        try {
            progress.complete(cohort, metadataNext, clock.instant());
            LOGGER.info("BGG metadata prewarm advanced details to {} / {}", metadataNext, target);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG metadata prewarm progress could not be persisted; the lease will be retried");
        }
    }

    private void translateCachedSources(java.time.Instant deadline) {
        int afterBggId = 0;
        int ready = 0;
        int deferred = 0;
        while (clock.instant().isBefore(deadline) && !Thread.currentThread().isInterrupted()) {
            var sources = cache.translationSources(afterBggId, metadataCohortSize, clock.instant());
            if (sources.isEmpty()) break;
            for (var source : sources) {
                if (!clock.instant().isBefore(deadline)) return;
                PrewarmResult result = localization.prewarm(source);
                if (result.status() == com.rulepilot.catalog.BggMetadataTranslation.PrewarmStatus.READY) ready++;
                else deferred++;
                if (sharedTranslationCapacityUnavailable(result)) {
                    LOGGER.info("BGG translation refresh paused: ready={}, deferred={}, status={}", ready, deferred, result.status());
                    return;
                }
                afterBggId = source.bggId();
            }
        }
        LOGGER.info("BGG translation source refresh complete: ready={}, deferred={}", ready, deferred);
    }

    private boolean sharedTranslationCapacityUnavailable(PrewarmResult result) {
        return switch (result.status()) {
            case RETRY_NOT_CONFIGURED, RETRY_PROVIDER_BUSY, RETRY_HOURLY_BUDGET -> true;
            default -> false;
        };
    }

    private boolean translateHotGames(java.time.Instant deadline) {
        try {
            for (DiscoveryGame game : bgg.hotGameDetails()) {
                if (!clock.instant().isBefore(deadline) || Thread.currentThread().isInterrupted()) return false;
                PrewarmResult result = localization.prewarm(game);
                if (sharedTranslationCapacityUnavailable(result)) {
                    LOGGER.info("BGG hot game translation paused for game {} with status {}",
                            game.bggId(), result.status());
                    return false;
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG hot game metadata unavailable; cached-source refresh will continue");
        }
        return true;
    }

    private void prewarmCovers(String snapshotSha256, int target) {
        CoverCohort cohort = coverProgress.claim(
                        snapshotSha256,
                        coverImages.formatVersion(),
                        target,
                        metadataCohortSize,
                        clock.instant(),
                        leaseDuration)
                .orElse(null);
        if (cohort == null) return;

        int next = warmCoverRange(cohort.start(), cohort.end());
        try {
            coverProgress.complete(cohort, next, clock.instant());
            LOGGER.info(
                    "BGG catalog cover prewarm advanced format {} to {} / {}",
                    cohort.formatVersion(),
                    next,
                    target);
        } catch (RuntimeException exception) {
            LOGGER.warn("BGG catalog cover prewarm progress could not be persisted; the lease will be retried");
        }
    }

    private int warmCoverRange(int start, int end) {
        int next = start;
        while (next < end) {
            List<Integer> ids = rankedIds(next, Math.min(BGG_BATCH_SIZE, end - next));
            if (ids.isEmpty()) return next;
            try {
                bgg.gameDetails(ids);
                if (!warmCovers(ids)) {
                    LOGGER.warn("BGG catalog cover prewarm paused at ranked offset {}", next);
                    return next;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("BGG catalog cover prewarm paused at ranked offset {}", next);
                return next;
            }
            next += ids.size();
        }
        return next;
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

    private boolean warmCovers(List<Integer> bggIds) {
        List<CompletableFuture<Asset>> warmups = bggIds.stream()
                .flatMap(bggId -> java.util.stream.Stream.of(
                        new CoverWarmup(bggId, Variant.COMPACT),
                        new CoverWarmup(bggId, Variant.DISPLAY)))
                .distinct()
                .map(request -> CompletableFuture.supplyAsync(
                        () -> coverImages.load(request.bggId(), request.variant()), coverExecutor))
                .toList();
        CompletableFuture.allOf(warmups.toArray(CompletableFuture<?>[]::new)).join();
        return warmups.stream().map(CompletableFuture::join).noneMatch(Retryable.class::isInstance);
    }

    private record CoverWarmup(int bggId, Variant variant) {}

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
            Duration leaseDuration) {
        if (targetGameCount < 0 || targetGameCount > MAX_TARGET_COUNT) {
            throw new IllegalArgumentException("BGG prewarm game count must be between 0 and 10000");
        }
        if (metadataCohortSize < 1 || metadataCohortSize > MAX_COHORT_SIZE) {
            throw new IllegalArgumentException("BGG metadata prewarm cohort must be between 1 and 500 games");
        }
        if (leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(5)) < 0
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("BGG prewarm lease must be between five minutes and two hours");
        }
    }
}
