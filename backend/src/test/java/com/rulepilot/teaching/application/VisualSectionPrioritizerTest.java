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
    void selects_every_evidenced_section_with_a_cited_page() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                section(1, VisualKind.REFERENCE_CARD, true, EvidenceStatus.SUPPORTED),
                section(2, VisualKind.SCOREBOARD, true, EvidenceStatus.SUPPORTED),
                section(3, VisualKind.FLOW_DIAGRAM, false, EvidenceStatus.SUPPORTED),
                section(4, VisualKind.TABLE_LAYOUT, true, EvidenceStatus.SUPPORTED),
                section(5, VisualKind.TABLE_LAYOUT, true, EvidenceStatus.INSUFFICIENT_EVIDENCE)));

        assertThat(selected).isEqualTo(Set.of(1, 2, 3, 4));
    }

    @Test
    void existing_visual_count_does_not_create_a_final_result_cap() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 1),
                sectionWithVisualSteps(2, 12)));

        assertThat(selected).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void does_not_drop_a_section_at_any_fixed_visual_density() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 3),
                sectionWithVisualSteps(2, 8)));

        assertThat(selected).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void keeps_every_cited_section_eligible_after_six_existing_visuals() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                sectionWithVisualSteps(1, 5),
                sectionWithVisualSteps(2, 6)));

        assertThat(selected).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void explicit_visual_intent_does_not_hide_other_evidenced_sections() {
        var selected = new VisualSectionPrioritizer().positions(List.of(
                section(1, VisualKind.TABLE_LAYOUT, true, EvidenceStatus.SUPPORTED),
                sectionWithUnresolvedVisualStep(2),
                section(3, VisualKind.FLOW_DIAGRAM, true, EvidenceStatus.SUPPORTED)));

        assertThat(selected).containsExactlyInAnyOrder(1, 2, 3);
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
