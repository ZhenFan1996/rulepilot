package com.rulepilot.teaching;

import com.rulepilot.teaching.domain.IllustratedLesson.VisualKind;
import com.rulepilot.teaching.domain.IllustratedLesson.TeachingMove;
import java.util.List;
import java.util.UUID;

public interface TeachingLessonModel {

    default String providerId() {
        return "unspecified";
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
            int playerCount,
            int beginnerCount,
            int totalDurationMinutes,
            int sectionDurationSeconds,
            int maxSteps,
            List<PriorSectionContext> priorSections,
            List<EvidenceInput> evidence,
            List<PageImageInput> pageImages) {

        public SectionRequest(
                String topicKey,
                String title,
                String objective,
                List<String> coverageTags,
                int playerCount,
                int beginnerCount,
                int totalDurationMinutes,
                int sectionDurationSeconds,
                int maxSteps,
                List<PriorSectionContext> priorSections,
                List<EvidenceInput> evidence) {
            this(
                    topicKey,
                    title,
                    objective,
                    coverageTags,
                    playerCount,
                    beginnerCount,
                    totalDurationMinutes,
                    sectionDurationSeconds,
                    maxSteps,
                    priorSections,
                    evidence,
                    List.of());
        }

        public SectionRequest {
            if (topicKey == null || topicKey.isBlank()
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || coverageTags == null
                    || playerCount < 1 || beginnerCount < 0 || totalDurationMinutes < 1
                    || sectionDurationSeconds < 10 || sectionDurationSeconds > totalDurationMinutes * 60
                    || maxSteps < 1 || maxSteps > 6
                    || priorSections == null || priorSections.size() > 2
                    || evidence == null || evidence.isEmpty()
                    || pageImages == null || pageImages.size() > 2) {
                throw new IllegalArgumentException("teaching model request is invalid");
            }
            coverageTags = List.copyOf(coverageTags);
            priorSections = List.copyOf(priorSections);
            evidence = List.copyOf(evidence);
            pageImages = List.copyOf(pageImages);
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

    record StepDraft(String heading, TeachingMove kind, String text, List<UUID> citationIds) {
        public StepDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }

        public StepDraft(String text, List<UUID> citationIds) {
            this("照着做", TeachingMove.DO, text, citationIds);
        }
    }
}
