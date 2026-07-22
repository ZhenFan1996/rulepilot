package com.rulepilot.teaching.application;

import com.rulepilot.teaching.domain.IllustratedLesson.EvidenceStatus;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonSection;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Limits background vision work to the sections where a compact diagram is most likely to teach. */
@Component
public final class VisualSectionPrioritizer {

    private static final int DEFAULT_MAX_VISUAL_STEPS_PER_SECTION = 2;

    public Set<Integer> positions(List<LessonSection> sections, int limit) {
        return positions(sections, limit, DEFAULT_MAX_VISUAL_STEPS_PER_SECTION);
    }

    public Set<Integer> positions(List<LessonSection> sections, int limit, int maxVisualStepsPerSection) {
        if (sections == null || limit < 1 || maxVisualStepsPerSection < 1) {
            throw new IllegalArgumentException("visual section priority input is invalid");
        }
        return sections.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .filter(section -> section.steps().stream().filter(step -> step.kind() == TeachingMove.VISUAL).count()
                        < maxVisualStepsPerSection)
                .filter(section -> section.steps().stream().anyMatch(step -> !step.sourcePages().isEmpty()))
                .sorted(Comparator.comparingInt(this::score).reversed()
                        .thenComparingInt(LessonSection::position))
                .limit(limit)
                .map(LessonSection::position)
                .collect(Collectors.toUnmodifiableSet());
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
