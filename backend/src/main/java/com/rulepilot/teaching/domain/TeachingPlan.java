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
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("creator is required");
        }
        sections = List.copyOf(sections);
        for (int index = 0; index < sections.size(); index++) {
            if (sections.get(index).position() != index + 1) {
                throw new IllegalArgumentException("section positions must be contiguous");
            }
        }
    }

    public boolean complete() {
        return sections.stream().filter(PlannedSection::required).allMatch(PlannedSection::evidenceAvailable);
    }

    public record PlannedSection(
            int position,
            TeachingSectionType type,
            boolean required,
            boolean evidenceAvailable,
            List<Integer> sourcePages,
            List<TeachingSectionType> dependencies) {
        public PlannedSection {
            if (position < 1 || type == null) {
                throw new IllegalArgumentException("section position and type are required");
            }
            sourcePages = List.copyOf(sourcePages);
            dependencies = List.copyOf(dependencies);
        }
    }
}
