package com.rulepilot.catalog;

import java.util.List;
import java.util.Optional;

/** Localizes public BGG presentation metadata without exposing a model credential to the browser. */
public interface BggMetadataTranslation {

    Optional<Translation> translate(Request request);

    default PrewarmResult prewarm(Request request) {
        return translate(request).isPresent()
                ? new PrewarmResult(PrewarmStatus.READY)
                : new PrewarmResult(PrewarmStatus.RETRY_LATER);
    }

    enum PrewarmStatus {
        READY,
        SKIPPED_INVALID_SOURCE,
        RETRY_NOT_CONFIGURED,
        RETRY_PROVIDER_BUSY,
        RETRY_HOURLY_BUDGET,
        RETRY_PROVIDER_UNAVAILABLE,
        RETRY_LATER
    }

    record PrewarmResult(PrewarmStatus status) {
        public PrewarmResult {
            if (status == null) throw new IllegalArgumentException("BGG translation prewarm status is required");
        }

        public boolean advanceCursor() {
            return status == PrewarmStatus.READY || status == PrewarmStatus.SKIPPED_INVALID_SOURCE;
        }
    }

    record Request(
            int bggId,
            String gameName,
            String description,
            List<String> categories,
            List<String> mechanics) {
        public Request {
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
        }
    }

    record Translation(String description, List<String> categories, List<String> mechanics) {
        public Translation {
            categories = List.copyOf(categories);
            mechanics = List.copyOf(mechanics);
        }
    }
}
