package com.rulepilot.ingestion.adapter.in.messaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Exposes process-local readiness for the non-web worker container. */
@Component
@Profile("worker")
final class WorkerReadinessMarker {

    private static final Logger log = LoggerFactory.getLogger(WorkerReadinessMarker.class);
    private final Path marker;

    WorkerReadinessMarker(
            @Value("${rulepilot.runtime.worker-readiness-file:/tmp/rulepilot-worker-ready}") String marker) {
        this.marker = Path.of(marker).toAbsolutePath().normalize();
        try {
            Files.deleteIfExists(this.marker);
        } catch (IOException exception) {
            throw new IllegalStateException("worker readiness marker could not be reset", exception);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        Path parent = marker.getParent();
        if (parent == null) throw new IllegalStateException("worker readiness marker needs a parent directory");
        Path temporary = parent.resolve(marker.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, "ready\n");
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("worker readiness marker could not be published", exception);
        }
    }

    @EventListener(ContextClosedEvent.class)
    void closed() {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException exception) {
            log.warn("Worker readiness marker could not be removed", exception);
        }
    }
}
