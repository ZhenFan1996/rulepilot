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

/** Limits background vision work to the sections where an owned rulebook visual is most likely to teach. */
@Component
public final class VisualSectionPrioritizer {

    private static final int DEFAULT_VISUAL_RESULT_BUDGET = 6;

    public Set<Integer> positions(List<LessonSection> sections, int limit) {
        return positions(sections, limit, DEFAULT_VISUAL_RESULT_BUDGET);
    }

    public Set<Integer> positions(List<LessonSection> sections, int limit, int visualResultBudget) {
        if (sections == null || limit < 1 || visualResultBudget < 1) {
            throw new IllegalArgumentException("visual section priority input is invalid");
        }
        List<LessonSection> eligible = sections.stream()
                .filter(section -> section.evidenceStatus() != EvidenceStatus.INSUFFICIENT_EVIDENCE)
                .filter(section -> resolvedVisualCount(section) < visualResultBudget)
                .filter(section -> section.steps().stream().anyMatch(step -> !step.sourcePages().isEmpty()))
                .toList();
        boolean hasExplicitVisualIntent = eligible.stream().anyMatch(this::hasUnresolvedExplicitVisual);
        return eligible.stream()
                .filter(section -> !hasExplicitVisualIntent || hasUnresolvedExplicitVisual(section))
                .sorted(Comparator.comparingInt(this::score).reversed()
                        .thenComparingInt(LessonSection::position))
                .limit(limit)
                .map(LessonSection::position)
                .collect(Collectors.toUnmodifiableSet());
    }

    private long resolvedVisualCount(LessonSection section) {
        return section.steps().stream()
                .mapToLong(step -> step.visualFoci().size())
                .sum();
    }

    private boolean hasUnresolvedExplicitVisual(LessonSection section) {
        return section.steps().stream()
                .filter(step -> step.kind() == TeachingMove.VISUAL)
                .filter(step -> step.visualFoci().isEmpty())
                .anyMatch(step -> !step.sourcePages().isEmpty() && !step.sourceChunkIds().isEmpty());
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
