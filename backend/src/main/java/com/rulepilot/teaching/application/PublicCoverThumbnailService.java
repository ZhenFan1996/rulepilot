package com.rulepilot.teaching.application;

import com.rulepilot.teaching.PublicCoverImageFetcher;
import com.rulepilot.teaching.PublicCoverThumbnailCache;
import com.rulepilot.teaching.PublicCoverThumbnailCache.Thumbnail;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Gives public readers a small, durable local cover rather than a publisher's original artwork.
 *
 * <p>Concurrent requests for the same cold source share one fetch. A storage outage is deliberately soft: the
 * generated thumbnail remains useful for the current reader even if it cannot be retained for the next one.</p>
 */
@Service
@Profile("!test")
public class PublicCoverThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(PublicCoverThumbnailService.class);

    private final PublicCoverThumbnailCache cache;
    private final PublicCoverImageFetcher fetcher;
    private final ConcurrentHashMap<String, CompletableFuture<Thumbnail>> inFlight = new ConcurrentHashMap<>();

    public PublicCoverThumbnailService(PublicCoverThumbnailCache cache, PublicCoverImageFetcher fetcher) {
        this.cache = cache;
        this.fetcher = fetcher;
    }

    public Thumbnail thumbnailFor(String sourceUrl) {
        URI source = trustedSource(sourceUrl);
        String cacheKey = digest(source);
        Optional<Thumbnail> cached = cached(cacheKey);
        if (cached.isPresent()) return cached.get();

        CompletableFuture<Thumbnail> created = new CompletableFuture<>();
        CompletableFuture<Thumbnail> existing = inFlight.putIfAbsent(cacheKey, created);
        if (existing != null) return await(existing);

        try {
            Thumbnail thumbnail = cached(cacheKey).orElseGet(() -> fetcher.fetch(source));
            retain(cacheKey, thumbnail);
            created.complete(thumbnail);
            return thumbnail;
        } catch (RuntimeException failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(cacheKey, created);
        }
    }

    /** Best-effort warmup used after application readiness; a failed cover must not affect reader availability. */
    public void warm(String sourceUrl) {
        try {
            thumbnailFor(sourceUrl);
        } catch (RuntimeException failure) {
            log.warn("Could not warm a public cover thumbnail: {}", failure.getClass().getSimpleName());
        }
    }

    private Optional<Thumbnail> cached(String cacheKey) {
        try {
            return cache.find(cacheKey);
        } catch (RuntimeException failure) {
            log.warn("Could not read a public cover thumbnail cache entry: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void retain(String cacheKey, Thumbnail thumbnail) {
        try {
            cache.store(cacheKey, thumbnail);
        } catch (RuntimeException failure) {
            log.warn("Could not retain a public cover thumbnail: {}", failure.getClass().getSimpleName());
        }
    }

    private Thumbnail await(CompletableFuture<Thumbnail> existing) {
        try {
            return existing.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("public cover thumbnail is unavailable", failure.getCause());
        }
    }

    private URI trustedSource(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("public cover source is required");
        URI source = URI.create(sourceUrl.strip());
        if (!"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null
                || source.getUserInfo() != null
                || (source.getPort() != -1 && source.getPort() != 443)) {
            throw new IllegalArgumentException("public cover source must be a standard public HTTPS URL");
        }
        return source.normalize();
    }

    private String digest(URI source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toASCIIString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
