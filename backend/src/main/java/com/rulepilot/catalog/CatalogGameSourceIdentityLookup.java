package com.rulepilot.catalog;

import java.util.List;
import java.util.Optional;

/** BGG-sourced identity hints for locating a publisher's own material; never rule evidence. */
public interface CatalogGameSourceIdentityLookup {

    Optional<Identity> findByBggId(int bggId);

    record Identity(String originalName, List<String> officialNames, List<String> publishers) {
        public Identity {
            if (originalName == null || originalName.isBlank()) {
                throw new IllegalArgumentException("catalog source identity requires an original name");
            }
            originalName = originalName.strip();
            officialNames = bounded(officialNames);
            publishers = bounded(publishers);
        }

        private static List<String> bounded(List<String> values) {
            if (values == null) return List.of();
            return values.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::strip)
                    .filter(value -> !value.isBlank() && value.length() <= 120)
                    .distinct()
                    .limit(12)
                    .toList();
        }
    }
}
