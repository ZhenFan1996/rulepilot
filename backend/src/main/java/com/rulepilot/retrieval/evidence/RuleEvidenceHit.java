package com.rulepilot.retrieval.evidence;

import java.util.UUID;

public record RuleEvidenceHit(
        UUID chunkId,
        UUID documentVersionId,
        String sectionType,
        String heading,
        String excerpt,
        int pageFrom,
        int pageTo,
        double score,
        ContentKind contentKind,
        String playerExcerpt,
        String visualFacts) {

    public enum ContentKind {
        CANONICAL_TEXT,
        VISUAL_PLACEHOLDER,
        CANONICAL_TEXT_WITH_VISUAL_FACTS,
        VISUAL_TRANSCRIPTION
    }

    public RuleEvidenceHit(
            UUID chunkId,
            UUID documentVersionId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo,
            double score) {
        this(
                chunkId,
                documentVersionId,
                sectionType,
                heading,
                excerpt,
                pageFrom,
                pageTo,
                score,
                ContentKind.CANONICAL_TEXT,
                excerpt,
                null);
    }

    public RuleEvidenceHit(
            UUID chunkId,
            UUID documentVersionId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo,
            double score,
            ContentKind contentKind,
            String playerExcerpt) {
        this(
                chunkId,
                documentVersionId,
                sectionType,
                heading,
                excerpt,
                pageFrom,
                pageTo,
                score,
                contentKind,
                playerExcerpt,
                null);
    }

    public RuleEvidenceHit {
        if (chunkId == null || documentVersionId == null || sectionType == null || sectionType.isBlank()
                || heading == null || heading.isBlank() || excerpt == null || excerpt.isBlank()
                || pageFrom < 1 || pageTo < pageFrom || !Double.isFinite(score) || score < 0
                || contentKind == null || playerExcerpt == null || playerExcerpt.isBlank()) {
            throw new IllegalArgumentException("rule evidence hit is invalid");
        }
        playerExcerpt = playerExcerpt.strip();
        visualFacts = visualFacts == null || visualFacts.isBlank() ? null : visualFacts.strip();
    }
}
