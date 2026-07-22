package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.ingestion.layout.RulebookUnderstanding;
import com.rulepilot.teaching.VisualRegionLocator;
import com.rulepilot.teaching.domain.IllustratedLesson;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualLessonEnricherTest {

    @Test
    void attaches_a_cited_crop_to_the_supported_rule_without_adding_a_seventh_step() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        RulebookUnderstandingCatalog catalog = ignored -> understanding();
        DocumentPageImages images = (ignored, pages) -> pages.contains(2)
                ? List.of(new DocumentPageImages.PageImage(2, "image/png", new byte[] {1}, 1_000, 1_000))
                : List.of();
        VisualRegionLocator locator = request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                2, "轨道", "一个探测器标记位于弧形刻度轨道上", 120, 220, 180, 120, List.of(chunk)));
        IllustratedLesson source = lesson(chunk);

        IllustratedLesson enriched = new VisualLessonEnricher(
                catalog, images, new VisualRegionCandidateSelector(), locator).enrich(version, source);

        var section = enriched.sections().getFirst();
        assertThat(section.steps()).hasSize(1);
        assertThat(section.steps().getFirst().text()).contains("把探测器放到轨道上。");
        assertThat(section.steps().getFirst().kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(section.steps().getFirst().visualFocus().pageNumber()).isEqualTo(2);
        assertThat(section.visualSourcePages()).containsExactly(2);
    }

    @Test
    void turns_a_vision_observation_into_a_player_facing_crop_explanation() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "轨道",
                                "圆形标记位于一条弧形刻度旁，箭头指向前进方向",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(version, lesson(chunk));

        assertThat(enriched.sections().getFirst().steps().getFirst().text())
                .isEqualTo("图中可见圆形标记位于一条弧形刻度旁，箭头指向前进方向。结合图片完成这一步：把探测器放到轨道上。");
    }

    @Test
    void rejects_a_visual_response_that_only_crops_a_section_heading() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "Setup section header",
                                "The word 'Setup' printed in a large, bold serif font at the top left of the page.",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void rejects_a_crop_without_a_literal_visual_observation() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "轨道", 120, 220, 180, 120, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(result.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.REJECTED_MISSING_OBSERVATION);
    }

    @Test
    void rejects_a_visual_response_that_only_repeats_a_numbered_text_step() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "Setup step 7 text",
                                "Text for the 7th setup step listing the faction order.",
                                120,
                                220,
                                180,
                                120,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void rejects_a_tiny_label_and_a_prose_paragraph_even_when_the_model_calls_them_visuals() {
        UUID chunk = UUID.randomUUID();
        var tiny = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "首任大师标记", "一个印有文字的小标记", 38, 259, 76, 20, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");
        var paragraph = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "示例段落", "一段文字描述三名玩家的行动顺序", 120, 220, 180, 120, List.of(chunk))))
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(tiny.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.REJECTED_TOO_SMALL);
        assertThat(paragraph.outcomes()).singleElement().extracting(VisualLessonEnricher.SectionOutcome::outcome)
                .isEqualTo(VisualLessonEnricher.Outcome.REJECTED_NON_VISUAL);
    }

    @Test
    void accepts_a_compact_icon_group_and_explains_how_to_read_it_with_the_rule() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "行动图标组",
                                "一个六点骰子图标紧挨着颜料筒图标，旁边有向右箭头",
                                120,
                                220,
                                34,
                                34,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        var step = enriched.sections().getFirst().steps().getFirst();
        assertThat(step.kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(step.text()).isEqualTo(
                "图中图标提示：一个六点骰子图标紧挨着颜料筒图标，旁边有向右箭头。先认出这组图标，再按规则处理：把探测器放到轨道上。");
        assertThat(step.visualFocus()).isNotNull();
    }

    @Test
    void expands_a_tight_visual_focus_into_a_player_readable_contextual_viewport() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2,
                                "卡牌行动图标",
                                "卡牌中央的金色圆形图标紧挨着向右箭头",
                                300,
                                400,
                                34,
                                34,
                                List.of(chunk))))
                .enrich(UUID.randomUUID(), lesson(chunk));

        assertThat(enriched.sections().getFirst().steps().getFirst().visualFocus())
                .isEqualTo(new IllustratedLesson.VisualFocus(2, "卡牌行动图标", 227, 357, 180, 120));
    }

    @Test
    void turns_two_grounded_visual_anchors_into_two_different_rule_steps() {
        UUID iconEvidence = UUID.randomUUID();
        UUID stateEvidence = UUID.randomUUID();
        VisualRegionLocator locator = new VisualRegionLocator() {
            @Override
            public java.util.Optional<VisualRegionLocator.LocatedRegion> locate(
                    VisualRegionLocator.VisualLocationRequest ignored) {
                return java.util.Optional.empty();
            }

            @Override
            public VisualRegionLocator.LocateGuideResult locateGuideWithResult(
                    VisualRegionLocator.VisualLocationRequest ignored) {
                return VisualRegionLocator.LocateGuideResult.found(List.of(
                        new VisualRegionLocator.LocatedRegion(
                                2, "行动图标", "一个六点骰子图标紧挨着向右箭头", 120, 220, 34, 34, List.of(iconEvidence)),
                        new VisualRegionLocator.LocatedRegion(
                                2, "棋子状态", "一枚探测器标记位于轨道的第三格", 440, 420, 180, 120, List.of(stateEvidence))));
            }
        };
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        locator)
                .enrichWithReport(UUID.randomUUID(), twoRuleLesson(iconEvidence, stateEvidence), "owner");

        var steps = result.lesson().sections().getFirst().steps();
        assertThat(steps).extracting(IllustratedLesson.LessonStep::kind)
                .containsExactly(IllustratedLesson.TeachingMove.VISUAL, IllustratedLesson.TeachingMove.VISUAL);
        assertThat(steps.get(0).text()).contains("图中图标提示", "掷骰后执行行动。");
        assertThat(steps.get(1).text()).contains("图中可见", "把探测器移到轨道上。");
        assertThat(steps).extracting(step -> step.visualFocus().label()).containsExactly("行动图标", "棋子状态");
        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.outcome()).isEqualTo(VisualLessonEnricher.Outcome.ADDED);
            assertThat(outcome.summary()).contains("2 处");
        });
    }

    @Test
    void binds_a_visual_crop_to_its_source_page_even_when_one_evidence_chunk_spans_two_steps() {
        UUID sharedEvidence = UUID.randomUUID();
        RulebookUnderstanding crossPageUnderstanding = new RulebookUnderstanding(
                List.of(block(2, "Game goal", 100, 200), block(3, "Artifact cards", 100, 200)),
                List.of(), List.of(), List.of());
        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> crossPageUnderstanding,
                        (ignored, pages) -> List.of(
                                new DocumentPageImages.PageImage(2, "image/png", new byte[] {2}, 1_000, 1_000),
                                new DocumentPageImages.PageImage(3, "image/png", new byte[] {3}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                3,
                                "文物卡字段",
                                "两张卡牌上有编号圆圈，指向不同字段",
                                60,
                                40,
                                860,
                                520,
                                List.of(sharedEvidence))))
                .enrich(UUID.randomUUID(), sameEvidenceAcrossPagesLesson(sharedEvidence));

        var steps = enriched.sections().getFirst().steps();
        assertThat(steps.get(0).kind()).isEqualTo(IllustratedLesson.TeachingMove.DO);
        assertThat(steps.get(1).kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(steps.get(1).visualFocus().pageNumber()).isEqualTo(3);
    }

    @Test
    void leaves_the_section_unchanged_when_the_locator_misses_every_candidate() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson source = lesson(chunk);
        var enriched = new VisualLessonEnricher(
                ignored -> understanding(),
                (ignored, pages) -> List.of(),
                new VisualRegionCandidateSelector(),
                request -> java.util.Optional.empty()).enrich(UUID.randomUUID(), source);

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void reports_when_a_visual_model_cannot_find_a_reliable_region() {
        UUID chunk = UUID.randomUUID();
        var result = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.empty())
                .enrichWithReport(UUID.randomUUID(), lesson(chunk), "owner");

        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.sectionPosition()).isEqualTo(1);
            assertThat(outcome.outcome()).isEqualTo(VisualLessonEnricher.Outcome.LOCATOR_RETURNED_NONE);
            assertThat(outcome.summary()).contains("视觉模型未找到可靠局部图示");
        });
    }

    @Test
    void accepts_a_vision_crop_from_a_cited_page_when_translation_has_no_text_anchor() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        RulebookUnderstanding EnglishSource = new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        2, 0, 0, RulebookUnderstanding.BlockRole.BODY, "Place the probe on its orbit track",
                        new RulebookUnderstanding.Rectangle(100, 200, 300, 30), null)),
                List.of(), List.of(), List.of());
        DocumentPageImages images = (ignored, pages) -> List.of(
                new DocumentPageImages.PageImage(2, "image/png", new byte[] {1}, 1_000, 1_000));
        VisualRegionLocator locator = request -> {
            assertThat(request.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.rectangle()).isEqualTo(new RulebookUnderstanding.Rectangle(0, 0, 1_000, 1_000));
                assertThat(candidate.sourceText()).isEqualTo("Cited page 2 visual context");
            });
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    2, "Orbit track", "一枚探测器标记位于弧形轨道上", 640, 700, 180, 120, List.of(chunk)));
        };

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> EnglishSource, images, new VisualRegionCandidateSelector(), locator)
                .enrich(version, lesson(chunk));

        assertThat(enriched.sections().getFirst().steps())
                .anySatisfy(step -> assertThat(step.visualFocus())
                        .isEqualTo(new IllustratedLesson.VisualFocus(2, "放置探测器", 640, 700, 180, 120)));
    }

    @Test
    void rejects_a_whole_page_response_even_when_the_page_is_the_search_boundary() {
        UUID chunk = UUID.randomUUID();
        IllustratedLesson source = lesson(chunk);

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> understanding(),
                        (ignored, pages) -> List.of(new DocumentPageImages.PageImage(
                                2, "image/png", new byte[] {1}, 1_000, 1_000)),
                        new VisualRegionCandidateSelector(),
                        request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                                2, "Entire page", 0, 0, 1_000, 1_000, List.of(chunk))))
                .enrich(UUID.randomUUID(), source);

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
    }

    @Test
    void sends_the_most_relevant_candidate_pages_before_lower_numbered_pages() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        RulebookUnderstanding understanding = new RulebookUnderstanding(
                List.of(
                        block(2, "Probe", 100, 200),
                        block(3, "Launch a probe", 100, 200)),
                List.of(), List.of(), List.of());
        DocumentPageImages images = (ignored, pages) -> List.of(
                new DocumentPageImages.PageImage(2, "image/png", new byte[] {2}, 1_000, 1_000),
                new DocumentPageImages.PageImage(3, "image/png", new byte[] {3}, 1_000, 1_000));
        VisualRegionLocator locator = request -> {
            assertThat(request.pages()).extracting(VisualRegionLocator.PageImage::pageNumber).containsExactly(3, 2);
            assertThat(request.candidates()).extracting(VisualRegionCandidateSelector.Candidate::pageNumber)
                    .containsExactly(3, 2, 3, 2);
            assertThat(request.candidates().getFirst().sourceText()).isEqualTo("Cited page 3 visual context");
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    3, "Launch", "发射台旁有一枚探测器和指向轨道的箭头", 100, 200, 300, 160, List.of(chunk)));
        };

        var enriched = new VisualLessonEnricher(
                        ignored -> understanding, images, new VisualRegionCandidateSelector(), locator)
                .enrich(version, twoPageLesson(chunk));

        assertThat(enriched.sections().getFirst().steps()).hasSize(1);
        assertThat(enriched.sections().getFirst().steps().getFirst().visualFocus()).isNotNull();
    }

    private RulebookUnderstanding understanding() {
        return new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        2, 0, 0, RulebookUnderstanding.BlockRole.BODY, "把探测器放到轨道上",
                        new RulebookUnderstanding.Rectangle(100, 200, 300, 180), null)),
                List.of(), List.of(), List.of());
    }

    private RulebookUnderstanding.PageBlock block(int page, String text, int x, int y) {
        return new RulebookUnderstanding.PageBlock(
                page, 0, 0, RulebookUnderstanding.BlockRole.BODY, text,
                new RulebookUnderstanding.Rectangle(x, y, 300, 160), null);
    }

    private IllustratedLesson lesson(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1, "放置探测器", IllustratedLesson.TeachingMove.DO, "把探测器放到轨道上。", List.of(2), List.of(chunk));
        var section = new IllustratedLesson.LessonSection(
                1, "setup", List.of("setup"), "开局设置", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "把探测器放到轨道上。", List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson twoPageLesson(UUID chunk) {
        var step = new IllustratedLesson.LessonStep(
                1, "Launch a probe", IllustratedLesson.TeachingMove.DO, "Launch a probe.", List.of(2, 3), List.of(chunk));
        var section = new IllustratedLesson.LessonSection(
                1, "launch", List.of("launch"), "Launch a probe", true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT, IllustratedLesson.VisualKind.TABLE_LAYOUT,
                "Launch a probe.", List.of(), List.of(), List.of(step));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson sameEvidenceAcrossPagesLesson(UUID sharedEvidence) {
        var overview = new IllustratedLesson.LessonStep(
                1, "游戏目标", IllustratedLesson.TeachingMove.DO, "收集最有价值的文物。", List.of(2), List.of(sharedEvidence));
        var cardAnatomy = new IllustratedLesson.LessonStep(
                2, "文物卡字段", IllustratedLesson.TeachingMove.DO, "对照卡牌上的字段。", List.of(3), List.of(sharedEvidence));
        var section = new IllustratedLesson.LessonSection(
                1, "overview", List.of("overview"), "先认识游戏", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.REFERENCE_CARD,
                "知道目标和卡牌字段", List.of(), List.of(), List.of(overview, cardAnatomy));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }

    private IllustratedLesson twoRuleLesson(UUID iconEvidence, UUID stateEvidence) {
        var first = new IllustratedLesson.LessonStep(
                1, "掷骰并行动", IllustratedLesson.TeachingMove.DO, "掷骰后执行行动。", List.of(2), List.of(iconEvidence));
        var second = new IllustratedLesson.LessonStep(
                2, "移动探测器", IllustratedLesson.TeachingMove.DO, "把探测器移到轨道上。", List.of(2), List.of(stateEvidence));
        var section = new IllustratedLesson.LessonSection(
                1, "turn", List.of("turn"), "轮到你时", true,
                IllustratedLesson.EvidenceStatus.SUPPORTED, IllustratedLesson.VisualKind.FLOW_DIAGRAM,
                "完成一次行动", List.of(), List.of(), List.of(first, second));
        return new IllustratedLesson(
                UUID.randomUUID(), UUID.randomUUID(), IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(section), "test", Instant.now());
    }
}
