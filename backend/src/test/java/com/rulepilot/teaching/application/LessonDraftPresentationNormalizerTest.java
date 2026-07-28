package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.TeachingLessonModel.EvidenceInput;
import com.rulepilot.teaching.TeachingLessonModel.PageImageInput;
import com.rulepilot.teaching.TeachingLessonModel.SectionDraft;
import com.rulepilot.teaching.TeachingLessonModel.SectionRequest;
import com.rulepilot.teaching.TeachingLessonModel.StepDraft;
import com.rulepilot.teaching.TeachingLessonModel.VisualFocusDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LessonDraftPresentationNormalizerTest {

    private final LessonDraftPresentationNormalizer normalizer = new LessonDraftPresentationNormalizer();

    @Test
    void makesModelDraftPlayerFacingAndAlignsVisualEvidence() {
        UUID textEvidence = UUID.randomUUID();
        UUID pageEvidence = UUID.randomUUID();
        SectionRequest request = new SectionRequest(
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
                List.of(
                        new EvidenceInput(textEvidence, "SETUP", "开局", "把主棋盘放到桌面中央。", 1, 1),
                        new EvidenceInput(pageEvidence, "SETUP", "开局图示", "图示中的主棋盘位于中央。", 2, 2)),
                List.of(new PageImageInput(2, "image/png", new byte[] {1}, 100, 100)));
        SectionDraft draft = new SectionDraft(
                "根据已提供的证据： [Setup]",
                VisualKind.TABLE_LAYOUT,
                "附件 rulebook page 2",
                List.of(),
                List.of(new StepDraft(
                        "找到主棋盘",
                        TeachingMove.VISUAL,
                        "根据当前证据：找到 👣，再把主棋盘放到桌面中央。",
                        List.of(textEvidence),
                        new VisualFocusDraft(2, "Gameplay Overview Diagram", 100, 100, 300, 300))));

        SectionDraft normalized = normalizer.normalize(draft, request);

        assertThat(normalized.title()).isEqualTo("“Setup”图标");
        assertThat(normalized.visualCaption()).isEqualTo("找到 “脚印（移动）”图标，再把主棋盘放到桌面中央。");
        assertThat(normalized.visualCitationIds()).containsExactly(textEvidence, pageEvidence);
        assertThat(normalized.steps()).singleElement().satisfies(step -> {
            assertThat(step.text()).isEqualTo("找到 “脚印（移动）”图标，再把主棋盘放到桌面中央。");
            assertThat(step.citationIds()).containsExactly(textEvidence, pageEvidence);
            assertThat(step.visualFocus().label()).isEqualTo("找到主棋盘");
        });
    }

    @Test
    void exposesPresentationValidationPredicates() {
        assertThat(LessonDraftPresentationNormalizer.containsUnresolvedPdfMarker("找到 [cost]"))
                .isTrue();
        assertThat(LessonDraftPresentationNormalizer.containsUnresolvedEmojiIcon("找到 🧩"))
                .isTrue();
        assertThat(LessonDraftPresentationNormalizer.containsTrailingIncompleteThought("然后继续…"))
                .isTrue();
        assertThat(LessonDraftPresentationNormalizer.containsTrailingUnansweredAlternative("选择红色，或者蓝色"))
                .isTrue();
        assertThat(LessonDraftPresentationNormalizer.containsInternalEvidenceLanguage("当前证据说明如此"))
                .isTrue();
        assertThat(LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference("参见 E1"))
                .isTrue();
        assertThat(LessonDraftPresentationNormalizer.containsInternalShortEvidenceReference("effectE12"))
                .isFalse();
    }

    @Test
    void repairsMissingStepPresentationMetadataWithoutChangingTheCitedRuleText() {
        UUID evidence = UUID.randomUUID();
        SectionRequest request = new SectionRequest(
                "setup",
                "完成开局",
                "让玩家完成开局布置",
                List.of("setup"),
                2,
                1,
                15,
                120,
                3,
                List.of(),
                List.of(new EvidenceInput(evidence, "SETUP", "开局", "把主棋盘放到桌面中央。", 1, 1)),
                List.of());
        SectionDraft draft = new SectionDraft(
                "完成开局",
                VisualKind.TABLE_LAYOUT,
                "把主棋盘放到桌面中央。",
                List.of(evidence),
                List.of(new StepDraft(null, null, "把主棋盘放到桌面中央。", List.of(evidence), null)));

        SectionDraft normalized = normalizer.normalize(draft, request);

        assertThat(normalized.steps()).singleElement().satisfies(step -> {
            assertThat(step.heading()).isEqualTo("本步要点");
            assertThat(step.kind()).isEqualTo(TeachingMove.UNDERSTAND);
            assertThat(step.text()).isEqualTo("把主棋盘放到桌面中央。");
            assertThat(step.citationIds()).containsExactly(evidence);
        });
    }
}
