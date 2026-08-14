package com.rulepilot.ingestion.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ProcessingProgressTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingProgressTracker.class);
    private static final Set<String> STAGES = Set.of(
            "UPLOADED",
            "VALIDATING",
            "EXTRACTING",
            "RENDERING",
            "STRUCTURING",
            "CHUNKING",
            "EMBEDDING",
            "INDEXING",
            "READY",
            "FAILED");
    private static final Set<String> TERMINAL_STAGES = Set.of("READY", "FAILED");

    private final ProcessingProgressStore store;
    private final ProcessingProgressNotifications notifications;

    public ProcessingProgressTracker(
            ProcessingProgressStore store, ProcessingProgressNotifications notifications) {
        this.store = store;
        this.notifications = notifications;
    }

    public void update(UUID versionId, String stage, int percentage, int processedPages, boolean complete) {
        update(versionId, stage, percentage, processedPages, processedPages, complete);
    }

    public void update(
            UUID versionId,
            String stage,
            int percentage,
            int processedPages,
            int totalPages,
            boolean complete) {
        ProgressSnapshot snapshot = new ProgressSnapshot(stage, percentage, processedPages, totalPages, complete);
        try {
            store.save(versionId, snapshot);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not persist processing progress for documentVersionId={}", versionId, exception);
        }
        try {
            notifications.publish(versionId, snapshot);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not publish processing progress for documentVersionId={}", versionId, exception);
        }
    }

    public Optional<ProgressSnapshot> current(UUID versionId) {
        try {
            return store.find(versionId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not read processing progress for documentVersionId={}", versionId, exception);
            return Optional.empty();
        }
    }

    public Runnable subscribe(UUID versionId, Consumer<ProgressSnapshot> listener) {
        return notifications.subscribe(versionId, listener);
    }

    public record ProgressSnapshot(String stage, int percentage, int processedPages, int totalPages, boolean complete) {
        public ProgressSnapshot {
            if (stage == null
                    || stage.isBlank()
                    || stage.length() > 64
                    || stage.indexOf('\t') >= 0
                    || stage.indexOf('\n') >= 0
                    || stage.indexOf('\r') >= 0
                    || !STAGES.contains(stage)
                    || percentage < 0
                    || percentage > 100
                    || processedPages < 0
                    || totalPages < processedPages
                    || complete && (!TERMINAL_STAGES.contains(stage) || percentage != 100)
                    || !complete && TERMINAL_STAGES.contains(stage)) {
                throw new IllegalArgumentException("processing progress is invalid");
            }
        }
    }
}
