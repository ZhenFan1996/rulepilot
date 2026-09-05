package com.rulepilot.catalog;

import java.util.List;
import java.util.Optional;

/** Localizes public BGG presentation metadata without exposing a model credential to the browser. */
public interface BggMetadataTranslation {

    /** Reads an already materialized translation and never calls a model provider. */
    Optional<Translation> readStored(Request request);

    default PrewarmResult prewarm(Request request) {
        return readStored(request).isPresent()
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
