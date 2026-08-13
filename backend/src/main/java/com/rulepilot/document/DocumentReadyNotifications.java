package com.rulepilot.document;

import java.time.Instant;
import java.util.UUID;

/**
 * Best-effort wake-up after a document has durably reached READY.
 *
 * <p>The database status and handoff claim remain authoritative. Losing or repeating this notification is safe
 * because the API also reconciles persisted waiting work on a bounded schedule.</p>
 */
public interface DocumentReadyNotifications {

    void publish(UUID documentVersionId, Instant readyAt);
}
