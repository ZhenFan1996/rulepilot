package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.RuleStructureCatalog.SectionView;
import com.rulepilot.ingestion.RuleStructureCatalog.StructureView;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.TeachingPlan;
import com.rulepilot.teaching.domain.TeachingPlan.PlannedSection;
import com.rulepilot.teaching.domain.TeachingSectionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IllustratedLessonFactoryTest {

    private final IllustratedLessonFactory factory = new IllustratedLessonFactory();

    @Test
    void composesOnlyCitedStepsAndMarksMissingEvidence() {
        var plan = new TeachingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                4,
                2,
                20,
                List.of(
                        new PlannedSection(1, TeachingSectionType.SETUP, true, true, List.of(3), List.of()),
                        new PlannedSection(2, TeachingSectionType.SCORING, true, false, List.of(), List.of())),
                "player",
                Instant.now());
        var structure = new StructureView(
                List.of(new SectionView(
                        "SETUP", "Setup", true, "Deal three cards. Place the board centrally.", List.of(3))),
                1,
                9);

        var lesson = factory.create(plan, structure);

        assertThat(lesson.status()).isEqualTo(LessonStatus.INCOMPLETE);
        assertThat(lesson.sections().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(lesson.sections().getFirst().visualKind()).isEqualTo(VisualKind.TABLE_LAYOUT);
        assertThat(lesson.sections().getFirst().steps())
                .allSatisfy(step -> assertThat(step.sourcePages()).containsExactly(3));
        assertThat(lesson.sections().get(1).evidenceStatus()).isEqualTo(EvidenceStatus.INSUFFICIENT_EVIDENCE);
        assertThat(lesson.sections().get(1).steps().getFirst().sourcePages()).isEmpty();
    }
}
