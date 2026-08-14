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
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class IllustratedLessonService {

    private static final Logger log = LoggerFactory.getLogger(IllustratedLessonService.class);

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

    /**
     * Starts the lesson from the plan that the preparation lane just created or loaded.
     * The document READY check remains authoritative, but the same immutable plan and its sections are not reloaded.
     */
    @Transactional
    RunSnapshot begin(TeachingPlan plan, String ownerUsername) {
        requireReadyPlan(plan, ownerUsername);
        return runs.start(AssistantRunMode.TEACHING, plan.id(), ownerUsername);
    }

    @Transactional
    public RunSnapshot beginCandidate(UUID teachingPlanId, String ownerUsername) {
        requireReadyPlan(teachingPlanId, ownerUsername);
        return runs.start(AssistantRunMode.TEACHING, candidateSubjectId(teachingPlanId), ownerUsername);
    }

    public GenerationOutcome generate(UUID teachingPlanId, String ownerUsername, RunSnapshot run) {
        return continueGeneration(startGeneration(teachingPlanId, ownerUsername, run));
    }

    public GenerationOutcome generateCandidate(UUID teachingPlanId, String ownerUsername, RunSnapshot run) {
        return Observation.createNotStarted("rulepilot.teaching.candidate.workflow", observations)
                .contextualName("teaching-candidate-workflow")
                .observe(() -> generateObserved(teachingPlanId, ownerUsername, run, GenerationTarget.CANDIDATE));
    }

    GenerationContinuation startGeneration(UUID teachingPlanId, String ownerUsername, RunSnapshot initialRun) {
        return Observation.createNotStarted("rulepilot.teaching.startup", observations)
                .contextualName("teaching-first-section")
                .observe(() -> startGenerationObserved(teachingPlanId, ownerUsername, initialRun));
    }

    GenerationContinuation startGeneration(TeachingPlan plan, String ownerUsername, RunSnapshot initialRun) {
        requirePreparedRun(plan, ownerUsername, initialRun);
        return Observation.createNotStarted("rulepilot.teaching.startup", observations)
                .contextualName("teaching-first-section")
                .observe(() -> startGenerationObserved(plan, initialRun));
    }

    GenerationOutcome continueGeneration(GenerationContinuation continuation) {
        if (continuation == null) throw new IllegalArgumentException("teaching continuation is required");
        return Observation.createNotStarted("rulepilot.teaching.continuation", observations)
                .contextualName("teaching-remaining-sections")
                .observe(() -> continueGenerationObserved(continuation));
    }

    private GenerationContinuation startGenerationObserved(
            UUID teachingPlanId,
            String ownerUsername,
            RunSnapshot initialRun) {
        var plan = requireReadyPlan(teachingPlanId, ownerUsername);
        return startGenerationObserved(plan, initialRun);
    }

    private GenerationContinuation startGenerationObserved(
            TeachingPlan plan,
            RunSnapshot initialRun) {
        RunSnapshot run = initialRun;
        try {
            run = advance(run, AssistantRunState.DOCUMENT_READINESS, "Rule document readiness is checked");
            run = advance(run, AssistantRunState.LESSON_PLANNING, "Teaching plan is loaded");
            run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, "Required lesson evidence is planned");
            run = advance(run, AssistantRunState.RETRIEVING, "Allow-listed rule search is running");
            IllustratedLesson previousLesson = repository.findLatestByPlan(plan.id()).orElse(null);
            var base = agent.startBase(
                    plan,
                    run.id(),
                    previousLesson,
                    progressPublisher::publish);
            return new GenerationContinuation(run, base);
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Teaching workflow stopped by execution budget", stopped);
            throw stopped;
        } catch (RuntimeException exception) {
            log.error("Teaching first-section workflow failed for plan {} and run {}", plan.id(), run.id(), exception);
            failRun(run, "TEACHING_WORKFLOW_FAILED", "Teaching workflow failed safely", exception);
            throw exception;
        }
    }

    private GenerationOutcome continueGenerationObserved(GenerationContinuation continuation) {
        RunSnapshot run = continuation.run();
        try {
            IllustratedLesson lesson = agent.continueBase(
                    continuation.base(),
                    progressPublisher::publish);
            run = advanceAfterWork(run, AssistantRunState.VERIFYING_EVIDENCE, "Lesson citations are scope checked");
            return new GenerationOutcome(run, lesson.status());
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Teaching workflow stopped by execution budget", stopped);
            throw stopped;
        } catch (RuntimeException exception) {
            log.error("Teaching continuation failed for run {}", run.id(), exception);
            failRun(run, "TEACHING_WORKFLOW_FAILED", "Teaching workflow failed safely", exception);
            throw exception;
        }
    }

    private GenerationOutcome generateObserved(
            UUID teachingPlanId,
            String ownerUsername,
            RunSnapshot initialRun,
            GenerationTarget target) {
        RunSnapshot run = initialRun;
        try {
            var plan = requireReadyPlan(teachingPlanId, ownerUsername);
            run = advance(run, AssistantRunState.DOCUMENT_READINESS, "Rule document readiness is checked");
            run = advance(run, AssistantRunState.LESSON_PLANNING, "Teaching plan is loaded");
            run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, "Required lesson evidence is planned");
            run = advance(run, AssistantRunState.RETRIEVING, "Allow-listed rule search is running");
            IllustratedLesson previousLesson = target == GenerationTarget.ACTIVE
                    ? repository.findLatestByPlan(teachingPlanId).orElse(null)
                    : null;
            IllustratedLesson lesson = agent.createBase(
                    plan,
                    run.id(),
                    previousLesson,
                    target == GenerationTarget.ACTIVE
                            ? progressPublisher::publish
                            : progressPublisher::publishCandidate);
            run = advanceAfterWork(run, AssistantRunState.VERIFYING_EVIDENCE, "Lesson citations are scope checked");
            return new GenerationOutcome(run, lesson.status());
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Teaching workflow stopped by execution budget", stopped);
            throw stopped;
        } catch (RuntimeException exception) {
            log.error("Teaching workflow failed for plan {} and run {}", teachingPlanId, run.id(), exception);
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
                run = advanceAfterWork(run, AssistantRunState.INSUFFICIENT_EVIDENCE, "Required lesson evidence is incomplete");
            } else {
                run = advanceAfterWork(run, AssistantRunState.LESSON_COMPOSITION, "Cited illustrated lesson is composed");
                run = advanceAfterWork(run, AssistantRunState.COMPLETED, "Illustrated lesson generation completed");
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

    @Transactional
    public void failContinuationScheduling(GenerationContinuation continuation) {
        RunSnapshot run = continuation.run();
        if (!run.state().terminal()) {
            runs.fail(
                    run.id(),
                    run.revision(),
                    "TEACHING_CONTINUATION_QUEUE_FULL",
                    "The first cited section is readable but remaining teaching work could not be scheduled");
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

    static UUID candidateSubjectId(UUID teachingPlanId) {
        if (teachingPlanId == null) throw new IllegalArgumentException("teaching plan is required");
        return UUID.nameUUIDFromBytes(("lesson-candidate:" + teachingPlanId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private RunSnapshot advance(RunSnapshot run, AssistantRunState state, String summary) {
        return runs.advance(run.id(), run.revision(), state, summary);
    }

    private RunSnapshot advanceAfterWork(RunSnapshot run, AssistantRunState state, String summary) {
        return runs.advanceAfterWork(run.id(), run.revision(), state, summary);
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

    private void requireReadyPlan(TeachingPlan plan, String ownerUsername) {
        if (plan == null || !plan.createdBy().equals(ownerUsername)) {
            throw new IllegalArgumentException("teaching plan does not exist");
        }
        var document = documents.findVersion(plan.documentVersionId())
                .orElseThrow(() -> new IllegalArgumentException("document version does not exist"));
        if (!document.createdBy().equals(ownerUsername) || !"READY".equals(document.processingStatus())) {
            throw new IllegalArgumentException("rule document is not ready for teaching");
        }
    }

    private void requirePreparedRun(TeachingPlan plan, String ownerUsername, RunSnapshot run) {
        if (plan == null
                || run == null
                || !plan.createdBy().equals(ownerUsername)
                || run.mode() != AssistantRunMode.TEACHING
                || !run.subjectId().equals(plan.id())
                || !run.ownerUsername().equals(ownerUsername)) {
            throw new IllegalArgumentException("prepared teaching run does not match the plan");
        }
    }

    public record GenerationOutcome(RunSnapshot run, LessonStatus lessonStatus) {}

    record GenerationContinuation(
            RunSnapshot run,
            GroundedTeachingAgent.BaseLessonContinuation base) {
        GenerationContinuation {
            if (run == null || base == null) throw new IllegalArgumentException("teaching continuation is invalid");
        }

        boolean hasRemainingWork() {
            return base.hasRemainingWork();
        }
    }

    private enum GenerationTarget {
        ACTIVE,
        CANDIDATE
    }
}
