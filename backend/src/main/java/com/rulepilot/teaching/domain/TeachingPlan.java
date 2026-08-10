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
                || createdBy == null || createdBy.isBlank()) {
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
                sections,
                createdBy,
                createdAt);
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
                    || retrievalQueries == null || retrievalQueries.isEmpty() || retrievalQueries.size() > 5
                    || retrievalQueries.stream().anyMatch(query -> query == null || query.isBlank() || query.length() > 300)
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
