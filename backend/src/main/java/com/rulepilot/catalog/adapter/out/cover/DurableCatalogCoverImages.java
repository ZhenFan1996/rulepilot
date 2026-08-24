package com.rulepilot.catalog.adapter.out.cover;

import static com.rulepilot.catalog.CatalogCoverImages.ContentType.JPEG;
import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.COMPACT_PROFILE;
import static com.rulepilot.catalog.adapter.out.cover.DurableCoverThumbnailService.Profile.DISPLAY_PROFILE;

import com.rulepilot.catalog.CatalogCoverImages;
import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.catalog.CatalogGameSelectionLookup.GameSelection;
import com.rulepilot.catalog.adapter.out.cover.CoverImageFetcher.SourceAbsentException;
import com.rulepilot.catalog.adapter.out.cover.CoverThumbnailCache.Thumbnail;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Resolves catalog identity, source role, durable profile, and terminal fallback in one boundary. */
@Component
@Profile("!test")
public class DurableCatalogCoverImages implements CatalogCoverImages {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final CatalogGameSelectionLookup games;
    private final DurableCoverThumbnailService thumbnails;

    public DurableCatalogCoverImages(CatalogGameSelectionLookup games, DurableCoverThumbnailService thumbnails) {
        this.games = games;
        this.thumbnails = thumbnails;
    }

    @Override
    public String formatVersion() {
        return thumbnails.formatVersion();
    }

    @Override
    public Asset load(int bggId, Variant variant) {
        if (bggId <= 0 || variant == null) return new Absent();
        Optional<GameSelection> selected;
        try {
            selected = games.findStored(bggId);
        } catch (RuntimeException unavailable) {
            return new Retryable(RETRY_AFTER);
        }
        if (selected.isEmpty()) return new Absent();
        GameSelection game = selected.orElseThrow();
        String thumbnail = source(game.thumbnailUrl());
        String image = source(game.imageUrl());
        if (variant == Variant.DISPLAY) {
            String displaySource = image.isEmpty() ? thumbnail : image;
            return displaySource.isEmpty() ? new Absent() : load(displaySource, DISPLAY_PROFILE);
        }
        return loadCompact(thumbnail, image);
    }

    private Asset loadCompact(String thumbnail, String image) {
        List<String> sources = List.copyOf(new LinkedHashSet<>(List.of(thumbnail, image))).stream()
                .filter(source -> !source.isEmpty())
                .toList();
        boolean retryable = false;
        for (String source : sources) {
            Asset loaded = load(source, COMPACT_PROFILE);
            if (loaded instanceof Ready) return loaded;
            if (loaded instanceof Retryable) retryable = true;
        }
        return retryable ? new Retryable(RETRY_AFTER) : new Absent();
    }

    private Asset load(String source, DurableCoverThumbnailService.Profile profile) {
        try {
            Thumbnail thumbnail = thumbnails.thumbnailFor(source, profile);
            byte[] content = thumbnail.content();
            return new Ready(content, JPEG, digest(content));
        } catch (SourceAbsentException | IllegalArgumentException absent) {
            return new Absent();
        } catch (RuntimeException unavailable) {
            return new Retryable(RETRY_AFTER);
        }
    }

    private String source(String value) {
        return value == null ? "" : value.strip();
    }

    private String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
