package com.rulepilot.catalog;

import java.util.List;
import java.util.Optional;

/** Localizes public BGG presentation metadata without exposing a model credential to the browser. */
public interface BggMetadataTranslation {

    Optional<Translation> translate(Request request);

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
