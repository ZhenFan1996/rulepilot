package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.RulebookUnderstandingCatalog;
import com.rulepilot.ingestion.domain.RulebookUnderstanding;
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

    private RulebookUnderstanding understanding() {
        return new RulebookUnderstanding(
                List.of(new RulebookUnderstanding.PageBlock(
                        2, 0, 0, RulebookUnderstanding.BlockRole.BODY, "把探测器放到轨道上",
                        new RulebookUnderstanding.Rectangle(100, 200, 300, 180), null)),
                List.of(), List.of(), List.of());
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
}
