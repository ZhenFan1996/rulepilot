package com.rulepilot.teaching.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * One bounded live-process owner reconciles Teaching terminal writes after transient persistence failures.
 * A restart deliberately drops these closures: the earlier {@code InterruptedAssistantRunRecovery} then owns every
 * durable non-terminal run before any new handoff can be launched.
 */
@Component
@Profile("!test")
final class TeachingTerminalRecovery {

    static final int MAX_PENDING_INTENTS = 128;
    private static final int MAX_ATTEMPTS_PER_SWEEP = 32;
    static final int MAX_RECOVERY_ATTEMPTS = 30;
    private static final Duration INITIAL_RETRY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY = Duration.ofMinutes(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingTerminalRecovery.class);

    private final TaskScheduler scheduler;
    private final MeterRegistry metrics;
    private final Duration initialRetry;
    private final Duration maxRetry;
    private final Map<UUID, PendingIntent> pending = new ConcurrentHashMap<>();
    private ScheduledFuture<?> scheduled;

    @Autowired
    TeachingTerminalRecovery(
            @Qualifier("teachingTerminalRecoveryScheduler") TaskScheduler scheduler,
            MeterRegistry metrics) {
        this(scheduler, metrics, INITIAL_RETRY, MAX_RETRY);
    }

    TeachingTerminalRecovery(
            TaskScheduler scheduler,
            MeterRegistry metrics,
            Duration initialRetry,
            Duration maxRetry) {
        if (scheduler == null
                || metrics == null
                || initialRetry == null
                || maxRetry == null
                || initialRetry.isNegative()
                || initialRetry.isZero()
                || maxRetry.compareTo(initialRetry) < 0) {
            throw new IllegalArgumentException("teaching terminal recovery configuration is invalid");
        }
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.initialRetry = initialRetry;
        this.maxRetry = maxRetry;
        Gauge.builder("rulepilot.teaching.terminal.recovery.pending", pending, Map::size)
                .description("Teaching terminal intents awaiting bounded live-process reconciliation")
                .register(metrics);
    }

    synchronized void register(UUID runId, Supplier<Boolean> recorder) {
        if (runId == null || recorder == null) {
            throw new IllegalArgumentException("teaching terminal recovery intent is invalid");
        }
        if (!pending.containsKey(runId) && pending.size() >= MAX_PENDING_INTENTS) {
            metrics.counter("rulepilot.teaching.terminal.recovery.capacity.rejected").increment();
            LOGGER.error(
                    "Teaching terminal recovery capacity is exhausted; run {} requires process-start reconciliation",
                    runId);
            return;
        }
        pending.putIfAbsent(runId, new PendingIntent(recorder, 1, nextAttemptNanos(runId, 1)));
        ensureScheduled();
    }

    private void reconcile() {
        List<Attempt> attempts = new ArrayList<>();
        synchronized (this) {
            scheduled = null;
            long now = System.nanoTime();
            for (Map.Entry<UUID, PendingIntent> entry : pending.entrySet()) {
                if (attempts.size() >= MAX_ATTEMPTS_PER_SWEEP) break;
                if (now - entry.getValue().nextAttemptNanos() >= 0) {
                    attempts.add(new Attempt(entry.getKey(), entry.getValue()));
                }
            }
        }

        for (Attempt attempt : attempts) {
            boolean recorded;
            try {
                recorded = Boolean.TRUE.equals(attempt.intent().recorder().get());
            } catch (RuntimeException persistenceFailure) {
                recorded = false;
            }
            metrics.counter("rulepilot.teaching.terminal.recovery.attempts").increment();
            synchronized (this) {
                if (pending.get(attempt.runId()) != attempt.intent()) continue;
                if (recorded) {
                    pending.remove(attempt.runId());
                    LOGGER.info(
                            "Teaching terminal state recovered after {} reconciliation attempts for run {}",
                            attempt.intent().attempt(),
                            attempt.runId());
                } else if (attempt.intent().attempt() >= MAX_RECOVERY_ATTEMPTS) {
                    pending.remove(attempt.runId());
                    metrics.counter("rulepilot.teaching.terminal.recovery.exhausted").increment();
                    LOGGER.error(
                            "Teaching terminal recovery exhausted {} bounded attempts for run {}; "
                                    + "process-start recovery must reconcile it",
                            MAX_RECOVERY_ATTEMPTS,
                            attempt.runId());
                } else {
                    int nextAttempt = attempt.intent().attempt() + 1;
                    pending.put(
                            attempt.runId(),
                            new PendingIntent(
                                    attempt.intent().recorder(),
                                    nextAttempt,
                                    nextAttemptNanos(attempt.runId(), nextAttempt)));
                    if (attempt.intent().attempt() == 7) {
                        LOGGER.error(
                                "Teaching terminal recovery entered low-frequency reconciliation for run {}",
                                attempt.runId());
                    }
                }
            }
        }

        synchronized (this) {
            ensureScheduled();
        }
    }

    private void ensureScheduled() {
        if (scheduled != null || pending.isEmpty()) return;
        long now = System.nanoTime();
        long earliest = pending.values().stream()
                .mapToLong(PendingIntent::nextAttemptNanos)
                .min()
                .orElse(now);
        long delayNanos = Math.max(1L, earliest - now);
        try {
            scheduled = scheduler.schedule(this::reconcile, Instant.now().plusNanos(delayNanos));
        } catch (RuntimeException schedulerStopping) {
            scheduled = null;
            LOGGER.info("Teaching terminal reconciliation will transfer to process-start recovery");
        }
    }

    private long nextAttemptNanos(UUID runId, int attempt) {
        return System.nanoTime() + retryDelay(runId, attempt, initialRetry, maxRetry).toNanos();
    }

    static Duration retryDelay(UUID runId, int attempt) {
        return retryDelay(runId, attempt, INITIAL_RETRY, MAX_RETRY);
    }

    private static Duration retryDelay(
            UUID runId,
            int attempt,
            Duration initialRetry,
            Duration maxRetry) {
        if (runId == null || attempt < 1) {
            throw new IllegalArgumentException("teaching terminal recovery attempt is invalid");
        }
        int exponent = Math.min(attempt - 1, 6);
        Duration base = initialRetry.multipliedBy(1L << exponent);
        if (base.compareTo(maxRetry) > 0) base = maxRetry;
        int percentage = 80 + Math.floorMod(runId.hashCode() * 31 + attempt, 21);
        return base.multipliedBy(percentage).dividedBy(100);
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private record PendingIntent(Supplier<Boolean> recorder, int attempt, long nextAttemptNanos) {}

    private record Attempt(UUID runId, PendingIntent intent) {}
}
