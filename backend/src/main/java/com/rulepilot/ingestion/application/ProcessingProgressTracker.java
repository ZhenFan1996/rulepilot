package com.rulepilot.ingestion.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProcessingProgressTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingProgressTracker.class);

    private final ProcessingProgressStore store;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Consumer<ProgressSnapshot>>> listeners =
            new ConcurrentHashMap<>();

    public ProcessingProgressTracker(ProcessingProgressStore store) {
        this.store = store;
    }

    public void update(UUID versionId, String stage, int percentage, int processedPages, boolean complete) {
        ProgressSnapshot snapshot = new ProgressSnapshot(stage, percentage, processedPages, complete);
        try {
            store.save(versionId, snapshot);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not persist processing progress for documentVersionId={}", versionId, exception);
        }
        listeners.getOrDefault(versionId, new CopyOnWriteArrayList<>())
                .forEach(listener -> listener.accept(snapshot));
        if (complete) {
            listeners.remove(versionId);
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
        listeners.computeIfAbsent(versionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> listeners.computeIfPresent(versionId, (ignored, existing) -> {
            existing.remove(listener);
            return existing.isEmpty() ? null : existing;
        });
    }

    public record ProgressSnapshot(String stage, int percentage, int processedPages, boolean complete) {
        public ProgressSnapshot {
            if (stage == null || stage.isBlank() || percentage < 0 || percentage > 100 || processedPages < 0) {
                throw new IllegalArgumentException("processing progress is invalid");
            }
        }
    }
}
