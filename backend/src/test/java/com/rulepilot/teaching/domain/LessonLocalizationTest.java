package com.rulepilot.teaching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.shared.PlayerLocale;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualFocus;
import com.rulepilot.teaching.domain.LessonLocalization.SectionTranslation;
import com.rulepilot.teaching.domain.LessonLocalization.StepTranslation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LessonLocalizationTest {

    @Test
    void translatesReadingProseWithoutReorderingOrReidentifyingTheFigures() {
        var focus = new VisualFocus(2, "source", 100, 100, 300, 300);
        var blocks = List.of(
                new IllustratedLesson.ReadingBlock(IllustratedLesson.ReadingKind.PARAGRAPH, "看这条路线。", List.of(), com.rulepilot.teaching.domain.IllustratedLesson.FigureFlow.NONE),
                new IllustratedLesson.ReadingBlock(IllustratedLesson.ReadingKind.FIGURES, "",
                        List.of(new IllustratedLesson.ReadingFigure(focus, "蓝色路线")), IllustratedLesson.FigureFlow.ASIDE),
                new IllustratedLesson.ReadingBlock(IllustratedLesson.ReadingKind.PARAGRAPH, "沿箭头前进。", List.of(), com.rulepilot.teaching.domain.IllustratedLesson.FigureFlow.NONE));
        var source = new LessonStep(1, "路线", IllustratedLesson.TeachingMove.DO,
                "看这条路线。\n\n沿箭头前进。", List.of(2), List.of(UUID.randomUUID())).withReadingBlocks(blocks);
        var translation = new StepTranslation(1, "Route", "Look at this route.\n\nFollow the arrow.", "", "", List.of(), List.of(
                new LessonLocalization.BlockTranslation(1, "Look at this route.", List.of()),
                new LessonLocalization.BlockTranslation(2, "", List.of("Blue route")),
                new LessonLocalization.BlockTranslation(3, "Follow the arrow.", List.of())));
        var translated = LessonLocalization.translateReadingBlocks(source, translation);
        assertThat(translated).extracting(IllustratedLesson.ReadingBlock::kind)
                .containsExactly(IllustratedLesson.ReadingKind.PARAGRAPH, IllustratedLesson.ReadingKind.FIGURES, IllustratedLesson.ReadingKind.PARAGRAPH);
        assertThat(translated.get(1).figures().getFirst().focus()).isEqualTo(focus);
        assertThat(translated.get(1).figures().getFirst().caption()).isEqualTo("Blue route");
        assertThat(translated.get(1).flow()).isEqualTo(IllustratedLesson.FigureFlow.ASIDE);
        assertThatThrownBy(() -> LessonLocalization.translateReadingBlocks(source,
                new StepTranslation(1, "Route", "Look", "", "", List.of(), translation.readingBlocks().subList(0, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replacesOnlyPlayerVisibleProseWhileKeepingTheCitedStructureAndCrop(boolean sourceHasDescription) {
        UUID lessonId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        VisualFocus primary = new VisualFocus(
                2, "玩家板", sourceHasDescription ? "蓝色玩家板旁放着三个木制标记" : "", 100, 200, 300, 400);
        VisualFocus secondary = new VisualFocus(
                4, "行动牌", "三张行动牌并排放在玩家板右侧", 250, 150, 450, 250);
        IllustratedLesson source = new IllustratedLesson(
                lessonId,
                planId,
                IllustratedLesson.LessonStatus.COMPLETE,
                List.of(new LessonSection(
                        1,
                        "setup",
                        List.of("setup"),
                        "摆好桌面",
                        true,
                        IllustratedLesson.EvidenceStatus.SUPPORTED,
                        IllustratedLesson.VisualKind.TABLE_LAYOUT,
                        "看玩家区域。",
                        List.of(2),
                        List.of(chunkId),
                        List.of(new LessonStep(
                                1,
                                "放置玩家板",
                                IllustratedLesson.TeachingMove.VISUAL,
                                "把玩家板放在自己面前。",
                                List.of(2),
                                List.of(chunkId),
                                List.of(),
                                primary,
                                List.of(primary, secondary))))),
                "test",
                Instant.parse("2026-07-23T00:00:00Z"));
        LessonLocalization localization = LessonLocalization.pending(lessonId, PlayerLocale.EN, Instant.now())
                .complete(
                        List.of(new SectionTranslation(
                                1,
                                "Set up the table",
                                "Look at your player area.",
                                List.of(new StepTranslation(
                                        1,
                                        "Place your player mat",
                                        "Put your player mat in front of you.",
                                        "Player mat",
                                        sourceHasDescription ? "Three wooden markers sit beside a blue player mat." : "")))),
                        Instant.now());

        IllustratedLesson localized = localization.applyTo(source);
        var step = localized.sections().getFirst().steps().getFirst();

        assertThat(localized.sections().getFirst().title()).isEqualTo("Set up the table");
        assertThat(step.heading()).isEqualTo("Place your player mat");
        assertThat(step.sourceChunkIds()).containsExactly(chunkId);
        assertThat(step.sourcePages()).containsExactly(2);
        assertThat(step.visualFocus()).isEqualTo(new VisualFocus(
                2,
                "Player mat",
                sourceHasDescription ? "Three wooden markers sit beside a blue player mat." : "",
                100,
                200,
                300,
                400));
        assertThat(step.visualFoci())
                .containsExactly(
                        new VisualFocus(
                                2,
                                "Player mat",
                                sourceHasDescription ? "Three wooden markers sit beside a blue player mat." : "",
                                100,
                                200,
                                300,
                                400),
                        secondary);
    }

    @Test
    void rejectsATranslationThatDropsASourceStep() {
        UUID lessonId = UUID.randomUUID();
        LessonLocalization localization = LessonLocalization.pending(lessonId, PlayerLocale.EN, Instant.now())
                .complete(
                        List.of(new SectionTranslation(
                                1, "Setup", "", List.of(new StepTranslation(1, "Place", "Put it down.", "")))),
                        Instant.now());
        IllustratedLesson source = lessonWithTwoSteps(lessonId);

        assertThatThrownBy(() -> localization.applyTo(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("localized steps do not match the source section");
    }

    @Test
    void keepsLateSourceVisualsWhenAReadyTranslationPredatesEnrichment() {
        UUID lessonId = UUID.randomUUID();
        IllustratedLesson source = lessonWithLateVisuals(lessonId);
        LessonLocalization localization = LessonLocalization.pending(lessonId, PlayerLocale.EN, Instant.now())
                .complete(
                        List.of(new SectionTranslation(
                                1, "Set up", "", List.of(new StepTranslation(1, "Check components", "Check the components.", "")))),
                        Instant.now());

        IllustratedLesson localized = localization.applyTo(source);

        assertThat(localized.sections().getFirst().title()).isEqualTo("Set up");
        assertThat(localized.sections().getFirst().steps().getFirst().heading()).isEqualTo("Check components");
        assertThat(localized.sections().getFirst().steps().getFirst().text()).isEqualTo("Check the components.");
        assertThat(localized.sections().getFirst().steps().getFirst().visualFoci())
                .containsExactlyElementsOf(source.sections().getFirst().steps().getFirst().visualFoci());
    }

    @Test
    void rejectsAPartiallyTranslatedVisualOverlay() {
        UUID lessonId = UUID.randomUUID();
        LessonLocalization localization = LessonLocalization.pending(lessonId, PlayerLocale.EN, Instant.now())
                .complete(
                        List.of(new SectionTranslation(
                                1,
                                "Set up",
                                "",
                                List.of(new StepTranslation(
                                        1,
                                        "Check components",
                                        "Check the components.",
                                        "Score marker",
                                        "",
                                        List.of())))),
                        Instant.now());

        assertThatThrownBy(() -> localization.applyTo(lessonWithLateVisuals(lessonId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localized visual focus");
    }

    private IllustratedLesson lessonWithLateVisuals(UUID lessonId) {
        UUID chunkId = UUID.randomUUID();
        VisualFocus primary = new VisualFocus(
                3, "计分标记", "圆形计分标记位于分数轨道旁", 10, 20, 30, 40);
        VisualFocus secondary = new VisualFocus(
                5, "回合标记", "木制回合标记位于回合轨道左侧", 110, 120, 230, 240);
        return new IllustratedLesson(
                lessonId,
                UUID.randomUUID(),
                IllustratedLesson.LessonStatus.COMPLETE,
                List.of(new LessonSection(
                        1,
                        "setup",
                        List.of(),
                        "设置",
                        true,
                        IllustratedLesson.EvidenceStatus.SUPPORTED,
                        IllustratedLesson.VisualKind.TABLE_LAYOUT,
                        "",
                        List.of(3, 5),
                        List.of(chunkId),
                        List.of(new LessonStep(
                                1,
                                "查看组件",
                                IllustratedLesson.TeachingMove.VISUAL,
                                "查看组件。",
                                List.of(3, 5),
                                List.of(chunkId),
                                List.of(),
                                primary,
                                List.of(primary, secondary))))),
                "test",
                Instant.now());
    }

    private IllustratedLesson lessonWithTwoSteps(UUID lessonId) {
        UUID chunkId = UUID.randomUUID();
        return new IllustratedLesson(
                lessonId,
                UUID.randomUUID(),
                IllustratedLesson.LessonStatus.COMPLETE,
                List.of(new LessonSection(
                        1,
                        "setup",
                        List.of(),
                        "设置",
                        true,
                        IllustratedLesson.EvidenceStatus.SUPPORTED,
                        IllustratedLesson.VisualKind.REFERENCE_CARD,
                        "",
                        List.of(),
                        List.of(),
                        List.of(
                                new LessonStep(1, "第一步", IllustratedLesson.TeachingMove.DO, "先做这个。", List.of(1), List.of(chunkId)),
                                new LessonStep(2, "第二步", IllustratedLesson.TeachingMove.DO, "再做那个。", List.of(1), List.of(chunkId))))),
                "test",
                Instant.now());
    }
}
