package com.rulepilot.ingestion.application;

import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.util.UUID;
import java.util.function.Consumer;

/** Cross-runtime progress fan-out; the durable progress store remains the reconnect authority. */
public interface ProcessingProgressNotifications {

    void publish(UUID versionId, ProgressSnapshot snapshot);

    Runnable subscribe(UUID versionId, Consumer<ProgressSnapshot> listener);
}
