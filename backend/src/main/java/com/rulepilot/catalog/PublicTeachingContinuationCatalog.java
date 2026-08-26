package com.rulepilot.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog-facing read projection for games that can continue immediately into
 * an already published guide and grounded questions.
 *
 * <p>The contract belongs to the neutral catalog boundary: recommendation
 * supplies catalog-authoritative identities, while teaching implements the
 * public lesson projection without either business module depending on the
 * other.</p>
 */
public interface PublicTeachingContinuationCatalog {

    /**
     * Resolves only the supplied, catalog-authoritative candidates. A missing
     * result is {@link AvailabilityStatus#NONE} only when the projection could
     * determine that absence. Exact positive matches may still be returned as
     * {@link AvailabilityStatus#PARTIAL} when metadata or bounded-discovery
     * uncertainty prevents a negative claim for the remaining candidates.
     */
    Availability continuationsFor(List<Candidate> candidates);

    enum AvailabilityStatus {
        AVAILABLE,
        PARTIAL,
        NONE,
        UNAVAILABLE
    }

    record Candidate(int bggId, String authoritativeTitle) {
        public Candidate {
            if (bggId < 1 || authoritativeTitle == null || authoritativeTitle.isBlank()) {
                throw new IllegalArgumentException("public teaching candidate is invalid");
            }
            authoritativeTitle = authoritativeTitle.strip();
        }
    }

    record Availability(AvailabilityStatus status, Map<Integer, Continuation> continuations) {
        public Availability {
            if (status == null) throw new IllegalArgumentException("public teaching availability status is required");
            continuations = continuations == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(continuations));
            if ((status == AvailabilityStatus.AVAILABLE || status == AvailabilityStatus.PARTIAL)
                    && continuations.isEmpty()) {
                throw new IllegalArgumentException("available public teaching requires a continuation");
            }
            if (status != AvailabilityStatus.AVAILABLE
                    && status != AvailabilityStatus.PARTIAL
                    && !continuations.isEmpty()) {
                throw new IllegalArgumentException("unavailable public teaching cannot expose continuations");
            }
            if (continuations.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getValue() == null
                    || entry.getKey() != entry.getValue().bggId())) {
                throw new IllegalArgumentException("public teaching availability identity is invalid");
            }
        }

        public static Availability available(Map<Integer, Continuation> continuations) {
            return new Availability(AvailabilityStatus.AVAILABLE, continuations);
        }

        public static Availability partial(Map<Integer, Continuation> continuations) {
            return new Availability(AvailabilityStatus.PARTIAL, continuations);
        }

        public static Availability none() {
            return new Availability(AvailabilityStatus.NONE, Map.of());
        }

        public static Availability unavailable() {
            return new Availability(AvailabilityStatus.UNAVAILABLE, Map.of());
        }
    }

    record Continuation(
            int bggId,
            UUID teachingPlanId,
            int sectionCount,
            int stepCount) {
        public Continuation {
            if (bggId < 1
                    || teachingPlanId == null
                    || sectionCount < 1
                    || stepCount < 1) {
                throw new IllegalArgumentException("public teaching continuation is invalid");
            }
        }
    }
}
