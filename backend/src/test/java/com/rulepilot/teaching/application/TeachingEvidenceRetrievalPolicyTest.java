package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageAvailability;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageSlotDraft;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingEvidenceRetrievalPolicyTest {

    @Test
    void preservesTheModelChosenQueryInsteadOfApplyingSemanticStopWordRules() {
        assertThat(TeachingEvidenceRetrievalPolicy.focusedQuery(
                        "What is the cost to launch a probe and what is the default limit on probes in space?"))
                .isEqualTo("What is the cost to launch a probe and what is the default limit on probes in space?");
        assertThat(TeachingEvidenceRetrievalPolicy.focusedQuery("  任意语言的完整检索条件  "))
                .isEqualTo("任意语言的完整检索条件");
    }

    @Test
    void searchesOnlyTheOutlineAgentsStructuredSourceIdentifiers() {
        PlannedSection planned = new PlannedSection(
                1,
                "setup",
                "Setup",
                "Set up the board and hand out starting cards.",
                true,
                false,
                List.of("setup", "starting cards"),
                List.of("setup"));

        assertThat(TeachingEvidenceRetrievalPolicy.queries(planned, 2))
                .containsExactly("setup", "starting cards");
        assertThat(TeachingEvidenceRetrievalPolicy.queries(planned, 3))
                .containsExactly("setup", "starting cards");
    }

    @Test
    void neverTurnsAnUnresolvedTypedSourceIntoARetrievalQuery() {
        List<SourceCoverageSlotDraft> slots = List.of(
                new SourceCoverageSlotDraft(
                        "sourced-slot", SourceCoverageRole.CORE_LOOP, "R-loop", List.of(2),
                        "mixed-topic", "sourced-unit", SourceCoverageAvailability.SOURCED),
                new SourceCoverageSlotDraft(
                        "missing-slot", SourceCoverageRole.ENDING, "External ending", List.of(3),
                        "mixed-topic", "missing-unit", SourceCoverageAvailability.MISSING_EXTERNAL_SOURCE),
                new SourceCoverageSlotDraft(
                        "unresolved-slot", SourceCoverageRole.SCORING, "Unresolved scoring", List.of(),
                        "mixed-topic", "unresolved-unit", SourceCoverageAvailability.UNRESOLVED));
        PlannedSection planned = new PlannedSection(
                1,
                "mixed-topic",
                "Mixed source state",
                "Teach what the typed source inventory supports.",
                true,
                false,
                TeachingUnitContract.encodeUnits(slots),
                List.of("source_contract_v1"),
                List.of(2, 3));

        assertThat(TeachingEvidenceRetrievalPolicy.queries(planned, 3))
                .containsExactly("R-loop", "External ending")
                .doesNotContain("Unresolved scoring");
    }

    @Test
    void balancesDistinctIntentsByRankAndKeepsTheEvidenceCap() {
        List<RuleEvidence> firstIntent = evidenceRange(1, 6);
        List<RuleEvidence> secondIntent = evidenceRange(7, 12);

        List<RuleEvidence> selected = TeachingEvidenceRetrievalPolicy.balancedEvidence(
                List.of(firstIntent, secondIntent));

        assertThat(selected).extracting(RuleEvidence::heading).containsExactly(
                "Rule 1", "Rule 7", "Rule 2", "Rule 8", "Rule 3",
                "Rule 9", "Rule 4", "Rule 10", "Rule 5", "Rule 11");
    }

    private List<RuleEvidence> evidenceRange(int first, int last) {
        return java.util.stream.IntStream.rangeClosed(first, last)
                .mapToObj(number -> new RuleEvidence(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "SETUP",
                        "Rule " + number,
                        "Rule text " + number,
                        number,
                        number))
                .toList();
    }
}
