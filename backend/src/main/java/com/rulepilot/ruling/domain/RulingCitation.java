package com.rulepilot.ruling.domain;

import java.util.UUID;

public record RulingCitation(
        UUID chunkId,
        UUID documentVersionId,
        String sectionType,
        String heading,
        String excerpt,
        int pageFrom,
        int pageTo) {

    public RulingCitation {
        if (chunkId == null || documentVersionId == null || sectionType == null || sectionType.isBlank()
                || heading == null || heading.isBlank() || excerpt == null || excerpt.isBlank()
                || pageFrom < 1 || pageTo < pageFrom) {
            throw new IllegalArgumentException("ruling citation is invalid");
        }
    }
}
