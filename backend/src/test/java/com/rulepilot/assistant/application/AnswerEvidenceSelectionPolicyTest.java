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
    void placesIdentifierBoundPageEvidenceBeforeGenericTextAnchors() {
        UUID documentVersionId = UUID.randomUUID();
        HybridEvidenceHit visual = hit(
                documentVersionId, "VISUAL", "A-01", "A-01 grants one movement when activated.", 7, 0.4);
        HybridEvidenceHit textAnchor = hit(
                documentVersionId, "RULES", "Actions", "Actions may grant movement or resources.", 5, 0.9);
        Map<UUID, HybridEvidenceHit> evidence = Map.of(
                visual.evidence().chunkId(), visual,
                textAnchor.evidence().chunkId(), textAnchor);

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "A-01 的功能是什么？", evidence, List.of(textAnchor), Set.of(visual.evidence().chunkId()));

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

        assertThat(AnswerEvidencePolicy.asksScoring(
                        "When does the game end, how do we score, and who wins a tie?"))
                .isTrue();
        assertThat(AnswerEvidencePolicy.hasEndgameScoring(scoring.evidence().excerpt())).isTrue();

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "When does the game end, how do we score, and who wins a tie?",
                evidence,
                List.of(resolution, scoring, tie),
                Set.of());

        assertThat(selected).extracting(hit -> hit.evidence().chunkId())
                .containsExactly(resolution.evidence().chunkId(), scoring.evidence().chunkId(), tie.evidence().chunkId());
    }

    @Test
    void keepsMoreThanThreeSourcesForACompleteListRequest() {
        UUID documentVersionId = UUID.randomUUID();
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 7; index++) {
            HybridEvidenceHit hit = hit(
                    documentVersionId,
                    "RULES",
                    "Ability " + index,
                    "Ability " + index + " has a distinct condition.",
                    20 + index,
                    0.9 - index * 0.01);
            evidence.put(hit.evidence().chunkId(), hit);
        }

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "List all seven abilities and explain each condition.", evidence, List.of(), Set.of());

        assertThat(selected).hasSize(7);
    }

    @Test
    void keepsAllConfirmedPageSegmentsForACompoundTimingAndConsequenceQuestion() {
        UUID documentVersionId = UUID.randomUUID();
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 6; index++) {
            HybridEvidenceHit hit = hit(
                    documentVersionId,
                    "RULES",
                    "Confirmed page segment " + index,
                    "Segment " + index + " supports a different part of the ordered procedure.",
                    13,
                    0.9);
            evidence.put(hit.evidence().chunkId(), hit);
        }

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "If the required action fails, what happens, and when does the phase end?",
                evidence,
                evidence.values(),
                Set.of());

        assertThat(selected).hasSize(6);
    }

    @Test
    void keepsTheOrdinaryBudgetForASingleDirectQuestion() {
        UUID documentVersionId = UUID.randomUUID();
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 7; index++) {
            HybridEvidenceHit hit = hit(
                    documentVersionId,
                    "RULES",
                    "Candidate " + index,
                    "Candidate evidence " + index + '.',
                    3 + index,
                    0.9 - index * 0.01);
            evidence.put(hit.evidence().chunkId(), hit);
        }

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "When does this action resolve?", evidence, evidence.values(), Set.of());

        assertThat(selected).hasSize(5);
    }

    @Test
    void ranksAnEarlierSpecificExceptionObservationAheadOfANewerGenericPhaseObservation() {
        UUID documentVersionId = UUID.randomUUID();
        HybridEvidenceHit generic = hit(
                documentVersionId,
                "RULES",
                "Faction phase",
                "During this phase the faction may take an action.",
                18,
                1.0);
        HybridEvidenceHit specific = hit(
                documentVersionId,
                "RULES",
                "Failed requirement",
                "If the required decree action cannot be taken, resolve turmoil and end daylight.",
                13,
                1.0);
        Map<UUID, HybridEvidenceHit> evidence = Map.of(
                generic.evidence().chunkId(), generic,
                specific.evidence().chunkId(), specific);

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "If the decree action cannot be taken, what happens, and when does daylight end?",
                evidence,
                List.of(generic, specific),
                Set.of());

        assertThat(selected).first().isEqualTo(specific);
    }

    @Test
    void ranksSpecificPaymentSequenceEvidenceAheadOfATemptingGenericTurnMatch() {
        UUID documentVersionId = UUID.randomUUID();
        HybridEvidenceHit generic = hit(
                documentVersionId,
                "RULES",
                "Player turn",
                "A player takes a turn and resolves one action.",
                4,
                1.0);
        HybridEvidenceHit specific = hit(
                documentVersionId,
                "RULES",
                "Payment sequence",
                "After choosing the upgrade, pay its energy cost before placing the marker.",
                9,
                1.0);
        Map<UUID, HybridEvidenceHit> evidence = Map.of(
                generic.evidence().chunkId(), generic,
                specific.evidence().chunkId(), specific);

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "After choosing an upgrade, when do I pay the energy cost, and when is the marker placed?",
                evidence,
                List.of(generic, specific),
                Set.of());

        assertThat(selected).first().isEqualTo(specific);
    }

    @Test
    void preservesTheCompleteRelevantConfirmedPageGroupBeforeANewerWeakPage() {
        UUID documentVersionId = UUID.randomUUID();
        List<HybridEvidenceHit> relevantPage = List.of(
                hit(documentVersionId, "RULES", "Required action", "The decree action cannot be taken.", 13, 1.0),
                hit(documentVersionId, "RULES", "Consequence", "Resolve the failed requirement procedure.", 13, 1.0),
                hit(documentVersionId, "RULES", "Exception", "Enter turmoil after the failure.", 13, 1.0),
                hit(documentVersionId, "RULES", "Sequence", "Apply each consequence in order.", 13, 1.0),
                hit(documentVersionId, "RULES", "Timing", "Then end daylight immediately.", 13, 1.0),
                hit(documentVersionId, "RULES", "Completion", "The current phase is now complete.", 13, 1.0));
        List<HybridEvidenceHit> weakNewerPage = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> hit(
                        documentVersionId,
                        "RULES",
                        "Faction reference " + index,
                        "A faction may take one action during its phase.",
                        18,
                        1.0))
                .toList();
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        java.util.stream.Stream.concat(weakNewerPage.stream(), relevantPage.stream())
                .forEach(hit -> evidence.put(hit.evidence().chunkId(), hit));

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "If the required decree action cannot be taken, what happens, and when does daylight end?",
                evidence,
                evidence.values(),
                Set.of(),
                List.of(weakNewerPage, relevantPage));

        assertThat(selected.subList(0, relevantPage.size())).containsExactlyElementsOf(relevantPage);
    }

    @Test
    void preservesASpecificPaymentPageGroupAheadOfARepeatedGenericTurnPage() {
        UUID documentVersionId = UUID.randomUUID();
        List<HybridEvidenceHit> paymentPage = List.of(
                hit(documentVersionId, "RULES", "Choose", "Choose the upgrade before paying.", 9, 1.0),
                hit(documentVersionId, "RULES", "Pay", "Pay the complete energy cost.", 9, 1.0),
                hit(documentVersionId, "RULES", "Place", "Place the marker only after payment.", 9, 1.0));
        List<HybridEvidenceHit> genericPage = List.of(
                hit(documentVersionId, "RULES", "Turn", "A player takes a turn.", 4, 1.0),
                hit(documentVersionId, "RULES", "Action", "Resolve one action on a turn.", 4, 1.0));
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        java.util.stream.Stream.concat(genericPage.stream(), paymentPage.stream())
                .forEach(hit -> evidence.put(hit.evidence().chunkId(), hit));

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                "After choosing an upgrade, when is the energy cost paid, and when is its marker placed?",
                evidence,
                evidence.values(),
                Set.of(),
                List.of(genericPage, paymentPage));

        assertThat(selected.subList(0, paymentPage.size())).containsExactlyElementsOf(paymentPage);
    }

    private HybridEvidenceHit hit(
            UUID documentVersionId, String sectionType, String heading, String excerpt, int page, double score) {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), documentVersionId, sectionType, heading, excerpt, page, page, score);
        return new HybridEvidenceHit(source, score, 1, null, false);
    }
}
