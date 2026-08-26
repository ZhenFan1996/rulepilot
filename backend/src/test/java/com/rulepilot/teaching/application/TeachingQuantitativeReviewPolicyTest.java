package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.RuleFactDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.RuleFactRole;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingQuantitativeReviewPolicyTest {

    private final UUID evidenceId = UUID.randomUUID();

    @Test
    void reviewsANumericRelationshipRegardlessOfTeachingMove() {
        SectionDraft draft = draft(new StepDraft(
                "准备供应区",
                TeachingMove.UNDERSTAND,
                "在供应区放置 14 枚标记，并为每位玩家再放置 2 枚。",
                List.of(evidenceId)));

        assertThat(TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(planned(), draft)).isTrue();
    }

    @Test
    void reviewsWrittenQuantitiesAndEnglishMultipliers() {
        SectionDraft chinese = draft(new StepDraft(
                "结算奖励", TeachingMove.DO, "每张已完成卡得到九分。", List.of(evidenceId)));
        SectionDraft english = draft(new StepDraft(
                "Resolve reward", TeachingMove.DO, "Score once per completed row.", List.of(evidenceId)));

        assertThat(TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(planned(), chinese)).isTrue();
        assertThat(TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(planned(), english)).isTrue();
    }

    @Test
    void reviewsALegalityChangingFactEvenWhenItContainsNoNumber() {
        RuleFactDraft exception = new RuleFactDraft(
                RuleFactRole.EXCEPTION,
                "封锁期间，持有通行标记的玩家仍可进入。",
                List.of(evidenceId));
        SectionDraft draft = draft(new StepDraft(
                "检查例外",
                TeachingMove.CHECK,
                "先检查角色是否满足例外。",
                List.of(evidenceId),
                List.of("exception-unit"),
                List.of(exception)));

        assertThat(TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(planned(), draft)).isTrue();
    }

    @Test
    void doesNotTreatOrdinarySequenceLanguageAsAQuantity() {
        SectionDraft draft = draft(new StepDraft(
                "继续流程",
                TeachingMove.FLOW,
                "完成当前行动后，交给下一位玩家继续。",
                List.of(evidenceId)));

        assertThat(TeachingQuantitativeReviewPolicy.requiresCompleteReviewEvidence(planned(), draft)).isFalse();
    }

    private SectionDraft draft(StepDraft step) {
        return new SectionDraft(
                "流程说明",
                VisualKind.FLOW_DIAGRAM,
                "按顺序完成本节流程。",
                List.of(evidenceId),
                List.of(step));
    }

    private TeachingPlan.PlannedSection planned() {
        return new TeachingPlan.PlannedSection(
                1,
                "opaque-flow",
                "流程说明",
                "学会执行这一段规则。",
                true,
                false,
                List.of("opaque source relation"),
                List.of());
    }
}
