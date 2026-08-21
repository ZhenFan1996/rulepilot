package com.rulepilot.shared.cover;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableCoverThumbnailServiceTest {

    @Test
    void reusesTheStoredThumbnailAcrossServiceRestartsWithoutRefetchingBgg() {
        MemoryCache cache = new MemoryCache();
        AtomicInteger fetches = new AtomicInteger();
        CoverImageFetcher fetcher = source -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {1, 2, 3});
        };

        var firstProcess = new DurableCoverThumbnailService(cache, fetcher);
        assertThat(firstProcess.thumbnailFor("https://cf.geekdo-images.com/game.jpg").content())
                .containsExactly(1, 2, 3);
        var restartedProcess = new DurableCoverThumbnailService(cache, fetcher);
        assertThat(restartedProcess.thumbnailFor("https://cf.geekdo-images.com/game.jpg").content())
                .containsExactly(1, 2, 3);

        assertThat(fetches).hasValue(1);
        assertThat(cache.entries).hasSize(1);
    }

    @Test
    void rejectsNonHttpsAndCredentialBearingCoverSourcesBeforeFetching() {
        AtomicInteger fetches = new AtomicInteger();
        var service = new DurableCoverThumbnailService(new MemoryCache(), source -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {1});
        });

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service.thumbnailFor("http://example.test/cover.jpg"));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> service.thumbnailFor("https://user@example.test/cover.jpg"));
        assertThat(fetches).hasValue(0);
    }

    private static final class MemoryCache implements CoverThumbnailCache {
        private final Map<String, Thumbnail> entries = new HashMap<>();

        @Override
        public Optional<Thumbnail> find(String sourceDigest) {
            return Optional.ofNullable(entries.get(sourceDigest));
        }

        @Override
        public void store(String sourceDigest, Thumbnail thumbnail) {
            entries.put(sourceDigest, thumbnail);
        }
    }
}
