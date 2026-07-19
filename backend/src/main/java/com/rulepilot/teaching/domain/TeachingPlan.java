package com.rulepilot.teaching.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TeachingPlan(
        UUID id,
        UUID documentVersionId,
        int playerCount,
        int beginnerCount,
        int durationMinutes,
        String gameTitle,
        String premise,
        List<PlannedSection> sections,
        String createdBy,
        Instant createdAt) {

    public TeachingPlan {
        if (id == null || documentVersionId == null || createdAt == null) {
            throw new IllegalArgumentException("plan identity is required");
        }
        if (playerCount < 1 || playerCount > 20) {
            throw new IllegalArgumentException("player count must be between 1 and 20");
        }
        if (beginnerCount < 0 || beginnerCount > playerCount) {
            throw new IllegalArgumentException("beginner count must be between zero and player count");
        }
        if (durationMinutes < 2 || durationMinutes > 180) {
            throw new IllegalArgumentException("duration must be between 2 and 180 minutes");
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

    public record PlannedSection(
            int position,
            String topicKey,
            String title,
            String objective,
            boolean required,
            List<String> retrievalQueries,
            List<String> coverageTags) {
        public PlannedSection {
            if (position < 1
                    || topicKey == null || topicKey.isBlank() || topicKey.length() > 80
                    || title == null || title.isBlank() || title.length() > 160
                    || objective == null || objective.isBlank() || objective.length() > 600
                    || retrievalQueries == null || retrievalQueries.isEmpty() || retrievalQueries.size() > 5
                    || retrievalQueries.stream().anyMatch(query -> query == null || query.isBlank() || query.length() > 300)
                    || coverageTags == null) {
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
        }
    }
}
