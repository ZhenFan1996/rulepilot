package com.rulepilot.teaching.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/** Linearizes accepted Teaching work against its worker-admission timeout and durable terminal record. */
final class TeachingQueueAdmission {

    enum Expiry {
        TIMEOUT,
        REJECTED,
        WORKER_ADMISSION_FAILED
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingQueueAdmission.class);

    private final TaskScheduler scheduler;
    private final Duration timeout;
    private final long deadlineNanos;
    private final UUID runId;
    private final Function<Expiry, Boolean> terminalRecorder;
    private final TeachingTerminalRecovery terminalRecovery;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();

    TeachingQueueAdmission(
            TaskScheduler scheduler,
            Duration timeout,
            UUID runId,
            Function<Expiry, Boolean> terminalRecorder) {
        this(scheduler, null, timeout, runId, terminalRecorder);
    }

    TeachingQueueAdmission(
            TaskScheduler scheduler,
            TeachingTerminalRecovery terminalRecovery,
            Duration timeout,
            UUID runId,
            Function<Expiry, Boolean> terminalRecorder) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("teaching queue admission timeout must be positive");
        }
        if (runId == null || terminalRecorder == null) {
            throw new IllegalArgumentException("teaching queue admission identity is required");
        }
        this.scheduler = scheduler;
        this.timeout = timeout;
        this.deadlineNanos = System.nanoTime() + timeout.toNanos();
        this.runId = runId;
        this.terminalRecorder = terminalRecorder;
        this.terminalRecovery = terminalRecovery;
    }

    void scheduleExpiry() {
        if (scheduler == null) return;
        scheduled.set(scheduler.schedule(this::expire, Instant.now().plus(timeout)));
    }

    boolean activate() {
        while (state.get() == State.QUEUED) {
            if (System.nanoTime() - deadlineNanos >= 0) {
                if (state.compareAndSet(State.QUEUED, State.EXPIRED)) {
                    cancelScheduled();
                    recordOrReconcile(Expiry.TIMEOUT);
                }
                return false;
            }
            if (state.compareAndSet(State.QUEUED, State.ACTIVE)) {
                cancelScheduled();
                return true;
            }
        }
        return false;
    }

    void finish() {
        if (state.compareAndSet(State.ACTIVE, State.FINISHED)) cancelScheduled();
    }

    void reject() {
        cancelScheduled();
        if (state.compareAndSet(State.QUEUED, State.EXPIRED)) recordOrReconcile(Expiry.REJECTED);
    }

    void failWorkerAdmission() {
        cancelScheduled();
        if (state.compareAndSet(State.ACTIVE, State.EXPIRED)) {
            recordOrReconcile(Expiry.WORKER_ADMISSION_FAILED);
        }
    }

    void failBeforeDurableClaim() {
        cancelScheduled();
        if (state.compareAndSet(State.QUEUED, State.EXPIRED)) {
            recordOrReconcile(Expiry.WORKER_ADMISSION_FAILED);
        }
    }

    private void expire() {
        if (state.compareAndSet(State.QUEUED, State.EXPIRED)) recordOrReconcile(Expiry.TIMEOUT);
    }

    private void recordOrReconcile(Expiry expiry) {
        scheduled.set(null);
        if (record(expiry)) return;
        LOGGER.warn("Teaching queue terminal state could not yet be recorded for run {}", runId);
        if (terminalRecovery != null) {
            terminalRecovery.register(runId, () -> record(expiry));
        } else {
            // Unit-only construction has no live reconciliation owner. Production always injects the shared owner;
            // process-start recovery remains the last boundary if that owner is shutting down.
            LOGGER.info("Teaching queue terminal state will require process-start recovery for run {}", runId);
        }
    }

    private boolean record(Expiry expiry) {
        try {
            return Boolean.TRUE.equals(terminalRecorder.apply(expiry));
        } catch (RuntimeException persistenceFailure) {
            return false;
        }
    }

    private void cancelScheduled() {
        ScheduledFuture<?> future = scheduled.getAndSet(null);
        if (future != null) future.cancel(false);
    }

    private enum State {
        QUEUED,
        ACTIVE,
        FINISHED,
        EXPIRED
    }
}
