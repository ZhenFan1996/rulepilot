package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rulepilot.catalog.BggMetadataTranslation;
import com.rulepilot.catalog.BggMetadataTranslation.PrewarmResult;
import com.rulepilot.catalog.BggMetadataTranslation.PrewarmStatus;
import com.rulepilot.catalog.BggMetadataTranslation.Request;
import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.application.BggPopularMetadataPrewarmProgress.Cohort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class BggPopularMetadataPrewarmerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T08:00:00Z"), ZoneOffset.UTC);
    private final BggRankedCatalogRepository ranked = mock(BggRankedCatalogRepository.class);
    private final BoardGameGeekCatalog bgg = mock(BoardGameGeekCatalog.class);
    private final BggMetadataCache cache = mock(BggMetadataCache.class);
    private final BggMetadataTranslation translations = mock(BggMetadataTranslation.class);
    private final BggPopularMetadataPrewarmProgress progress = mock(BggPopularMetadataPrewarmProgress.class);
    private final BggCatalogCoverPrewarmProgress covers = mock(BggCatalogCoverPrewarmProgress.class);
    private final CatalogCoverImages images = mock(CatalogCoverImages.class);
    private final Cohort cohort = new Cohort(UUID.randomUUID(), "a".repeat(64), 20, 20);

    private BggPopularMetadataPrewarmer worker() {
        when(progress.claim(anyString(), anyInt(), anyInt(), any(), any())).thenReturn(Optional.of(cohort));
        when(translations.prewarm(any())).thenReturn(new PrewarmResult(PrewarmStatus.READY));
        return new BggPopularMetadataPrewarmer(ranked, bgg, cache,
                new BggMetadataLocalizationService(translations), progress, covers,
                new SyncTaskExecutor(), images, new SyncTaskExecutor(), clock,
                true, 10000, 500, Duration.ofMinutes(30));
    }

    @Test
    void translatesAllCachedSourcesWithoutARankedSnapshotIncludingGamesBeyondTheRankedTarget() {
        var worker = worker();
        var first = source(15001);
        var next = source(90001);
        when(cache.translationSources(eq(0), anyInt(), any())).thenReturn(List.of(first));
        when(cache.translationSources(eq(15001), anyInt(), any())).thenReturn(List.of(next));

        worker.prewarm();

        verify(translations).prewarm(first);
        verify(translations).prewarm(next);
        verify(progress).complete(cohort, 20, clock.instant());
        verify(bgg, never()).gameDetails(anyList());
    }

    @Test
    void aFailedGameDoesNotPreventOtherCachedGamesFromBeingTranslated() {
        var worker = worker();
        var failed = source(42);
        var usable = source(81);
        when(cache.translationSources(eq(0), anyInt(), any())).thenReturn(List.of(failed, usable));
        when(translations.prewarm(failed)).thenReturn(new PrewarmResult(PrewarmStatus.RETRY_PROVIDER_UNAVAILABLE));

        worker.prewarm();

        verify(translations).prewarm(usable);
        verify(progress).complete(cohort, 20, clock.instant());
    }

    @Test
    void sharedProviderCapacityStopsNewWorkAndLeavesTheCycleRecoverable() {
        var worker = worker();
        var deferred = source(42);
        var next = source(81);
        when(cache.translationSources(eq(0), anyInt(), any())).thenReturn(List.of(deferred, next));
        when(translations.prewarm(deferred)).thenReturn(new PrewarmResult(PrewarmStatus.RETRY_HOURLY_BUDGET));

        worker.prewarm();
        verify(translations, never()).prewarm(next);
        verify(progress).complete(cohort, 20, clock.instant());

        when(translations.prewarm(deferred)).thenReturn(new PrewarmResult(PrewarmStatus.READY));
        worker.prewarm();
        verify(translations).prewarm(next);
    }

    @Test
    void hotGamesReceiveAvailableCapacityBeforeOtherCachedGames() {
        var worker = worker();
        var hot = mock(BoardGameGeekCatalog.DiscoveryGame.class);
        when(hot.bggId()).thenReturn(91);
        when(hot.name()).thenReturn("New game");
        when(hot.description()).thenReturn("A complete publisher description.");
        when(bgg.hotGameDetails()).thenReturn(List.of(hot));
        var other = source(42);
        when(cache.translationSources(eq(0), anyInt(), any())).thenReturn(List.of(other));
        List<Integer> successful = new ArrayList<>();
        when(translations.prewarm(any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            if (successful.isEmpty()) {
                successful.add(request.bggId());
                return new PrewarmResult(PrewarmStatus.READY);
            }
            return new PrewarmResult(PrewarmStatus.RETRY_HOURLY_BUDGET);
        });

        worker.prewarm();
        assertThat(successful).containsExactly(91);
    }

    @Test
    void unavailableSourceScanStillReleasesTheMetadataLease() {
        var worker = worker();
        when(cache.translationSources(anyInt(), anyInt(), any())).thenThrow(new IllegalStateException("unavailable"));
        worker.prewarm();
        verify(progress).complete(cohort, 20, clock.instant());
    }

    @Test
    void anotherWorkerLeasePreventsDuplicateTranslationWork() {
        var worker = worker();
        when(progress.claim(anyString(), anyInt(), anyInt(), any(), any())).thenReturn(Optional.empty());
        worker.prewarm();
        verifyNoInteractions(translations);
    }

    @Test
    void hydratesMetadataEvenWhenTranslationAndCoverWorkAreUnavailable() {
        var worker = worker();
        var active = new Cohort(UUID.randomUUID(), "a".repeat(64), 0, 2);
        when(ranked.findSnapshot()).thenReturn(Optional.of(new BggRankedCatalog.Snapshot(
                clock.instant(), LocalDate.of(2026, 9, 5), 2, "a".repeat(64))));
        var game = mock(BggRankedCatalog.RankedGame.class);
        when(game.bggId()).thenReturn(42);
        when(ranked.findRankedRange(0, 2)).thenReturn(List.of(game));
        when(progress.claim(anyString(), anyInt(), anyInt(), any(), any())).thenReturn(Optional.of(active));
        when(cache.translationSources(anyInt(), anyInt(), any())).thenThrow(new IllegalStateException("unavailable"));
        when(covers.claim(anyString(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("cover lease unavailable"));

        worker.prewarm();

        verify(bgg).gameDetails(List.of(42));
        verify(progress).complete(active, 1, clock.instant());
    }

    private Request source(int id) {
        return new Request(id, "Game " + id, "Publisher description.", List.of(), List.of());
    }
}
