package com.rulepilot.teaching;

import java.util.List;

/** Lets the model decide how this particular game should be taught before retrieval begins. */
public interface TeachingOutlineModel {

    OutlineDraft organize(OutlineRequest request);

    /**
     * Produces a source-derived outline when a provider response is structurally unusable.
     * Implementations must not make another paid model call here.
     */
    default OutlineDraft fallback(OutlineRequest request) {
        return organize(request);
    }

    /**
     * Rebuilds an otherwise usable outline when its broad flow chapter steals detail owned by later chapters.
     * Implementations must preserve the supplied draft when refinement cannot complete.
     */
    default OutlineDraft refineChapterOwnership(OutlineRequest request, OutlineDraft current, String feedback) {
        return current;
    }

    /**
     * Marks a provider or structured-output failure at the model adapter boundary. Application services may recover
     * from this failure only when an independently complete source ledger is already available.
     */
    final class OutlineGenerationException extends RuntimeException {

        public OutlineGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record OutlineRequest(
            List<PageInput> pages,
            List<PageImageInput> pageImages,
            String learningGoal,
            String modelConfigurationOwner) {
        public OutlineRequest {
            learningGoal = normalizeOptional(learningGoal);
            if (pages == null || pages.isEmpty() || pageImages == null) {
                throw new IllegalArgumentException("teaching outline request is invalid");
            }
            pages = List.copyOf(pages);
            pageImages = List.copyOf(pageImages);
            modelConfigurationOwner = modelConfigurationOwner == null || modelConfigurationOwner.isBlank()
                    ? null
                    : modelConfigurationOwner.strip();
        }

        public String learningGoalForPrompt() {
            return learningGoal == null ? "NO_ADDITIONAL_GOAL" : learningGoal;
        }

        private static String normalizeOptional(String value) {
            if (value == null || value.isBlank()) return null;
            String normalized = value.strip();
            if (normalized.length() > 500) throw new IllegalArgumentException("teaching learning goal is too long");
            return normalized;
        }

        public OutlineRequest(
                List<PageInput> pages,
                List<PageImageInput> pageImages,
                String modelConfigurationOwner) {
            this(pages, pageImages, null, modelConfigurationOwner);
        }

        public OutlineRequest(
                List<PageInput> pages,
                List<PageImageInput> pageImages) {
            this(pages, pageImages, null, null);
        }

        public OutlineRequest(List<PageInput> pages) {
            this(pages, List.of(), null, null);
        }
    }

    record PageInput(
            int pageNumber,
            String text,
            List<VisualRulebookPageCatalogModel.SourceDependency> sourceDependencies,
            List<String> sourceRuleGroupIdentifiers,
            boolean sourceRuleGroupInventoryComplete) {
        public PageInput {
            if (pageNumber < 1 || text == null || text.isBlank() || sourceDependencies == null
                    || sourceRuleGroupIdentifiers == null || sourceRuleGroupIdentifiers.size() > 16
                    || sourceRuleGroupIdentifiers.stream()
                            .anyMatch(identifier -> identifier == null || identifier.isBlank() || identifier.length() > 160)) {
                throw new IllegalArgumentException("rulebook page input is invalid");
            }
            text = text.strip();
            sourceDependencies = sourceDependencies.stream().distinct().toList();
            sourceRuleGroupIdentifiers = sourceRuleGroupIdentifiers.stream().map(String::strip).distinct().toList();
        }

        public PageInput(
                int pageNumber,
                String text,
                List<VisualRulebookPageCatalogModel.SourceDependency> sourceDependencies) {
            this(pageNumber, text, sourceDependencies, List.of(), false);
        }

        public PageInput(int pageNumber, String text) {
            this(pageNumber, text, List.of(), List.of(), false);
        }
    }

    record PageImageInput(int pageNumber, String mediaType, byte[] content) {
        public PageImageInput {
            if (pageNumber < 1 || mediaType == null || mediaType.isBlank() || content == null || content.length == 0) {
                throw new IllegalArgumentException("rulebook outline page image is invalid");
            }
            mediaType = mediaType.strip();
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record OutlineDraft(String gameTitle, String premise, List<TopicDraft> topics) {
        public OutlineDraft {
            if (gameTitle == null || gameTitle.isBlank() || gameTitle.length() > 200
                    || premise == null || premise.isBlank() || premise.length() > 1_200) {
                throw new IllegalArgumentException("teaching outline identity is invalid");
            }
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    record TopicDraft(
            String key,
            String title,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<String> retrievalQueries,
            List<String> coverageTags,
            List<Integer> sourcePageNumbers) {
        public TopicDraft {
            if (title == null || title.isBlank() || title.length() > 160
                    || objective == null || objective.isBlank() || objective.length() > 600
                    || (key != null && key.length() > 100)
                    || (retrievalQueries != null && (retrievalQueries.size() > 8 || retrievalQueries.stream()
                            .anyMatch(query -> query == null || query.isBlank() || query.length() > 300)))
                    || (sourcePageNumbers != null && (sourcePageNumbers.size() > 5 || sourcePageNumbers.stream()
                            .anyMatch(pageNumber -> pageNumber == null || pageNumber < 1)))) {
                throw new IllegalArgumentException("teaching outline topic is invalid");
            }
            key = key == null ? "" : key.strip();
            title = title.strip();
            objective = objective.strip();
            retrievalQueries = retrievalQueries == null ? List.of() : List.copyOf(retrievalQueries);
            coverageTags = coverageTags == null ? List.of() : List.copyOf(coverageTags);
            sourcePageNumbers = sourcePageNumbers == null ? List.of() : sourcePageNumbers.stream().distinct().toList();
        }

        public TopicDraft(
                String key,
                String title,
                String objective,
                boolean required,
                boolean visualEvidenceRecommended,
                List<String> retrievalQueries,
                List<String> coverageTags) {
            this(key, title, objective, required, visualEvidenceRecommended, retrievalQueries, coverageTags, List.of());
        }
    }
}
