package com.rulepilot.document.domain;

import java.time.Instant;
import java.net.URI;
import java.util.UUID;

public record RuleDocument(
        UUID id,
        UUID gameEditionId,
        String title,
        DocumentSourceType sourceType,
        String officialSourceUrl,
        String officialCoverUrl,
        String createdBy,
        Instant createdAt) {

    public RuleDocument {
        if (id == null || sourceType == null || createdAt == null) {
            throw new IllegalArgumentException("document identity, source type, and timestamp are required");
        }
        title = normalized(title, "title", 160);
        officialSourceUrl = officialSourceUrl(officialSourceUrl);
        officialCoverUrl = officialSourceUrl(officialCoverUrl);
        createdBy = normalized(createdBy, "creator", 120);
    }

    public RuleDocument(
            UUID id,
            UUID gameEditionId,
            String title,
            DocumentSourceType sourceType,
            String createdBy,
            Instant createdAt) {
        this(id, gameEditionId, title, sourceType, null, null, createdBy, createdAt);
    }

    public RuleDocument(
            UUID id,
            UUID gameEditionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String createdBy,
            Instant createdAt) {
        this(id, gameEditionId, title, sourceType, officialSourceUrl, null, createdBy, createdAt);
    }

    public static RuleDocument create(
            UUID gameEditionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String officialCoverUrl,
            String createdBy,
            Instant now) {
        return new RuleDocument(UUID.randomUUID(), gameEditionId, title, sourceType, officialSourceUrl, officialCoverUrl, createdBy, now);
    }

    public static RuleDocument create(
            UUID gameEditionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            String createdBy,
            Instant now) {
        return create(gameEditionId, title, sourceType, officialSourceUrl, null, createdBy, now);
    }

    public static RuleDocument create(
            UUID gameEditionId, String title, DocumentSourceType sourceType, String createdBy, Instant now) {
        return create(gameEditionId, title, sourceType, null, null, createdBy, now);
    }

    public RuleDocument assignTo(UUID editionId) {
        if (editionId == null) {
            throw new IllegalArgumentException("game edition is required");
        }
        if (gameEditionId != null && !gameEditionId.equals(editionId)) {
            throw new IllegalStateException("rule document is already assigned to another game edition");
        }
        return gameEditionId == null
                ? new RuleDocument(id, editionId, title, sourceType, officialSourceUrl, officialCoverUrl, createdBy, createdAt)
                : this;
    }

    public RuleDocument withOfficialSourceUrl(String sourceUrl) {
        String normalized = officialSourceUrl(sourceUrl);
        return java.util.Objects.equals(officialSourceUrl, normalized)
                ? this
                : new RuleDocument(id, gameEditionId, title, sourceType, normalized, officialCoverUrl, createdBy, createdAt);
    }

    public RuleDocument withOfficialCoverUrl(String coverUrl) {
        String normalized = officialSourceUrl(coverUrl);
        return java.util.Objects.equals(officialCoverUrl, normalized)
                ? this
                : new RuleDocument(id, gameEditionId, title, sourceType, officialSourceUrl, normalized, createdBy, createdAt);
    }

    private static String officialSourceUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 2_000) {
            throw new IllegalArgumentException("official source URL is too long");
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("official source URL is invalid", invalidUri);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("official source URL must be an HTTPS URL without credentials");
        }
        return uri.toASCIIString();
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
