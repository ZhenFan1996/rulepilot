package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.rulepilot.catalog.BggMetadataTranslation;
import com.rulepilot.catalog.BggGameType;
import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.CatalogCoverImages.Absent;
import com.rulepilot.catalog.CatalogCoverImages.Asset;
import com.rulepilot.catalog.CatalogCoverImages.Retryable;
import com.rulepilot.catalog.CatalogCoverImages.Variant;
import com.rulepilot.catalog.application.BggCatalogCoverPrewarmProgress.CoverCohort;
import com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.Cohort;
import com.rulepilot.catalog.application.BggRankedCatalog.Page;
import com.rulepilot.catalog.application.BggRankedCatalog.Query;
import com.rulepilot.catalog.application.BggRankedCatalog.RankedGame;
import com.rulepilot.catalog.application.BggRankedCatalog.Snapshot;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.DiscoveryGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameDetails;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.GameMatch;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.HotGame;
import com.rulepilot.catalog.application.BoardGameGeekCatalog.SearchResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;

class BggPopularMetadataPrewarmerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void runsOnlyInTheDedicatedWorkerRuntime() {
        ConditionalOnProperty ownership = BggPopularMetadataPrewarmer.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(ownership).isNotNull();
        assertThat(ownership.name()).containsExactly("rulepilot.runtime.worker-enabled");
        assertThat(ownership.havingValue()).isEqualTo("true");
        assertThat(ownership.matchIfMissing()).isFalse();
    }

    @Test
    void defersLowPriorityPrewarmUntilTheWorkerStartupLaneIsClear() throws Exception {
        Scheduled schedule = BggPopularMetadataPrewarmer.class
                .getDeclaredMethod("resume")
                .getAnnotation(Scheduled.class);

        assertThat(schedule).isNotNull();
        assertThat(schedule.initialDelayString())
                .isEqualTo("${rulepilot.bgg.cache.prewarm.initial-delay:PT5M}");
        assertThat(schedule.fixedDelayString())
                .isEqualTo("${rulepilot.bgg.cache.prewarm.resume-delay:PT1H}");
    }

    @Test
    void acceptsTheTenThousandGamePrewarmTargetUsedByProduction() {
        assertThatCode(() -> new BggPopularMetadataPrewarmer(
                        new MemoryRankedCatalog(0),
                        new RecordingBgg(),
                        new BggMetadataLocalizationService(new RecordingTranslation(-1)),
                        new RecordingProgress(null),
                        new RecordingCoverProgress(null),
                        new SyncTaskExecutor(),
                        new RecordingCoverImages(),
                        new SyncTaskExecutor(),
                        CLOCK,
                        false,
                        10_000,
                        500,
                        60,
                        Duration.ofMinutes(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void hydratesOneFiveHundredStyleCohortAndAdvancesTranslationsIndependently() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(60);
        RecordingBgg bgg = new RecordingBgg();
        RecordingTranslation translations = new RecordingTranslation(-1);
        RecordingCoverImages covers = new RecordingCoverImages();
        RecordingProgress progress = new RecordingProgress(new Cohort(
                UUID.randomUUID(), "a".repeat(64), 0, 45, 0, 3));
        RecordingCoverProgress coverProgress = new RecordingCoverProgress(new CoverCohort(
                UUID.randomUUID(), "a".repeat(64), covers.formatVersion(), 0, 45));
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked,
                bgg,
                new BggMetadataLocalizationService(translations),
                progress,
                coverProgress,
                new SyncTaskExecutor(),
                covers,
                new SyncTaskExecutor(),
                CLOCK,
                true,
                45,
                45,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(bgg.batches).hasSize(7);
        assertThat(bgg.batches.get(0)).containsExactlyElementsOf(ids(1, 20));
        assertThat(bgg.batches.get(1)).containsExactlyElementsOf(ids(21, 40));
        assertThat(bgg.batches.get(2)).containsExactlyElementsOf(ids(41, 45));
        assertThat(bgg.batches.get(3)).containsExactlyElementsOf(ids(1, 20));
        assertThat(bgg.batches.get(4)).containsExactlyElementsOf(ids(21, 40));
        assertThat(bgg.batches.get(5)).containsExactlyElementsOf(ids(41, 45));
        assertThat(bgg.batches.get(6)).containsExactly(1, 2, 3);
        assertThat(translations.translatedIds).containsExactly(1, 2, 3);
        assertThat(covers.requests).hasSize(90);
        assertThat(covers.requests.getFirst()).isEqualTo("1:COMPACT");
        assertThat(covers.requests.get(1)).isEqualTo("1:DISPLAY");
        assertThat(covers.requests.getLast()).isEqualTo("45:DISPLAY");
        assertThat(progress.metadataNext).isEqualTo(45);
        assertThat(progress.translationNext).isEqualTo(3);
        assertThat(coverProgress.claimedFormatVersion).isEqualTo(covers.formatVersion());
        assertThat(coverProgress.next).isEqualTo(45);
    }

    @Test
    void stopsOnlyTheTranslationCursorAtTheFirstUnavailableTranslation() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(20);
        RecordingBgg bgg = new RecordingBgg();
        RecordingTranslation translations = new RecordingTranslation(2);
        RecordingProgress progress = new RecordingProgress(new Cohort(
                UUID.randomUUID(), "a".repeat(64), 0, 20, 0, 3));
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked,
                bgg,
                new BggMetadataLocalizationService(translations),
                progress,
                new RecordingCoverProgress(null),
                new SyncTaskExecutor(),
                new RecordingCoverImages(),
                new SyncTaskExecutor(),
                CLOCK,
                true,
                20,
                20,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(progress.metadataNext).isEqualTo(20);
        assertThat(progress.translationNext).isEqualTo(1);
        assertThat(translations.translatedIds).containsExactly(1, 2);
    }

    @Test
    void skipsOnePermanentlyInvalidSourceWithoutBlockingTheRestOfTheRankedCohort() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(20);
        RecordingBgg bgg = new RecordingBgg();
        RecordingTranslation translations = new RecordingTranslation(-1, 2);
        RecordingProgress progress = new RecordingProgress(new Cohort(
                UUID.randomUUID(), "a".repeat(64), 0, 20, 0, 3));
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked,
                bgg,
                new BggMetadataLocalizationService(translations),
                progress,
                new RecordingCoverProgress(null),
                new SyncTaskExecutor(),
                new RecordingCoverImages(),
                new SyncTaskExecutor(),
                CLOCK,
                true,
                20,
                20,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(progress.metadataNext).isEqualTo(20);
        assertThat(progress.translationNext).isEqualTo(3);
        assertThat(translations.translatedIds).containsExactly(1, 3);
    }

    @Test
    void warmsANewCoverFormatAfterMetadataAndTranslationAreAlreadyComplete() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(20);
        RecordingCoverImages covers = new RecordingCoverImages();
        RecordingCoverProgress coverProgress = new RecordingCoverProgress(new CoverCohort(
                UUID.randomUUID(), "a".repeat(64), covers.formatVersion(), 0, 20));
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked,
                new RecordingBgg(),
                new BggMetadataLocalizationService(new RecordingTranslation(-1)),
                new RecordingProgress(null),
                coverProgress,
                new SyncTaskExecutor(),
                covers,
                new SyncTaskExecutor(),
                CLOCK,
                true,
                20,
                20,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(coverProgress.next).isEqualTo(20);
        assertThat(covers.requests).hasSize(40);
    }

    @Test
    void retryableCoverAssetsPauseOnlyTheCoverCursor() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(20);
        RecordingTranslation translations = new RecordingTranslation(-1);
        RecordingCoverImages covers = new RecordingCoverImages(2);
        RecordingProgress progress = new RecordingProgress(new Cohort(
                UUID.randomUUID(), "a".repeat(64), 0, 20, 0, 3));
        RecordingCoverProgress coverProgress = new RecordingCoverProgress(new CoverCohort(
                UUID.randomUUID(), "a".repeat(64), covers.formatVersion(), 0, 20));
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked,
                new RecordingBgg(),
                new BggMetadataLocalizationService(translations),
                progress,
                coverProgress,
                new SyncTaskExecutor(),
                covers,
                new SyncTaskExecutor(),
                CLOCK,
                true,
                20,
                20,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(coverProgress.next).isZero();
        assertThat(progress.metadataNext).isEqualTo(20);
        assertThat(progress.translationNext).isEqualTo(3);
        assertThat(translations.translatedIds).containsExactly(1, 2, 3);
    }

    @Test
    void unavailableCoverProgressDoesNotBlockMetadataOrTranslation() {
        RecordingProgress progress = new RecordingProgress(new Cohort(
                UUID.randomUUID(), "a".repeat(64), 0, 20, 0, 3));
        BggCatalogCoverPrewarmProgress unavailableCoverProgress = new BggCatalogCoverPrewarmProgress() {
            @Override
            public Optional<CoverCohort> claim(
                    String snapshotSha256,
                    String formatVersion,
                    int targetCount,
                    int cohortSize,
                    Instant claimedAt,
                    Duration leaseDuration) {
                throw new IllegalStateException("cover progress database unavailable");
            }

            @Override
            public void complete(CoverCohort cohort, int nextOffset, Instant completedAt) {
                throw new AssertionError("an unavailable claim cannot be completed");
            }
        };
        var prewarmer = new BggPopularMetadataPrewarmer(
                new MemoryRankedCatalog(20),
                new RecordingBgg(),
                new BggMetadataLocalizationService(new RecordingTranslation(-1)),
                progress,
                unavailableCoverProgress,
                new SyncTaskExecutor(),
                new RecordingCoverImages(),
                new SyncTaskExecutor(),
                CLOCK,
                true,
                20,
                20,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(progress.metadataNext).isEqualTo(20);
        assertThat(progress.translationNext).isEqualTo(3);
    }

    private static List<Integer> ids(int first, int last) {
        return java.util.stream.IntStream.rangeClosed(first, last).boxed().toList();
    }

    private static final class MemoryRankedCatalog implements BggRankedCatalogRepository {
        private final int count;

        private MemoryRankedCatalog(int count) {
            this.count = count;
        }

        @Override
        public Optional<Snapshot> findSnapshot() {
            return Optional.of(new Snapshot(
                    CLOCK.instant(), LocalDate.parse("2026-08-20"), count, "a".repeat(64)));
        }

        @Override
        public Page find(Query query) {
            int start = query.page() * query.size() + 1;
            List<RankedGame> games = java.util.stream.IntStream.range(start, Math.min(start + query.size(), count + 1))
                    .mapToObj(id -> new RankedGame(
                            id, "Game " + id, 2026, id, BigDecimal.ONE, BigDecimal.ONE, 100, false,
                            Map.of(BggGameType.STRATEGY, id)))
                    .toList();
            return new Page(count, query.page(), query.size(), games);
        }

        @Override
        public List<RankedGame> findRankedRange(int offset, int limit) {
            return java.util.stream.IntStream.range(offset + 1, Math.min(offset + limit, count) + 1)
                    .mapToObj(id -> new RankedGame(
                            id,
                            "Game " + id,
                            2026,
                            id,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            100,
                            false,
                            Map.of(BggGameType.STRATEGY, id)))
                    .toList();
        }

        @Override
        public void stage(UUID importId, List<RankedGame> games) {}

        @Override
        public void publish(UUID importId, Snapshot snapshot) {}
    }

    private static final class RecordingBgg implements BoardGameGeekCatalog {
        private final List<List<Integer>> batches = new ArrayList<>();

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public List<SearchResult> search(String query) {
            return List.of();
        }

        @Override
        public List<GameMatch> exactMatches(String query) {
            return List.of();
        }

        @Override
        public List<HotGame> hotGames() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> hotGameDetails() {
            return List.of();
        }

        @Override
        public List<DiscoveryGame> gameDetails(List<Integer> bggIds) {
            batches.add(List.copyOf(bggIds));
            return bggIds.stream()
                    .map(id -> new DiscoveryGame(
                            id,
                            id,
                            "Game " + id,
                            "游戏 " + id,
                            2026,
                            "https://example.test/" + id + ".jpg",
                            2,
                            4,
                            60,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            List.of("Strategy"),
                            List.of("Card Drafting"),
                            60,
                            60,
                            null,
                            null,
                            "",
                            "",
                            null,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            "Stored description " + id,
                            "https://example.test/" + id + "-original.jpg"))
                    .toList();
        }

        @Override
        public GameDetails game(int bggId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingCoverImages implements CatalogCoverImages {
        private final List<String> requests = new ArrayList<>();
        private final int retryableBggId;

        private RecordingCoverImages() {
            this(-1);
        }

        private RecordingCoverImages(int retryableBggId) {
            this.retryableBggId = retryableBggId;
        }

        @Override
        public String formatVersion() {
            return "test-profiled-cover-v1";
        }

        @Override
        public Asset load(int bggId, Variant variant) {
            requests.add(bggId + ":" + variant);
            if (bggId == retryableBggId) return new Retryable(Duration.ofSeconds(5));
            return new Absent();
        }
    }

    private static final class RecordingCoverProgress implements BggCatalogCoverPrewarmProgress {
        private final CoverCohort cohort;
        private String claimedFormatVersion;
        private int next = -1;

        private RecordingCoverProgress(CoverCohort cohort) {
            this.cohort = cohort;
        }

        @Override
        public Optional<CoverCohort> claim(
                String snapshotSha256,
                String formatVersion,
                int targetCount,
                int cohortSize,
                Instant claimedAt,
                Duration leaseDuration) {
            claimedFormatVersion = formatVersion;
            return Optional.ofNullable(cohort);
        }

        @Override
        public void complete(CoverCohort cohort, int nextOffset, Instant completedAt) {
            next = nextOffset;
        }
    }

    private static final class RecordingTranslation implements BggMetadataTranslation {
        private final int unavailableId;
        private final int invalidSourceId;
        private final List<Integer> translatedIds = new ArrayList<>();

        private RecordingTranslation(int unavailableId) {
            this(unavailableId, -1);
        }

        private RecordingTranslation(int unavailableId, int invalidSourceId) {
            this.unavailableId = unavailableId;
            this.invalidSourceId = invalidSourceId;
        }

        @Override
        public Optional<Translation> translate(Request request) {
            translatedIds.add(request.bggId());
            if (request.bggId() == unavailableId) return Optional.empty();
            return Optional.of(new Translation("中文简介", List.of("策略"), List.of("卡牌轮抽")));
        }

        @Override
        public PrewarmResult prewarm(Request request) {
            if (request.bggId() == invalidSourceId) {
                return new PrewarmResult(PrewarmStatus.SKIPPED_INVALID_SOURCE);
            }
            if (request.bggId() == unavailableId) {
                translatedIds.add(request.bggId());
                return new PrewarmResult(PrewarmStatus.RETRY_PROVIDER_UNAVAILABLE);
            }
            return BggMetadataTranslation.super.prewarm(request);
        }
    }

    private static final class RecordingProgress implements BggPopularMetadataPrewarmProgress {
        private final Cohort cohort;
        private int metadataNext = -1;
        private int translationNext = -1;

        private RecordingProgress(Cohort cohort) {
            this.cohort = cohort;
        }

        @Override
        public Optional<Cohort> claim(
                String snapshotSha256,
                int targetCount,
                int metadataCohortSize,
                int translationCohortSize,
                Instant claimedAt,
                Duration leaseDuration) {
            return Optional.ofNullable(cohort);
        }

        @Override
        public void complete(Cohort cohort, int metadataNextOffset, int translationNextOffset, Instant completedAt) {
            metadataNext = metadataNextOffset;
            translationNext = translationNextOffset;
        }
    }
}
