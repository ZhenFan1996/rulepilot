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
    void uses_the_default_total_visual_budget_instead_of_a_fixed_two_visual_cap() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 1),
                sectionWithVisualSteps(2, 2)), 2);

        assertThat(selected).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void supports_a_higher_visual_density_when_the_published_lesson_requests_it() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 3),
                sectionWithVisualSteps(2, 4)), 2, 4);

        assertThat(selected).containsExactly(1);
    }

    @Test
    void keeps_a_section_eligible_until_every_published_rule_step_has_been_considered() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 5),
                sectionWithVisualSteps(2, 6)), 2, 6);

        assertThat(selected).containsExactly(1);
    }

    @Test
    void prioritizesOnlySectionsWithUnresolvedExplicitVisualIntentWhenTheLessonProvidesIt() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                section(1, VisualKind.TABLE_LAYOUT, true, EvidenceStatus.SUPPORTED),
                sectionWithUnresolvedVisualStep(2),
                section(3, VisualKind.FLOW_DIAGRAM, true, EvidenceStatus.SUPPORTED)), 3, 6);

        assertThat(selected).containsExactly(2);
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
                List.of(UUID.randomUUID()),
                new IllustratedLesson.VisualFocus(
                        1, "Resolved visual " + index, 100 + index * 10, 100 + index * 10, 180, 120)));
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

    private LessonSection sectionWithUnresolvedVisualStep(int position) {
        var visual = new LessonStep(
                1,
                "Look here",
                TeachingMove.VISUAL,
                "Use this cited diagram to understand the rule.",
                List.of(4),
                List.of(UUID.randomUUID()));
        var ordinary = new LessonStep(
                2,
                "Then act",
                TeachingMove.DO,
                "Follow the cited procedure.",
                List.of(4),
                List.of(UUID.randomUUID()));
        return new LessonSection(
                position, "section-" + position, List.of(), "Section " + position, true,
                EvidenceStatus.SUPPORTED, VisualKind.REFERENCE_CARD, "caption", List.of(), List.of(),
                List.of(visual, ordinary));
    }
}
