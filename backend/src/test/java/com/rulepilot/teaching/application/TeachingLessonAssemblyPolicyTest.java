package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.WholeGameContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeachingLessonAssemblyPolicyTest {

    private final TeachingLessonAssemblyPolicy policy = new TeachingLessonAssemblyPolicy();

    @Test
    void keepsReadableChaptersAndMakesUnresolvedWorkExplicitlyDegraded() {
        assertThat(policy.status(plan(List.of()), List.of(section(1), section(2))))
                .isEqualTo(LessonStatus.COMPLETE);
        assertThat(policy.status(plan(List.of("Repairing remains unresolved")), List.of(section(1), section(2))))
                .isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(policy.status(plan(List.of()), List.of(section(1))))
                .isEqualTo(LessonStatus.DRAFT_READY);
        assertThat(policy.status(plan(List.of()), List.of()))
                .isEqualTo(LessonStatus.INCOMPLETE);
    }

    @Test
    void givesDependentChaptersEveryReadablePrerequisiteWithoutAChapterCountCutoff() {
        assertThat(policy.continuityContext(List.of(section(1), section(2), section(3), section(4))))
                .extracting(context -> context.topicKey())
                .containsExactly("chapter-1", "chapter-2", "chapter-3", "chapter-4");
    }

    private TeachingPlan plan(List<String> unresolved) {
        return new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Game",
                "Learn naturally.",
                new WholeGameContext(List.of(), unresolved),
                List.of(planned(1), planned(2)),
                "owner",
                Instant.EPOCH);
    }

    private TeachingPlan.PlannedSection planned(int position) {
        return new TeachingPlan.PlannedSection(
                position,
                "chapter-" + position,
                "Chapter " + position,
                "Learn chapter " + position,
                true,
                false,
                List.of("chapter " + position),
                List.of(),
                List.of(position));
    }

    private LessonSection section(int position) {
        UUID citation = UUID.randomUUID();
        return new LessonSection(
                position,
                "chapter-" + position,
                List.of(),
                "Chapter " + position,
                true,
                EvidenceStatus.SUPPORTED,
                VisualKind.REFERENCE_CARD,
                "Chapter " + position,
                List.of(position),
                List.of(citation),
                List.of(new LessonStep(
                        1,
                        "Act",
                        TeachingMove.DO,
                        "Follow the cited rule.",
                        List.of(position),
                        List.of(citation))));
    }
}
