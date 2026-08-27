package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import com.rulepilot.document.PublicRulebookReferenceLookup;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicLessonCatalogTest {

    private final TeachingPlanRepository plans = mock(TeachingPlanRepository.class);
    private final IllustratedLessonRepository lessons = mock(IllustratedLessonRepository.class);
    private final PublicRulebookReferenceLookup rulebooks = mock(PublicRulebookReferenceLookup.class);
    private final PublicGameCoverLookup covers = mock(PublicGameCoverLookup.class);
    private final PublicGameIdentityLookup identities = mock(PublicGameIdentityLookup.class);
    private final PublicLessonCatalog catalog =
            new PublicLessonCatalog(plans, lessons, rulebooks, covers, identities);

    @Test
    void lists_only_distinct_usable_lessons_with_an_official_rulebook_source() {
        UUID documentVersionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference first = plan(documentVersionId);
        TeachingPlanRepository.PlanReference duplicate = plan(documentVersionId);
        TeachingPlanRepository.PlanReference missingSource = plan(UUID.randomUUID());
        when(plans.findRecentReferences(200)).thenReturn(List.of(first, duplicate, missingSource));
        when(lessons.findLatestSummariesByPlans(List.of(first.teachingPlanId(), duplicate.teachingPlanId(), missingSource.teachingPlanId())))
                .thenReturn(List.of(
                        summary(first.teachingPlanId()), summary(duplicate.teachingPlanId()), summary(missingSource.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId, documentVersionId, missingSource.documentVersionId())))
                .thenReturn(Map.of(
                        documentVersionId,
                        reference(documentVersionId, "https://publisher.example/first.pdf"),
                        missingSource.documentVersionId(),
                        reference(missingSource.documentVersionId(), null)));
        when(covers.findByEditions(List.of())).thenReturn(Map.of());
        when(identities.findByTitles(List.of("Orbit", "Orbit", "Orbit"))).thenReturn(Map.of(
                "Orbit", new PublicGameIdentityLookup.Identity(
                        123, "Orbit", "https://boardgamegeek.com/boardgame/123")));

        assertThat(catalog.latest(24)).singleElement().satisfies(entry -> {
            assertThat(entry.teachingPlanId()).isEqualTo(first.teachingPlanId());
            assertThat(entry.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(entry.publicGame().bggId()).isEqualTo(123);
            assertThat(entry.sectionCount()).isEqualTo(1);
            assertThat(entry.stepCount()).isEqualTo(1);
        });
        assertThat(catalog.latest(24)).hasSize(1);
        verify(plans, times(1)).findRecentReferences(200);
    }

    @Test
    void keepsPublicLessonsReadableWhenOptionalBggIdentityLookupIsUnavailable() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId);
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(summary(plan.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId, reference(documentVersionId, editionId, "https://publisher.example/first.pdf")));
        when(covers.findByEditions(List.of(editionId))).thenReturn(Map.of());
        when(identities.findByTitles(List.of("Orbit"))).thenThrow(new IllegalStateException("snapshot unavailable"));
        assertThat(catalog.latest(12)).singleElement().satisfies(entry -> {
            assertThat(entry.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(entry.publicGame()).isNull();
        });
    }

    private TeachingPlanRepository.PlanReference plan(UUID documentVersionId) {
        return plan(documentVersionId, "Orbit");
    }

    private TeachingPlanRepository.PlanReference plan(UUID documentVersionId, String gameTitle) {
        return new TeachingPlanRepository.PlanReference(UUID.randomUUID(), documentVersionId, gameTitle);
    }

    private IllustratedLessonRepository.LessonSummary summary(UUID planId) {
        return new IllustratedLessonRepository.LessonSummary(planId, LessonStatus.COMPLETE, true, 1, 1);
    }

    private PublicRulebookReferenceLookup.Reference reference(UUID documentVersionId, String officialSourceUrl) {
        return reference(documentVersionId, null, officialSourceUrl);
    }

    private PublicRulebookReferenceLookup.Reference reference(
            UUID documentVersionId,
            UUID editionId,
            String officialSourceUrl) {
        return new PublicRulebookReferenceLookup.Reference(
                documentVersionId, editionId, "Orbit Rules", officialSourceUrl, null);
    }
}
