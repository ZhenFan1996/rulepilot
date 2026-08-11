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
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonDraftValidatorTest {

    @Test
    void validatesSchemaAndCitationScopeWithoutInterpretingNaturalRuleWording() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, 8, "All other players may discard or pass before the trick closes.");
        SectionDraft naturallyParaphrased = textDraft(
                chunkId,
                "当其他玩家连续弃牌或跳过后，本墩结束；胜者接着开下一墩。",
                "自然改述");

        LessonDraftValidator.validateDraft(naturallyParaphrased, textRequest(chunkId));
        var step = LessonDraftValidator.validatedStep(1, naturallyParaphrased.steps().getFirst(), Map.of(chunkId, evidence));

        assertThat(step.text()).contains("弃牌或跳过");
        assertThat(step.sourcePages()).containsExactly(8);
    }

    @Test
    void rejectsCitationsOutsideTheRetrievedScope() {
        UUID allowedId = UUID.randomUUID();
        UUID inventedId = UUID.randomUUID();
        SectionDraft draft = textDraft(inventedId, "照此执行。", "引用越界");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validatedStep(
                        1, draft.steps().getFirst(), Map.of(allowedId, evidence(allowedId, 2, "Rule"))))
                .withMessageContaining("outside retrieval scope");
    }

    @Test
    void rejectsInternalEvidenceReferencesAndExtractionPlaceholders() {
        UUID chunkId = UUID.randomUUID();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validateDraft(
                        textDraft(chunkId, "参见 E1 后执行。", "内部引用"), textRequest(chunkId)))
                .withMessageContaining("internal evidence references");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validateDraft(
                        textDraft(chunkId, "支付 [cost] 后执行。", "提取占位符"), textRequest(chunkId)))
                .withMessageContaining("extraction markers");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validateDraft(
                        textDraft(chunkId, "支付 🎲 后执行。", "推测图标"), textRequest(chunkId)))
                .withMessageContaining("inferred emoji");
    }

    @Test
    void keepsAVisualStepBoundToItsAttachedAndCitedPage() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidence evidence = evidence(chunkId, 3, "Place the board in the center.");
        SectionRequest request = visualRequest(chunkId);
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
        var step = LessonDraftValidator.validatedStep(1, draft.steps().getFirst(), allowedEvidence);

        assertThat(LessonDraftValidator.validatedVisualCitationIds(draft, allowedEvidence)).containsExactly(chunkId);
        assertThat(step.visualFocus()).isEqualTo(new VisualFocus(3, "主棋盘", 150, 180, 450, 400));
    }

    @Test
    void rejectsAnAlmostCompletePageAsAVisualFocus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LessonDraftValidator.validatedFocus(
                        new VisualFocusDraft(3, "整页", 0, 0, 1_000, 1_000)))
                .withMessageContaining("tight focus region");
    }

    @Test
    void doesNotDuplicateAVisualCaptionAsASeparateCriticClaim() {
        UUID chunkId = UUID.randomUUID();
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

        assertThat(LessonDraftValidator.reviewClaims(draft, List.of(chunkId)))
                .extracting(com.rulepilot.assistant.GeneratedContentCritic.Claim::text)
                .containsExactly("找到主棋盘：在图中找到主棋盘。");
    }

    private SectionDraft textDraft(UUID chunkId, String text, String heading) {
        return new SectionDraft(
                "本章规则",
                VisualKind.REFERENCE_CARD,
                "本章要点。",
                List.of(chunkId),
                List.of(new StepDraft(heading, TeachingMove.DO, text, List.of(chunkId))));
    }

    private SectionRequest textRequest(UUID chunkId) {
        return new SectionRequest(
                "flow",
                "流程",
                "讲清流程",
                List.of("flow"),
                List.of(),
                List.of(new EvidenceInput(chunkId, "FLOW", "Flow", "Rule", 8, 8)));
    }

    private SectionRequest visualRequest(UUID chunkId) {
        return new SectionRequest(
                "setup",
                "设置",
                "完成设置",
                List.of("setup"),
                List.of(),
                List.of(new EvidenceInput(chunkId, "SETUP", "Setup", "Place the board.", 3, 3)),
                List.of(new PageImageInput(3, "image/png", new byte[] {1}, 100, 100)));
    }

    private RuleEvidence evidence(UUID chunkId, int page, String excerpt) {
        return new RuleEvidence(chunkId, UUID.randomUUID(), "RULE", "Rule", excerpt, page, page);
    }
}
