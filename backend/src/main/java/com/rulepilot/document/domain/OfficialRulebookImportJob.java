package com.rulepilot.document.domain;

import java.time.Instant;
import java.util.UUID;

public record OfficialRulebookImportJob(
        UUID id,
        String ownerUsername,
        UUID editionId,
        String title,
        DocumentSourceType sourceType,
        String sourceUrl,
        Stage stage,
        long downloadedBytes,
        Long totalBytes,
        UUID documentVersionId,
        boolean duplicate,
        String errorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public enum Stage {
        QUEUED,
        CONNECTING,
        DOWNLOADING,
        COMPRESSING,
        VERIFYING_FILE,
        SAVING,
        COMPLETED,
        FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    public OfficialRulebookImportJob {
        if (id == null || ownerUsername == null || ownerUsername.isBlank() || title == null || title.isBlank()
                || sourceType == null || sourceUrl == null || sourceUrl.isBlank() || stage == null
                || downloadedBytes < 0 || totalBytes != null && totalBytes <= 0
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("official rulebook import job is invalid");
        }
        ownerUsername = ownerUsername.strip();
        title = title.strip();
        sourceUrl = sourceUrl.strip();
        if (stage.terminal() != (completedAt != null)
                || (stage == Stage.FAILED) != (errorCode != null)
                || stage == Stage.COMPLETED && documentVersionId == null) {
            throw new IllegalArgumentException("official rulebook import terminal state is invalid");
        }
    }

    public static OfficialRulebookImportJob queued(
            UUID id,
            String ownerUsername,
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String sourceUrl,
            Instant now) {
        return new OfficialRulebookImportJob(
                id, ownerUsername, editionId, title, sourceType, sourceUrl, Stage.QUEUED,
                0, null, null, false, null, now, now, null);
    }
}
