package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import java.util.Objects;
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
            @Qualifier("teachingStartupExecutor") TaskExecutor executor) {
        this.plans = plans;
        this.lessons = lessons;
        this.runs = runs;
        this.executor = executor;
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

        RunSnapshot run = runs.start(AssistantRunMode.TEACHING_PREPARATION, documentVersionId, ownerUsername);
        try {
            executor.execute(() -> prepare(
                    run,
                    documentVersionId,
                    normalizedLearningGoal,
                    ownerUsername));
        } catch (RuntimeException schedulingFailure) {
            runs.fail(run.id(), run.revision(), "TEACHING_PREPARATION_QUEUE_FULL", "Teaching preparation could not start");
            throw schedulingFailure;
        }
        return new PlanLaunch(run.id(), run.state(), false);
    }

    private void prepare(
            RunSnapshot initial,
            UUID documentVersionId,
            String learningGoal,
            String ownerUsername) {
        RunSnapshot current = initial;
        try {
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.DOCUMENT_READINESS,
                    "Rulebook pages are ready for teaching");
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.LESSON_PLANNING,
                    "Reading rulebook pages and organizing the lesson");
            RunSnapshot planningRun = current;
            var plan = plans.latest(documentVersionId, ownerUsername)
                    .filter(existingPlan -> Objects.equals(existingPlan.learningGoal(), learningGoal))
                    .orElseGet(() -> plans.create(
                            documentVersionId,
                            learningGoal,
                            ownerUsername,
                            planningRun.id()));
            // Preparation already owns the startup lane. Generate and persist the first cited section here before
            // handing the remaining chapters to the continuation lane, so old long-tail work cannot delay usefulness.
            lessons.launchImmediately(plan.id(), ownerUsername);
            current = runs.advance(
                    current.id(), current.revision(), AssistantRunState.COMPLETED,
                    "Teaching plan is ready");
        } catch (RuntimeException failure) {
            failIfActive(current, ownerUsername, failure);
            LOGGER.warn("Teaching preparation failed for document version {}", documentVersionId, failure);
        } catch (Error fatalFailure) {
            // A background worker must never disappear while its persisted run still says “organizing”. Record a
            // recoverable state for the player first, then rethrow so the executor and process diagnostics still see
            // a genuinely fatal JVM failure.
            failIfActive(current, ownerUsername, fatalFailure);
            LOGGER.error("Teaching preparation stopped by a fatal worker error for document version {}", documentVersionId, fatalFailure);
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
        return failure instanceof IllegalArgumentException
                ? "TEACHING_PREPARATION_INVALID_PLAN"
                : "TEACHING_PREPARATION_FAILED";
    }

    private String normalizeLearningGoal(String learningGoal) {
        if (learningGoal == null || learningGoal.isBlank()) return null;
        String normalized = learningGoal.strip();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("teaching learning goal is too long");
        }
        return normalized;
    }

    public record PlanLaunch(UUID assistantRunId, AssistantRunState state, boolean reused) {}
}
