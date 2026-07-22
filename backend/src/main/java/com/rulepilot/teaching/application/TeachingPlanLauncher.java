package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import java.util.UUID;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(TeachingPlanLauncher.class);

    private final TeachingPlanService plans;
    private final IllustratedLessonLauncher lessons;
    private final AssistantRuns runs;
    private final TaskExecutor executor;

    @Autowired
    public TeachingPlanLauncher(
            TeachingPlanService plans,
            IllustratedLessonLauncher lessons,
            AssistantRuns runs,
            @Qualifier("teachingGenerationExecutor") TaskExecutor executor) {
        this.plans = plans;
        this.lessons = lessons;
        this.runs = runs;
        this.executor = executor;
    }

    public synchronized PlanLaunch launch(
            UUID documentVersionId,
            int playerCount,
            int beginnerCount,
            int durationMinutes,
            String ownerUsername) {
        validate(playerCount, beginnerCount, durationMinutes);
        var existing = runs.findLatestOwned(
                        AssistantRunMode.TEACHING_PREPARATION, documentVersionId, ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal());
        if (existing.isPresent()) {
            RunSnapshot run = existing.get();
            return new PlanLaunch(run.id(), run.state(), true);
        }

        RunSnapshot run = runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, ownerUsername);
        try {
            executor.execute(() -> prepare(
                    run, documentVersionId, playerCount, beginnerCount, durationMinutes, ownerUsername));
        } catch (RuntimeException schedulingFailure) {
            runs.fail(run.id(), run.revision(), "TEACHING_PREPARATION_QUEUE_FULL", "Teaching preparation could not start");
            throw schedulingFailure;
        }
        return new PlanLaunch(run.id(), run.state(), false);
    }

    private void prepare(
            RunSnapshot initial,
            UUID documentVersionId,
            int playerCount,
            int beginnerCount,
            int durationMinutes,
            String ownerUsername) {
        RunSnapshot current = initial;
        try {
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.DOCUMENT_READINESS,
                    "Rulebook pages are ready for teaching");
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.LESSON_PLANNING,
                    "Reading rulebook pages and organizing the lesson");
            var plan = plans.create(
                    documentVersionId, playerCount, beginnerCount, durationMinutes, ownerUsername, current.id());
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.COMPLETED,
                    "Teaching plan is ready");
            try {
                lessons.launch(plan.id(), ownerUsername);
            } catch (RuntimeException launchFailure) {
                LOGGER.warn(
                        "Teaching plan {} is ready but lesson generation did not start: {}",
                        plan.id(), launchFailure.getClass().getSimpleName());
            }
        } catch (RuntimeException failure) {
            failIfActive(current, ownerUsername);
            LOGGER.warn("Teaching preparation failed for document version {}", documentVersionId, failure);
        }
    }

    private void failIfActive(RunSnapshot lastKnown, String ownerUsername) {
        runs.findOwned(lastKnown.id(), ownerUsername)
                .map(AssistantRuns.RunDetails::run)
                .filter(run -> !run.state().terminal())
                .ifPresent(run -> runs.fail(
                        run.id(), run.revision(), "TEACHING_PREPARATION_FAILED", "Teaching preparation failed safely"));
    }

    private void validate(int playerCount, int beginnerCount, int durationMinutes) {
        if (playerCount < 1 || playerCount > 20 || beginnerCount < 0 || beginnerCount > playerCount
                || durationMinutes < 2 || durationMinutes > 180) {
            throw new IllegalArgumentException("teaching preferences are invalid");
        }
    }

    public record PlanLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}
}
