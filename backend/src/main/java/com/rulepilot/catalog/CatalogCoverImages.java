package com.rulepilot.catalog;

import java.time.Duration;
import java.util.Objects;

/** Loads one browser-ready cover representation from the catalog's local BGG projection. */
public interface CatalogCoverImages {

    String formatVersion();

    Asset load(int bggId, Variant variant);

    enum Variant {
        COMPACT,
        DISPLAY
    }

    enum ContentType {
        JPEG("image/jpeg");

        private final String mediaType;

        ContentType(String mediaType) {
            this.mediaType = mediaType;
        }

        public String mediaType() {
            return mediaType;
        }
    }

    sealed interface Asset permits Ready, Absent, Retryable {}

    record Ready(byte[] content, ContentType contentType, String entityTag) implements Asset {
        public Ready {
            if (content == null || content.length == 0) throw new IllegalArgumentException("cover content is required");
            content = content.clone();
            contentType = Objects.requireNonNull(contentType, "cover content type is required");
            if (entityTag == null || !entityTag.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("cover entity tag must be a lowercase SHA-256 digest");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record Absent() implements Asset {}

    record Retryable(Duration retryAfter) implements Asset {
        public Retryable {
            if (retryAfter == null
                    || retryAfter.compareTo(Duration.ofSeconds(1)) < 0
                    || retryAfter.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("cover retry delay must be between one second and one hour");
            }
        }
    }
}
