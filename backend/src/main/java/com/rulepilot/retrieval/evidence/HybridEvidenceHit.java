package com.rulepilot.retrieval.evidence;

public record HybridEvidenceHit(
        RuleEvidenceHit evidence,
        double score,
        Integer fullTextRank,
        Integer vectorRank,
        boolean currentSectionBoosted) {

    public HybridEvidenceHit {
        if (evidence == null || !Double.isFinite(score) || score < 0
                || (fullTextRank != null && fullTextRank < 1)
                || (vectorRank != null && vectorRank < 1)
                || (fullTextRank == null && vectorRank == null)) {
            throw new IllegalArgumentException("hybrid evidence hit is invalid");
        }
    }
}
