package com.rulepilot.teaching.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.LifecycleSignal;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.PublicationChannel;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.AgentExecutionStoppedException;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.document.DocumentVersionScopeLookup;
import com.rulepilot.teaching.domain.IllustratedLesson;
import com.rulepilot.teaching.domain.IllustratedLesson.LessonStatus;
import com.rulepilot.teaching.domain.TeachingPlan;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import java.util.List;
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
    private static final ObjectMapper TRACE_JSON = new ObjectMapper().findAndRegisterModules();

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
        return startGeneration(
                teachingPlanId, ownerUsername, initialRun, CaptureHandle.noop());
    }

    GenerationContinuation startGeneration(
            UUID teachingPlanId,
            String ownerUsername,
            RunSnapshot initialRun,
            CaptureHandle capture) {
        return Observation.createNotStarted("rulepilot.teaching.startup", observations)
                .contextualName("teaching-first-section")
                .observe(() -> startGenerationObserved(
                        teachingPlanId, ownerUsername, initialRun, PrivateAgentTraceCapture.failOpen(capture)));
    }

    GenerationContinuation startGeneration(TeachingPlan plan, String ownerUsername, RunSnapshot initialRun) {
        return startGeneration(plan, ownerUsername, initialRun, CaptureHandle.noop());
    }

    GenerationContinuation startGeneration(
            TeachingPlan plan,
            String ownerUsername,
            RunSnapshot initialRun,
            CaptureHandle capture) {
        requirePreparedRun(plan, ownerUsername, initialRun);
        return Observation.createNotStarted("rulepilot.teaching.startup", observations)
                .contextualName("teaching-first-section")
                .observe(() -> startGenerationObserved(
                        plan, initialRun, PrivateAgentTraceCapture.failOpen(capture)));
    }

    GenerationOutcome continueGeneration(GenerationContinuation continuation) {
        return continueGeneration(continuation, CaptureHandle.noop());
    }

    GenerationOutcome continueGeneration(
            GenerationContinuation continuation, CaptureHandle capture) {
        if (continuation == null) throw new IllegalArgumentException("teaching continuation is required");
        return Observation.createNotStarted("rulepilot.teaching.continuation", observations)
                .contextualName("teaching-remaining-sections")
                .observe(() -> continueGenerationObserved(
                        continuation, PrivateAgentTraceCapture.failOpen(capture)));
    }

    private GenerationContinuation startGenerationObserved(
            UUID teachingPlanId,
            String ownerUsername,
            RunSnapshot initialRun,
            CaptureHandle capture) {
        var plan = requireReadyPlan(teachingPlanId, ownerUsername);
        return startGenerationObserved(plan, initialRun, capture);
    }

    private GenerationContinuation startGenerationObserved(
            TeachingPlan plan,
            RunSnapshot initialRun,
            CaptureHandle capture) {
        RunSnapshot run = initialRun;
        try {
            run = advance(run, AssistantRunState.DOCUMENT_READINESS, "Rule document readiness is checked");
            run = advance(run, AssistantRunState.LESSON_PLANNING, "Teaching plan is loaded");
            run = advance(run, AssistantRunState.RETRIEVAL_PLANNING, "Required lesson evidence is planned");
            run = advance(run, AssistantRunState.RETRIEVING, "Allow-listed rule search is running");
            IllustratedLesson previousLesson = repository.findLatestByPlan(plan.id()).orElse(null);
            UUID activeRunId = run.id();
            var base = agent.startBase(
                    plan,
                    activeRunId,
                    previousLesson,
                    lesson -> publishProgress(activeRunId, lesson, capture),
                    capture);
            return new GenerationContinuation(run, base);
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Teaching workflow stopped by execution budget", stopped);
            captureFailure(capture, run.id(), "AGENT_" + stopped.reason().name());
            throw stopped;
        } catch (RuntimeException exception) {
            log.error(
                    "Teaching first-section workflow failed for plan {} and run {} (failureType={})",
                    plan.id(),
                    run.id(),
                    exception.getClass().getSimpleName());
            failRun(run, "TEACHING_WORKFLOW_FAILED", "Teaching workflow failed safely", exception);
            captureFailure(capture, run.id(), "TEACHING_WORKFLOW_FAILED");
            throw exception;
        }
    }

    private GenerationOutcome continueGenerationObserved(
            GenerationContinuation continuation, CaptureHandle capture) {
        RunSnapshot run = continuation.run();
        try {
            UUID activeRunId = run.id();
            IllustratedLesson lesson = agent.continueBase(
                    continuation.base(),
                    published -> publishProgress(activeRunId, published, capture),
                    capture);
            capturePublication(capture, activeRunId, lesson, PublicationChannel.TEACHING_LESSON);
            run = advanceAfterWork(run, AssistantRunState.VERIFYING_EVIDENCE, "Lesson citations are scope checked");
            return new GenerationOutcome(run, lesson.status());
        } catch (AgentExecutionStoppedException stopped) {
            failRun(run, "AGENT_" + stopped.reason().name(), "Teaching workflow stopped by execution budget", stopped);
            captureFailure(capture, run.id(), "AGENT_" + stopped.reason().name());
            throw stopped;
        } catch (RuntimeException exception) {
            log.error(
                    "Teaching continuation failed for run {} (failureType={})",
                    run.id(),
                    exception.getClass().getSimpleName());
            failRun(run, "TEACHING_WORKFLOW_FAILED", "Teaching workflow failed safely", exception);
            captureFailure(capture, run.id(), "TEACHING_WORKFLOW_FAILED");
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
            log.error(
                    "Teaching workflow failed for plan {} and run {} (failureType={})",
                    teachingPlanId,
                    run.id(),
                    exception.getClass().getSimpleName());
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

    private void publishProgress(UUID teachingRunId, IllustratedLesson lesson, CaptureHandle capture) {
        progressPublisher.publish(lesson);
        capturePublication(capture, teachingRunId, lesson, PublicationChannel.TEACHING_SECTION);
    }

    private void capturePublication(
            CaptureHandle capture,
            UUID teachingRunId,
            IllustratedLesson lesson,
            PublicationChannel channel) {
        if (!capture.enabled()) return;
        try {
            ResourceRef resource = new ResourceRef(ResourceType.TEACHING_RUN, teachingRunId);
            List<UUID> citations = lesson.sections().stream()
                    .flatMap(section -> java.util.stream.Stream.concat(
                            section.visualSourceChunkIds().stream(),
                            section.steps().stream().flatMap(step -> java.util.stream.Stream.concat(
                                    step.sourceChunkIds().stream(),
                                    step.ruleFacts().stream().flatMap(fact -> fact.sourceChunkIds().stream())))))
                    .distinct()
                    .limit(200)
                    .toList();
            capture.publication(new Publication(
                    traceContext(UUID.randomUUID(), teachingRunId, resource),
                    channel,
                    TRACE_JSON.writeValueAsString(lesson),
                    lesson.status().name(),
                    citations));
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Persisted lesson progress remains authoritative when private diagnostics are unavailable.
        }
    }

    private void captureFailure(CaptureHandle capture, UUID teachingRunId, String code) {
        if (!capture.enabled()) return;
        try {
            ResourceRef resource = new ResourceRef(ResourceType.TEACHING_RUN, teachingRunId);
            capture.bindingOrFailure(new BindingOrFailure(
                    traceContext(UUID.randomUUID(), teachingRunId, resource),
                    LifecycleSignal.FAILURE,
                    code,
                    resource,
                    null));
        } catch (RuntimeException ignored) {
            // Private diagnostics never replace persisted lesson state.
        }
    }

    private TraceEventContext traceContext(UUID operationId, UUID parentOperationId, ResourceRef resource) {
        return TraceEventContext.create(
                java.time.Instant.now(), JourneyStage.TEACHING, operationId, parentOperationId, resource);
    }

    @Transactional(readOnly = true)
    public Optional<IllustratedLesson> latest(UUID teachingPlanId) {
        return repository.findLatestByPlan(teachingPlanId);
    }

    @Transactional(readOnly = true)
    public Optional<TeachingPlanSummary.LessonProgress> latestProgress(UUID teachingPlanId) {
        return repository.findLatestProgressSummariesByPlans(List.of(teachingPlanId)).stream()
                .findFirst()
                .map(TeachingPlanSummary.LessonProgress::from);
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
