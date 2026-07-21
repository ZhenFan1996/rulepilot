package com.rulepilot.teaching.application;

import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class IllustratedLessonService {

    private final TeachingPlanRepository plans;
    private final GroundedTeachingAgent agent;
    private final IllustratedLessonRepository repository;
    private final AssistantRuns runs;
    private final DocumentVersionScopeLookup documents;
    private final ObservationRegistry observations;
    private final IllustratedLessonProgressPublisher progressPublisher;

    public IllustratedLessonService(
            TeachingPlanRepository plans,
            GroundedTeachingAgent agent,
            IllustratedLessonRepository repository,
            AssistantRuns runs,
            DocumentVersionScopeLookup documents,
            ObservationRegistry observations,
            IllustratedLessonProgressPublisher progressPublisher) {
        this.plans = plans;
        this.agent = agent;
        this.repository = repository;
        this.runs = runs;
        this.documents = documents;
        this.observations = observations;
        this.progressPublisher = progressPublisher;
    }

    @Transactional
    public RunSnapshot begin(UUID teachingPlanId, String ownerUsername) {
        var plan = requireReadyPlan(teachingPlanId, ownerUsername);
        return runs.start(AssistantRunMode.TEACHING, plan.id(), ownerUsername);
    }

    public GenerationOutcome generate(UUID teachingPlanId, String ownerUsername, RunSnapshot run) {
        return Observation.createNotStarted("rulepilot.teaching.workflow", observations)
                .contextualName("teaching-workflow")
                .observe(() -> generateObserved(teachingPlanId, ownerUsername, run));
    }

    private GenerationOutcome generateObserved(UUID teachingPlanId, String ownerUsername, RunSnapshot initialRun) {
        RunSnapshot run = initialRun;
        try {
            var plan = requireReadyPlan(teachingPlanId, ownerUsername);
            run = advance(run, AssistantRunState.DOCUMENT_READINESS, "Rule document readiness is checked");
            run = advance(run, AssistantRunState.LESSON_PLANNING, "Teaching plan is loaded");
            run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, "Required lesson evidence is planned");
            run = advance(run, AssistantRunState.RETRIEVING, "Allow-listed rule search is running");
            IllustratedLesson previousLesson = repository.findLatestByPlan(teachingPlanId).orElse(null);
            IllustratedLesson lesson = agent.createBase(
                    plan, run.id(), previousLesson, progressPublisher::publish);
            run = advance(run, AssistantRunState.VERIFYING_EVIDENCE, "Lesson citations are scope checked");
            return new GenerationOutcome(run, lesson.status());
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Teaching workflow stopped by execution budget", stopped);
            throw stopped;
        } catch (RuntimeException exception) {
            failRun(run, "TEACHING_WORKFLOW_FAILED", "Teaching workflow failed safely", exception);
            throw exception;
        }
    }

    @Transactional
    public void finish(GenerationOutcome outcome) {
        RunSnapshot run = outcome.run();
        try {
            LessonStatus status = outcome.lessonStatus();
            if (status == LessonStatus.INCOMPLETE) {
                run = advance(run, AssistantRunState.INSUFFICIENT_EVIDENCE, "Required lesson evidence is incomplete");
            } else {
                run = advance(run, AssistantRunState.LESSON_COMPOSITION, "Cited illustrated lesson is composed");
                run = advance(run, AssistantRunState.COMPLETED, "Illustrated lesson generation completed");
            }
        } catch (RuntimeException exception) {
            failRun(run, "TEACHING_COMPLETION_FAILED", "Persisted lesson could not be marked complete", exception);
            throw exception;
        }
    }

    @Transactional
    public void failScheduling(RunSnapshot run) {
        if (!run.state().terminal()) {
            runs.fail(run.id(), run.revision(), "TEACHING_QUEUE_FULL", "Teaching generation could not be scheduled");
        }
    }

    private void failRun(RunSnapshot run, String errorCode, String summary, RuntimeException exception) {
        if (!run.state().terminal()) {
            try {
                runs.fail(run.id(), run.revision(), errorCode, summary);
            } catch (RuntimeException trackingFailure) {
                exception.addSuppressed(trackingFailure);
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<IllustratedLesson> latest(UUID teachingPlanId) {
        return repository.findLatestByPlan(teachingPlanId);
    }

    private RunSnapshot advance(RunSnapshot run, AssistantRunState state, String summary) {
        return runs.advance(run.id(), run.revision(), state, summary);
    }

    private TeachingPlan requireReadyPlan(UUID teachingPlanId, String ownerUsername) {
        var plan = plans.findById(teachingPlanId)
                .orElseThrow(() -> new IllegalArgumentException("teaching plan does not exist"));
        if (!plan.createdBy().equals(ownerUsername)) {
            throw new IllegalArgumentException("teaching plan does not exist");
        }
        var document = documents.findVersion(plan.documentVersionId())
                .orElseThrow(() -> new IllegalArgumentException("document version does not exist"));
        if (!"READY".equals(document.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
        return plan;
    }

    public record GenerationOutcome(RunSnapshot run, LessonStatus lessonStatus) {}
}
