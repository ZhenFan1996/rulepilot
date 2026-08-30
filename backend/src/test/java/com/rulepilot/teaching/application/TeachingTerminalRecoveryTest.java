package com.rulepilot.teaching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class TeachingTerminalRecoveryTest {

    @Test
    void oneSharedOwnerRetriesAfterTheFastWindowWithoutGrowingPerRunTimers() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        List<Runnable> callbacks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return future;
        });
        var recovery = new TeachingTerminalRecovery(
                scheduler,
                new SimpleMeterRegistry(),
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        AtomicInteger attempts = new AtomicInteger();
        UUID runId = UUID.randomUUID();

        recovery.register(runId, () -> attempts.incrementAndGet() == 2
                ? TeachingTerminalRecordResult.SETTLED
                : TeachingTerminalRecordResult.RETRYABLE);
        callbacks.get(0).run();
        callbacks.get(1).run();

        assertThat(attempts).hasValue(2);
        assertThat(recovery.pendingCount()).isZero();
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void preservesTheEarliestTerminalCauseForTheSameRun() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        List<Runnable> callbacks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return future;
        });
        var recovery = new TeachingTerminalRecovery(
                scheduler,
                new SimpleMeterRegistry(),
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        AtomicInteger first = new AtomicInteger();
        AtomicInteger later = new AtomicInteger();
        UUID runId = UUID.randomUUID();

        recovery.register(runId, () -> {
            first.incrementAndGet();
            return TeachingTerminalRecordResult.SETTLED;
        });
        recovery.register(runId, () -> {
            later.incrementAndGet();
            return TeachingTerminalRecordResult.SETTLED;
        });
        callbacks.getFirst().run();

        assertThat(first).hasValue(1);
        assertThat(later).hasValue(0);
        assertThat(recovery.pendingCount()).isZero();
    }

    @Test
    void boundsTheNumberOfRetainedTerminalIntents() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        var recovery = new TeachingTerminalRecovery(
                scheduler,
                new SimpleMeterRegistry(),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5));

        for (int index = 0; index < TeachingTerminalRecovery.MAX_PENDING_INTENTS + 1; index++) {
            recovery.register(UUID.randomUUID(), () -> TeachingTerminalRecordResult.RETRYABLE);
        }

        assertThat(recovery.pendingCount()).isEqualTo(TeachingTerminalRecovery.MAX_PENDING_INTENTS);
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void keepsATransientlyFailingIntentUntilTheTerminalWriteSettles() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        List<Runnable> callbacks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return future;
        });
        var metrics = new SimpleMeterRegistry();
        var recovery = new TeachingTerminalRecovery(
                scheduler,
                metrics,
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        AtomicInteger attempts = new AtomicInteger();

        recovery.register(UUID.randomUUID(), () -> attempts.incrementAndGet() == 41
                ? TeachingTerminalRecordResult.SETTLED
                : TeachingTerminalRecordResult.RETRYABLE);
        for (int index = 0; index < 41; index++) {
            callbacks.get(index).run();
        }

        assertThat(attempts).hasValue(41);
        assertThat(recovery.pendingCount()).isZero();
        assertThat(metrics.counter("rulepilot.teaching.terminal.recovery.retryable").count())
                .isEqualTo(40);
        assertThat(metrics.counter("rulepilot.teaching.terminal.recovery.settled").count())
                .isEqualTo(1);
        verify(scheduler, times(41))
                .schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void settledPermanentIntentsReleaseEveryCapacitySlot() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        List<Runnable> callbacks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return future;
        });
        var metrics = new SimpleMeterRegistry();
        var recovery = new TeachingTerminalRecovery(
                scheduler,
                metrics,
                Duration.ofNanos(1),
                Duration.ofNanos(1));
        AtomicInteger attempts = new AtomicInteger();

        for (int index = 0; index < TeachingTerminalRecovery.MAX_PENDING_INTENTS; index++) {
            recovery.register(UUID.randomUUID(), () -> {
                attempts.incrementAndGet();
                return TeachingTerminalRecordResult.SETTLED;
            });
        }
        int callbackIndex = 0;
        while (recovery.pendingCount() > 0) {
            callbacks.get(callbackIndex++).run();
        }
        recovery.register(UUID.randomUUID(), () -> {
            attempts.incrementAndGet();
            return TeachingTerminalRecordResult.SETTLED;
        });
        callbacks.get(callbackIndex).run();

        assertThat(attempts).hasValue(TeachingTerminalRecovery.MAX_PENDING_INTENTS + 1);
        assertThat(recovery.pendingCount()).isZero();
        assertThat(metrics.counter("rulepilot.teaching.terminal.recovery.settled").count())
                .isEqualTo(TeachingTerminalRecovery.MAX_PENDING_INTENTS + 1);
    }

    @Test
    void usesCappedDeterministicJitterInsteadOfSynchronizingEveryRun() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000123");

        assertThat(TeachingTerminalRecovery.retryDelay(runId, 1))
                .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(5));
        assertThat(TeachingTerminalRecovery.retryDelay(runId, 7))
                .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
        assertThat(TeachingTerminalRecovery.retryDelay(runId, 100))
                .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
    }
}
