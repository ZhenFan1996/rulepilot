package com.rulepilot.teaching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TeachingPlan(
        UUID id,
        UUID documentVersionId,
        String learningGoal,
        String gameTitle,
        String premise,
        WholeGameContext wholeGameContext,
        List<PlannedSection> sections,
        String createdBy,
        Instant createdAt) {

    public TeachingPlan {
        learningGoal = learningGoal == null || learningGoal.isBlank() ? null : learningGoal;
        if (id == null || documentVersionId == null || createdAt == null) {
            throw new IllegalArgumentException("plan identity is required");
        }
        if (gameTitle == null || gameTitle.isBlank() || premise == null || premise.isBlank()
                || wholeGameContext == null || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("generated teaching plan identity is required");
        }
        sections = List.copyOf(sections);
        for (int index = 0; index < sections.size(); index++) {
            if (sections.get(index).position() != index + 1) {
                throw new IllegalArgumentException("section positions must be contiguous");
            }
        }
    }

    public TeachingPlan(
            UUID id,
            UUID documentVersionId,
            String learningGoal,
            String gameTitle,
            String premise,
            List<PlannedSection> sections,
            String createdBy,
            Instant createdAt) {
        this(
                id,
                documentVersionId,
                learningGoal,
                gameTitle,
                premise,
                WholeGameContext.legacy(premise),
                sections,
                createdBy,
                createdAt);
    }

    public TeachingPlan(
            UUID id,
            UUID documentVersionId,
            String gameTitle,
            String premise,
            List<PlannedSection> sections,
            String createdBy,
            Instant createdAt) {
        this(
                id,
                documentVersionId,
                null,
                gameTitle,
                premise,
                WholeGameContext.legacy(premise),
                sections,
                createdBy,
                createdAt);
    }

    public record WholeGameContext(
            List<TopicDependency> topicDependencies,
            List<String> unresolvedTopics) {
        public WholeGameContext {
            topicDependencies = topicDependencies == null ? List.of() : List.copyOf(topicDependencies);
            unresolvedTopics = unresolvedTopics == null ? List.of() : unresolvedTopics;
            if (topicDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || unresolvedTopics.stream().anyMatch(topic -> topic == null || topic.isBlank())) {
                throw new IllegalArgumentException("whole-game teaching context is invalid");
            }
            unresolvedTopics = unresolvedTopics.stream().map(String::strip).distinct().toList();
        }

        public static WholeGameContext legacy(String premise) {
            return new WholeGameContext(List.of(), List.of());
        }
    }

    public record TopicDependency(String prerequisiteTopicKey, String dependentTopicKey, String reason) {
        public TopicDependency {
            if (prerequisiteTopicKey == null || prerequisiteTopicKey.isBlank()
                    || dependentTopicKey == null || dependentTopicKey.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("whole-game topic dependency is invalid");
            }
        }
    }

    public record PlannedSection(
            int position,
            String topicKey,
            String title,
            String objective,
            boolean required,
            boolean visualEvidenceRecommended,
            List<String> retrievalQueries,
            List<String> coverageTags,
            List<Integer> sourcePageNumbers,
            List<Integer> visualSourcePageNumbers) {
        public PlannedSection {
            if (position < 1
                    || topicKey == null || topicKey.isBlank()
                    || title == null || title.isBlank()
                    || objective == null || objective.isBlank()
                    || retrievalQueries == null || retrievalQueries.isEmpty()
                    || retrievalQueries.stream().anyMatch(query -> query == null || query.isBlank())
                    || coverageTags == null
                    || sourcePageNumbers == null
                    || sourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)
                    || visualSourcePageNumbers == null
                    || visualSourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)
                    || (!visualEvidenceRecommended && !visualSourcePageNumbers.isEmpty())) {
                throw new IllegalArgumentException("generated teaching topic is invalid");
            }
            retrievalQueries = retrievalQueries.stream().distinct().toList();
            coverageTags = coverageTags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .distinct()
                    .toList();
            sourcePageNumbers = sourcePageNumbers.stream().distinct().toList();
            visualSourcePageNumbers = visualSourcePageNumbers.stream().distinct().toList();
        }

        public PlannedSection(
                int position,
                String topicKey,
                String title,
                String objective,
                boolean required,
                boolean visualEvidenceRecommended,
                List<String> retrievalQueries,
                List<String> coverageTags,
                List<Integer> sourcePageNumbers) {
            this(
                    position,
                    topicKey,
                    title,
                    objective,
                    required,
                    visualEvidenceRecommended,
                    retrievalQueries,
                    coverageTags,
                    sourcePageNumbers,
                    List.of());
        }

        public PlannedSection(
                int position,
                String topicKey,
                String title,
                String objective,
                boolean required,
                boolean visualEvidenceRecommended,
                List<String> retrievalQueries,
                List<String> coverageTags) {
            this(position, topicKey, title, objective, required, visualEvidenceRecommended, retrievalQueries, coverageTags, List.of(), List.of());
        }
    }
}
