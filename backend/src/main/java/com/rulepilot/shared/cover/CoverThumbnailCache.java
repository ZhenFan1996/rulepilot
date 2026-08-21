package com.rulepilot.shared.cover;

import java.util.Optional;

/** Durable storage for bounded cover thumbnails served from RulePilot's own origin. */
public interface CoverThumbnailCache {

    Optional<Thumbnail> find(String sourceDigest);

    void store(String sourceDigest, Thumbnail thumbnail);

    record Thumbnail(byte[] content) {
        public static final int MAX_CONTENT_BYTES = 800_000;

        public Thumbnail {
            if (content == null || content.length == 0 || content.length > MAX_CONTENT_BYTES) {
                throw new IllegalArgumentException("cover thumbnail is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
