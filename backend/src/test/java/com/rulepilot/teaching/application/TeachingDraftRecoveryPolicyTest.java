package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingDraftRecoveryPolicyTest {

    private final TeachingDraftRecoveryPolicy policy = new TeachingDraftRecoveryPolicy();

    @Test
    void limits_visual_drafts_to_one_repair_and_falls_back_only_when_text_evidence_remains() {
        assertThat(policy.maxRepairAttempts(true)).isEqualTo(1);
        assertThat(policy.maxRepairAttempts(false)).isEqualTo(3);
        assertThat(policy.shouldFallbackToCitedText(true, false, 0)).isFalse();
        assertThat(policy.shouldFallbackToCitedText(true, false, 1)).isTrue();
        assertThat(policy.shouldFallbackToCitedText(true, true, 1)).isFalse();
        assertThat(policy.shouldFallbackToCitedText(false, false, 3)).isFalse();
    }

    @Test
    void adds_visual_repair_guidance_only_for_a_visual_validation_failure() {
        List<String> visualFeedback = policy.repairFeedback("VISUAL_FOCUS_MISSING", true, true);

        assertThat(visualFeedback).hasSize(2);
        assertThat(visualFeedback.getFirst()).isEqualTo("VISUAL_FOCUS_MISSING");
        assertThat(visualFeedback.get(1)).contains("compact 0-1000 focus rectangle");
        assertThat(policy.repairFeedback("STEP_CITATIONS_MISSING", true, false))
                .containsExactly("STEP_CITATIONS_MISSING");
        assertThat(policy.repairFeedback("VISUAL_FOCUS_MISSING", false, true))
                .containsExactly("VISUAL_FOCUS_MISSING");
    }

    @Test
    void removes_page_images_without_losing_the_grounded_request_context() {
        SectionRequest visualRequest = request(List.of(new PageImageInput(2, "image/png", new byte[] {1}, 640, 480)));

        SectionRequest textOnlyRequest = policy.withoutPageImages(visualRequest);

        assertThat(textOnlyRequest.pageImages()).isEmpty();
        assertThat(textOnlyRequest.evidence()).isEqualTo(visualRequest.evidence());
        assertThat(textOnlyRequest.requiredRuleIntents()).isEqualTo(visualRequest.requiredRuleIntents());
        assertThat(textOnlyRequest.chapterScope()).isEqualTo(visualRequest.chapterScope());
        assertThat(textOnlyRequest.modelConfigurationOwner()).isEqualTo(visualRequest.modelConfigurationOwner());
    }

    @Test
    void retains_valid_presentation_metadata_when_a_text_only_revision_drops_it() {
        UUID chunkId = UUID.randomUUID();
        SectionDraft previous = new SectionDraft(
                "原章节",
                VisualKind.REFERENCE_CARD,
                "按引用完成这一节。",
                List.of(chunkId),
                List.of(new StepDraft("原步骤", TeachingMove.DO, "按引用完成这一节。", List.of(chunkId))));
        StepDraft revisedStep = new StepDraft(
                "修订步骤", TeachingMove.DO, "修订后的规则文字。", List.of(chunkId));
        SectionDraft revised = new SectionDraft(
                "修订章节", null, "", List.of(), List.of(revisedStep));

        SectionDraft preserved = policy.preserveTextOnlyPresentationMetadata(previous, revised);

        assertThat(preserved.visualKind()).isEqualTo(VisualKind.REFERENCE_CARD);
        assertThat(preserved.visualCaption()).isEqualTo("按引用完成这一节。");
        assertThat(preserved.visualCitationIds()).containsExactly(chunkId);
        assertThat(preserved.steps()).containsExactly(revisedStep);
    }

    @Test
    void keeps_a_valid_revised_presentation_and_wraps_text_only_feedback_for_the_player_facing_repair() {
        UUID chunkId = UUID.randomUUID();
        SectionDraft revised = new SectionDraft(
                "修订章节",
                VisualKind.REFERENCE_CARD,
                "有效图例说明。",
                List.of(chunkId),
                List.of(new StepDraft("修订步骤", TeachingMove.DO, "修订后的规则文字。", List.of(chunkId))));

        assertThat(policy.preserveTextOnlyPresentationMetadata(null, revised)).isSameAs(revised);
        assertThat(policy.preserveTextOnlyPresentationMetadata(revised, revised)).isSameAs(revised);
        assertThat(policy.textFallbackFeedback("STEP_CITATIONS_MISSING"))
                .containsExactly(
                        "Keep this section text-only and preserve all grounded rule coverage. STEP_CITATIONS_MISSING");
    }

    private SectionRequest request(List<PageImageInput> pageImages) {
        UUID evidenceId = UUID.randomUUID();
        return new SectionRequest(
                "SETUP",
                "开局",
                "让玩家完成开局。",
                List.of("setup"),
                4,
                4,
                20,
                120,
                3,
                List.of(),
                List.of(new EvidenceInput(evidenceId, "SETUP", "开局", "把主棋盘放在桌面中央。", 2, 2)),
                pageImages,
                List.of("如何摆放主棋盘？"),
                "owner",
                "开局范围");
    }
}
