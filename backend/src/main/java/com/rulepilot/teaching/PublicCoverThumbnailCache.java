package com.rulepilot.teaching;

import java.util.Optional;

/** Durable thumbnail cache for the small cover images exposed to anonymous readers. */
public interface PublicCoverThumbnailCache {

    Optional<Thumbnail> find(String sourceDigest);

    void store(String sourceDigest, Thumbnail thumbnail);

    record Thumbnail(byte[] content) {
        public static final int MAX_CONTENT_BYTES = 800_000;

        public Thumbnail {
            if (content == null || content.length == 0 || content.length > MAX_CONTENT_BYTES) {
                throw new IllegalArgumentException("public cover thumbnail is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
