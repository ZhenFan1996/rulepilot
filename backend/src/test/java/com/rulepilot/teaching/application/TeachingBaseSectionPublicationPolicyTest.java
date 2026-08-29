package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingBaseSectionPublicationPolicyTest {

    private final UUID evidenceId = UUID.randomUUID();
    private final TeachingPlan.PlannedSection planned = new TeachingPlan.PlannedSection(
            1, "flow", "流程", "学会执行流程。", true, false, List.of("flow source"), List.of());

    @Test
    void publishesAQuantitativeSectionAfterDeterministicValidation() {
        TeachingSectionDraftCandidate candidate = candidate("每位玩家拿取 3 枚标记。");

        LessonSection published = new TeachingBaseSectionPublicationPolicy().publish(candidate);

        assertThat(published.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    @Test
    void publishesANonQuantitativeCitedSectionWithoutAddingAnotherModelGate() {
        TeachingSectionDraftCandidate candidate = candidate("完成行动后，把回合交给下一位玩家。");

        LessonSection published = new TeachingBaseSectionPublicationPolicy().publish(candidate);

        assertThat(published.evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    private TeachingSectionDraftCandidate candidate(String text) {
        StepDraft step = new StepDraft("执行", TeachingMove.DO, text, List.of(evidenceId));
        SectionDraft draft = new SectionDraft(
                "流程", VisualKind.FLOW_DIAGRAM, "按顺序执行。", List.of(evidenceId), List.of(step));
        LessonSection section = new LessonSection(
                1,
                "flow",
                List.of(),
                "流程",
                true,
                EvidenceStatus.CITED_DRAFT,
                VisualKind.FLOW_DIAGRAM,
                "按顺序执行。",
                List.of(1),
                List.of(evidenceId),
                List.of(new LessonStep(1, "执行", TeachingMove.DO, text, List.of(1), List.of(evidenceId))));
        return new TeachingSectionDraftCandidate(0, planned, List.of(), null, draft, section);
    }
}
