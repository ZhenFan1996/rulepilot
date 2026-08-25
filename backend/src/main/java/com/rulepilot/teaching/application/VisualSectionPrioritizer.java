package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Orders every evidenced, cited section without imposing a final section or image count. */
@Component
public final class VisualSectionPrioritizer {

    public Set<Integer> positions(List<LessonSection> sections) {
        if (sections == null) {
            throw new IllegalArgumentException("visual section priority input is invalid");
        }
        LinkedHashSet<Integer> positions = sections.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .filter(section -> section.steps().stream().anyMatch(step -> !step.sourcePages().isEmpty()))
                .sorted(Comparator.comparingInt(this::score).reversed()
                        .thenComparingInt(LessonSection::position))
                .map(LessonSection::position)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(positions);
    }

    private int score(LessonSection section) {
        int kind = switch (section.visualKind()) {
            case TABLE_LAYOUT -> 40;
            case FLOW_DIAGRAM -> 30;
            case SCOREBOARD -> 20;
            case REFERENCE_CARD -> 10;
        };
        return kind + (section.required() ? 5 : 0)
                + (section.evidenceStatus() == EvidenceStatus.SUPPORTED ? 2 : 0);
    }
}
