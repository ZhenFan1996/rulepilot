package com.rulepilot.shared.cover;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    @Test
    void doesNotReuseTheLegacyLowResolutionCacheEntry() {
        String source = "https://cf.geekdo-images.com/game.jpg";
        MemoryCache cache = new MemoryCache();
        cache.entries.put(digest(source), new Thumbnail(new byte[] {1}));
        AtomicInteger fetches = new AtomicInteger();
        var service = new DurableCoverThumbnailService(cache, ignored -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {2});
        });

        assertThat(service.thumbnailFor(source).content()).containsExactly(2);
        assertThat(fetches).hasValue(1);
        assertThat(cache.entries).hasSize(2);
    }

    private static String digest(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
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
