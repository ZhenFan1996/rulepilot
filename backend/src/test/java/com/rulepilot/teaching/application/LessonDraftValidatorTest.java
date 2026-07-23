package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.rulepilot.assistant.AssistantReadTools.RuleEvidence;
import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonDraftValidatorTest {

    @Test
    void keepsAVisualStepBoundToItsAttachedAndCitedRulebookPage() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = new RuleEvidence(
                chunkId, UUID.randomUUID(), "SETUP", "Setup", "Place the board in the center.", 3, 3);
        SectionRequest request = request(chunkId);
        SectionDraft draft = new SectionDraft(
                "完成开局",
                VisualKind.TABLE_LAYOUT,
                "在图中找到主棋盘。",
                List.of(chunkId),
                List.of(new StepDraft(
                        "找到主棋盘",
                        TeachingMove.VISUAL,
                        "在图中找到主棋盘。",
                        List.of(chunkId),
                        new VisualFocusDraft(3, "主棋盘", 150, 180, 450, 400))));
        Map<UUID, RuleEvidence> allowedEvidence = Map.of(chunkId, evidence);

        LessonDraftValidator.validateDraft(draft, request);
        LessonDraftValidator.validateVisualBlockEvidence(draft, request, allowedEvidence);
        LessonStep step = LessonDraftValidator.validatedStep(1, draft.steps().getFirst(), allowedEvidence);

        assertThat(LessonDraftValidator.validatedVisualCitationIds(draft, allowedEvidence)).containsExactly(chunkId);
        assertThat(step.sourcePages()).containsExactly(3);
        assertThat(step.visualFocus()).isEqualTo(new VisualFocus(3, "主棋盘", 150, 180, 450, 400));
    }

    @Test
    void clampsAValidFocusButRejectsAnAlmostCompletePage() {
        assertThat(LessonDraftValidator.validatedFocus(new VisualFocusDraft(3, "主棋盘", -30, 990, 2_000, 100)))
                .isEqualTo(new VisualFocus(3, "主棋盘", 0, 980, 1_000, 20));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validatedFocus(
                        new VisualFocusDraft(3, "整页", 0, 0, 1_000, 1_000)))
                .withMessageContaining("tight focus region");
    }

    private SectionRequest request(UUID chunkId) {
        return new SectionRequest(
                "setup",
                "完成开局",
                "让玩家完成开局布置",
                List.of("开局"),
                2,
                1,
                15,
                120,
                3,
                List.of(),
                List.of(new EvidenceInput(chunkId, "SETUP", "Setup", "Place the board in the center.", 3, 3)),
                List.of(new PageImageInput(3, "image/png", new byte[] {1}, 100, 100)));
    }
}
