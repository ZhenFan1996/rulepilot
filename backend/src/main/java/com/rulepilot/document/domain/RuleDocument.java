package com.rulepilot.document.domain;

import java.time.Instant;
import java.util.UUID;

public record RuleDocument(
        UUID id,
        UUID gameEditionId,
        String title,
        DocumentSourceType sourceType,
        String createdBy,
        Instant createdAt) {

    public RuleDocument {
        if (id == null || gameEditionId == null || sourceType == null || createdAt == null) {
            throw new IllegalArgumentException("document identity, edition, source type, and timestamp are required");
        }
        title = normalized(title, "title", 160);
        createdBy = normalized(createdBy, "creator", 120);
    }

    public static RuleDocument create(
            UUID gameEditionId, String title, DocumentSourceType sourceType, String createdBy, Instant now) {
        return new RuleDocument(UUID.randomUUID(), gameEditionId, title, sourceType, createdBy, now);
    }

    private static String normalized(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
