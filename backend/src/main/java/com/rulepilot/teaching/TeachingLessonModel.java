package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import java.util.List;
import java.util.UUID;

public interface TeachingLessonModel {

    default String providerId() {
        return "unspecified";
    }

    default boolean supportsVisualEvidence() {
        return false;
    }

    /**
     * Visual capability is selected from the lesson owner's model configuration, not worker-thread security state.
     */
    default boolean supportsVisualEvidence(String modelConfigurationOwner) {
        return supportsVisualEvidence();
    }

    /**
     * Provider accounts can impose a lower safe request concurrency than the lesson executor.
     */
    default int maxConcurrentSectionRequests(String modelConfigurationOwner) {
        return Integer.MAX_VALUE;
    }

    SectionDraft compose(SectionRequest request);

    default SectionDraft revise(SectionRequest request, SectionDraft previousDraft, List<String> feedback) {
        return compose(request);
    }

    record SectionRequest(
            String topicKey,
            String title,
            String objective,
            List<String> coverageTags,
            List<PriorSectionContext> priorSections,
            List<EvidenceInput> evidence,
            List<PageImageInput> pageImages,
            List<String> requiredRuleIntents,
            String modelConfigurationOwner,
            String chapterScope) {

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    priorSections,
                    evidence,
                    List.of(),
                    List.of(),
                    null,
                    "");
        }

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence,
                List<PageImageInput> pageImages) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    priorSections,
                    evidence,
                    pageImages,
                    List.of(),
                    null,
                    "");
        }

        public SectionRequest {
            if (topicKey == null || topicKey.isBlank()
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || coverageTags == null
                    || priorSections == null || priorSections.size() > 2
                    || evidence == null || evidence.isEmpty()
                    || pageImages == null || pageImages.size() > 2
                    || requiredRuleIntents == null || requiredRuleIntents.size() > 5
                    || requiredRuleIntents.stream()
                            .anyMatch(intent -> intent == null || intent.isBlank() || intent.length() > 300)
                    || chapterScope == null || chapterScope.length() > 4_000) {
                throw new IllegalArgumentException("teaching model request is invalid");
            }
            coverageTags = List.copyOf(coverageTags);
            priorSections = List.copyOf(priorSections);
            evidence = List.copyOf(evidence);
            pageImages = List.copyOf(pageImages);
            requiredRuleIntents = requiredRuleIntents.stream().map(String::strip).distinct().toList();
            chapterScope = chapterScope.strip();
        }
    }

    record PriorSectionContext(String topicKey, String title, String closingStep) {
        public PriorSectionContext {
            if (topicKey == null || topicKey.isBlank() || title == null || title.isBlank() || title.length() > 160
                    || closingStep == null || closingStep.isBlank() || closingStep.length() > 600) {
                throw new IllegalArgumentException("prior teaching section context is invalid");
            }
            title = title.strip();
            closingStep = closingStep.strip();
        }
    }

    record EvidenceInput(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            int pageFrom,
            int pageTo) {}

    record PageImageInput(int pageNumber, String mediaType, byte[] content, int width, int height) {
        public PageImageInput {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0
                    || width < 1 || height < 1) {
                throw new IllegalArgumentException("teaching page image is invalid");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record SectionDraft(
            String title,
            VisualKind visualKind,
            String visualCaption,
            List<UUID> visualCitationIds,
            List<StepDraft> steps) {
        public SectionDraft {
            visualCitationIds = visualCitationIds == null ? List.of() : List.copyOf(visualCitationIds);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record StepDraft(
            String heading,
            TeachingMove kind,
            String text,
            List<UUID> citationIds,
            VisualFocusDraft visualFocus) {
        public StepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }

        public StepDraft(String heading, TeachingMove kind, String text, List<UUID> citationIds) {
            this(heading, kind, text, citationIds, null);
        }

        public StepDraft(String text, List<UUID> citationIds) {
            this("照着做", TeachingMove.DO, text, citationIds, null);
        }
    }

    record VisualFocusDraft(
            int pageNumber,
            String label,
            String visibleDescription,
            int x,
            int y,
            int width,
            int height) {
        public VisualFocusDraft {
            label = label == null ? "" : label.strip();
            visibleDescription = visibleDescription == null ? "" : visibleDescription.strip();
            if (visibleDescription.length() > 240) {
                throw new IllegalArgumentException("visual focus description is too long");
            }
        }

        public VisualFocusDraft(
                int pageNumber,
                String label,
                int x,
                int y,
                int width,
                int height) {
            this(pageNumber, label, "", x, y, width, height);
        }
    }
}
