package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerEvidenceSelectionPolicyTest {

    @Test
    void placesPageScopedVisualEvidenceBeforeTextAnchorsForAnIconQuestion() {
        UUID documentVersionId = UUID.randomUUID();
        HybridEvidenceHit visual = hit(
                documentVersionId,
                "VISUAL",
                "Icon",
                "The green printed icon is next to the energy token label.",
                4,
                0.4);
        HybridEvidenceHit textAnchor = hit(
                documentVersionId, "RULES", "Cost", "Pay the required resource to use this action.", 5, 0.9);
        Map<UUID, HybridEvidenceHit> evidence = Map.of(
                visual.evidence().chunkId(), visual,
                textAnchor.evidence().chunkId(), textAnchor);

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "这个图标表示什么？",
                evidence,
                List.of(textAnchor),
                Set.of(visual.evidence().chunkId()));

        assertThat(selected).extracting(hit -> hit.evidence().chunkId())
                .containsExactly(visual.evidence().chunkId(), textAnchor.evidence().chunkId());
    }

    @Test
    void retainsDistinctEndgameResolutionScoringAndTieEvidence() {
        UUID documentVersionId = UUID.randomUUID();
        HybridEvidenceHit resolution = hit(
                documentVersionId, "ENDGAME", "Game end", "When the final round ends, the game ends.", 8, 0.8);
        HybridEvidenceHit scoring = hit(
                documentVersionId, "SCORING", "Final scoring", "Players score points for completed rows.", 9, 0.7);
        HybridEvidenceHit tie = hit(
                documentVersionId, "TIE", "Tie", "On a tie, the player with more coins wins.", 10, 0.6);
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        List.of(resolution, scoring, tie).forEach(hit -> evidence.put(hit.evidence().chunkId(), hit));

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "When does the game end, how do we score, and who wins a tie?",
                evidence,
                List.of(resolution, scoring, tie),
                Set.of());

        assertThat(selected).extracting(hit -> hit.evidence().chunkId())
                .containsExactly(resolution.evidence().chunkId(), scoring.evidence().chunkId(), tie.evidence().chunkId());
    }

    private HybridEvidenceHit hit(
            UUID documentVersionId, String sectionType, String heading, String excerpt, int page, double score) {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), documentVersionId, sectionType, heading, excerpt, page, page, score);
        return new HybridEvidenceHit(source, score, 1, null, false);
    }
}
