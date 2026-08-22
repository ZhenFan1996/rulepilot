package com.rulepilot.retrieval;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit.ContentKind;

/** Mechanical evidence identity and locator rules shared by answer retrieval adapters. */
public final class AnswerEvidencePolicy {

    private AnswerEvidencePolicy() {}

    public static boolean isVisualPlaceholder(HybridEvidenceHit hit) {
        return hit != null && isVisualPlaceholder(hit.evidence());
    }

    public static boolean isVisualPlaceholder(RuleEvidenceHit hit) {
        return hit != null && hit.contentKind() == ContentKind.VISUAL_PLACEHOLDER;
    }

    static boolean sameEvidenceSnapshot(HybridEvidenceHit first, HybridEvidenceHit second) {
        var left = first.evidence();
        var right = second.evidence();
        return left.chunkId().equals(right.chunkId())
                && left.documentVersionId().equals(right.documentVersionId())
                && left.sectionType().equals(right.sectionType())
                && left.heading().equals(right.heading())
                && left.excerpt().equals(right.excerpt())
                && left.contentKind() == right.contentKind()
                && left.playerExcerpt().equals(right.playerExcerpt())
                && java.util.Objects.equals(left.visualFacts(), right.visualFacts())
                && left.pageFrom() == right.pageFrom()
                && left.pageTo() == right.pageTo();
    }
}
