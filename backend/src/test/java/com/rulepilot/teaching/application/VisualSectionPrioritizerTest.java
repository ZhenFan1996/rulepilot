package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStep;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualSectionPrioritizerTest {

    @Test
    void selects_a_bounded_set_of_the_most_teachable_visual_sections() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                section(1, VisualKind.REFERENCE_CARD, true, EvidenceStatus.SUPPORTED),
                section(2, VisualKind.SCOREBOARD, true, EvidenceStatus.SUPPORTED),
                section(3, VisualKind.FLOW_DIAGRAM, false, EvidenceStatus.SUPPORTED),
                section(4, VisualKind.TABLE_LAYOUT, true, EvidenceStatus.SUPPORTED),
                section(5, VisualKind.TABLE_LAYOUT, true, EvidenceStatus.INSUFFICIENT_EVIDENCE)), 3);

        assertThat(selected).isEqualTo(Set.of(2, 3, 4));
    }

    @Test
    void allows_a_second_visual_anchor_but_does_not_revisit_a_section_that_already_has_two() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 1),
                sectionWithVisualSteps(2, 2)), 2);

        assertThat(selected).containsExactly(1);
    }

    private LessonSection section(int position, VisualKind kind, boolean required, EvidenceStatus evidence) {
        return new LessonSection(
                position, "section-" + position, List.of(), "Section " + position, required, evidence, kind,
                "caption", List.of(), List.of(), List.of(new LessonStep(
                        1, "Do", TeachingMove.DO, "Follow this cited step.", List.of(1), List.of(UUID.randomUUID()))));
    }

    private LessonSection sectionWithVisualSteps(int position, int visualSteps) {
        var steps = new java.util.ArrayList<LessonStep>();
        for (int index = 0; index < visualSteps; index++) {
            steps.add(new LessonStep(
                    index + 1,
                    "Look " + index,
                    TeachingMove.VISUAL,
                    "Read this grounded visual aid.",
                    List.of(1),
                    List.of(UUID.randomUUID())));
        }
        steps.add(new LessonStep(
                visualSteps + 1,
                "Do",
                TeachingMove.DO,
                "Follow this cited step.",
                List.of(1),
                List.of(UUID.randomUUID())));
        return new LessonSection(
                position, "section-" + position, List.of(), "Section " + position, true,
                EvidenceStatus.SUPPORTED, VisualKind.TABLE_LAYOUT, "caption", List.of(), List.of(), steps);
    }
}
