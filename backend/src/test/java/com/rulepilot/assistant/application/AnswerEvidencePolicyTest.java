package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidencePolicyTest {

    @Test
    void recognizesOnlyMechanicalVisualPlaceholderAndLanguageBoundaries() {
        HybridEvidenceHit placeholder = hit(
                UUID.randomUUID(),
                "Visual page",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                3);
        HybridEvidenceHit ordinary = hit(
                UUID.randomUUID(), "Visual page", "The rendered page shows a green token.", 3);

        assertThat(AnswerEvidencePolicy.isVisualPlaceholder(placeholder)).isTrue();
        assertThat(AnswerEvidencePolicy.isVisualPlaceholder(ordinary)).isFalse();
        assertThat(AnswerEvidencePolicy.requiresCrossLanguageExpansion("这个图标表示什么？")).isTrue();
        assertThat(AnswerEvidencePolicy.requiresCrossLanguageExpansion("one icon")).isFalse();
    }

    @Test
    void extractsDocumentPrintedIdentifiersWithoutInterpretingQuestionIntent() {
        assertThat(AnswerEvidencePolicy.printedIdentifiers("比较 a - 01、B#02、x_7 和 A-01"))
                .containsExactly("A-01", "B#02", "X_7");
        assertThat(AnswerEvidencePolicy.printedIdentifiers("How does this ordinary action work?"))
                .isEmpty();
    }

    @Test
    void comparesCompleteEvidenceSnapshotsInsteadOfNaturalLanguageSimilarity() {
        UUID chunkId = UUID.randomUUID();
        HybridEvidenceHit first = hit(chunkId, "Timing", "Resolve movement before drawing.", 6);
        HybridEvidenceHit same = hit(chunkId, "Timing", "Resolve movement before drawing.", 6);
        HybridEvidenceHit changed = hit(chunkId, "Timing", "Draw before movement.", 6);

        assertThat(AnswerEvidencePolicy.sameEvidenceSnapshot(first, same)).isTrue();
        assertThat(AnswerEvidencePolicy.sameEvidenceSnapshot(first, changed)).isFalse();
    }

    private HybridEvidenceHit hit(UUID chunkId, String heading, String excerpt, int page) {
        RuleEvidenceHit evidence = new RuleEvidenceHit(
                chunkId, UUID.nameUUIDFromBytes("version".getBytes()), "RULE", heading, excerpt,
                page, page, 0.8);
        return new HybridEvidenceHit(evidence, 0.8, 1, null, false);
    }
}
