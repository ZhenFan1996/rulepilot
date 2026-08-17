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
        Instant downloadCompletedAt,
        TeachingHandoff teachingHandoff,
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
                || teachingHandoff == null || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || downloadCompletedAt != null
                        && (downloadCompletedAt.isBefore(createdAt) || downloadCompletedAt.isAfter(updatedAt))) {
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
        return queued(id, ownerUsername, editionId, title, sourceType, sourceUrl, false, null, now);
    }

    public static OfficialRulebookImportJob queued(
            UUID id,
            String ownerUsername,
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String sourceUrl,
            boolean startTeaching,
            String learningGoal,
            Instant now) {
        return new OfficialRulebookImportJob(
                id, ownerUsername, editionId, title, sourceType, sourceUrl, Stage.QUEUED,
                0, null, null, false, null,
                null,
                startTeaching ? TeachingHandoff.requested(learningGoal, now) : TeachingHandoff.notRequested(),
                now, now, null);
    }

    public OfficialRulebookImportJob(
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
        this(
                id, ownerUsername, editionId, title, sourceType, sourceUrl, stage,
                downloadedBytes, totalBytes, documentVersionId, duplicate, errorCode,
                null, TeachingHandoff.notRequested(), createdAt, updatedAt, completedAt);
    }

    public OfficialRulebookImportJob(
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
            TeachingHandoff teachingHandoff,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
        this(
                id, ownerUsername, editionId, title, sourceType, sourceUrl, stage,
                downloadedBytes, totalBytes, documentVersionId, duplicate, errorCode,
                null, teachingHandoff, createdAt, updatedAt, completedAt);
    }

    public enum TeachingHandoffState {
        NOT_REQUESTED,
        WAITING_FOR_DOCUMENT,
        LAUNCHING,
        LAUNCHED,
        FAILED
    }

    public record TeachingHandoff(
            TeachingHandoffState state,
            String learningGoal,
            UUID preparationRunId,
            String errorCode,
            Instant updatedAt) {

        public TeachingHandoff {
            if (state == null) throw new IllegalArgumentException("teaching handoff state is required");
            if (learningGoal != null) {
                learningGoal = learningGoal.strip();
                if (learningGoal.isBlank()) {
                    throw new IllegalArgumentException("teaching learning goal is invalid");
                }
            }
            boolean notRequested = state == TeachingHandoffState.NOT_REQUESTED;
            boolean launched = state == TeachingHandoffState.LAUNCHED;
            boolean failed = state == TeachingHandoffState.FAILED;
            if (notRequested != (updatedAt == null)
                    || notRequested && (learningGoal != null || preparationRunId != null || errorCode != null)
                    || launched != (preparationRunId != null)
                    || failed != (errorCode != null)) {
                throw new IllegalArgumentException("teaching handoff shape is invalid");
            }
        }

        public static TeachingHandoff notRequested() {
            return new TeachingHandoff(TeachingHandoffState.NOT_REQUESTED, null, null, null, null);
        }

        public static TeachingHandoff requested(String learningGoal, Instant now) {
            return new TeachingHandoff(
                    TeachingHandoffState.WAITING_FOR_DOCUMENT, normalizeGoal(learningGoal), null, null, now);
        }

        private static String normalizeGoal(String learningGoal) {
            if (learningGoal == null || learningGoal.isBlank()) return null;
            return learningGoal.strip();
        }
    }
}
