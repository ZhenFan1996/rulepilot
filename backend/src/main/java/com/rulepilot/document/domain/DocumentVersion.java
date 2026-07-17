package com.rulepilot.document.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersion(
        UUID id,
        UUID documentId,
        int versionNumber,
        String originalFilename,
        String objectKey,
        String checksum,
        long size,
        String contentType,
        ProcessingStatus status,
        Instant createdAt) {

    public DocumentVersion {
        if (id == null || documentId == null || createdAt == null || status == null) {
            throw new IllegalArgumentException("version identity, document, status, and timestamp are required");
        }
        if (versionNumber < 1 || size < 1 || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("document version metadata is invalid");
        }
        if (originalFilename == null || originalFilename.isBlank() || originalFilename.length() > 255
                || objectKey == null || objectKey.isBlank()
                || contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("document file metadata is required");
        }
    }

    public static DocumentVersion create(
            UUID documentId,
            int versionNumber,
            String originalFilename,
            String objectKey,
            String checksum,
            long size,
            String contentType,
            Instant now) {
        return new DocumentVersion(
                UUID.randomUUID(), documentId, versionNumber, originalFilename, objectKey, checksum,
                size, contentType, ProcessingStatus.UPLOADED, now);
    }

    public DocumentVersion transitionTo(ProcessingStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("cannot transition document from " + status + " to " + next);
        }
        return new DocumentVersion(
                id, documentId, versionNumber, originalFilename, objectKey, checksum, size, contentType, next, createdAt);
    }
}
