package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.BggMetadataTranslation;
import com.rulepilot.catalog.BggGameType;
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
    void hydratesOneFiveHundredStyleCohortAndAdvancesTranslationsIndependently() {
        MemoryRankedCatalog ranked = new MemoryRankedCatalog(60);
        RecordingBgg bgg = new RecordingBgg();
        RecordingTranslation translations = new RecordingTranslation(-1);
        RecordingProgress progress = new RecordingProgress(new Cohort(
                UUID.randomUUID(), "a".repeat(64), 0, 45, 0, 3));
        var prewarmer = new BggPopularMetadataPrewarmer(
                ranked,
                bgg,
                new BggMetadataLocalizationService(translations),
                progress,
                new SyncTaskExecutor(),
                CLOCK,
                true,
                45,
                45,
                3,
                Duration.ofMinutes(30));

        prewarmer.prewarm();

        assertThat(bgg.batches).hasSize(4);
        assertThat(bgg.batches.get(0)).containsExactlyElementsOf(ids(1, 20));
        assertThat(bgg.batches.get(1)).containsExactlyElementsOf(ids(21, 40));
        assertThat(bgg.batches.get(2)).containsExactlyElementsOf(ids(41, 45));
        assertThat(bgg.batches.get(3)).containsExactly(1, 2, 3);
        assertThat(translations.translatedIds).containsExactly(1, 2, 3);
        assertThat(progress.metadataNext).isEqualTo(45);
        assertThat(progress.translationNext).isEqualTo(3);
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
                            "",
                            2,
                            4,
                            60,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            List.of("Strategy"),
                            List.of("Card Drafting")))
                    .toList();
        }

        @Override
        public GameDetails game(int bggId) {
            throw new UnsupportedOperationException();
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
            return Optional.of(cohort);
        }

        @Override
        public void complete(Cohort cohort, int metadataNextOffset, int translationNextOffset, Instant completedAt) {
            metadataNext = metadataNextOffset;
            translationNext = translationNextOffset;
        }
    }
}
