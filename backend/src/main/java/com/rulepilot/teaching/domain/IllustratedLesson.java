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

    public enum LessonStatus {
        COMPLETE,
        DRAFT_READY,
        INCOMPLETE
    }

    public enum EvidenceStatus {
        /** Passed the deterministic publication boundary; this is not a human or second-model fact-check claim. */
        SUPPORTED,
        /** Cited content retained for inspection after a local, optional enrichment or review concern. */
        CITED_DRAFT,
        INSUFFICIENT_EVIDENCE
    }

    public enum VisualKind {
        REFERENCE_CARD,
        TABLE_LAYOUT,
        FLOW_DIAGRAM,
        SCOREBOARD
    }

    /** Identifies which immutable rulebook visual asset a player-facing focus refers to. */
    public enum VisualSourceKind {
        FULL_PAGE,
        PAGE_REGION,
        EMBEDDED_AUTHOR_IMAGE
    }

    public enum TeachingMove {
        UNDERSTAND,
        DO,
        EXAMPLE,
        WATCH,
        CHECK,
        VISUAL,
        FLOW,
        LEDGER,
        REFERENCE_CARD,
        LIMIT
    }

    /**
     * Presentation role for one independently cited rule fact inside a natural teaching step.
     *
     * <p>The role is model-authored structured output. Readers use it to lay out a condition, action, cost, timing,
     * result, or exception without trying to recover that meaning from the step's free prose.</p>
     */
    public enum RuleFactRole {
        PREREQUISITE,
        CHOICE,
        ACTION,
        COST_OR_GAIN,
        TIMING,
        LIMIT,
        RESULT,
        EXCEPTION,
        TABLE_STATE,
        EXAMPLE_STATE
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
            List<UUID> sourceChunkIds,
            List<RuleFact> ruleFacts,
            VisualFocus visualFocus,
            List<VisualFocus> visualFoci) {
        public LessonStep {
            if (position < 1 || heading == null || heading.isBlank() || kind == null
                    || text == null || text.isBlank()) {
                throw new IllegalArgumentException("lesson step content is required");
            }
            sourcePages = List.copyOf(sourcePages);
            sourceChunkIds = List.copyOf(sourceChunkIds);
            ruleFacts = ruleFacts == null ? List.of() : List.copyOf(ruleFacts);
            visualFoci = visualFoci == null ? List.of() : List.copyOf(visualFoci);
            if (visualFoci.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("lesson step visual foci are invalid");
            }
            if (visualFocus == null && !visualFoci.isEmpty()) visualFocus = visualFoci.getFirst();
            if (visualFocus != null && visualFoci.isEmpty()) visualFoci = List.of(visualFocus);
            if (visualFocus != null && !visualFocus.equals(visualFoci.getFirst())) {
                throw new IllegalArgumentException("lesson step primary visual focus must be the first visual focus");
            }
        }

        /** Compatibility constructor for stored lessons and clients that still expose only the first visual. */
        public LessonStep(
                int position,
                String heading,
                TeachingMove kind,
                String text,
                List<Integer> sourcePages,
                List<UUID> sourceChunkIds,
                List<RuleFact> ruleFacts,
                VisualFocus visualFocus) {
            this(
                    position,
                    heading,
                    kind,
                    text,
                    sourcePages,
                    sourceChunkIds,
                    ruleFacts,
                    visualFocus,
                    visualFocus == null ? List.of() : List.of(visualFocus));
        }

        public LessonStep withVisualFoci(TeachingMove teachingMove, List<VisualFocus> foci) {
            List<VisualFocus> stable = foci == null ? List.of() : List.copyOf(foci);
            return new LessonStep(
                    position,
                    heading,
                    teachingMove,
                    text,
                    sourcePages,
                    sourceChunkIds,
                    ruleFacts,
                    stable.isEmpty() ? null : stable.getFirst(),
                    stable);
        }

        public LessonStep(int position, String text, List<Integer> sourcePages, List<UUID> sourceChunkIds) {
            this(position, "照着做", TeachingMove.DO, text, sourcePages, sourceChunkIds, List.of(), null);
        }

        public LessonStep(
                int position,
                String heading,
                TeachingMove kind,
                String text,
                List<Integer> sourcePages,
                List<UUID> sourceChunkIds) {
            this(position, heading, kind, text, sourcePages, sourceChunkIds, List.of(), null);
        }

        public LessonStep(
                int position,
                String heading,
                TeachingMove kind,
                String text,
                List<Integer> sourcePages,
                List<UUID> sourceChunkIds,
                VisualFocus visualFocus) {
            this(position, heading, kind, text, sourcePages, sourceChunkIds, List.of(), visualFocus);
        }
    }

    public record RuleFact(
            int position,
            RuleFactRole role,
            String text,
            List<Integer> sourcePages,
            List<UUID> sourceChunkIds) {
        public RuleFact {
            if (position < 1 || role == null || text == null || text.isBlank()
                    || sourcePages == null || sourceChunkIds == null || sourceChunkIds.isEmpty()) {
                throw new IllegalArgumentException("lesson rule fact is invalid");
            }
            text = text.strip();
            sourcePages = List.copyOf(sourcePages);
            sourceChunkIds = List.copyOf(sourceChunkIds);
        }
    }

    public record VisualFocus(
            int pageNumber,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height,
            VisualSourceKind sourceKind) {
        public VisualFocus(
                int pageNumber,
                String label,
                String visibleDescription,
                int x,
                int y,
                int width,
                int height) {
            this(pageNumber, label, visibleDescription, x, y, width, height, inferredSourceKind(x, y, width, height));
        }

        public VisualFocus(int pageNumber, String label, int x, int y, int width, int height) {
            this(pageNumber, label, "", x, y, width, height, inferredSourceKind(x, y, width, height));
        }

        public VisualFocus {
            if (pageNumber < 1 || label == null || label.isBlank()
                    || visibleDescription == null
                    || sourceKind == null
                    || x < 0 || x > 980 || y < 0 || y > 980
                    || width < 20 || width > 1_000 - x
                    || height < 20 || height > 1_000 - y) {
                throw new IllegalArgumentException("lesson visual focus is invalid");
            }
            boolean completePage = x == 0 && y == 0 && width == 1_000 && height == 1_000;
            if ((sourceKind == VisualSourceKind.FULL_PAGE) != completePage) {
                throw new IllegalArgumentException("full-page visual focus kind and geometry must agree");
            }
        }

        private static VisualSourceKind inferredSourceKind(int x, int y, int width, int height) {
            return x == 0 && y == 0 && width == 1_000 && height == 1_000
                    ? VisualSourceKind.FULL_PAGE
                    : VisualSourceKind.PAGE_REGION;
        }
    }
}
