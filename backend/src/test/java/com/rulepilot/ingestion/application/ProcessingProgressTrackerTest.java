package com.rulepilot.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.ingestion.application.ProcessingProgressTracker.ProgressSnapshot;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ProcessingProgressTrackerTest {

    @Test
    void persistsBeforePublishingTheCrossRuntimeNotification() {
        ProcessingProgressStore store = mock(ProcessingProgressStore.class);
        ProcessingProgressNotifications notifications = mock(ProcessingProgressNotifications.class);
        UUID versionId = UUID.randomUUID();
        var tracker = new ProcessingProgressTracker(store, notifications);

        tracker.update(versionId, "RENDERING", 55, 4, 12, false);

        ProgressSnapshot expected = new ProgressSnapshot("RENDERING", 55, 4, 12, false);
        var ordered = org.mockito.Mockito.inOrder(store, notifications);
        ordered.verify(store).save(versionId, expected);
        ordered.verify(notifications).publish(versionId, expected);
    }

    @Test
    void aNotificationFailureDoesNotLoseTheDurableProgressSnapshot() {
        ProcessingProgressStore store = mock(ProcessingProgressStore.class);
        ProcessingProgressNotifications notifications = mock(ProcessingProgressNotifications.class);
        UUID versionId = UUID.randomUUID();
        var snapshot = new ProgressSnapshot("INDEXING", 95, 12, 12, false);
        doThrow(new IllegalStateException("redis pubsub unavailable"))
                .when(notifications)
                .publish(versionId, snapshot);
        when(store.find(versionId)).thenReturn(Optional.of(snapshot));
        var tracker = new ProcessingProgressTracker(store, notifications);

        tracker.update(versionId, "INDEXING", 95, 12, 12, false);

        assertThat(tracker.current(versionId)).contains(snapshot);
        verify(store).save(versionId, snapshot);
    }

    @Test
    void delegatesSubscriptionsToTheCrossRuntimeNotificationPort() {
        ProcessingProgressStore store = mock(ProcessingProgressStore.class);
        ProcessingProgressNotifications notifications = mock(ProcessingProgressNotifications.class);
        UUID versionId = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        Consumer<ProgressSnapshot> listener = mock(Consumer.class);
        Runnable unsubscribe = mock(Runnable.class);
        when(notifications.subscribe(versionId, listener)).thenReturn(unsubscribe);

        Runnable result = new ProcessingProgressTracker(store, notifications).subscribe(versionId, listener);

        assertThat(result).isSameAs(unsubscribe);
    }
}
