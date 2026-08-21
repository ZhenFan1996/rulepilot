package com.rulepilot.shared.cover;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
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

/** Keeps external cover availability off the browser's critical path after the first successful fetch. */
@Service
@Profile("!test")
public class DurableCoverThumbnailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableCoverThumbnailService.class);

    private final CoverThumbnailCache cache;
    private final CoverImageFetcher fetcher;
    private final ConcurrentHashMap<String, CompletableFuture<Thumbnail>> inFlight = new ConcurrentHashMap<>();

    public DurableCoverThumbnailService(CoverThumbnailCache cache, CoverImageFetcher fetcher) {
        this.cache = cache;
        this.fetcher = fetcher;
    }

    public Thumbnail thumbnailFor(String sourceUrl) {
        URI source = trustedSource(sourceUrl);
        String cacheKey = digest(source.toASCIIString());
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

    private Optional<Thumbnail> cached(String cacheKey) {
        try {
            return cache.find(cacheKey);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not read a durable cover thumbnail: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void retain(String cacheKey, Thumbnail thumbnail) {
        try {
            cache.store(cacheKey, thumbnail);
        } catch (RuntimeException failure) {
            LOGGER.warn("Could not retain a durable cover thumbnail: {}", failure.getClass().getSimpleName());
        }
    }

    private Thumbnail await(CompletableFuture<Thumbnail> existing) {
        try {
            return existing.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("cover thumbnail is unavailable", failure.getCause());
        }
    }

    private URI trustedSource(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("cover source is required");
        URI source = URI.create(sourceUrl.strip());
        if (!"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null
                || source.getUserInfo() != null
                || (source.getPort() != -1 && source.getPort() != 443)) {
            throw new IllegalArgumentException("cover source must be a standard public HTTPS URL");
        }
        return source.normalize();
    }

    private String digest(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
