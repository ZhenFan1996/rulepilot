package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.catalog.PublicGameCoverLookup;
import com.rulepilot.catalog.PublicGameIdentityLookup;
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
    private final PublicGameCoverLookup covers = mock(PublicGameCoverLookup.class);
    private final PublicGameIdentityLookup identities = mock(PublicGameIdentityLookup.class);
    private final PublicLessonReader reader = new PublicLessonReader(plans, lessons, rulebooks, covers, identities);

    @Test
    void exposes_a_read_only_lesson_projection_without_an_owner() {
        Fixture fixture = fixture();
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(fixture.lesson));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(fixture.reference));
        when(identities.findByTitle("Orbit")).thenReturn(Optional.of(new PublicGameIdentityLookup.Identity(
                123, "Orbit", "https://boardgamegeek.com/boardgame/123")));

        var publicLesson = reader.find(fixture.plan.id());

        assertThat(publicLesson).hasValueSatisfying(value -> {
            assertThat(value.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(value.officialSourceUrl()).isEqualTo("https://publisher.example/rules.pdf");
            assertThat(value.gameCover()).isNull();
            assertThat(value.publicGame().bggId()).isEqualTo(123);
            assertThat(value.citedPages()).containsExactlyInAnyOrder(2, 5);
        });
    }

    @Test
    void includes_a_cover_only_when_the_associated_catalog_game_has_public_bgg_metadata() {
        Fixture fixture = fixture();
        UUID editionId = UUID.randomUUID();
        var reference = new PublicRulebookReferenceLookup.Reference(
                fixture.plan.documentVersionId(), editionId, "Orbit Rules", "https://publisher.example/rules.pdf", null);
        var cover = new PublicGameCoverLookup.Cover(
                "Orbit", 123, "https://cf.geekdo-images.com/orbit.jpg", "https://boardgamegeek.com/boardgame/123");
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(fixture.lesson));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(reference));
        when(covers.findByEdition(editionId)).thenReturn(Optional.of(cover));

        assertThat(reader.find(fixture.plan.id())).hasValueSatisfying(value -> {
            assertThat(value.gameCover().imageUrl()).isEqualTo(cover.thumbnailUrl());
            assertThat(value.gameCover().attributionLabel()).isEqualTo("BoardGameGeek");
            assertThat(value.gameCover().gameName()).isEqualTo("Orbit");
            assertThat(value.publicGame().bggId()).isEqualTo(123);
        });
    }

    @Test
    void falls_back_to_a_recorded_publisher_cover_when_bgg_metadata_is_absent() {
        Fixture fixture = fixture();
        var reference = new PublicRulebookReferenceLookup.Reference(
                fixture.plan.documentVersionId(),
                null,
                "Orbit Rules",
                "https://publisher.example/rules.pdf",
                "https://publisher.example/orbit-cover.jpg");
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(fixture.lesson));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(reference));

        assertThat(reader.find(fixture.plan.id())).hasValueSatisfying(value -> {
            assertThat(value.gameCover().imageUrl()).isEqualTo("https://publisher.example/orbit-cover.jpg");
            assertThat(value.gameCover().attributionLabel()).isEqualTo("出版方官方封面");
        });
    }

    @Test
    void keepsTheLessonReadableWhenOptionalBggIdentityLookupIsUnavailable() {
        Fixture fixture = fixture();
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(fixture.lesson));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(fixture.reference));
        when(identities.findByTitle("Orbit")).thenThrow(new IllegalStateException("snapshot unavailable"));

        assertThat(reader.find(fixture.plan.id())).hasValueSatisfying(value -> {
            assertThat(value.rulebookTitle()).isEqualTo("Orbit Rules");
            assertThat(value.publicGame()).isNull();
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

    @Test
    void exposes_a_safe_draft_ready_lesson_while_post_publication_review_continues() {
        Fixture fixture = fixture();
        IllustratedLesson draft = new IllustratedLesson(
                fixture.lesson.id(),
                fixture.lesson.teachingPlanId(),
                IllustratedLesson.LessonStatus.DRAFT_READY,
                fixture.lesson.sections(),
                fixture.lesson.generatorVersion(),
                fixture.lesson.createdAt());
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(draft));
        when(rulebooks.findReference(fixture.plan.documentVersionId())).thenReturn(Optional.of(fixture.reference));

        assertThat(reader.find(fixture.plan.id())).isPresent();
    }

    @Test
    void keeps_a_draft_with_a_player_facing_source_gap_out_of_the_anonymous_reader() {
        Fixture fixture = fixture();
        IllustratedLesson.LessonSection sourceGap = new IllustratedLesson.LessonSection(
                1,
                "ending",
                List.of("end", "scoring"),
                "结束、计分与胜者",
                true,
                IllustratedLesson.EvidenceStatus.CITED_DRAFT,
                IllustratedLesson.VisualKind.REFERENCE_CARD,
                "页面没有提到游戏何时结束或如何计分。",
                List.of(5),
                List.of(UUID.randomUUID()),
                List.of(new IllustratedLesson.LessonStep(
                        1,
                        "缺少终局规则",
                        IllustratedLesson.TeachingMove.UNDERSTAND,
                        "关于结束触发，需要从游戏的其他规则部分来了解。",
                        List.of(5),
                        List.of(UUID.randomUUID()))));
        IllustratedLesson draft = new IllustratedLesson(
                fixture.lesson.id(),
                fixture.lesson.teachingPlanId(),
                IllustratedLesson.LessonStatus.DRAFT_READY,
                List.of(sourceGap),
                fixture.lesson.generatorVersion(),
                fixture.lesson.createdAt());
        when(plans.findById(fixture.plan.id())).thenReturn(Optional.of(fixture.plan));
        when(lessons.findLatestByPlan(fixture.plan.id())).thenReturn(Optional.of(draft));

        assertThat(reader.find(fixture.plan.id())).isEmpty();
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
                UUID.randomUUID(), planId, IllustratedLesson.LessonStatus.COMPLETE, List.of(section), "test", Instant.now());
        return new Fixture(
                plan,
                lesson,
                new PublicRulebookReferenceLookup.Reference(
                        versionId, null, "Orbit Rules", "https://publisher.example/rules.pdf", null));
    }

    private record Fixture(
            TeachingPlan plan, IllustratedLesson lesson, PublicRulebookReferenceLookup.Reference reference) {}
}
