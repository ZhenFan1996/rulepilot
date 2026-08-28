package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.AgentWorkAlreadyClaimedException;
import com.rulepilot.teaching.TeachingOutlineModel.OutlineCapacityExceededException;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class TeachingPlanLauncher {

    static final String STARTUP_PHASE_DURATION_METRIC = "rulepilot.teaching.preparation.phase.duration";
    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingPlanLauncher.class);

    private final TeachingPlanService plans;
    private final IllustratedLessonLauncher lessons;
    private final AssistantRuns runs;
    private final TaskExecutor startupExecutor;
    private final TaskExecutor extendedPreparationExecutor;
    private final TaskScheduler admissionScheduler;
    private final TeachingTerminalRecovery terminalRecovery;
    private final Duration startupAdmissionTimeout;
    private final Duration extendedAdmissionTimeout;
    private final MeterRegistry metrics;

    @Autowired
    public TeachingPlanLauncher(
            TeachingPlanService plans,
            IllustratedLessonLauncher lessons,
            AssistantRuns runs,
            @Qualifier("teachingStartupExecutor") TaskExecutor startupExecutor,
            @Qualifier("teachingLongPreparationExecutor") TaskExecutor extendedPreparationExecutor,
            @Qualifier("teachingAdmissionScheduler") TaskScheduler admissionScheduler,
            TeachingTerminalRecovery terminalRecovery,
            @Value("${rulepilot.teaching.startup.admission-timeout:PT2M}") Duration startupAdmissionTimeout,
            @Value("${rulepilot.teaching.long-preparation.admission-timeout:PT30M}") Duration extendedAdmissionTimeout,
            MeterRegistry metrics) {
        this.plans = plans;
        this.lessons = lessons;
        this.runs = runs;
        this.startupExecutor = startupExecutor;
        this.extendedPreparationExecutor = extendedPreparationExecutor;
        this.admissionScheduler = admissionScheduler;
        this.terminalRecovery = terminalRecovery;
        this.startupAdmissionTimeout = requireAdmissionTimeout(startupAdmissionTimeout, "teaching startup");
        this.extendedAdmissionTimeout = requireAdmissionTimeout(extendedAdmissionTimeout, "long teaching preparation");
        this.metrics = metrics;
    }

    TeachingPlanLauncher(
            TeachingPlanService plans,
            IllustratedLessonLauncher lessons,
            AssistantRuns runs,
            TaskExecutor executor,
            MeterRegistry metrics) {
        this(
                plans,
                lessons,
                runs,
                executor,
                executor,
                null,
                null,
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                metrics);
    }

    TeachingPlanLauncher(
            TeachingPlanService plans,
            IllustratedLessonLauncher lessons,
            AssistantRuns runs,
            TaskExecutor startupExecutor,
            TaskExecutor extendedPreparationExecutor,
            TaskScheduler admissionScheduler,
            Duration startupAdmissionTimeout,
            Duration extendedAdmissionTimeout,
            MeterRegistry metrics) {
        this(
                plans,
                lessons,
                runs,
                startupExecutor,
                extendedPreparationExecutor,
                admissionScheduler,
                null,
                startupAdmissionTimeout,
                extendedAdmissionTimeout,
                metrics);
    }

    public synchronized PlanLaunch launch(
            UUID documentVersionId,
            String ownerUsername) {
        return launch(documentVersionId, null, ownerUsername);
    }

    public synchronized PlanLaunch launch(
            UUID documentVersionId,
            String learningGoal,
            String ownerUsername) {
        String normalizedLearningGoal = normalizeLearningGoal(learningGoal);
        var existing = runs.findLatestOwned(
                        AssistantRunMode.TEACHING_PREPARATION, documentVersionId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            return new PlanLaunch(run.id(), run.state(), true);
        }

        var workload = plans.preparationWorkload(documentVersionId, ownerUsername);
        RunSnapshot run = runs.start(
                AssistantRunMode.TEACHING_PREPARATION,
                documentVersionId,
                ownerUsername,
                workload);
        boolean extended = TeachingPlanService.requiresExtendedPreparationLane(workload);
        TaskExecutor admittedExecutor = extended ? extendedPreparationExecutor : startupExecutor;
        Duration admissionTimeout = extended ? extendedAdmissionTimeout : startupAdmissionTimeout;
        UUID activationId = UUID.randomUUID();
        var admission = new TeachingQueueAdmission(
                admissionScheduler,
                terminalRecovery,
                admissionTimeout,
                run.id(),
                expiry -> recordQueueTerminal(run, ownerUsername, activationId, expiry));
        try {
            admission.scheduleExpiry();
            admittedExecutor.execute(() -> {
                if (!admission.activate()) return;
                RunSnapshot claimed;
                try {
                    claimed = claimQueued(run, activationId);
                } catch (AgentWorkAlreadyClaimedException duplicateDelivery) {
                    LOGGER.info("Teaching preparation run {} was already claimed by another worker", run.id());
                    admission.finish();
                    return;
                } catch (AgentExecutionStoppedException stopped) {
                    LOGGER.info("Teaching preparation run {} stopped before its worker claim completed", run.id());
                    admission.finish();
                    return;
                } catch (RuntimeException workerAdmissionFailure) {
                    admission.failWorkerAdmission();
                    LOGGER.warn("Teaching preparation run {} could not acquire its durable worker lease", run.id());
                    return;
                }
                try {
                    prepare(claimed, documentVersionId, normalizedLearningGoal, ownerUsername);
                } finally {
                    admission.finish();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            admission.reject();
            throw schedulingFailure;
        }
        return new PlanLaunch(run.id(), run.state(), false);
    }

    private boolean recordQueueTerminal(
            RunSnapshot run,
            String ownerUsername,
            UUID activationId,
            TeachingQueueAdmission.Expiry expiry) {
        String errorCode = switch (expiry) {
            case TIMEOUT -> "TEACHING_PREPARATION_QUEUE_TIMEOUT";
            case REJECTED -> "TEACHING_PREPARATION_QUEUE_FULL";
            case WORKER_ADMISSION_FAILED -> "TEACHING_PREPARATION_WORKER_ADMISSION_FAILED";
        };
        String summary = switch (expiry) {
            case TIMEOUT -> "Teaching preparation waited too long for a worker and is safe to retry";
            case REJECTED -> "Teaching preparation could not enter its bounded worker queue";
            case WORKER_ADMISSION_FAILED -> "Teaching preparation could not acquire its durable worker lease";
        };
        return failQueuedRun(run, ownerUsername, activationId, errorCode, summary, expiry);
    }

    private boolean failQueuedRun(
            RunSnapshot queued,
            String ownerUsername,
            UUID activationId,
            String errorCode,
            String summary,
            TeachingQueueAdmission.Expiry expiry) {
        try {
            if (expiry == TeachingQueueAdmission.Expiry.WORKER_ADMISSION_FAILED) {
                runs.failQueuedIfUnactivatedOrOwned(
                        queued.id(), ownerUsername, activationId, errorCode, summary);
            } else {
                runs.failQueuedIfUnactivated(queued.id(), ownerUsername, errorCode, summary);
            }
            return true;
        } catch (RuntimeException concurrentChange) {
            LOGGER.warn("Teaching preparation run {} queue terminal state could not yet be recorded", queued.id());
            return false;
        }
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
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException(lane + " admission timeout must be positive and at most two hours");
        }
        return timeout;
    }

    private void prepare(
            RunSnapshot initial,
            UUID documentVersionId,
            String learningGoal,
            String ownerUsername) {
        RunSnapshot current = initial;
        PreparationFailurePhase failurePhase = null;
        try {
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.DOCUMENT_READINESS,
                    "Rulebook pages are ready for teaching");
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.LESSON_PLANNING,
                    "Reading rulebook pages and organizing the lesson");
            RunSnapshot planningRun = current;
            failurePhase = PreparationFailurePhase.PLAN_RESOLUTION;
            long planResolutionStartedAt = System.nanoTime();
            var planResolution = recordPhase("plan-resolution", planResolutionStartedAt, () -> {
                var existingPlan = plans.latest(documentVersionId, ownerUsername)
                        .filter(plan -> Objects.equals(plan.learningGoal(), learningGoal));
                if (existingPlan.isPresent()) {
                    plans.refreshVisualEvidence(documentVersionId, ownerUsername, planningRun.id());
                    return new PlanResolution(existingPlan.get(), true);
                }
                return new PlanResolution(plans.create(
                        documentVersionId,
                        learningGoal,
                        ownerUsername,
                        planningRun.id()), false);
            });
            long planResolutionNanos = System.nanoTime() - planResolutionStartedAt;
            TeachingPlan plan = planResolution.plan();
            // Preparation already owns the startup lane. Generate and persist the first cited section here before
            // handing the remaining chapters to the continuation lane, so old long-tail work cannot delay usefulness.
            failurePhase = PreparationFailurePhase.FIRST_SECTION_STARTUP;
            long firstSectionStartedAt = System.nanoTime();
            var lessonLaunch = recordPhase(
                    "first-section-startup",
                    firstSectionStartedAt,
                    () -> lessons.launchImmediately(plan, ownerUsername));
            long firstSectionNanos = System.nanoTime() - firstSectionStartedAt;
            LOGGER.info(
                    "Teaching startup lane finished: planResolutionMs={}, firstSectionStartupMs={}, planReused={}, lessonRunReused={}",
                    milliseconds(planResolutionNanos),
                    milliseconds(firstSectionNanos),
                    planResolution.reused(),
                    lessonLaunch.reused());
            failurePhase = null;
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.COMPLETED,
                    "Teaching plan is ready");
        } catch (AgentWorkAlreadyClaimedException duplicateDelivery) {
            LOGGER.info("Teaching preparation run {} was already claimed by another worker", initial.id());
        } catch (RuntimeException failure) {
            failIfActive(current, ownerUsername, failure, failurePhase);
            LOGGER.warn("Teaching preparation failed for document version {}", documentVersionId, failure);
        } catch (Error fatalFailure) {
            // A background worker must never disappear while its persisted run still says “organizing”. Record a
            // recoverable state for the player first, then rethrow so the executor and process diagnostics still see
            // a genuinely fatal JVM failure.
            failIfActive(current, ownerUsername, fatalFailure, null);
            LOGGER.error("Teaching preparation stopped by a fatal worker error for document version {}", documentVersionId, fatalFailure);
            throw fatalFailure;
        }
    }

    private void failIfActive(
            RunSnapshot lastKnown,
            String ownerUsername,
            Throwable failure,
            PreparationFailurePhase failurePhase) {
        runs.findOwned(lastKnown.id(), ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal())
                .ifPresent(run -> runs.fail(
                        run.id(),
                        run.revision(),
                        failureCode(failure, failurePhase),
                        "Teaching preparation failed safely"));
    }

    private String failureCode(Throwable failure, PreparationFailurePhase failurePhase) {
        var stopped = cause(failure, AgentExecutionStoppedException.class);
        if (stopped != null) {
            return "AGENT_" + stopped.reason().name();
        }
        var lessonStartupFailure = immediateLessonStartupFailure(failure);
        if (failurePhase == PreparationFailurePhase.FIRST_SECTION_STARTUP
                && lessonStartupFailure != null
                && lessonStartupFailure.failureCode() != null
                && !lessonStartupFailure.failureCode().isBlank()) {
            return lessonStartupFailure.failureCode();
        }
        if (causedBy(failure, TeachingPreparationStorageException.class)) {
            return "TEACHING_PREPARATION_STORAGE_FAILED";
        }
        if (causedBy(failure, OutlineCapacityExceededException.class)) {
            return "TEACHING_OUTLINE_CAPACITY_EXCEEDED";
        }
        if (causedBy(failure, IllegalArgumentException.class)) {
            return "TEACHING_PREPARATION_INVALID_PLAN";
        }
        return failurePhase == null
                ? "TEACHING_PREPARATION_FAILED"
                : failurePhase.failureCode;
    }

    private IllustratedLessonLauncher.ImmediateLessonStartupFailure immediateLessonStartupFailure(
            Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllustratedLessonLauncher.ImmediateLessonStartupFailure startupFailure) {
                return startupFailure;
            }
            if (current.getCause() == current) return null;
            current = current.getCause();
        }
        return null;
    }

    private boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        return cause(failure, type) != null;
    }

    private <T extends Throwable> T cause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            if (current.getCause() == current) return null;
            current = current.getCause();
        }
        return null;
    }

    private String normalizeLearningGoal(String learningGoal) {
        if (learningGoal == null || learningGoal.isBlank()) return null;
        return learningGoal.strip();
    }

    private <T> T recordPhase(String phase, long startedAt, Supplier<T> work) {
        try {
            return work.get();
        } finally {
            Timer.builder(STARTUP_PHASE_DURATION_METRIC)
                    .description("Teaching preparation phase duration")
                    .tag("phase", phase)
                    .register(metrics)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private long milliseconds(long duration) {
        return TimeUnit.NANOSECONDS.toMillis(duration);
    }

    private record PlanResolution(TeachingPlan plan, boolean reused) {}

    private enum PreparationFailurePhase {
        PLAN_RESOLUTION("TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED"),
        FIRST_SECTION_STARTUP("TEACHING_PREPARATION_FIRST_SECTION_STARTUP_FAILED");

        private final String failureCode;

        PreparationFailurePhase(String failureCode) {
            this.failureCode = failureCode;
        }
    }

    public record PlanLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}
}
