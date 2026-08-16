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
        learningGoal = learningGoal == null || learningGoal.isBlank() ? null : learningGoal.strip();
        if (id == null || documentVersionId == null || createdAt == null) {
            throw new IllegalArgumentException("plan identity is required");
        }
        if (learningGoal != null && learningGoal.length() > 500) {
            throw new IllegalArgumentException("teaching learning goal is too long");
        }
        if (gameTitle == null || gameTitle.isBlank() || premise == null || premise.isBlank()
                || wholeGameContext == null || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("generated teaching plan identity is required");
        }
        gameTitle = gameTitle.strip();
        premise = premise.strip();
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
            String summary,
            List<GlobalConcept> concepts,
            List<TopicDependency> topicDependencies,
            boolean evidenceBound) {
        public WholeGameContext {
            if (summary == null || summary.isBlank() || summary.length() > 2_400
                    || concepts == null || concepts.size() > 32 || concepts.stream().anyMatch(java.util.Objects::isNull)
                    || topicDependencies == null || topicDependencies.size() > 32
                    || topicDependencies.stream().anyMatch(java.util.Objects::isNull)
                    || (evidenceBound && concepts.isEmpty())) {
                throw new IllegalArgumentException("whole-game teaching context is invalid");
            }
            summary = summary.strip();
            concepts = List.copyOf(concepts);
            topicDependencies = List.copyOf(topicDependencies);
        }

        public static WholeGameContext legacy(String premise) {
            String summary = premise == null || premise.isBlank()
                    ? "Legacy teaching plan without a source-bound whole-game context."
                    : premise;
            return new WholeGameContext(summary, List.of(), List.of(), false);
        }
    }

    public record GlobalConcept(
            String conceptId,
            String label,
            String explanation,
            List<String> sourceIdentifiers,
            List<Integer> sourcePageNumbers,
            List<String> relatedTopicKeys,
            List<String> prerequisiteConceptIds) {
        public GlobalConcept {
            if (conceptId == null || conceptId.isBlank() || conceptId.length() > 80
                    || label == null || label.isBlank() || label.length() > 160
                    || explanation == null || explanation.isBlank() || explanation.length() > 800
                    || sourceIdentifiers == null || sourceIdentifiers.isEmpty() || sourceIdentifiers.size() > 16
                    || sourceIdentifiers.stream().anyMatch(identifier -> identifier == null
                            || identifier.isBlank() || identifier.length() > 160)
                    || sourcePageNumbers == null || sourcePageNumbers.isEmpty() || sourcePageNumbers.size() > 10
                    || sourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)
                    || relatedTopicKeys == null || relatedTopicKeys.isEmpty() || relatedTopicKeys.size() > 16
                    || relatedTopicKeys.stream().anyMatch(topic -> topic == null
                            || topic.isBlank() || topic.length() > 100)
                    || prerequisiteConceptIds == null || prerequisiteConceptIds.size() > 16
                    || prerequisiteConceptIds.stream().anyMatch(concept -> concept == null
                            || concept.isBlank() || concept.length() > 80)) {
                throw new IllegalArgumentException("whole-game teaching concept is invalid");
            }
            conceptId = conceptId.strip();
            label = label.strip();
            explanation = explanation.strip();
            sourceIdentifiers = sourceIdentifiers.stream().map(String::strip).distinct().toList();
            sourcePageNumbers = sourcePageNumbers.stream().distinct().toList();
            relatedTopicKeys = relatedTopicKeys.stream().map(String::strip).distinct().toList();
            prerequisiteConceptIds = prerequisiteConceptIds.stream().map(String::strip).distinct().toList();
        }
    }

    public record TopicDependency(String prerequisiteTopicKey, String dependentTopicKey, String reason) {
        public TopicDependency {
            if (prerequisiteTopicKey == null || prerequisiteTopicKey.isBlank()
                    || prerequisiteTopicKey.length() > 100
                    || dependentTopicKey == null || dependentTopicKey.isBlank()
                    || dependentTopicKey.length() > 100
                    || reason == null || reason.isBlank() || reason.length() > 400) {
                throw new IllegalArgumentException("whole-game topic dependency is invalid");
            }
            prerequisiteTopicKey = prerequisiteTopicKey.strip();
            dependentTopicKey = dependentTopicKey.strip();
            reason = reason.strip();
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
            List<Integer> sourcePageNumbers) {
        public PlannedSection {
            if (position < 1
                    || topicKey == null || topicKey.isBlank() || topicKey.length() > 80
                    || title == null || title.isBlank() || title.length() > 160
                    || objective == null || objective.isBlank() || objective.length() > 600
                    || retrievalQueries == null || retrievalQueries.isEmpty() || retrievalQueries.size() > 16
                    || retrievalQueries.stream().anyMatch(query -> query == null || query.isBlank() || query.length() > 1_500)
                    || coverageTags == null
                    || sourcePageNumbers == null || sourcePageNumbers.size() > 5
                    || sourcePageNumbers.stream().anyMatch(page -> page == null || page < 1)) {
                throw new IllegalArgumentException("generated teaching topic is invalid");
            }
            topicKey = topicKey.strip();
            title = title.strip();
            objective = objective.strip();
            retrievalQueries = retrievalQueries.stream().map(String::strip).distinct().toList();
            coverageTags = coverageTags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::strip)
                    .distinct()
                    .toList();
            sourcePageNumbers = sourcePageNumbers.stream().distinct().toList();
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
            this(position, topicKey, title, objective, required, visualEvidenceRecommended, retrievalQueries, coverageTags, List.of());
        }
    }
}
