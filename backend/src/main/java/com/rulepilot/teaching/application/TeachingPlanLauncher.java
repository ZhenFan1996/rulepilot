package com.rulepilot.teaching.application;

import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.agenttrace.PrivateAgentTraceService;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class TeachingPlanLauncher {

    static final String STARTUP_PHASE_DURATION_METRIC = "rulepilot.teaching.preparation.phase.duration";
    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingPlanLauncher.class);

    private final TeachingPlanService plans;
    private final IllustratedLessonLauncher lessons;
    private final AssistantRuns runs;
    private final TaskExecutor executor;
    private final MeterRegistry metrics;
    private final Optional<PrivateAgentTraceService> privateTraces;

    @Autowired
    public TeachingPlanLauncher(
            TeachingPlanService plans,
            IllustratedLessonLauncher lessons,
            AssistantRuns runs,
            @Qualifier("teachingStartupExecutor") TaskExecutor executor,
            MeterRegistry metrics,
            Optional<PrivateAgentTraceService> privateTraces) {
        this.plans = plans;
        this.lessons = lessons;
        this.runs = runs;
        this.executor = executor;
        this.metrics = metrics;
        this.privateTraces = privateTraces == null ? Optional.empty() : privateTraces;
    }

    public TeachingPlanLauncher(
            TeachingPlanService plans,
            IllustratedLessonLauncher lessons,
            AssistantRuns runs,
            @Qualifier("teachingStartupExecutor") TaskExecutor executor,
            MeterRegistry metrics) {
        this(plans, lessons, runs, executor, metrics, Optional.empty());
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
        return launch(documentVersionId, learningGoal, ownerUsername, CaptureHandle.noop());
    }

    public synchronized PlanLaunch launch(
            UUID documentVersionId,
            String learningGoal,
            String ownerUsername,
            CaptureHandle capture) {
        String normalizedLearningGoal = normalizeLearningGoal(learningGoal);
        var existing = runs.findLatestOwned(
                        AssistantRunMode.TEACHING_PREPARATION, documentVersionId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            CaptureHandle trace = recoverPreparationTrace(run.id(), ownerUsername, capture);
            bindPreparationRun(
                    trace,
                    documentVersionId,
                    run,
                    LifecycleSignal.REPLAY,
                    "TEACHING_PREPARATION_REUSED");
            return new PlanLaunch(run.id(), run.state(), true);
        }

        RunSnapshot run = runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, ownerUsername);
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        bindPreparationRun(
                trace,
                documentVersionId,
                run,
                LifecycleSignal.BINDING,
                "TEACHING_PREPARATION_BOUND");
        try {
            executor.execute(() -> prepare(
                    run,
                    documentVersionId,
                    normalizedLearningGoal,
                    ownerUsername,
                    recoverPreparationTrace(run.id(), ownerUsername, trace)));
        } catch (RuntimeException schedulingFailure) {
            runs.fail(run.id(), run.revision(), "TEACHING_PREPARATION_QUEUE_FULL", "Teaching preparation could not start");
            captureFailure(trace, run.id(), "TEACHING_PREPARATION_QUEUE_FULL");
            throw schedulingFailure;
        }
        return new PlanLaunch(run.id(), run.state(), false);
    }

    private void prepare(
            RunSnapshot initial,
            UUID documentVersionId,
            String learningGoal,
            String ownerUsername,
            CaptureHandle capture) {
        RunSnapshot current = initial;
        try {
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.DOCUMENT_READINESS,
                    "Rulebook pages are ready for teaching");
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.LESSON_PLANNING,
                    "Reading rulebook pages and organizing the lesson");
            RunSnapshot planningRun = current;
            long planResolutionStartedAt = System.nanoTime();
            var planResolution = recordPhase("plan-resolution", planResolutionStartedAt, () -> {
                var existingPlan = plans.latest(documentVersionId, ownerUsername)
                        .filter(plan -> Objects.equals(plan.learningGoal(), learningGoal));
                if (existingPlan.isPresent()) {
                    if (capture.enabled()) {
                        plans.refreshVisualEvidence(
                                documentVersionId,
                                ownerUsername,
                                planningRun.id(),
                                capture);
                    } else {
                        plans.refreshVisualEvidence(documentVersionId, ownerUsername, planningRun.id());
                    }
                    return new PlanResolution(existingPlan.get(), true);
                }
                TeachingPlan created = capture.enabled()
                        ? plans.create(
                                documentVersionId,
                                learningGoal,
                                ownerUsername,
                                planningRun.id(),
                                capture)
                        : plans.create(
                                documentVersionId,
                                learningGoal,
                                ownerUsername,
                                planningRun.id());
                return new PlanResolution(created, false);
            });
            long planResolutionNanos = System.nanoTime() - planResolutionStartedAt;
            TeachingPlan plan = planResolution.plan();
            bindPlan(capture, documentVersionId, current.id(), plan.id());
            // Preparation already owns the startup lane. Generate and persist the first cited section here before
            // handing the remaining chapters to the continuation lane, so old long-tail work cannot delay usefulness.
            long firstSectionStartedAt = System.nanoTime();
            var lessonLaunch = recordPhase(
                    "first-section-startup",
                    firstSectionStartedAt,
                    () -> capture.enabled()
                            ? lessons.launchImmediately(plan, ownerUsername, capture)
                            : lessons.launchImmediately(plan, ownerUsername));
            long firstSectionNanos = System.nanoTime() - firstSectionStartedAt;
            LOGGER.info(
                    "Teaching startup lane finished: planResolutionMs={}, firstSectionStartupMs={}, planReused={}, lessonRunReused={}",
                    milliseconds(planResolutionNanos),
                    milliseconds(firstSectionNanos),
                    planResolution.reused(),
                    lessonLaunch.reused());
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.COMPLETED,
                    "Teaching plan is ready");
        } catch (RuntimeException failure) {
            failIfActive(current, ownerUsername, failure);
            captureFailure(capture, current.id(), failureCode(failure));
            LOGGER.warn(
                    "Teaching preparation failed for document version {} (failureType={})",
                    documentVersionId,
                    failure.getClass().getSimpleName());
        } catch (Error fatalFailure) {
            // A background worker must never disappear while its persisted run still says “organizing”. Record a
            // recoverable state for the player first, then rethrow so the executor and process diagnostics still see
            // a genuinely fatal JVM failure.
            failIfActive(current, ownerUsername, fatalFailure);
            captureFailure(capture, current.id(), "TEACHING_PREPARATION_FATAL");
            LOGGER.error(
                    "Teaching preparation stopped by a fatal worker error for document version {} (failureType={})",
                    documentVersionId,
                    fatalFailure.getClass().getSimpleName());
            throw fatalFailure;
        }
    }

    private void failIfActive(RunSnapshot lastKnown, String ownerUsername, Throwable failure) {
        runs.findOwned(lastKnown.id(), ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal())
                .ifPresent(run -> runs.fail(
                        run.id(), run.revision(), failureCode(failure), "Teaching preparation failed safely"));
    }

    private String failureCode(Throwable failure) {
        return causedByInvalidPlan(failure)
                ? "TEACHING_PREPARATION_INVALID_PLAN"
                : "TEACHING_PREPARATION_FAILED";
    }

    private boolean causedByInvalidPlan(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IllegalArgumentException) return true;
            if (current.getCause() == current) return false;
            current = current.getCause();
        }
        return false;
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

    private CaptureHandle recoverPreparationTrace(
            UUID runId, String ownerUsername, CaptureHandle fallback) {
        CaptureHandle recovered = PrivateAgentTraceCapture.recover(
                privateTraces,
                new ResourceRef(ResourceType.ASSISTANT_RUN, runId),
                ownerUsername);
        return recovered.enabled() ? recovered : PrivateAgentTraceCapture.failOpen(fallback);
    }

    private boolean bindPreparationRun(
            CaptureHandle capture,
            UUID documentVersionId,
            RunSnapshot run,
            LifecycleSignal signal,
            String code) {
        if (!capture.enabled()) return false;
        ResourceRef document = new ResourceRef(ResourceType.DOCUMENT_VERSION, documentVersionId);
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, run.id());
        boolean bound = captureBinding(capture, () ->
                capture.bind(assistantRun) && capture.bind(document));
        if (!bound) {
            captureGapIfAvailable(
                    capture,
                    run.id(),
                    assistantRun,
                    signal == LifecycleSignal.REPLAY
                            ? "TEACHING_PREPARATION_REUSE_GAP"
                            : "TEACHING_PREPARATION_BINDING_GAP",
                    document,
                    assistantRun);
            return false;
        }
        captureTrace(capture, () ->
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(run.id(), null, assistantRun),
                    signal,
                    code,
                    document,
                    assistantRun)));
        return true;
    }

    private void bindPlan(
            CaptureHandle capture, UUID documentVersionId, UUID preparationRunId, UUID teachingPlanId) {
        if (!capture.enabled()) return;
        ResourceRef document = new ResourceRef(ResourceType.DOCUMENT_VERSION, documentVersionId);
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, teachingPlanId);
        captureTrace(capture, () -> {
            capture.bind(plan);
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(preparationRunId, null, plan),
                    LifecycleSignal.BINDING,
                    "TEACHING_PLAN_BOUND",
                    document,
                    plan));
        });
    }

    private void captureFailure(CaptureHandle capture, UUID runId, String code) {
        if (!capture.enabled()) return;
        ResourceRef resource = new ResourceRef(ResourceType.ASSISTANT_RUN, runId);
        captureTrace(capture, () -> capture.bindingOrFailure(new BindingOrFailure(
                        traceContext(UUID.randomUUID(), runId, resource),
                        LifecycleSignal.FAILURE,
                        code,
                        resource,
                        null)));
    }

    private void captureGapIfAvailable(
            CaptureHandle capture,
            UUID operationId,
            ResourceRef resource,
            String code,
            ResourceRef parent,
            ResourceRef child) {
        if (!capture.enabled()) return;
        captureTrace(capture, () -> capture.bindingOrFailure(new BindingOrFailure(
                traceContext(operationId, null, resource),
                LifecycleSignal.GAP,
                code,
                parent,
                child)));
    }

    private boolean captureBinding(CaptureHandle capture, java.util.function.BooleanSupplier binding) {
        try {
            return binding.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void captureTrace(CaptureHandle capture, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException ignored) {
            // Private diagnostics never alter teaching preparation or startup.
        }
    }

    private TraceEventContext traceContext(UUID operationId, UUID parentOperationId, ResourceRef resource) {
        return TraceEventContext.create(
                java.time.Instant.now(), JourneyStage.TEACHING, operationId, parentOperationId, resource);
    }

    public record PlanLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}
}
