package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.RuleAnswerModel.AnswerAid;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
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
    void placesVisualEvidenceFirstOnlyWhenTheStructuredPlanRequestsIt() {
        UUID versionId = UUID.randomUUID();
        HybridEvidenceHit visual = hit(versionId, "VISUAL", "Printed cell", "A-01 is visible.", 7, 0.4);
        HybridEvidenceHit text = hit(versionId, "RULE", "Actions", "Actions grant movement.", 5, 0.9);
        Map<UUID, HybridEvidenceHit> evidence = Map.of(
                visual.evidence().chunkId(), visual,
                text.evidence().chunkId(), text);

        List<HybridEvidenceHit> visualFirst = AnswerEvidenceSelectionPolicy.select(
                evidence,
                List.of(text),
                Set.of(visual.evidence().chunkId()),
                plan(EvidenceNeed.VISUAL_REFERENCE),
                List.of());
        List<HybridEvidenceHit> textFirst = AnswerEvidenceSelectionPolicy.select(
                evidence,
                List.of(text),
                Set.of(visual.evidence().chunkId()),
                plan(EvidenceNeed.DIRECT_RULE),
                List.of());

        assertThat(visualFirst).containsExactly(visual, text);
        assertThat(textFirst).containsExactly(text, visual);
    }

    @Test
    void expandsTheEvidenceBudgetForAStructuredCompleteListObligation() {
        UUID versionId = UUID.randomUUID();
        Map<UUID, HybridEvidenceHit> evidence = evidence(versionId, 7);

        assertThat(AnswerEvidenceSelectionPolicy.select(
                        evidence, List.of(), Set.of(), plan(EvidenceNeed.DIRECT_RULE), List.of()))
                .hasSize(5);
        assertThat(AnswerEvidenceSelectionPolicy.select(
                        evidence, List.of(), Set.of(), plan(EvidenceNeed.COMPLETE_LIST), List.of()))
                .hasSize(7);
    }

    @Test
    void keepsTheGoverningRuleAndWorkedExampleBudgetForACalculation() {
        UUID versionId = UUID.randomUUID();
        Map<UUID, HybridEvidenceHit> evidence = evidence(versionId, 8);
        AnswerQuestionPlan calculation = new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion(
                        "calculate the current total", Set.of(EvidenceNeed.DIRECT_RULE))),
                true,
                AnswerAid.CALCULATION,
                ReferenceBinding.CURRENT_QUESTION);

        assertThat(AnswerEvidenceSelectionPolicy.select(
                        evidence, List.of(), Set.of(), calculation, List.of()))
                .hasSize(8);
    }

    @Test
    void preservesCallerConfirmedPageGroupsWithoutReinterpretingQuestionText() {
        UUID versionId = UUID.randomUUID();
        List<HybridEvidenceHit> firstConfirmedPage = List.of(
                hit(versionId, "RULE", "First A", "First fact A.", 4, 0.4),
                hit(versionId, "RULE", "First B", "First fact B.", 4, 0.3));
        List<HybridEvidenceHit> secondConfirmedPage = List.of(
                hit(versionId, "RULE", "Second", "Second fact.", 9, 1.0));
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        java.util.stream.Stream.concat(firstConfirmedPage.stream(), secondConfirmedPage.stream())
                .forEach(hit -> evidence.put(hit.evidence().chunkId(), hit));

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                evidence,
                List.of(),
                Set.of(),
                plan(EvidenceNeed.DIRECT_RULE),
                List.of(firstConfirmedPage, secondConfirmedPage));

        assertThat(selected).containsExactlyElementsOf(List.of(
                firstConfirmedPage.get(0), firstConfirmedPage.get(1), secondConfirmedPage.get(0)));
    }

    @Test
    void preservesStructuredIntentAnchorOrderRatherThanLexicallyRankingProse() {
        UUID versionId = UUID.randomUUID();
        HybridEvidenceHit lowerScoreFirst = hit(
                versionId, "RULE", "Chosen first", "Caller-confirmed first source.", 12, 0.2);
        HybridEvidenceHit higherScoreSecond = hit(
                versionId, "RULE", "Chosen second", "Caller-confirmed second source.", 3, 1.0);
        Map<UUID, HybridEvidenceHit> evidence = Map.of(
                lowerScoreFirst.evidence().chunkId(), lowerScoreFirst,
                higherScoreSecond.evidence().chunkId(), higherScoreSecond);

        List<HybridEvidenceHit> selected = AnswerEvidenceSelectionPolicy.select(
                evidence,
                List.of(lowerScoreFirst, higherScoreSecond),
                Set.of(),
                plan(EvidenceNeed.DIRECT_RULE),
                List.of());

        assertThat(selected).containsExactly(lowerScoreFirst, higherScoreSecond);
    }

    private AnswerQuestionPlan plan(EvidenceNeed need) {
        return new AnswerQuestionPlan(
                List.of(new AnswerQuestionPlan.Subquestion("bounded subquestion", Set.of(need))),
                true,
                AnswerAid.NONE,
                ReferenceBinding.CURRENT_QUESTION);
    }

    private Map<UUID, HybridEvidenceHit> evidence(UUID versionId, int count) {
        Map<UUID, HybridEvidenceHit> evidence = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            HybridEvidenceHit hit = hit(
                    versionId, "RULE", "Rule " + index, "Evidence " + index + '.', 2 + index,
                    1.0 - index * 0.01);
            evidence.put(hit.evidence().chunkId(), hit);
        }
        return evidence;
    }

    private HybridEvidenceHit hit(
            UUID versionId, String sectionType, String heading, String excerpt, int page, double score) {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, heading, excerpt, page, page, score);
        return new HybridEvidenceHit(source, score, 1, null, false);
    }
}
