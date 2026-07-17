package com.rulepilot.retrieval.domain;

import java.util.UUID;

public record RuleEvidenceHit(
        UUID chunkId,
        UUID documentVersionId,
        String sectionType,
        String heading,
        String excerpt,
        int pageFrom,
        int pageTo,
        double score) {

    public RuleEvidenceHit {
        if (chunkId == null || documentVersionId == null || sectionType == null || sectionType.isBlank()
                || heading == null || heading.isBlank() || excerpt == null || excerpt.isBlank()
                || pageFrom < 1 || pageTo < pageFrom || !Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("rule evidence hit is invalid");
        }
    }
}
