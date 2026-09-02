package com.rulepilot.teaching;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Lets one Agent read rule pages, publish chapter plans, and decide when its plan is complete. */
public interface TeachingOutlineModel {

    OutlineDraft organize(OutlineRequest request);

    default OutlineDraft organize(OutlineRequest request, ModelCallExecutor calls) {
        return calls.invoke(
                new ModelCall(
                        "organizeTeachingOutline",
                        estimateTokens(request),
                        "Rulebook lesson topics organized"),
                () -> organize(request),
                TeachingOutlineModel::estimateTokens);
    }

    interface ModelCallExecutor {
        <T> T invoke(ModelCall call, Supplier<T> invocation, ToIntFunction<T> outputTokens);

        default void recordRejection(String operation, String summary) {}

        static ModelCallExecutor direct() {
            return new ModelCallExecutor() {
                @Override
                public <T> T invoke(ModelCall call, Supplier<T> invocation, ToIntFunction<T> outputTokens) {
                    return invocation.get();
                }
            };
        }
    }

    record ModelCall(String operation, int estimatedInputTokens, String successSummary) {
        public ModelCall {
            if (operation == null || operation.isBlank()
                    || estimatedInputTokens < 1
                    || successSummary == null || successSummary.isBlank()) {
                throw new IllegalArgumentException("teaching outline model call is invalid");
            }
        }
    }

    private static int estimateTokens(Object value) {
        return Math.max(1, value == null ? 1 : (value.toString().length() + 3) / 4);
    }

    final class OutlineGenerationException extends RuntimeException {
        public OutlineGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A hard provider-capacity boundary, distinct from an Agent deciding that it has enough evidence. */
    final class OutlineCapacityExceededException extends RuntimeException {
        public OutlineCapacityExceededException(String message) {
            super(message);
        }
    }

    record OutlineRequest(
            List<PageInput> pages,
            List<PageImageInput> pageImages,
            String learningGoal,
            String modelConfigurationOwner) {
        public OutlineRequest {
            if (pages == null || pages.isEmpty() || pageImages == null) {
                throw new IllegalArgumentException("teaching outline request is invalid");
            }
            pages = List.copyOf(pages);
            pageImages = List.copyOf(pageImages);
            learningGoal = optional(learningGoal);
            modelConfigurationOwner = optional(modelConfigurationOwner);
        }

        public OutlineRequest(List<PageInput> pages, List<PageImageInput> pageImages, String modelConfigurationOwner) {
            this(pages, pageImages, null, modelConfigurationOwner);
        }

        public OutlineRequest(List<PageInput> pages, List<PageImageInput> pageImages) {
            this(pages, pageImages, null, null);
        }

        public OutlineRequest(List<PageInput> pages) {
            this(pages, List.of(), null, null);
        }

        public String learningGoalForPrompt() {
            return learningGoal == null ? "NO_ADDITIONAL_GOAL" : learningGoal;
        }
    }

    /** Full text is revealed only after a read action; availability is an observation, never a coverage verdict. */
    record PageInput(int pageNumber, String text, boolean available, boolean visualAidAvailable) {
        public PageInput {
            if (pageNumber < 1 || text == null || text.isBlank()) {
                throw new IllegalArgumentException("rulebook page input is invalid");
            }
            text = text.strip();
        }

        public PageInput(int pageNumber, String text) {
            this(pageNumber, text, true, false);
        }

        public PageInput(int pageNumber, String text, boolean available) {
            this(pageNumber, text, available, false);
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

    record OutlineDraft(
            String gameTitle,
            String premise,
            List<TopicDraft> topics,
            List<TopicDependencyDraft> topicDependencies,
            List<String> unresolvedTopics) {
        public OutlineDraft {
            if (gameTitle == null || gameTitle.isBlank() || premise == null || premise.isBlank()) {
                throw new IllegalArgumentException("teaching outline identity is invalid");
            }
            if (topics == null || topics.stream().anyMatch(java.util.Objects::isNull)
                    || topicDependencies == null || topicDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || unresolvedTopics == null
                    || unresolvedTopics.stream().anyMatch(topic -> topic == null || topic.isBlank())) {
                throw new IllegalArgumentException("teaching outline is invalid");
            }
            gameTitle = gameTitle.strip();
            premise = premise.strip();
            topics = List.copyOf(topics);
            topicDependencies = List.copyOf(topicDependencies);
            unresolvedTopics = unresolvedTopics.stream().map(String::strip).distinct().toList();
        }

        public OutlineDraft(String gameTitle, String premise, List<TopicDraft> topics) {
            this(gameTitle, premise, topics, List.of(), List.of());
        }
    }

    record TopicDependencyDraft(String prerequisiteTopicKey, String dependentTopicKey, String reason) {
        public TopicDependencyDraft {
            if (prerequisiteTopicKey == null || prerequisiteTopicKey.isBlank()
                    || dependentTopicKey == null || dependentTopicKey.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("teaching topic dependency is invalid");
            }
            prerequisiteTopicKey = prerequisiteTopicKey.strip();
            dependentTopicKey = dependentTopicKey.strip();
            reason = reason.strip();
        }
    }

    record TopicDraft(
            String key,
            String title,
            String objective,
            boolean visualEvidenceRecommended,
            List<Integer> sourcePageNumbers,
            List<Integer> visualSourcePageNumbers) {
        public TopicDraft {
            if (key == null || !key.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || sourcePageNumbers == null
                    || sourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)
                    || visualSourcePageNumbers == null
                    || visualSourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)
                    || (!visualEvidenceRecommended && !visualSourcePageNumbers.isEmpty())) {
                throw new IllegalArgumentException("teaching outline topic is invalid");
            }
            key = key.strip();
            title = title.strip();
            objective = objective.strip();
            sourcePageNumbers = sourcePageNumbers.stream().distinct().toList();
            visualSourcePageNumbers = visualSourcePageNumbers.stream().distinct().toList();
        }

        public TopicDraft(
                String key,
                String title,
                String objective,
                boolean visualEvidenceRecommended,
                List<Integer> sourcePageNumbers) {
            this(key, title, objective, visualEvidenceRecommended, sourcePageNumbers, List.of());
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
