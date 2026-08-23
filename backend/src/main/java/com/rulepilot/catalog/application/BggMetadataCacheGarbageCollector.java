package com.rulepilot.catalog.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class BggMetadataCacheGarbageCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(BggMetadataCacheGarbageCollector.class);

    private final BggMetadataCache cache;
    private final Clock clock;
    private final int maximumEntries;
    private final long maximumBytes;
    private final Counter expiredEvictions;
    private final Counter capacityEvictions;
    private final Counter failures;

    @Autowired
    public BggMetadataCacheGarbageCollector(
            BggMetadataCache cache,
            MeterRegistry metrics,
            @Value("${rulepilot.bgg.cache.maximum-entries:20000}") int maximumEntries,
            @Value("${rulepilot.bgg.cache.maximum-bytes:268435456}") long maximumBytes) {
        this(cache, metrics, Clock.systemUTC(), maximumEntries, maximumBytes);
    }

    BggMetadataCacheGarbageCollector(
            BggMetadataCache cache,
            MeterRegistry metrics,
            Clock clock,
            int maximumEntries,
            long maximumBytes) {
        if (maximumEntries < 100 || maximumEntries > 100_000) {
            throw new IllegalArgumentException("BGG cache maximum entries must be between 100 and 100000");
        }
        if (maximumBytes < 1_048_576 || maximumBytes > 1_073_741_824L) {
            throw new IllegalArgumentException("BGG cache maximum bytes must be between 1 MiB and 1 GiB");
        }
        this.cache = cache;
        this.clock = clock;
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
        this.expiredEvictions = metrics.counter("rulepilot.bgg.cache.evictions", "reason", "expired");
        this.capacityEvictions = metrics.counter("rulepilot.bgg.cache.evictions", "reason", "capacity");
        this.failures = metrics.counter("rulepilot.bgg.cache.cleanup", "result", "failure");
    }

    @Scheduled(fixedDelayString = "${rulepilot.bgg.cache.cleanup-delay:PT1H}")
    public void collect() {
        try {
            BggMetadataCache.CleanupResult result = cache.prune(clock.instant(), maximumEntries, maximumBytes);
            expiredEvictions.increment(result.expiredEntries());
            capacityEvictions.increment(result.capacityEntries());
        } catch (RuntimeException exception) {
            failures.increment();
            LOGGER.warn("BGG metadata cache cleanup failed; entries will be retried on the next interval");
        }
    }
}
