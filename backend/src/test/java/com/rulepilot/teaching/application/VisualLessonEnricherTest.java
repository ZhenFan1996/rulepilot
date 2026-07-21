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
    void appends_a_cited_visual_step_without_rewriting_existing_text() {
        UUID version = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        RulebookUnderstandingCatalog catalog = ignored -> understanding();
        DocumentPageImages images = (ignored, pages) -> pages.contains(2)
                ? List.of(new DocumentPageImages.PageImage(2, "image/png", new byte[] {1}, 1_000, 1_000))
                : List.of();
        VisualRegionLocator locator = request -> java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                2, "轨道", 120, 220, 180, 120, List.of(chunk)));
        IllustratedLesson source = lesson(chunk);

        IllustratedLesson enriched = new VisualLessonEnricher(
                catalog, images, new VisualRegionCandidateSelector(), locator).enrich(version, source);

        var section = enriched.sections().getFirst();
        assertThat(section.steps()).hasSize(2);
        assertThat(section.steps().getFirst().text()).isEqualTo("把探测器放到轨道上。");
        assertThat(section.steps().get(1).kind()).isEqualTo(IllustratedLesson.TeachingMove.VISUAL);
        assertThat(section.steps().get(1).visualFocus().pageNumber()).isEqualTo(2);
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

        assertThat(enriched.sections().getFirst().steps().get(1).text())
                .isEqualTo("看图：圆形标记位于一条弧形刻度旁，箭头指向前进方向。这就是本节要定位的“轨道”。");
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
                    2, "Orbit track", 640, 700, 180, 120, List.of(chunk)));
        };

        IllustratedLesson enriched = new VisualLessonEnricher(
                        ignored -> EnglishSource, images, new VisualRegionCandidateSelector(), locator)
                .enrich(version, lesson(chunk));

        assertThat(enriched.sections().getFirst().steps())
                .anySatisfy(step -> assertThat(step.visualFocus())
                        .isEqualTo(new IllustratedLesson.VisualFocus(2, "Orbit track", 640, 700, 180, 120)));
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
                    .containsExactly(3, 2, 3);
            assertThat(request.candidates().getLast().sourceText()).isEqualTo("Cited page 3 visual context");
            return java.util.Optional.of(new VisualRegionLocator.LocatedRegion(
                    3, "Launch", 100, 200, 300, 160, List.of(chunk)));
        };

        var enriched = new VisualLessonEnricher(
                        ignored -> understanding, images, new VisualRegionCandidateSelector(), locator)
                .enrich(version, twoPageLesson(chunk));

        assertThat(enriched.sections().getFirst().steps()).hasSize(2);
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
}
