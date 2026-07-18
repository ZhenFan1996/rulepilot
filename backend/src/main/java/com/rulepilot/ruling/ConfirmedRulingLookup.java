package com.rulepilot.ruling;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ConfirmedRulingLookup {

    Optional<ConfirmedAnswer> find(
            UUID documentVersionId,
            Set<UUID> expansionIds,
            String question,
            String username);

    record ConfirmedAnswer(
            UUID rulingId,
            UUID documentVersionId,
            String shortVerdict,
            String explanation,
            List<Citation> citations,
            List<String> exceptions,
            String confidence,
            boolean official,
            long version) {
        public ConfirmedAnswer {
            citations = List.copyOf(citations);
            exceptions = List.copyOf(exceptions);
        }
    }

    record Citation(
            UUID chunkId,
            UUID documentVersionId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}
}
