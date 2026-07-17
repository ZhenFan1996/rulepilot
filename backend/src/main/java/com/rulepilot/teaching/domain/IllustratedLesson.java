package com.rulepilot.teaching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IllustratedLesson(
        UUID id,
        UUID teachingPlanId,
        LessonStatus status,
        List<LessonSection> sections,
        Instant createdAt) {

    public IllustratedLesson {
        if (id == null || teachingPlanId == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("lesson identity is required");
        }
        sections = List.copyOf(sections);
    }

    public enum LessonStatus {
        COMPLETE,
        INCOMPLETE
    }

    public enum EvidenceStatus {
        SUPPORTED,
        INSUFFICIENT_EVIDENCE
    }

    public enum VisualKind {
        REFERENCE_CARD,
        TABLE_LAYOUT,
        FLOW_DIAGRAM,
        SCOREBOARD
    }

    public record LessonSection(
            int position,
            TeachingSectionType type,
            String title,
            boolean required,
            EvidenceStatus evidenceStatus,
            VisualKind visualKind,
            String visualCaption,
            List<LessonStep> steps) {
        public LessonSection {
            if (position < 1 || type == null || title == null || title.isBlank()) {
                throw new IllegalArgumentException("lesson section identity is required");
            }
            steps = List.copyOf(steps);
        }
    }

    public record LessonStep(int position, String text, List<Integer> sourcePages) {
        public LessonStep {
            if (position < 1 || text == null || text.isBlank()) {
                throw new IllegalArgumentException("lesson step content is required");
            }
            sourcePages = List.copyOf(sourcePages);
        }
    }
}
