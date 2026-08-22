package com.rulepilot.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidencePolicyTest {

    @Test
    void recognizesOnlyTheTypedVisualPlaceholder() {
        HybridEvidenceHit placeholder = hit(
                UUID.randomUUID(),
                "Visual page",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                3,
                RuleEvidenceHit.ContentKind.VISUAL_PLACEHOLDER);
        HybridEvidenceHit ordinary = hit(
                UUID.randomUUID(),
                "Visual page",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                3,
                RuleEvidenceHit.ContentKind.CANONICAL_TEXT);

        assertThat(AnswerEvidencePolicy.isVisualPlaceholder(placeholder)).isTrue();
        assertThat(AnswerEvidencePolicy.isVisualPlaceholder(ordinary)).isFalse();
    }

    @Test
    void comparesCompleteEvidenceSnapshotsInsteadOfNaturalLanguageSimilarity() {
        UUID chunkId = UUID.randomUUID();
        HybridEvidenceHit first = hit(
                chunkId, "Timing", "Resolve movement before drawing.", 6, RuleEvidenceHit.ContentKind.CANONICAL_TEXT);
        HybridEvidenceHit same = hit(
                chunkId, "Timing", "Resolve movement before drawing.", 6, RuleEvidenceHit.ContentKind.CANONICAL_TEXT);
        HybridEvidenceHit changed = hit(
                chunkId, "Timing", "Draw before movement.", 6, RuleEvidenceHit.ContentKind.CANONICAL_TEXT);

        assertThat(AnswerEvidencePolicy.sameEvidenceSnapshot(first, same)).isTrue();
        assertThat(AnswerEvidencePolicy.sameEvidenceSnapshot(first, changed)).isFalse();
    }

    private HybridEvidenceHit hit(
            UUID chunkId,
            String heading,
            String excerpt,
            int page,
            RuleEvidenceHit.ContentKind contentKind) {
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                chunkId, UUID.nameUUIDFromBytes("version".getBytes()), "RULE", heading, excerpt,
                page, page, 0.8, contentKind, excerpt);
        return new HybridEvidenceHit(evidence, 0.8, 1, null, false);
    }
}
