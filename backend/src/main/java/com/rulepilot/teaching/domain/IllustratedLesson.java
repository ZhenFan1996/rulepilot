package com.rulepilot.teaching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IllustratedLesson(
        UUID id,
        UUID teachingPlanId,
        LessonStatus status,
        List<LessonSection> sections,
        String generatorVersion,
        Instant createdAt) {

    public IllustratedLesson {
        if (id == null || teachingPlanId == null || status == null
                || generatorVersion == null || generatorVersion.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("lesson identity is required");
        }
        sections = List.copyOf(sections);
    }

    public IllustratedLesson(
            UUID id,
            UUID teachingPlanId,
            LessonStatus status,
            List<LessonSection> sections,
            Instant createdAt) {
        this(id, teachingPlanId, status, sections, "legacy", createdAt);
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

    public enum TeachingMove {
        UNDERSTAND,
        DO,
        EXAMPLE,
        WATCH,
        CHECK
    }

    public record LessonSection(
            int position,
            String topicKey,
            List<String> coverageTags,
            String title,
            boolean required,
            EvidenceStatus evidenceStatus,
            VisualKind visualKind,
            String visualCaption,
            List<Integer> visualSourcePages,
            List<UUID> visualSourceChunkIds,
            List<LessonStep> steps) {
        public LessonSection {
            if (position < 1 || topicKey == null || topicKey.isBlank() || title == null || title.isBlank()) {
                throw new IllegalArgumentException("lesson section identity is required");
            }
            coverageTags = coverageTags == null ? List.of() : List.copyOf(coverageTags);
            visualSourcePages = visualSourcePages == null ? List.of() : List.copyOf(visualSourcePages);
            visualSourceChunkIds = visualSourceChunkIds == null ? List.of() : List.copyOf(visualSourceChunkIds);
            steps = List.copyOf(steps);
        }

        public LessonSection(
                int position,
                String topicKey,
                List<String> coverageTags,
                String title,
                boolean required,
                EvidenceStatus evidenceStatus,
                VisualKind visualKind,
                String visualCaption,
                List<LessonStep> steps) {
            this(
                    position,
                    topicKey,
                    coverageTags,
                    title,
                    required,
                    evidenceStatus,
                    visualKind,
                    visualCaption,
                    List.of(),
                    List.of(),
                    steps);
        }
    }

    public record LessonStep(
            int position,
            String heading,
            TeachingMove kind,
            String text,
            List<Integer> sourcePages,
            List<UUID> sourceChunkIds) {
        public LessonStep {
            if (position < 1 || heading == null || heading.isBlank() || kind == null
                    || text == null || text.isBlank()) {
                throw new IllegalArgumentException("lesson step content is required");
            }
            sourcePages = List.copyOf(sourcePages);
            sourceChunkIds = List.copyOf(sourceChunkIds);
        }

        public LessonStep(int position, String text, List<Integer> sourcePages, List<UUID> sourceChunkIds) {
            this(position, "照着做", TeachingMove.DO, text, sourcePages, sourceChunkIds);
        }
    }
}
