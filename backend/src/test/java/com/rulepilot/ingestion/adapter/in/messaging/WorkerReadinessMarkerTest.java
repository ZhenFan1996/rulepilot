package com.rulepilot.ingestion.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerReadinessMarkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesOnlyAfterApplicationReadinessAndClearsTheMarkerOnShutdownOrRestart() throws Exception {
        Path marker = temporaryDirectory.resolve("worker-ready");
        Files.writeString(marker, "stale\n");

        WorkerReadinessMarker readiness = new WorkerReadinessMarker(marker.toString());

        assertThat(marker).doesNotExist();
        readiness.ready();
        assertThat(marker).hasContent("ready\n");
        readiness.closed();
        assertThat(marker).doesNotExist();
    }
}
