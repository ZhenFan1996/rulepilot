package com.rulepilot.catalog.adapter.out.bgg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BoundedExpiringCacheTest {

    @Test
    void removesExpiredEntriesBeforeLeastRecentlyUsedEntries() {
        Instant now = Instant.parse("2026-08-07T12:00:00Z");
        var clock = new MutableClock(now);
        var cache = new BoundedExpiringCache<String, String>(2, clock);
        cache.put("expired", "old", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        cache.put("first", "one", Duration.ofMinutes(1));
        cache.put("second", "two", Duration.ofMinutes(1));
        assertThat(cache.get("first")).isEqualTo("one");

        cache.put("third", "three", Duration.ofMinutes(1));

        assertThat(cache.get("expired")).isNull();
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("first")).isEqualTo("one");
        assertThat(cache.get("second")).isNull();
        assertThat(cache.get("third")).isEqualTo("three");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
