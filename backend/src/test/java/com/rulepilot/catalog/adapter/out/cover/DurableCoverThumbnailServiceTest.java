package com.rulepilot.catalog.adapter.out.cover;

import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.COMPACT_PROFILE;
import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.DISPLAY_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.adapter.out.cover.CoverThumbnailCache.Thumbnail;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableCoverThumbnailServiceTest {

    @Test
    void reusesTheSameVariantAcrossRestartsAndSeparatesCompactFromDisplay() {
        MemoryCache cache = new MemoryCache();
        AtomicInteger fetches = new AtomicInteger();
        CoverImageFetcher fetcher = (source, profile) -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {(byte) (profile == COMPACT_PROFILE ? 1 : 2)});
        };

        var firstProcess = new DurableCoverThumbnailService(cache, fetcher);
        assertThat(firstProcess.thumbnailFor("https://cf.geekdo-images.com/game.jpg", COMPACT_PROFILE).content())
                .containsExactly(1);
        var restartedProcess = new DurableCoverThumbnailService(cache, fetcher);
        assertThat(restartedProcess.thumbnailFor("https://cf.geekdo-images.com/game.jpg", COMPACT_PROFILE).content())
                .containsExactly(1);
        assertThat(restartedProcess.thumbnailFor("https://cf.geekdo-images.com/game.jpg", DISPLAY_PROFILE).content())
                .containsExactly(2);

        assertThat(fetches).hasValue(2);
        assertThat(cache.entries).hasSize(2);
    }

    @Test
    void keysTheCacheByFormatVersionVariantAndSourceDigest() {
        String source = "https://cf.geekdo-images.com/game.jpg";
        MemoryCache cache = new MemoryCache();
        var service = new DurableCoverThumbnailService(
                cache, (ignored, profile) -> new Thumbnail(new byte[] {1}));

        service.thumbnailFor(source, COMPACT_PROFILE);

        String sourceDigest = digest(source);
        assertThat(cache.entries).containsOnlyKeys(digest(
                DurableCoverThumbnailService.CACHE_FORMAT_VERSION
                        + '\n'
                        + COMPACT_PROFILE.name()
                        + '\n'
                        + sourceDigest));
    }

    @Test
    void doesNotTreatAnUnavailableCacheAsATrueMissOrFetchTheOrigin() {
        AtomicInteger fetches = new AtomicInteger();
        CoverThumbnailCache unavailable = new CoverThumbnailCache() {
            @Override
            public Optional<Thumbnail> find(String sourceDigest) {
                throw new IllegalStateException("cache unavailable");
            }

            @Override
            public void store(String sourceDigest, Thumbnail thumbnail) {}
        };
        var service = new DurableCoverThumbnailService(unavailable, (source, profile) -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {1});
        });

        assertThatThrownBy(() -> service.thumbnailFor(
                        "https://cf.geekdo-images.com/game.jpg", COMPACT_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache unavailable");
        assertThat(fetches).hasValue(0);
    }

    @Test
    void doesNotPublishAnOriginFetchThatCouldNotBePersistedDurably() {
        AtomicInteger fetches = new AtomicInteger();
        CoverThumbnailCache unavailable = new CoverThumbnailCache() {
            @Override
            public Optional<Thumbnail> find(String sourceDigest) {
                return Optional.empty();
            }

            @Override
            public void store(String sourceDigest, Thumbnail thumbnail) {
                throw new IllegalStateException("cache write unavailable");
            }
        };
        var service = new DurableCoverThumbnailService(unavailable, (source, profile) -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {1});
        });

        assertThatThrownBy(() -> service.thumbnailFor(
                        "https://cf.geekdo-images.com/game.jpg", COMPACT_PROFILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache write unavailable");
        assertThat(fetches).hasValue(1);
    }

    @Test
    void boundsConcurrentDistinctOriginFetchesWithoutBlockingAnotherRequestLane() throws Exception {
        MemoryCache cache = new MemoryCache();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var service = new DurableCoverThumbnailService(cache, (source, profile) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test fetch timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test fetch interrupted", interrupted);
            }
            return new Thumbnail(new byte[] {1});
        }, 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.thumbnailFor(
                    "https://cf.geekdo-images.com/first.jpg", COMPACT_PROFILE));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.thumbnailFor(
                            "https://cf.geekdo-images.com/second.jpg", COMPACT_PROFILE))
                    .isInstanceOf(DurableCoverThumbnailService.CapacityUnavailableException.class);

            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).content()).containsExactly(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsNonHttpsAndCredentialBearingCoverSourcesBeforeFetching() {
        AtomicInteger fetches = new AtomicInteger();
        var service = new DurableCoverThumbnailService(new MemoryCache(), (source, profile) -> {
            fetches.incrementAndGet();
            return new Thumbnail(new byte[] {1});
        });

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.thumbnailFor("http://example.test/cover.jpg", COMPACT_PROFILE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.thumbnailFor("https://user@example.test/cover.jpg", COMPACT_PROFILE));
        assertThat(fetches).hasValue(0);
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
