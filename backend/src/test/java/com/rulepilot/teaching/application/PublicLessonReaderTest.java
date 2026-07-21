package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicLessonReaderTest {

    private final TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
    private final IllustratedLessonRepository lessons = mock(IllustratedLessonRepository.class);
    private final PublicRulebookReferenceLookup rulebooks = mock(PublicRulebookReferenceLookup.class);
    private final PublicLessonReader reader = new PublicLessonReader(plans, lessons, rulebooks);

    @Test
    void exposes_a_read_only_lesson_projection_without_an_owner() {
        Fixture fixture = fixture();
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(fixture.lesson));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(fixture.reference));

        var publicLesson = reader.find(fixture.plan.id());

        assertThat(publicLesson).hasValueSatisfying(value -> {
            assertThat(value.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(value.officialSourceUrl()).isEqualTo("https://publisher.example/rules.pdf");
            assertThat(value.citedPages()).containsExactlyInAnyOrder(2, 5);
        });
    }

    @Test
    void allows_only_pages_that_the_public_lesson_cites() {
        Fixture fixture = fixture();
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(fixture.lesson));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(fixture.reference));

        assertThat(reader.requireCitedPage(fixture.plan.id(), 5).documentVersionId())
                .isEqualTo(fixture.plan.documentVersionId());
        assertThatThrownBy(() -> reader.requireCitedPage(fixture.plan.id(), 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rulebook page is not cited by this lesson");
    }

    private Fixture fixture() {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        TeachingPlan plan = new TeachingPlan(
                planId,
                versionId,
                3,
                2,
                30,
                "Orbit",
                "Learn the orbit game.",
                List.of(new TeachingPlan.PlannedSection(1, "setup", "Setup", "Set up the board.", true, true,
                        List.of("setup"), List.of("setup"))),
                "owner-is-not-public",
                Instant.parse("2026-07-21T00:00:00Z"));
        IllustratedLesson.LessonStep step = new IllustratedLesson.LessonStep(
                1, "Place board", IllustratedLesson.TeachingMove.DO, "Place the board in the center.",
                List.of(2), List.of(chunkId));
        IllustratedLesson.LessonSection section = new IllustratedLesson.LessonSection(
                1, "setup", List.of("setup"), "Setup", true, IllustratedLesson.EvidenceStatus.CITED_DRAFT,
                IllustratedLesson.VisualKind.TABLE_LAYOUT, "Board setup", List.of(5), List.of(chunkId), List.of(step));
        IllustratedLesson lesson = new IllustratedLesson(
                UUID.randomUUID(), planId, IllustratedLesson.LessonStatus.DRAFT_READY, List.of(section), "test", Instant.now());
        return new Fixture(
                plan,
                lesson,
                new PublicRulebookReferenceLookup.Reference(versionId, "Orbit Rules", "https://publisher.example/rules.pdf"));
    }

    private record Fixture(
            TeachingPlan plan, IllustratedLesson lesson, PublicRulebookReferenceLookup.Reference reference) {}
}
