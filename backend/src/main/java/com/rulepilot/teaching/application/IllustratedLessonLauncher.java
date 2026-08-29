package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentWorkAlreadyClaimedException;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class IllustratedLessonLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(IllustratedLessonLauncher.class);

    private final IllustratedLessonService lessons;
    private final AssistantRuns runs;
    private final TaskExecutor startupExecutor;
    private final TaskExecutor continuationExecutor;
    private final TaskScheduler admissionScheduler;
    private final TeachingTerminalRecovery terminalRecovery;
    private final Duration startupAdmissionTimeout;
    private final Duration continuationAdmissionTimeout;

    @Autowired
    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            @Qualifier("teachingStartupExecutor") TaskExecutor startupExecutor,
            @Qualifier("teachingGenerationExecutor") TaskExecutor continuationExecutor,
            @Qualifier("teachingAdmissionScheduler") TaskScheduler admissionScheduler,
            TeachingTerminalRecovery terminalRecovery,
            @Value("${rulepilot.teaching.startup.admission-timeout:PT2M}") Duration startupAdmissionTimeout,
            @Value("${rulepilot.teaching.continuation.admission-timeout:PT30M}") Duration continuationAdmissionTimeout) {
        this.lessons = lessons;
        this.runs = runs;
        this.startupExecutor = startupExecutor;
        this.continuationExecutor = continuationExecutor;
        this.admissionScheduler = admissionScheduler;
        this.terminalRecovery = terminalRecovery;
        this.startupAdmissionTimeout = requireAdmissionTimeout(startupAdmissionTimeout, "teaching startup");
        this.continuationAdmissionTimeout = requireAdmissionTimeout(
                continuationAdmissionTimeout, "teaching continuation");
    }

    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor executor) {
        this(lessons, runs, executor, executor, null, null, Duration.ofMinutes(2), Duration.ofMinutes(30));
    }

    IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor startupExecutor,
            TaskExecutor continuationExecutor) {
        this(
                lessons,
                runs,
                startupExecutor,
                continuationExecutor,
                null,
                null,
                Duration.ofMinutes(2),
                Duration.ofMinutes(30));
    }

    IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor startupExecutor,
            TaskExecutor continuationExecutor,
            TaskScheduler admissionScheduler,
            Duration startupAdmissionTimeout) {
        this(
                lessons,
                runs,
                startupExecutor,
                continuationExecutor,
                admissionScheduler,
                null,
                startupAdmissionTimeout,
                Duration.ofMinutes(30));
    }

    IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor startupExecutor,
            TaskExecutor continuationExecutor,
            TaskScheduler admissionScheduler,
            Duration startupAdmissionTimeout,
            Duration continuationAdmissionTimeout) {
        this(
                lessons,
                runs,
                startupExecutor,
                continuationExecutor,
                admissionScheduler,
                null,
                startupAdmissionTimeout,
                continuationAdmissionTimeout);
    }

    public synchronized LessonLaunch launch(UUID teachingPlanId, String ownerUsername) {
        var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, teachingPlanId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            return new LessonLaunch(run.id(), run.state(), true);
        }

        RunSnapshot run = lessons.begin(teachingPlanId, ownerUsername);
        UUID activationId = UUID.randomUUID();
        var admission = new TeachingQueueAdmission(
                admissionScheduler,
                terminalRecovery,
                startupAdmissionTimeout,
                run.id(),
                expiry -> recordQueueTerminal(run, ownerUsername, activationId, expiry));
        AtomicBoolean taskStarted = new AtomicBoolean();
        try {
            admission.scheduleExpiry();
            startupExecutor.execute(() -> {
                if (!admission.activate()) return;
                taskStarted.set(true);
                RunSnapshot claimed;
                try {
                    claimed = claimQueued(run, activationId);
                } catch (AgentWorkAlreadyClaimedException duplicateDelivery) {
                    LOGGER.info("Teaching run {} was already claimed by another worker", run.id());
                    admission.finish();
                    return;
                } catch (AgentExecutionStoppedException stopped) {
                    LOGGER.info("Teaching run {} stopped before its worker claim completed", run.id());
                    admission.finish();
                    return;
                } catch (RuntimeException workerAdmissionFailure) {
                    admission.failWorkerAdmission();
                    LOGGER.warn("Teaching run {} could not acquire its durable worker lease", run.id());
                    return;
                }
                try {
                    startAndScheduleContinuation(teachingPlanId, ownerUsername, claimed);
                } finally {
                    admission.finish();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            if (!taskStarted.get()) admission.reject();
            throw schedulingFailure;
        }
        return new LessonLaunch(run.id(), run.state(), false);
    }

    /** Runs on the dedicated startup lane already occupied by teaching-plan preparation. */
    LessonLaunch launchImmediately(TeachingPlan plan, String ownerUsername) {
        if (plan == null) throw new IllegalArgumentException("teaching plan is required");
        RunSnapshot run;
        boolean reused;
        synchronized (this) {
            var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, plan.id(), ownerUsername)
                    .map(AssistantRuns.RunDetails::run)
                    .filter(candidate -> !candidate.state().terminal());
            if (existing.isPresent()) {
                RunSnapshot active = existing.get();
                if (lessons.hasDurableCitedSection(plan.id())) {
                    return new LessonLaunch(active.id(), active.state(), true);
                }
                if (active.state() != AssistantRunState.RECEIVED) {
                    // A different worker already owns the teaching run. The plan is ready and the Teaching run is
                    // the durable source of truth for first-section progress; failing preparation here would create
                    // a contradictory red preparation beside a healthy in-flight lesson.
                    return new LessonLaunch(active.id(), active.state(), true);
                }
                run = active;
                reused = true;
            } else {
                run = lessons.begin(plan, ownerUsername);
                reused = false;
            }
        }
        UUID activationId = UUID.randomUUID();
        RunSnapshot claimed;
        try {
            claimed = claimQueued(run, activationId);
        } catch (AgentWorkAlreadyClaimedException duplicateDelivery) {
            if (lessons.hasDurableCitedSection(plan.id())) {
                return new LessonLaunch(run.id(), run.state(), true);
            }
            LOGGER.info("Teaching run {} was claimed while preparation handed off its first section", run.id());
            return new LessonLaunch(run.id(), run.state(), true);
        } catch (AgentExecutionStoppedException stopped) {
            throw new ImmediateLessonStartupFailure(
                    run.id(), persistedFailureCode(run.id(), ownerUsername, stopped), stopped);
        } catch (RuntimeException workerAdmissionFailure) {
            recoverFailedWorkerAdmission(run, ownerUsername, activationId);
            throw new ImmediateLessonStartupFailure(
                    run.id(), persistedFailureCode(run.id(), ownerUsername, workerAdmissionFailure), workerAdmissionFailure);
        }
        try {
            startAndScheduleContinuation(plan, ownerUsername, claimed);
        } catch (AgentWorkAlreadyClaimedException duplicateDelivery) {
            LOGGER.info("Teaching run {} observed duplicate work after owning its worker lease", run.id());
            return new LessonLaunch(run.id(), run.state(), true);
        } catch (RuntimeException startupFailure) {
            throw new ImmediateLessonStartupFailure(
                    run.id(), persistedFailureCode(run.id(), ownerUsername, startupFailure), startupFailure);
        }
        return new LessonLaunch(run.id(), run.state(), reused);
    }

    private String persistedFailureCode(
            UUID runId,
            String ownerUsername,
            RuntimeException startupFailure) {
        try {
            return runs.findOwned(runId, ownerUsername)
                    .map(AssistantRuns.RunDetails::run)
                    .filter(run -> run.state().terminal())
                    .map(RunSnapshot::lastErrorCode)
                    .filter(code -> code != null && !code.isBlank())
                    .orElse(null);
        } catch (RuntimeException trackingFailure) {
            startupFailure.addSuppressed(trackingFailure);
            return null;
        }
    }

    private TeachingTerminalRecordResult recordQueueTerminal(
            RunSnapshot queued,
            String ownerUsername,
            UUID activationId,
            TeachingQueueAdmission.Expiry expiry) {
        String errorCode = switch (expiry) {
            case TIMEOUT -> "TEACHING_QUEUE_TIMEOUT";
            case REJECTED -> "TEACHING_QUEUE_FULL";
            case WORKER_ADMISSION_FAILED -> "TEACHING_WORKER_ADMISSION_FAILED";
        };
        String summary = switch (expiry) {
            case TIMEOUT -> "Teaching generation waited too long for a worker and is safe to retry";
            case REJECTED -> "Teaching generation could not enter its bounded worker queue";
            case WORKER_ADMISSION_FAILED -> "Teaching generation could not acquire its durable worker lease";
        };
        try {
            if (expiry == TeachingQueueAdmission.Expiry.WORKER_ADMISSION_FAILED) {
                runs.failQueuedIfUnactivatedOrOwned(
                        queued.id(), ownerUsername, activationId, errorCode, summary);
            } else {
                runs.failQueuedIfUnactivated(queued.id(), ownerUsername, errorCode, summary);
            }
            return TeachingTerminalRecordResult.SETTLED;
        } catch (AgentExecutionStoppedException | AgentWorkAlreadyClaimedException settledRace) {
            LOGGER.info("Teaching run {} queue terminal intent already has a durable winner", queued.id());
            return TeachingTerminalRecordResult.SETTLED;
        } catch (IllegalArgumentException permanentFailure) {
            LOGGER.error(
                    "Teaching run {} queue terminal intent was permanently rejected",
                    queued.id(),
                    permanentFailure);
            return TeachingTerminalRecordResult.SETTLED;
        } catch (RuntimeException persistenceFailure) {
            LOGGER.warn("Teaching run {} queue terminal state could not yet be recorded", queued.id());
            return TeachingTerminalRecordResult.RETRYABLE;
        }
    }

    private void recoverFailedWorkerAdmission(RunSnapshot run, String ownerUsername, UUID activationId) {
        var recovery = new TeachingQueueAdmission(
                admissionScheduler,
                terminalRecovery,
                startupAdmissionTimeout,
                run.id(),
                expiry -> recordQueueTerminal(run, ownerUsername, activationId, expiry));
        recovery.failBeforeDurableClaim();
    }

    private RunSnapshot claimQueued(RunSnapshot queued, UUID activationId) {
        Instant admittedAt = Instant.now();
        try {
            return runs.activateQueued(queued, activationId, admittedAt);
        } catch (AgentWorkAlreadyClaimedException | AgentExecutionStoppedException definitive) {
            throw definitive;
        } catch (RuntimeException ambiguousResponse) {
            try {
                return runs.activateQueued(queued, activationId, admittedAt);
            } catch (RuntimeException retryFailure) {
                retryFailure.addSuppressed(ambiguousResponse);
                throw retryFailure;
            }
        }
    }

    private static Duration requireAdmissionTimeout(Duration timeout, String lane) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException(lane + " admission timeout must be positive and at most two hours");
        }
        return timeout;
    }

    private void startAndScheduleContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            RunSnapshot run) {
        var continuation = lessons.startGeneration(teachingPlanId, ownerUsername, run);
        scheduleContinuation(teachingPlanId, ownerUsername, continuation);
    }

    private void startAndScheduleContinuation(
            TeachingPlan plan,
            String ownerUsername,
            RunSnapshot run) {
        var continuation = lessons.startGeneration(plan, ownerUsername, run);
        scheduleContinuation(plan.id(), ownerUsername, continuation);
    }

    private void scheduleContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            IllustratedLessonService.GenerationContinuation continuation) {
        if (!continuation.hasRemainingWork()) {
            finishContinuation(teachingPlanId, ownerUsername, continuation);
            return;
        }
        AtomicBoolean taskStarted = new AtomicBoolean();
        long queuedAtNanos = System.nanoTime();
        var admission = new TeachingQueueAdmission(
                admissionScheduler,
                terminalRecovery,
                continuationAdmissionTimeout,
                continuation.run().id(),
                expiry -> recordContinuationTerminal(continuation, ownerUsername, expiry));
        try {
            admission.scheduleExpiry();
            continuationExecutor.execute(() -> {
                if (!admission.activate()) return;
                taskStarted.set(true);
                try {
                    Duration queueWait = Duration.ofNanos(Math.max(0L, System.nanoTime() - queuedAtNanos));
                    try {
                        lessons.admitContinuation(continuation, queueWait);
                    } catch (RuntimeException continuationAdmissionFailure) {
                        admission.failWorkerAdmission();
                        throw continuationAdmissionFailure;
                    }
                    finishContinuation(teachingPlanId, ownerUsername, continuation);
                } finally {
                    admission.finish();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            if (taskStarted.get()) throw schedulingFailure;
            admission.reject();
            // The first source-cited section is already durable. Keep preparation successful so the reader can open it;
            // the failed Teaching run truthfully exposes the retry instead of hiding useful content.
            LOGGER.warn(
                    "Teaching continuation could not be scheduled after the first cited section for plan {}",
                    teachingPlanId,
                    schedulingFailure);
        }
    }

    private TeachingTerminalRecordResult recordContinuationTerminal(
            IllustratedLessonService.GenerationContinuation continuation,
            String ownerUsername,
            TeachingQueueAdmission.Expiry expiry) {
        String errorCode = switch (expiry) {
            case TIMEOUT -> "TEACHING_CONTINUATION_QUEUE_TIMEOUT";
            case REJECTED -> "TEACHING_CONTINUATION_QUEUE_FULL";
            case WORKER_ADMISSION_FAILED -> "TEACHING_CONTINUATION_ADMISSION_FAILED";
        };
        String summary = switch (expiry) {
            case TIMEOUT -> "The first cited section is readable but remaining teaching work waited too long for a worker";
            case REJECTED -> "The first cited section is readable but remaining teaching work could not enter its bounded worker queue";
            case WORKER_ADMISSION_FAILED -> "The first cited section is readable but remaining teaching work could not acquire its worker lease";
        };
        try {
            runs.failActiveIfOwned(continuation.run().id(), ownerUsername, errorCode, summary);
            return TeachingTerminalRecordResult.SETTLED;
        } catch (AgentExecutionStoppedException | AgentWorkAlreadyClaimedException settledRace) {
            LOGGER.info(
                    "Teaching continuation run {} queue terminal intent already has a durable winner",
                    continuation.run().id());
            return TeachingTerminalRecordResult.SETTLED;
        } catch (IllegalArgumentException permanentFailure) {
            LOGGER.error(
                    "Teaching continuation run {} queue terminal intent was permanently rejected",
                    continuation.run().id(),
                    permanentFailure);
            return TeachingTerminalRecordResult.SETTLED;
        } catch (RuntimeException persistenceFailure) {
            LOGGER.warn(
                    "Teaching continuation run {} queue terminal state could not yet be recorded",
                    continuation.run().id());
            return TeachingTerminalRecordResult.RETRYABLE;
        }
    }

    private void finishContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            IllustratedLessonService.GenerationContinuation continuation) {
        var outcome = lessons.continueGeneration(continuation);
        if (outcome.complete()) {
            lessons.finish(outcome);
        } else {
            scheduleContinuation(teachingPlanId, ownerUsername, outcome.continuation());
        }
    }

    static final class ImmediateLessonStartupFailure extends RuntimeException {

        private final UUID assistantRunId;
        private final String failureCode;

        ImmediateLessonStartupFailure(UUID assistantRunId, String failureCode, RuntimeException cause) {
            super("Immediate teaching startup failed for run " + assistantRunId, cause);
            this.assistantRunId = assistantRunId;
            this.failureCode = failureCode;
        }

        UUID assistantRunId() {
            return assistantRunId;
        }

        String failureCode() {
            return failureCode;
        }
    }

    public record LessonLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}
}
