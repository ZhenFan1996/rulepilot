package com.rulepilot.shared.cover;

import com.rulepilot.shared.cover.CoverThumbnailCache.Thumbnail;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Keeps bounded external cover work behind a variant-aware durable cache and concurrency limit. */
@Service
@Profile("!test")
public class DurableCoverThumbnailService {

    public static final String CACHE_FORMAT_VERSION = "catalog-cover-v3-profiled-jpeg";
    private static final int DEFAULT_MAX_CONCURRENT_FETCHES = 4;

    private final CoverThumbnailCache cache;
    private final CoverImageFetcher fetcher;
    private final Semaphore fetchPermits;
    private final ConcurrentHashMap<String, CompletableFuture<Thumbnail>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public DurableCoverThumbnailService(CoverThumbnailCache cache, CoverImageFetcher fetcher) {
        this(cache, fetcher, DEFAULT_MAX_CONCURRENT_FETCHES);
    }

    DurableCoverThumbnailService(CoverThumbnailCache cache, CoverImageFetcher fetcher, int maximumConcurrentFetches) {
        if (maximumConcurrentFetches < 1 || maximumConcurrentFetches > 16) {
            throw new IllegalArgumentException("cover fetch concurrency must be between one and sixteen");
        }
        this.cache = Objects.requireNonNull(cache, "cover cache is required");
        this.fetcher = Objects.requireNonNull(fetcher, "cover fetcher is required");
        this.fetchPermits = new Semaphore(maximumConcurrentFetches, true);
    }

    public String formatVersion() {
        return CACHE_FORMAT_VERSION;
    }

    public Thumbnail thumbnailFor(String sourceUrl, Profile profile) {
        URI source = trustedSource(sourceUrl);
        Profile checkedProfile = Objects.requireNonNull(profile, "cover profile is required");
        String cacheKey = cacheKey(source, checkedProfile);
        Optional<Thumbnail> cached = cache.find(cacheKey);
        if (cached.isPresent()) return cached.orElseThrow();

        CompletableFuture<Thumbnail> created = new CompletableFuture<>();
        CompletableFuture<Thumbnail> existing = inFlight.putIfAbsent(cacheKey, created);
        if (existing != null) return await(existing);
        try {
            Thumbnail thumbnail = loadMissing(cacheKey, source, checkedProfile);
            created.complete(thumbnail);
            return thumbnail;
        } catch (RuntimeException failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(cacheKey, created);
        }
    }

    private Thumbnail loadMissing(String cacheKey, URI source, Profile profile) {
        if (!fetchPermits.tryAcquire()) {
            throw new CapacityUnavailableException("cover origin fetch capacity is temporarily exhausted");
        }
        try {
            Optional<Thumbnail> cached = cache.find(cacheKey);
            if (cached.isPresent()) return cached.orElseThrow();
            Thumbnail thumbnail = fetcher.fetch(source, profile);
            cache.store(cacheKey, thumbnail);
            return thumbnail;
        } finally {
            fetchPermits.release();
        }
    }

    private Thumbnail await(CompletableFuture<Thumbnail> existing) {
        try {
            return existing.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("cover image is unavailable", failure.getCause());
        }
    }

    private String cacheKey(URI source, Profile profile) {
        String sourceDigest = digest(source.toASCIIString());
        return digest(CACHE_FORMAT_VERSION + '\n' + profile.name() + '\n' + sourceDigest);
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

    public enum Profile {
        COMPACT_PROFILE,
        DISPLAY_PROFILE
    }

    public static final class CapacityUnavailableException extends IllegalStateException {
        public CapacityUnavailableException(String message) {
            super(message);
        }
    }
}
