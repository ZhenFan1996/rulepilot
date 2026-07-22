package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicLessonCatalogTest {

    private final TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
    private final PublicLessonReader lessons = mock(PublicLessonReader.class);
    private final PublicLessonCatalog catalog = new PublicLessonCatalog(plans, lessons);

    @Test
    void lists_only_distinct_usable_lessons_with_an_official_rulebook_source() {
        TeachingPlan first = plan(UUID.randomUUID());
        TeachingPlan duplicate = plan(first.documentVersionId());
        TeachingPlan missingSource = plan(UUID.randomUUID());
        when(plans.findRecent(200)).thenReturn(List.of(first, duplicate, missingSource));
        when(lessons.find(first.id())).thenReturn(Optional.of(publicLesson(first, "https://publisher.example/first.pdf")));
        when(lessons.find(duplicate.id())).thenReturn(Optional.of(publicLesson(duplicate, "https://publisher.example/first.pdf")));
        when(lessons.find(missingSource.id())).thenReturn(Optional.of(publicLesson(missingSource, null)));

        assertThat(catalog.latest(24)).singleElement().satisfies(entry -> {
            assertThat(entry.teachingPlanId()).isEqualTo(first.id());
            assertThat(entry.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(entry.sectionCount()).isEqualTo(1);
            assertThat(entry.stepCount()).isEqualTo(1);
        });
    }

    private TeachingPlan plan(UUID documentVersionId) {
        return new TeachingPlan(
                UUID.randomUUID(), documentVersionId, 3, 2, 30, "Orbit", "Learn Orbit.",
                List.of(new TeachingPlan.PlannedSection(1, "setup", "Setup", "Set up.", true, false, List.of("setup"), List.of())),
                "private-owner", Instant.now());
    }

    private PublicLessonReader.PublicLesson publicLesson(TeachingPlan plan, String officialSourceUrl) {
        var step = new IllustratedLesson.LessonStep(
                1, "Place board", IllustratedLesson.TeachingMove.DO, "Place board.", List.of(1), List.of(UUID.randomUUID()));
        var section = new IllustratedLesson.LessonSection(
                1, "setup", List.of(), "Setup", true, IllustratedLesson.EvidenceStatus.CITED_DRAFT,
                IllustratedLesson.VisualKind.REFERENCE_CARD, "", List.of(), List.of(), List.of(step));
        var lesson = new IllustratedLesson(
                UUID.randomUUID(), plan.id(), IllustratedLesson.LessonStatus.COMPLETE, List.of(section), "test", Instant.now());
        return new PublicLessonReader.PublicLesson(
                plan.id(), plan.documentVersionId(), "Orbit Rules", officialSourceUrl, null, lesson);
    }
}
