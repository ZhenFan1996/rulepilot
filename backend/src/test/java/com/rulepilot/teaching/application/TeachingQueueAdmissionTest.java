package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

class TeachingQueueAdmissionTest {

    @Test
    void anExpiredAdmissionRecordsOneTimeoutAndSuppressesTheLateWorker() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        org.mockito.Mockito.when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    expiry.set(invocation.getArgument(0));
                    return future;
                });
        List<TeachingQueueAdmission.Expiry> recorded = new ArrayList<>();
        var admission = new TeachingQueueAdmission(
                scheduler,
                Duration.ofMinutes(2),
                UUID.randomUUID(),
                reason -> {
                    recorded.add(reason);
                    return true;
                });

        admission.scheduleExpiry();
        expiry.get().run();

        assertThat(admission.activate()).isFalse();
        assertThat(recorded).containsExactly(TeachingQueueAdmission.Expiry.TIMEOUT);
    }

    @Test
    void retriesAQueueTerminalRecordAfterTransientPersistenceFailure() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        org.mockito.Mockito.when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    expiry.set(invocation.getArgument(0));
                    return future;
                });
        TeachingTerminalRecovery recovery = mock(TeachingTerminalRecovery.class);
        AtomicInteger attempts = new AtomicInteger();
        var admission = new TeachingQueueAdmission(
                scheduler,
                recovery,
                Duration.ofMinutes(2),
                UUID.randomUUID(),
                reason -> attempts.incrementAndGet() == 2);

        admission.scheduleExpiry();
        expiry.get().run();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Boolean>> recorder = ArgumentCaptor.forClass(Supplier.class);
        verify(recovery).register(any(UUID.class), recorder.capture());
        assertThat(recorder.getValue().get()).isTrue();

        assertThat(attempts).hasValue(2);
        assertThat(admission.activate()).isFalse();
    }

    @Test
    void immediateRejectionCancelsTheTimerAndUsesTheQueueFullBoundary() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(ignored -> future);
        List<TeachingQueueAdmission.Expiry> recorded = new ArrayList<>();
        var admission = new TeachingQueueAdmission(
                scheduler,
                Duration.ofMinutes(2),
                UUID.randomUUID(),
                reason -> {
                    recorded.add(reason);
                    return true;
                });

        admission.scheduleExpiry();
        admission.reject();

        verify(future).cancel(false);
        assertThat(admission.activate()).isFalse();
        assertThat(recorded).containsExactly(TeachingQueueAdmission.Expiry.REJECTED);
    }

    @Test
    void aLateWorkerCannotBypassTheQueueDeadlineWhenTheSchedulerCallbackIsDelayed() throws InterruptedException {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(ignored -> future);
        List<TeachingQueueAdmission.Expiry> recorded = new ArrayList<>();
        var admission = new TeachingQueueAdmission(
                scheduler,
                Duration.ofNanos(1),
                UUID.randomUUID(),
                reason -> {
                    recorded.add(reason);
                    return true;
                });
        admission.scheduleExpiry();
        Thread.sleep(1);

        assertThat(admission.activate()).isFalse();
        assertThat(recorded).containsExactly(TeachingQueueAdmission.Expiry.TIMEOUT);
        verify(future).cancel(false);
    }
}
