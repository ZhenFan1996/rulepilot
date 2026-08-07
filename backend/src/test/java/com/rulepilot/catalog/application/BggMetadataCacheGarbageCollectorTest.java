package com.rulepilot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BggMetadataCacheGarbageCollectorTest {

    @Test
    void recordsExpiredAndCapacityEvictionsFromTheBoundedCleanup() {
        Instant now = Instant.parse("2026-08-07T12:00:00Z");
        BggMetadataCache cache = mock(BggMetadataCache.class);
        when(cache.prune(now, 10_000, 67_108_864L)).thenReturn(new BggMetadataCache.CleanupResult(3, 2));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var collector = new BggMetadataCacheGarbageCollector(
                cache, metrics, Clock.fixed(now, ZoneOffset.UTC), 10_000, 67_108_864L);

        collector.collect();

        verify(cache).prune(now, 10_000, 67_108_864L);
        assertThat(metrics.counter("rulepilot.bgg.cache.evictions", "reason", "expired").count()).isEqualTo(3);
        assertThat(metrics.counter("rulepilot.bgg.cache.evictions", "reason", "capacity").count()).isEqualTo(2);
    }
}
