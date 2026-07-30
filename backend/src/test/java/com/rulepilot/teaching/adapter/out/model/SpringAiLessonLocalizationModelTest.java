package com.rulepilot.teaching.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.adapter.out.model.SpringAiLessonLocalizationModel.SectionTranslationDraft;
import com.rulepilot.teaching.adapter.out.model.SpringAiLessonLocalizationModel.StepTranslationDraft;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringAiLessonLocalizationModelTest {

    @Test
    void boundsOptionalVisualProseAndClearsItForTextOnlySteps() {
        LessonSection source = new LessonSection(
                1,
                "setup",
                List.of("setup"),
                "设置",
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.TABLE_LAYOUT,
                "查看桌面。",
                List.of(2),
                List.of(),
                List.of(
                        new LessonStep(
                                1,
                                "摆放",
                                TeachingMove.VISUAL,
                                "摆好组件。",
                                List.of(2),
                                List.of(),
                                new VisualFocus(2, "主棋盘", "图中显示主棋盘和周围组件。", 100, 100, 300, 300)),
                        new LessonStep(
                                2,
                                "检查",
                                TeachingMove.CHECK,
                                "检查数量。",
                                List.of(2),
                                List.of(),
                                null)));
        String longDescription = "This crop shows the board and every nearby component in a deliberately verbose "
                .repeat(6);
        SectionTranslationDraft draft = new SectionTranslationDraft(
                1,
                "Setup",
                "Look at the table.",
                List.of(
                        new StepTranslationDraft(1, "Place", "Place the components.", "Main board", longDescription),
                        new StepTranslationDraft(2, "Check", "Check the count.", "invented label", "invented view")));

        var translated = SpringAiLessonLocalizationModel.toDomain(source, draft);

        assertThat(translated.steps().getFirst().visualDescription()).hasSizeLessThanOrEqualTo(240).endsWith("…");
        assertThat(translated.steps().get(1).visualLabel()).isEmpty();
        assertThat(translated.steps().get(1).visualDescription()).isEmpty();
    }
}
