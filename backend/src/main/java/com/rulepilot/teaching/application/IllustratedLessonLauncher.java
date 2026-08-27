package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.teaching.domain.TeachingPlan;
import java.util.UUID;
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

    @Autowired
    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            @Qualifier("teachingStartupExecutor") TaskExecutor startupExecutor,
            @Qualifier("teachingGenerationExecutor") TaskExecutor continuationExecutor,
            @Qualifier("visualEnrichmentExecutor") TaskExecutor visualEnrichmentExecutor,
            VisualLessonEnrichmentService visuals) {
        this.lessons = lessons;
        this.runs = runs;
        this.startupExecutor = startupExecutor;
        this.continuationExecutor = continuationExecutor;
        this.visualEnrichmentExecutor = visualEnrichmentExecutor;
        this.visuals = visuals;
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
        var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, teachingPlanId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            return new LessonLaunch(run.id(), run.state(), true);
        }

        RunSnapshot run = lessons.begin(teachingPlanId, ownerUsername);
        AtomicBoolean taskStarted = new AtomicBoolean();
        try {
            startupExecutor.execute(() -> {
                taskStarted.set(true);
                startAndScheduleContinuation(teachingPlanId, ownerUsername, run);
            });
        } catch (RuntimeException schedulingFailure) {
            if (!taskStarted.get()) lessons.failScheduling(run);
            throw schedulingFailure;
        }
        return new LessonLaunch(run.id(), run.state(), false);
    }

    /** Runs on the dedicated startup lane already occupied by teaching-plan preparation. */
    LessonLaunch launchImmediately(TeachingPlan plan, String ownerUsername) {
        if (plan == null) throw new IllegalArgumentException("teaching plan is required");
        RunSnapshot run;
        synchronized (this) {
            var existing = runs.findLatestOwned(AssistantRunMode.TEACHING, plan.id(), ownerUsername)
                    .map(AssistantRuns.RunDetails::run)
                    .filter(candidate -> !candidate.state().terminal());
            if (existing.isPresent()) {
                RunSnapshot active = existing.get();
                return new LessonLaunch(active.id(), active.state(), true);
            }
            run = lessons.begin(plan, ownerUsername);
        }
        try {
            startAndScheduleContinuation(plan, ownerUsername, run);
        } catch (RuntimeException startupFailure) {
            throw new ImmediateLessonStartupFailure(
                    run.id(), persistedFailureCode(run.id(), ownerUsername, startupFailure), startupFailure);
        }
        return new LessonLaunch(run.id(), run.state(), false);
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
        try {
            continuationExecutor.execute(() -> {
                taskStarted.set(true);
                finishContinuation(teachingPlanId, ownerUsername, continuation);
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
                    "Teaching continuation could not be scheduled after the first cited section for plan {}",
                    teachingPlanId,
                    schedulingFailure);
        }
    }

    private void finishContinuation(
            UUID teachingPlanId,
            String ownerUsername,
            IllustratedLessonService.GenerationContinuation continuation) {
        var outcome = lessons.continueGeneration(continuation);
        lessons.finish(outcome);
        if (visuals != null
                && visuals.supportsVisualEvidence(ownerUsername)
                && outcome.lessonStatus() != com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus.INCOMPLETE) {
            enrichLatest(teachingPlanId, ownerUsername);
        }
    }

    public VisualLessonEnrichmentService.VisualEnrichmentLaunch enrichLatest(UUID teachingPlanId, String ownerUsername) {
        if (visuals == null) throw new IllegalStateException("visual enrichment is unavailable");
        var launch = visuals.launch(teachingPlanId, ownerUsername);
        if (launch.reused()) return launch;
        try {
            visualEnrichmentExecutor.execute(() -> {
                visuals.enrichLatest(teachingPlanId, new RunSnapshot(
                        launch.assistantRunId(),
                        AssistantRunMode.VISUAL_ENRICHMENT,
                        teachingPlanId,
                        ownerUsername,
                        launch.state(),
                        launch.revision(),
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        null,
                        null));
            });
        } catch (RuntimeException schedulingFailure) {
            visuals.failScheduling(launch);
            throw schedulingFailure;
        }
        return launch;
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
