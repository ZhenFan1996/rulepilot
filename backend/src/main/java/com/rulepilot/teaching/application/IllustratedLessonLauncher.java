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
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class IllustratedLessonLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger(IllustratedLessonLauncher.class);

    private final IllustratedLessonService lessons;
    private final AssistantRuns runs;
    private final TaskExecutor startupExecutor;
    private final TaskExecutor continuationExecutor;
    private final TaskExecutor visualEnrichmentExecutor;
    private final VisualLessonEnrichmentService visuals;
    private final Optional<PrivateAgentTraceService> privateTraces;

    @Autowired
    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            @Qualifier("teachingStartupExecutor") TaskExecutor startupExecutor,
            @Qualifier("teachingGenerationExecutor") TaskExecutor continuationExecutor,
            @Qualifier("visualEnrichmentExecutor") TaskExecutor visualEnrichmentExecutor,
            VisualLessonEnrichmentService visuals,
            Optional<PrivateAgentTraceService> privateTraces) {
        this.lessons = lessons;
        this.runs = runs;
        this.startupExecutor = startupExecutor;
        this.continuationExecutor = continuationExecutor;
        this.visualEnrichmentExecutor = visualEnrichmentExecutor;
        this.visuals = visuals;
        this.privateTraces = privateTraces == null ? Optional.empty() : privateTraces;
    }

    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor startupExecutor,
            TaskExecutor continuationExecutor,
            TaskExecutor visualEnrichmentExecutor,
            VisualLessonEnrichmentService visuals) {
        this(
                lessons,
                runs,
                startupExecutor,
                continuationExecutor,
                visualEnrichmentExecutor,
                visuals,
                Optional.empty());
    }

    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor executor) {
        this(lessons, runs, executor, executor, executor, null);
    }

    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor executor,
            VisualLessonEnrichmentService visuals) {
        this(lessons, runs, executor, executor, executor, visuals);
    }

    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor lessonExecutor,
            TaskExecutor visualEnrichmentExecutor,
            VisualLessonEnrichmentService visuals) {
        this(lessons, runs, lessonExecutor, lessonExecutor, visualEnrichmentExecutor, visuals);
    }

    public synchronized LessonLaunch launch(UUID teachingPlanId, String ownerUsername) {
        return launch(teachingPlanId, ownerUsername, CaptureHandle.noop());
    }

    public synchronized LessonLaunch launch(
            UUID teachingPlanId, String ownerUsername, CaptureHandle capture) {
        var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, teachingPlanId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            CaptureHandle trace = recoverLessonTrace(run.id(), ownerUsername, capture);
            bindLessonRun(
                    trace,
                    teachingPlanId,
                    run,
                    LifecycleSignal.REPLAY,
                    "TEACHING_RUN_REUSED");
            return new LessonLaunch(run.id(), run.state(), true);
        }

        RunSnapshot run = lessons.begin(teachingPlanId, ownerUsername);
        CaptureHandle trace = PrivateAgentTraceCapture.failOpen(capture);
        bindLessonRun(
                trace,
                teachingPlanId,
                run,
                LifecycleSignal.BINDING,
                "TEACHING_RUN_BOUND");
        AtomicBoolean taskStarted = new AtomicBoolean();
        try {
            startupExecutor.execute(() -> {
                taskStarted.set(true);
                CaptureHandle recovered = recoverLessonTrace(run.id(), ownerUsername, trace);
                startAndScheduleContinuation(teachingPlanId, ownerUsername, run, recovered);
            });
        } catch (RuntimeException schedulingFailure) {
            if (!taskStarted.get()) lessons.failScheduling(run);
            captureFailure(trace, run.id(), "TEACHING_QUEUE_FULL");
            throw schedulingFailure;
        }
        return new LessonLaunch(run.id(), run.state(), false);
    }

    /** Runs on the dedicated startup lane already occupied by teaching-plan preparation. */
    LessonLaunch launchImmediately(TeachingPlan plan, String ownerUsername) {
        return launchImmediately(plan, ownerUsername, CaptureHandle.noop());
    }

    LessonLaunch launchImmediately(
            TeachingPlan plan, String ownerUsername, CaptureHandle capture) {
        if (plan == null) throw new IllegalArgumentException("teaching plan is required");
        RunSnapshot run;
        boolean reused;
        synchronized (this) {
            var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, plan.id(), ownerUsername)
                    .map(AssistantRuns.RunDetails::run)
                    .filter(candidate -> !candidate.state().terminal());
            if (existing.isPresent()) {
                run = existing.get();
                reused = true;
            } else {
                run = lessons.begin(plan, ownerUsername);
                reused = false;
            }
        }
        CaptureHandle trace = reused
                ? recoverLessonTrace(run.id(), ownerUsername, capture)
                : PrivateAgentTraceCapture.failOpen(capture);
        bindLessonRun(
                trace,
                plan.id(),
                run,
                reused ? LifecycleSignal.REPLAY : LifecycleSignal.BINDING,
                reused ? "TEACHING_RUN_REUSED" : "TEACHING_RUN_BOUND");
        if (reused) return new LessonLaunch(run.id(), run.state(), true);
        startAndScheduleContinuation(plan, ownerUsername, run, trace);
        return new LessonLaunch(run.id(), run.state(), false);
    }

    private void startAndScheduleContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            RunSnapshot run,
            CaptureHandle capture) {
        var continuation = capture.enabled()
                ? lessons.startGeneration(teachingPlanId, ownerUsername, run, capture)
                : lessons.startGeneration(teachingPlanId, ownerUsername, run);
        scheduleContinuation(teachingPlanId, ownerUsername, continuation, capture);
    }

    private void startAndScheduleContinuation(
            TeachingPlan plan,
            String ownerUsername,
            RunSnapshot run,
            CaptureHandle capture) {
        var continuation = capture.enabled()
                ? lessons.startGeneration(plan, ownerUsername, run, capture)
                : lessons.startGeneration(plan, ownerUsername, run);
        scheduleContinuation(plan.id(), ownerUsername, continuation, capture);
    }

    private void scheduleContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            IllustratedLessonService.GenerationContinuation continuation,
            CaptureHandle capture) {
        if (!continuation.hasRemainingWork()) {
            finishContinuation(teachingPlanId, ownerUsername, continuation, capture);
            return;
        }
        AtomicBoolean taskStarted = new AtomicBoolean();
        try {
            continuationExecutor.execute(() -> {
                taskStarted.set(true);
                CaptureHandle recovered = recoverLessonTrace(
                        continuation.run().id(), ownerUsername, capture);
                finishContinuation(teachingPlanId, ownerUsername, continuation, recovered);
            });
        } catch (RuntimeException schedulingFailure) {
            if (taskStarted.get()) throw schedulingFailure;
            try {
                lessons.failContinuationScheduling(continuation);
            } catch (RuntimeException trackingFailure) {
                schedulingFailure.addSuppressed(trackingFailure);
            }
            // The first source-cited section is already durable. Keep preparation successful so the reader can open it;
            // the failed Teaching run truthfully exposes the retry instead of hiding useful content.
            LOGGER.warn(
                    "Teaching continuation could not be scheduled after the first cited section for plan {} (failureType={})",
                    teachingPlanId,
                    schedulingFailure.getClass().getSimpleName());
            CaptureHandle recovered = recoverLessonTrace(
                    continuation.run().id(), ownerUsername, capture);
            captureFailure(recovered, continuation.run().id(), "TEACHING_CONTINUATION_QUEUE_FULL");
        }
    }

    private void finishContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            IllustratedLessonService.GenerationContinuation continuation,
            CaptureHandle capture) {
        var outcome = capture.enabled()
                ? lessons.continueGeneration(continuation, capture)
                : lessons.continueGeneration(continuation);
        try {
            lessons.finish(outcome);
        } catch (RuntimeException completionFailure) {
            captureFailure(capture, continuation.run().id(), "TEACHING_COMPLETION_FAILED");
            throw completionFailure;
        }
        if (visuals != null
                && visuals.supportsVisualEvidence(ownerUsername)
                && outcome.lessonStatus() != com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus.INCOMPLETE) {
            enrichLatest(teachingPlanId, ownerUsername, capture);
        }
    }

    public VisualLessonEnrichmentService.VisualEnrichmentLaunch enrichLatest(UUID teachingPlanId, String ownerUsername) {
        return enrichLatest(teachingPlanId, ownerUsername, CaptureHandle.noop());
    }

    public VisualLessonEnrichmentService.VisualEnrichmentLaunch enrichLatest(
            UUID teachingPlanId, String ownerUsername, CaptureHandle capture) {
        if (visuals == null) throw new IllegalStateException("visual enrichment is unavailable");
        var launch = visuals.launch(teachingPlanId, ownerUsername);
        CaptureHandle trace = recoverVisualTrace(
                teachingPlanId, launch.assistantRunId(), ownerUsername, capture);
        bindVisualRun(
                trace,
                teachingPlanId,
                launch.assistantRunId(),
                launch.reused() ? LifecycleSignal.REPLAY : LifecycleSignal.BINDING,
                launch.reused() ? "VISUAL_ENRICHMENT_REUSED" : "VISUAL_ENRICHMENT_BOUND");
        if (launch.reused()) return launch;
        try {
            visualEnrichmentExecutor.execute(() -> {
                RunSnapshot run = new RunSnapshot(
                        launch.assistantRunId(),
                        AssistantRunMode.VISUAL_ENRICHMENT,
                        teachingPlanId,
                        ownerUsername,
                        launch.state(),
                        launch.revision(),
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        null,
                        null);
                if (trace.enabled()) {
                    visuals.enrichLatest(teachingPlanId, run, trace);
                } else {
                    visuals.enrichLatest(teachingPlanId, run);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            visuals.failScheduling(launch);
            captureVisualFailure(trace, launch.assistantRunId(), "VISUAL_ENRICHMENT_QUEUE_FULL");
            throw schedulingFailure;
        }
        return launch;
    }

    public VisualLessonEnrichmentService.VisualEnrichmentLaunch prepareIconGlossary(
            UUID teachingPlanId, String ownerUsername) {
        return prepareIconGlossary(teachingPlanId, ownerUsername, CaptureHandle.noop());
    }

    public VisualLessonEnrichmentService.VisualEnrichmentLaunch prepareIconGlossary(
            UUID teachingPlanId, String ownerUsername, CaptureHandle capture) {
        if (visuals == null) throw new IllegalStateException("visual enrichment is unavailable");
        var launch = visuals.launch(teachingPlanId, ownerUsername);
        CaptureHandle trace = recoverVisualTrace(
                teachingPlanId, launch.assistantRunId(), ownerUsername, capture);
        bindVisualRun(
                trace,
                teachingPlanId,
                launch.assistantRunId(),
                launch.reused() ? LifecycleSignal.REPLAY : LifecycleSignal.BINDING,
                launch.reused() ? "ICON_GLOSSARY_REUSED" : "ICON_GLOSSARY_BOUND");
        if (launch.reused()) return launch;
        try {
            visualEnrichmentExecutor.execute(() -> {
                RunSnapshot run = new RunSnapshot(
                        launch.assistantRunId(),
                        AssistantRunMode.VISUAL_ENRICHMENT,
                        teachingPlanId,
                        ownerUsername,
                        launch.state(),
                        launch.revision(),
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        null,
                        null);
                if (trace.enabled()) {
                    visuals.extractIconGlossaryOnly(teachingPlanId, run, trace);
                } else {
                    visuals.extractIconGlossaryOnly(teachingPlanId, run);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            visuals.failScheduling(launch);
            captureVisualFailure(trace, launch.assistantRunId(), "ICON_GLOSSARY_QUEUE_FULL");
            throw schedulingFailure;
        }
        return launch;
    }

    public record LessonLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}

    private CaptureHandle recoverLessonTrace(UUID runId, String ownerUsername, CaptureHandle fallback) {
        return recoverTrace(
                ownerUsername,
                fallback,
                teachingRun(runId),
                new ResourceRef(ResourceType.ASSISTANT_RUN, runId));
    }

    private CaptureHandle recoverVisualTrace(
            UUID teachingPlanId, UUID runId, String ownerUsername, CaptureHandle fallback) {
        return recoverTrace(
                ownerUsername,
                fallback,
                new ResourceRef(ResourceType.ASSISTANT_RUN, runId),
                new ResourceRef(ResourceType.VISUAL_RUN, runId),
                new ResourceRef(ResourceType.TEACHING_PLAN, teachingPlanId));
    }

    private CaptureHandle recoverTrace(
            String ownerUsername, CaptureHandle fallback, ResourceRef... durableResources) {
        for (ResourceRef resource : durableResources) {
            CaptureHandle recovered = PrivateAgentTraceCapture.recover(privateTraces, resource, ownerUsername);
            if (recovered.enabled()) return recovered;
        }
        return PrivateAgentTraceCapture.failOpen(fallback);
    }

    private boolean bindLessonRun(
            CaptureHandle capture,
            UUID teachingPlanId,
            RunSnapshot run,
            LifecycleSignal signal,
            String code) {
        if (!capture.enabled()) return false;
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, teachingPlanId);
        ResourceRef teachingRun = teachingRun(run.id());
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, run.id());
        boolean bound = captureBinding(capture, teachingRun, assistantRun, plan);
        if (!bound) {
            captureGapIfAvailable(
                    capture,
                    run.id(),
                    teachingRun,
                    signal == LifecycleSignal.REPLAY
                            ? "TEACHING_RUN_REUSE_GAP"
                            : "TEACHING_RUN_BINDING_GAP",
                    plan,
                    teachingRun);
            return false;
        }
        captureTrace(capture, () -> {
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(run.id(), null, teachingRun),
                    signal,
                    code,
                    plan,
                    teachingRun));
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(run.id(), null, teachingRun),
                    signal,
                    signal == LifecycleSignal.REPLAY
                            ? "TEACHING_ASSISTANT_RUN_REUSED"
                            : "TEACHING_ASSISTANT_RUN_BOUND",
                    teachingRun,
                    assistantRun));
        });
        return true;
    }

    private boolean bindVisualRun(
            CaptureHandle capture,
            UUID teachingPlanId,
            UUID runId,
            LifecycleSignal signal,
            String code) {
        if (!capture.enabled()) return false;
        ResourceRef plan = new ResourceRef(ResourceType.TEACHING_PLAN, teachingPlanId);
        ResourceRef visualRun = new ResourceRef(ResourceType.VISUAL_RUN, runId);
        ResourceRef assistantRun = new ResourceRef(ResourceType.ASSISTANT_RUN, runId);
        boolean bound = captureBinding(capture, assistantRun, visualRun, plan);
        if (!bound) {
            captureGapIfAvailable(
                    capture,
                    runId,
                    visualRun,
                    signal == LifecycleSignal.REPLAY
                            ? "VISUAL_RUN_REUSE_GAP"
                            : "VISUAL_RUN_BINDING_GAP",
                    plan,
                    visualRun);
            return false;
        }
        captureTrace(capture, () -> {
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(runId, null, visualRun),
                    signal,
                    code,
                    plan,
                    visualRun));
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(runId, null, visualRun),
                    signal,
                    signal == LifecycleSignal.REPLAY
                            ? "VISUAL_ASSISTANT_RUN_REUSED"
                            : "VISUAL_ASSISTANT_RUN_BOUND",
                    visualRun,
                    assistantRun));
        });
        return true;
    }

    private void captureFailure(CaptureHandle capture, UUID runId, String code) {
        if (!capture.enabled()) return;
        ResourceRef resource = teachingRun(runId);
        captureTrace(capture, () -> capture.bindingOrFailure(new BindingOrFailure(
                        traceContext(UUID.randomUUID(), runId, resource),
                        LifecycleSignal.FAILURE,
                        code,
                        resource,
                        null)));
    }

    private void captureVisualFailure(CaptureHandle capture, UUID runId, String code) {
        if (!capture.enabled()) return;
        ResourceRef resource = new ResourceRef(ResourceType.VISUAL_RUN, runId);
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

    private boolean captureBinding(CaptureHandle capture, ResourceRef... resources) {
        try {
            for (ResourceRef resource : resources) {
                if (!capture.bind(resource)) return false;
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void captureTrace(CaptureHandle capture, Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException ignored) {
            // Private diagnostics never alter lesson startup or continuation.
        }
    }

    private ResourceRef teachingRun(UUID runId) {
        return new ResourceRef(ResourceType.TEACHING_RUN, runId);
    }

    private TraceEventContext traceContext(UUID operationId, UUID parentOperationId, ResourceRef resource) {
        return TraceEventContext.create(
                java.time.Instant.now(), JourneyStage.TEACHING, operationId, parentOperationId, resource);
    }
}
