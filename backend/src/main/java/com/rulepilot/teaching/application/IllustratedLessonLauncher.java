package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class IllustratedLessonLauncher {

    private final IllustratedLessonService lessons;
    private final AssistantRuns runs;
    private final TaskExecutor executor;
    private final VisualLessonEnrichmentService visuals;

    @Autowired
    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            @Qualifier("teachingGenerationExecutor") TaskExecutor executor,
            VisualLessonEnrichmentService visuals) {
        this.lessons = lessons;
        this.runs = runs;
        this.executor = executor;
        this.visuals = visuals;
    }

    public IllustratedLessonLauncher(
            IllustratedLessonService lessons,
            AssistantRuns runs,
            TaskExecutor executor) {
        this(lessons, runs, executor, null);
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
        try {
            executor.execute(() -> {
                var outcome = lessons.generate(teachingPlanId, ownerUsername, run);
                lessons.finish(outcome);
                if (visuals != null && outcome.lessonStatus() != com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus.INCOMPLETE) {
                    enrichLatest(teachingPlanId, ownerUsername);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            lessons.failScheduling(run);
            throw schedulingFailure;
        }
        return new LessonLaunch(run.id(), run.state(), false);
    }

    public VisualLessonEnrichmentService.VisualEnrichmentLaunch enrichLatest(UUID teachingPlanId, String ownerUsername) {
        if (visuals == null) throw new IllegalStateException("visual enrichment is unavailable");
        var launch = visuals.launch(teachingPlanId, ownerUsername);
        if (launch.reused()) return launch;
        try {
            executor.execute(() -> visuals.enrichLatest(teachingPlanId, new RunSnapshot(
                    launch.assistantRunId(),
                    AssistantRunMode.VISUAL_ENRICHMENT,
                    teachingPlanId,
                    ownerUsername,
                    launch.state(),
                    launch.revision(),
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    null,
                    null)));
        } catch (RuntimeException schedulingFailure) {
            visuals.failScheduling(launch);
            throw schedulingFailure;
        }
        return launch;
    }

    public record LessonLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}
}
