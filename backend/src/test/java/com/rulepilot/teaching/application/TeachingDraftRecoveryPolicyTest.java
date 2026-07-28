package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
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
    void givesAnActionableRepairForADanglingPlayerChoice() {
        assertThat(policy.repairFeedback(
                        "Finish every player-facing instruction; do not end it with an unanswered either/or alternative.",
                        false,
                        false))
                .containsExactly(
                        "Finish every player-facing instruction; do not end it with an unanswered either/or alternative.",
                        "Rewrite the dangling alternative as a complete grounded instruction. If the cited rule gives an "
                                + "exclusive choice, state every branch and its result; otherwise retain only the resolved cited "
                                + "procedure. Do not end any player-facing step with “还是”, “或者”, or another unanswered alternative.");
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

    @Test
    void preservesCitedEndOfRoundTimingWithoutDiscardingTheStepCitation() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence endOfRound = new RuleEvidence(
                evidenceId,
                UUID.randomUUID(),
                "ENDGAME",
                "End game",
                "The game ends at the end of a round when the last card is taken.",
                8,
                8);
        StepDraft immediateEnding = new StepDraft(
                "检查终局",
                TeachingMove.CHECK,
                "拿走最后一张卡后，游戏立即结束。",
                List.of(evidenceId));
        SectionDraft draft = new SectionDraft(
                "结束",
                VisualKind.REFERENCE_CARD,
                "查看结束条件。",
                List.of(evidenceId),
                List.of(immediateEnding));

        SectionDraft corrected = policy.preserveCitedEndOfRoundTiming(draft, List.of(endOfRound));

        assertThat(corrected).isNotSameAs(draft);
        assertThat(corrected.steps().getFirst().citationIds()).containsExactly(evidenceId);
        assertThat(corrected.steps().getFirst().text()).isEqualTo("触发这一终局条件后，先完成当前轮次；游戏在本轮结束时结束。");
        assertThat(LessonDraftValidator.claimsImmediateEndingForEndOfRoundTrigger(
                        corrected.steps().getFirst().text(), List.of(endOfRound)))
                .isFalse();
    }

    @Test
    void restoresEveryCitedPlayerCountRoundPairWhenTheDraftNarrowsTheAudience() {
        UUID evidenceId = UUID.randomUUID();
        RuleEvidence endSchedule = new RuleEvidence(
                evidenceId,
                UUID.randomUUID(),
                "END",
                "End of Game",
                "At the end of the 7th round (if playing with 2 or 3 players) or the 10th round (if playing with 4 or 5 players) the game ends.",
                10,
                10);
        SectionDraft narrowed = new SectionDraft(
                "结束",
                VisualKind.REFERENCE_CARD,
                "检查结束时间。",
                List.of(evidenceId),
                List.of(new StepDraft("结束时间", TeachingMove.FLOW, "4人游戏在第10轮结束。", List.of(evidenceId))));

        SectionDraft repaired = policy.preserveCitedPlayerCountRoundSchedule(narrowed, List.of(endSchedule));

        assertThat(repaired.steps().getFirst().text())
                .contains("2、3人均在第7轮结束", "4、5人均在第10轮结束");
        assertThat(repaired.steps().getFirst().citationIds()).containsExactly(evidenceId);
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
