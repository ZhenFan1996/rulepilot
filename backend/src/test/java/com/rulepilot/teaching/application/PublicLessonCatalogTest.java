package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.catalog.PublicGameEditionIdentityLookup;
import com.rulepilot.catalog.PublicGameIdentityLookup;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.AvailabilityStatus;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Candidate;
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
    private final PublicGameEditionIdentityLookup editionIdentities = mock(PublicGameEditionIdentityLookup.class);
    private final PublicGameIdentityLookup identities = mock(PublicGameIdentityLookup.class);
    private final PublicLessonCatalog catalog =
            new PublicLessonCatalog(plans, lessons, rulebooks, covers, editionIdentities, identities);

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
        when(editionIdentities.findBggIds(List.of(editionId)))
                .thenThrow(new IllegalStateException("edition identity unavailable"));

        assertThat(catalog.latest(12)).singleElement().satisfies(entry -> {
            assertThat(entry.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(entry.publicGame()).isNull();
        });
        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .as("an unavailable BGG identity lookup must not become a no-continuation claim")
                .isEqualTo(AvailabilityStatus.UNAVAILABLE);
    }

    @Test
    void exposesAReadyGuideAndQuestionContinuationByExactPublicBggIdentity() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId);
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(new IllustratedLessonRepository.LessonSummary(
                        plan.teachingPlanId(), LessonStatus.COMPLETE, true, 4, 11)));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId, reference(documentVersionId, editionId, "https://publisher.example/orbit.pdf")));
        when(editionIdentities.findBggIds(List.of(editionId))).thenReturn(Map.of(editionId, 123));

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))))
                .satisfies(availability -> {
                    assertThat(availability.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
                    assertThat(availability.continuations()).containsOnlyKeys(123);
                    assertThat(availability.continuations().get(123)).satisfies(continuation -> {
                        assertThat(continuation.teachingPlanId()).isEqualTo(plan.teachingPlanId());
                        assertThat(continuation.sectionCount()).isEqualTo(4);
                        assertThat(continuation.stepCount()).isEqualTo(11);
                    });
                });
        verifyNoInteractions(covers);
    }

    @Test
    void preservesAnExactReadyMatchWhenAnotherPublishedLessonHasUnknownEditionIdentity() {
        UUID readyDocumentVersionId = UUID.randomUUID();
        UUID unknownDocumentVersionId = UUID.randomUUID();
        UUID readyEditionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference ready = plan(readyDocumentVersionId, "Ready Game");
        TeachingPlanRepository.PlanReference unknown = plan(unknownDocumentVersionId, "Legacy Game");
        when(plans.findRecentReferences(200)).thenReturn(List.of(ready, unknown));
        when(lessons.findLatestSummariesByPlans(List.of(ready.teachingPlanId(), unknown.teachingPlanId())))
                .thenReturn(List.of(summary(ready.teachingPlanId()), summary(unknown.teachingPlanId())));
        when(rulebooks.findReferences(List.of(readyDocumentVersionId, unknownDocumentVersionId)))
                .thenReturn(Map.of(
                        readyDocumentVersionId,
                        reference(
                                readyDocumentVersionId,
                                readyEditionId,
                                "https://publisher.example/ready.pdf"),
                        unknownDocumentVersionId,
                        reference(
                                unknownDocumentVersionId,
                                null,
                                "https://publisher.example/legacy.pdf")));
        when(editionIdentities.findBggIds(List.of(readyEditionId)))
                .thenReturn(Map.of(readyEditionId, 123));

        assertThat(catalog.continuationsFor(List.of(
                        new Candidate(123, "Ready Game"),
                        new Candidate(999, "Another Game"))))
                .satisfies(availability -> {
                    assertThat(availability.status()).isEqualTo(AvailabilityStatus.PARTIAL);
                    assertThat(availability.continuations()).containsOnlyKeys(123);
                    assertThat(availability.continuations().get(123).teachingPlanId())
                            .isEqualTo(ready.teachingPlanId());
                });
    }

    @Test
    void preservesExactReadyMetadataWhenAnotherRequestedLessonHasZeroCounts() {
        UUID readyDocumentVersionId = UUID.randomUUID();
        UUID unresolvedDocumentVersionId = UUID.randomUUID();
        UUID readyEditionId = UUID.randomUUID();
        UUID unresolvedEditionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference ready = plan(readyDocumentVersionId, "Ready Game");
        TeachingPlanRepository.PlanReference unresolved = plan(unresolvedDocumentVersionId, "Unresolved Game");
        when(plans.findRecentReferences(200)).thenReturn(List.of(ready, unresolved));
        when(lessons.findLatestSummariesByPlans(List.of(ready.teachingPlanId(), unresolved.teachingPlanId())))
                .thenReturn(List.of(
                        new IllustratedLessonRepository.LessonSummary(
                                ready.teachingPlanId(), LessonStatus.COMPLETE, true, 4, 11),
                        new IllustratedLessonRepository.LessonSummary(
                                unresolved.teachingPlanId(), LessonStatus.COMPLETE, true, 0, 7)));
        when(rulebooks.findReferences(List.of(readyDocumentVersionId, unresolvedDocumentVersionId)))
                .thenReturn(Map.of(
                        readyDocumentVersionId,
                        reference(
                                readyDocumentVersionId,
                                readyEditionId,
                                "https://publisher.example/ready.pdf"),
                        unresolvedDocumentVersionId,
                        reference(
                                unresolvedDocumentVersionId,
                                unresolvedEditionId,
                                "https://publisher.example/unresolved.pdf")));
        when(editionIdentities.findBggIds(List.of(readyEditionId, unresolvedEditionId)))
                .thenReturn(Map.of(readyEditionId, 123, unresolvedEditionId, 999));

        assertThat(catalog.continuationsFor(List.of(
                        new Candidate(123, "Ready Game"),
                        new Candidate(999, "Unresolved Game"))))
                .satisfies(availability -> {
                    assertThat(availability.status()).isEqualTo(AvailabilityStatus.PARTIAL);
                    assertThat(availability.continuations()).containsOnlyKeys(123);
                    assertThat(availability.continuations().get(123)).satisfies(continuation -> {
                        assertThat(continuation.teachingPlanId()).isEqualTo(ready.teachingPlanId());
                        assertThat(continuation.sectionCount()).isEqualTo(4);
                        assertThat(continuation.stepCount()).isEqualTo(11);
                    });
                });
    }

    @Test
    void joinsLocalizedPlanAndCatalogTitlesOnlyThroughTheirVerifiedBggIdentity() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId, "沙丘：帝国");
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(new IllustratedLessonRepository.LessonSummary(
                        plan.teachingPlanId(), LessonStatus.COMPLETE, true, 4, 11)));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId, reference(documentVersionId, editionId, "https://publisher.example/dune.pdf")));
        when(editionIdentities.findBggIds(List.of(editionId))).thenReturn(Map.of(editionId, 316554));

        assertThat(catalog.continuationsFor(List.of(new Candidate(316554, "Dune: Imperium"))))
                .satisfies(availability -> {
                    assertThat(availability.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
                    assertThat(availability.continuations()).containsOnlyKeys(316554);
                    assertThat(availability.continuations().get(316554).teachingPlanId())
                            .isEqualTo(plan.teachingPlanId());
                });
    }

    @Test
    void distinguishesSameNamedPlansByTheirExactImportedEditions() {
        UUID firstDocumentVersionId = UUID.randomUUID();
        UUID secondDocumentVersionId = UUID.randomUUID();
        UUID firstEditionId = UUID.randomUUID();
        UUID secondEditionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference first = plan(firstDocumentVersionId, "Shared Name");
        TeachingPlanRepository.PlanReference second = plan(secondDocumentVersionId, "Shared Name");
        when(plans.findRecentReferences(200)).thenReturn(List.of(first, second));
        when(lessons.findLatestSummariesByPlans(List.of(first.teachingPlanId(), second.teachingPlanId())))
                .thenReturn(List.of(
                        new IllustratedLessonRepository.LessonSummary(
                                first.teachingPlanId(), LessonStatus.COMPLETE, true, 2, 6),
                        new IllustratedLessonRepository.LessonSummary(
                                second.teachingPlanId(), LessonStatus.COMPLETE, true, 5, 17)));
        when(rulebooks.findReferences(List.of(firstDocumentVersionId, secondDocumentVersionId)))
                .thenReturn(Map.of(
                        firstDocumentVersionId,
                        reference(
                                firstDocumentVersionId,
                                firstEditionId,
                                "https://publisher.example/first.pdf"),
                        secondDocumentVersionId,
                        reference(
                                secondDocumentVersionId,
                                secondEditionId,
                                "https://publisher.example/second.pdf")));
        when(editionIdentities.findBggIds(List.of(firstEditionId, secondEditionId)))
                .thenReturn(Map.of(firstEditionId, 123, secondEditionId, 999));

        assertThat(catalog.continuationsFor(List.of(new Candidate(999, "Shared Name"))))
                .satisfies(availability -> {
                    assertThat(availability.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
                    assertThat(availability.continuations()).containsOnlyKeys(999);
                    assertThat(availability.continuations().get(999)).satisfies(continuation -> {
                        assertThat(continuation.teachingPlanId()).isEqualTo(second.teachingPlanId());
                        assertThat(continuation.sectionCount()).isEqualTo(5);
                        assertThat(continuation.stepCount()).isEqualTo(17);
                    });
                });
        verifyNoInteractions(identities);
    }

    @Test
    void reportsUnavailableWhenAUsableLessonHasNoImportedEditionIdentity() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId);
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(summary(plan.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId,
                reference(documentVersionId, editionId, "https://publisher.example/orbit.pdf")));
        when(editionIdentities.findBggIds(List.of(editionId))).thenReturn(Map.of());

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .isEqualTo(AvailabilityStatus.UNAVAILABLE);
    }

    @Test
    void reportsUnavailableWhenAUsableLessonHasNoEditionReference() {
        UUID documentVersionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId);
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(summary(plan.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId, reference(documentVersionId, "https://publisher.example/orbit.pdf")));

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .isEqualTo(AvailabilityStatus.UNAVAILABLE);
        verifyNoInteractions(editionIdentities);
    }

    @Test
    void reportsNoReadyGuideWhenTheExactEditionBelongsToAnotherBggGame() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId);
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(summary(plan.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId, reference(documentVersionId, editionId, "https://publisher.example/other.pdf")));
        when(editionIdentities.findBggIds(List.of(editionId))).thenReturn(Map.of(editionId, 999));

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .isEqualTo(AvailabilityStatus.NONE);
        verifyNoInteractions(covers, identities);
    }

    @Test
    void doesNotOfferAContinuationWithoutANonBlankOfficialRulebookSource() {
        UUID documentVersionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference plan = plan(documentVersionId);
        when(plans.findRecentReferences(200)).thenReturn(List.of(plan));
        when(lessons.findLatestSummariesByPlans(List.of(plan.teachingPlanId())))
                .thenReturn(List.of(summary(plan.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId)))
                .thenReturn(Map.of(documentVersionId, reference(documentVersionId, "   ")));

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .isEqualTo(AvailabilityStatus.NONE);
    }

    @Test
    void reportsNoneOnlyWhenTheBoundedPlanReadIsExhaustedBeforeItsLimit() {
        UUID documentVersionId = UUID.randomUUID();
        UUID editionId = UUID.randomUUID();
        TeachingPlanRepository.PlanReference other = plan(documentVersionId, "Other Game");
        when(plans.findRecentReferences(200)).thenReturn(List.of(other));
        when(lessons.findLatestSummariesByPlans(List.of(other.teachingPlanId())))
                .thenReturn(List.of(summary(other.teachingPlanId())));
        when(rulebooks.findReferences(List.of(documentVersionId))).thenReturn(Map.of(
                documentVersionId, reference(documentVersionId, editionId, "https://publisher.example/other.pdf")));
        when(editionIdentities.findBggIds(List.of(editionId))).thenReturn(Map.of(editionId, 999));

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .isEqualTo(AvailabilityStatus.NONE);
    }

    @Test
    void doesNotTurnAFullBoundedPlanWindowIntoANoneClaim() {
        List<TeachingPlanRepository.PlanReference> fullWindow = java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> plan(UUID.randomUUID(), "Other Game " + index))
                .toList();
        when(plans.findRecentReferences(200)).thenReturn(fullWindow);

        assertThat(catalog.continuationsFor(List.of(new Candidate(123, "Orbit"))).status())
                .isEqualTo(AvailabilityStatus.UNAVAILABLE);
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
