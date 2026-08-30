package com.rulepilot.teaching.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
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
 * One bounded live-process owner reconciles explicitly retryable Teaching terminal writes after transient
 * persistence failures. Settled or permanently rejected intents never occupy recovery capacity.
 * A restart deliberately drops these closures: the earlier {@code InterruptedAssistantRunRecovery} then owns every
 * durable non-terminal run before any new handoff can be launched.
 */
@Component
@Profile("!test")
final class TeachingTerminalRecovery {

    static final int MAX_PENDING_INTENTS = 128;
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

    synchronized void register(UUID runId, Supplier<TeachingTerminalRecordResult> recorder) {
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
        List<Attempt> attempts;
        synchronized (this) {
            scheduled = null;
            long now = System.nanoTime();
            attempts = pending.entrySet().stream()
                    .filter(entry -> now - entry.getValue().nextAttemptNanos() >= 0)
                    .map(entry -> new Attempt(entry.getKey(), entry.getValue()))
                    .toList();
        }

        for (Attempt attempt : attempts) {
            TeachingTerminalRecordResult result;
            try {
                result = attempt.intent().recorder().get();
                if (result == null) {
                    result = TeachingTerminalRecordResult.SETTLED;
                    metrics.counter("rulepilot.teaching.terminal.recovery.invalid-result").increment();
                    LOGGER.error(
                            "Teaching terminal recorder returned no result for run {}; stopping permanent retry",
                            attempt.runId());
                }
            } catch (RuntimeException persistenceFailure) {
                result = TeachingTerminalRecordResult.RETRYABLE;
            }
            metrics.counter("rulepilot.teaching.terminal.recovery.attempts").increment();
            synchronized (this) {
                if (pending.get(attempt.runId()) != attempt.intent()) continue;
                if (result == TeachingTerminalRecordResult.SETTLED) {
                    pending.remove(attempt.runId());
                    metrics.counter("rulepilot.teaching.terminal.recovery.settled").increment();
                    if (attempt.intent().attempt() == 1) {
                        LOGGER.debug("Teaching terminal intent settled on first reconciliation for run {}", attempt.runId());
                    } else {
                        LOGGER.info(
                                "Teaching terminal intent settled after {} reconciliation attempts for run {}",
                                attempt.intent().attempt(),
                                attempt.runId());
                    }
                } else {
                    metrics.counter("rulepilot.teaching.terminal.recovery.retryable").increment();
                    long nextAttempt = attempt.intent().attempt() + 1;
                    pending.put(
                            attempt.runId(),
                            new PendingIntent(
                                    attempt.intent().recorder(),
                                    nextAttempt,
                                    nextAttemptNanos(attempt.runId(), nextAttempt)));
                    if (attempt.intent().attempt() == 7) {
                        LOGGER.error(
                                "Teaching terminal recovery entered low-frequency reconciliation for run {}; "
                                        + "it will continue until the terminal write settles or startup recovery takes ownership",
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

    private long nextAttemptNanos(UUID runId, long attempt) {
        return System.nanoTime() + retryDelay(runId, attempt, initialRetry, maxRetry).toNanos();
    }

    static Duration retryDelay(UUID runId, long attempt) {
        return retryDelay(runId, attempt, INITIAL_RETRY, MAX_RETRY);
    }

    private static Duration retryDelay(
            UUID runId,
            long attempt,
            Duration initialRetry,
            Duration maxRetry) {
        if (runId == null || attempt < 1) {
            throw new IllegalArgumentException("teaching terminal recovery attempt is invalid");
        }
        int exponent = (int) Math.min(attempt - 1, 6);
        Duration base = initialRetry.multipliedBy(1L << exponent);
        if (base.compareTo(maxRetry) > 0) base = maxRetry;
        int percentage = 80 + (int) Math.floorMod(runId.hashCode() * 31L + attempt, 21L);
        return base.multipliedBy(percentage).dividedBy(100);
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private record PendingIntent(
            Supplier<TeachingTerminalRecordResult> recorder,
            long attempt,
            long nextAttemptNanos) {}

    private record Attempt(UUID runId, PendingIntent intent) {}
}
