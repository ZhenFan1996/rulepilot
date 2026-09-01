package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.AssistantRunMode;
import com.rulepilot.assistant.AssistantRunState;
import com.rulepilot.assistant.AssistantRuns;
import com.rulepilot.assistant.AssistantRuns.RunSnapshot;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.application.RuleAnswerRateLimiter.Permit;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StructuredRuleAnswerTraceTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final UUID versionId = UUID.randomUUID();

    @Test
    void capturesTheExactAcceptedModelEvidenceBeforeCompositionUnderTheAnswerRun() throws Exception {
        UUID runId = UUID.randomUUID();
        RuleEvidenceHit source = source("The active player may move one space.");
        List<String> timeline = new ArrayList<>();
        RecordingCapture capture = new RecordingCapture(timeline);
        AtomicReference<ModelRequest> composed = new AtomicReference<>();
        RuleAnswerModel model = request -> {
            timeline.add("compose");
            composed.set(request);
            return draft(source);
        };

        StructuredRuleAnswerService.AnswerCreation creation = service(
                        search(source), model, null, runs(runId))
                .answerWithRun(
                        "How far may the active player move?",
                        new QuestionContext(versionId),
                        "alice",
                        null,
                        ignored -> {},
                        capture);

        assertThat(creation.answer().status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(capture.toolCalls).singleElement();
        assertThat(capture.toolObservations).singleElement();
        ToolCall call = capture.toolCalls.getFirst();
        ToolObservation observation = capture.toolObservations.getFirst();
        assertPairedAnswerRunOperation(call, observation, runId);
        assertThat(call.toolName()).isEqualTo(AnswerEvidenceTrace.TOOL_NAME);
        assertThat(call.rawArgumentsJson()).isEqualTo(call.canonicalArgumentsJson());
        JsonNode arguments = JSON.readTree(call.canonicalArgumentsJson());
        assertThat(arguments.at("/context/documentVersionId").asText()).isEqualTo(versionId.toString());
        assertThat(arguments.at("/question/currentQuestion").asText())
                .isEqualTo("How far may the active player move?");

        JsonNode observed = JSON.readTree(observation.modelVisibleObservationJson());
        assertThat(observed.path("outcome").asText()).isEqualTo("READY");
        assertThat(observed.path("retrievalState").asText()).isEqualTo("READY");
        assertThat(observed.path("evidence"))
                .isEqualTo(JSON.valueToTree(composed.get().evidence()));
        assertThat(observation.evidenceCount()).isEqualTo(composed.get().evidence().size());
        assertThat(timeline.indexOf("toolObservation")).isLessThan(timeline.indexOf("compose"));
        assertThat(capture.modelTurns).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(EvidenceFailure.class)
    void closesRejectedUnavailableAndConflictingEvidenceWithoutInventingAModelTurn(EvidenceFailure failure)
            throws Exception {
        UUID runId = UUID.randomUUID();
        RuleEvidenceHit initial = source("A deterministic candidate that must not reach composition.");
        List<String> timeline = new ArrayList<>();
        RecordingCapture capture = new RecordingCapture(timeline);
        AnswerEvidenceRefiner refiner = (ignoredRun,
                        question,
                        context,
                        username,
                        session,
                        deterministic) -> failure.result();
        RuleAnswerModel model = request -> {
            throw new AssertionError("rejected evidence must stop before composition");
        };

        StructuredRuleAnswer answer = service(search(initial), model, refiner, runs(runId))
                .answerWithRun(
                        "What is the rule?",
                        new QuestionContext(versionId),
                        "alice",
                        null,
                        ignored -> {},
                        capture)
                .answer();

        assertThat(answer.status()).isEqualTo(failure.answerStatus);
        assertThat(capture.toolCalls).singleElement();
        assertThat(capture.toolObservations).singleElement();
        ToolObservation observation = capture.toolObservations.getFirst();
        assertPairedAnswerRunOperation(capture.toolCalls.getFirst(), observation, runId);
        assertThat(observation.statusCode()).isEqualTo(failure.traceStatus);
        assertThat(observation.evidenceCount()).isZero();
        JsonNode observed = JSON.readTree(observation.modelVisibleObservationJson());
        assertThat(observed.path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(observed.path("retrievalState").asText()).isEqualTo(failure.retrievalState.name());
        assertThat(observed.path("failureStatus").asText()).isEqualTo(failure.answerStatus.name());
        assertThat(observed.path("evidence")).isEqualTo(JSON.createArrayNode());
        assertThat(timeline).doesNotContain("compose");
        assertThat(capture.modelTurns).isEmpty();
    }

    @Test
    void tracesRefinedEvidenceRatherThanTheEarlierDeterministicCandidates() throws Exception {
        UUID runId = UUID.randomUUID();
        RuleEvidenceHit initial = source("A generic overview.");
        RuleEvidenceHit refined = source("The exact rule permits one move.");
        AtomicReference<ModelRequest> composed = new AtomicReference<>();
        RecordingCapture capture = new RecordingCapture(new ArrayList<>());
        AnswerEvidenceRefiner refiner = (ignoredRun,
                        question,
                        context,
                        username,
                        session,
                        deterministic) -> new AnswerEvidenceRetriever.Result(
                        List.of(hit(refined)), AnswerEvidenceRetriever.State.READY);
        RuleAnswerModel model = request -> {
            composed.set(request);
            return draft(refined);
        };

        StructuredRuleAnswer answer = service(search(initial), model, refiner, runs(runId))
                .answerWithRun(
                        "May I move?",
                        new QuestionContext(versionId),
                        "alice",
                        null,
                        ignored -> {},
                        capture)
                .answer();

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        JsonNode observedEvidence = JSON.readTree(
                        capture.toolObservations.getFirst().modelVisibleObservationJson())
                .path("evidence");
        assertThat(observedEvidence).isEqualTo(JSON.valueToTree(composed.get().evidence()));
        assertThat(observedEvidence.findValuesAsText("chunkId"))
                .containsExactly(refined.chunkId().toString())
                .doesNotContain(initial.chunkId().toString());
        assertThat(answer.citations()).extracting(citation -> citation.chunkId())
                .containsExactly(refined.chunkId());
        assertThat(capture.modelTurns).isEmpty();
    }

    private void assertPairedAnswerRunOperation(ToolCall call, ToolObservation observation, UUID runId) {
        ResourceRef expectedResource = new ResourceRef(ResourceType.ASSISTANT_RUN, runId);
        assertThat(observation.callId()).isEqualTo(call.callId());
        assertThat(call.callId()).isEqualTo(call.context().operationId().toString());
        assertThat(observation.context().operationId()).isEqualTo(call.context().operationId());
        assertThat(call.context().parentOperationId()).isEqualTo(runId);
        assertThat(observation.context().parentOperationId()).isEqualTo(runId);
        assertThat(call.context().stage()).isEqualTo(JourneyStage.ANSWER);
        assertThat(observation.context().stage()).isEqualTo(JourneyStage.ANSWER);
        assertThat(call.context().resource()).isEqualTo(expectedResource);
        assertThat(observation.context().resource()).isEqualTo(expectedResource);
    }

    private StructuredRuleAnswerService service(
            HybridRuleSearch retrieval,
            RuleAnswerModel model,
            AnswerEvidenceRefiner refiner,
            AssistantRuns runs) {
        RuleEvidenceLookup evidenceLookup = (documentVersionId, chunkIds) -> List.of();
        return new StructuredRuleAnswerService(
                new DeterministicQuestionUnderstanding(),
                retrieval,
                VisualRulebookPageFactSearch.empty(),
                evidenceLookup,
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                (documentVersionId, expansionIds, question, username) -> Optional.empty(),
                new PolicyEvidenceVerifier(),
                (request, risk) -> new Review(false, List.of()),
                runs,
                new ImmediateAuditedAgentInvocations(),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                refiner);
    }

    private AssistantRuns runs(UUID runId) {
        AssistantRuns runs = mock(AssistantRuns.class);
        Instant now = Instant.now();
        AtomicReference<RunSnapshot> current = new AtomicReference<>(new RunSnapshot(
                runId,
                AssistantRunMode.QUESTION_ANSWER,
                versionId,
                "alice",
                AssistantRunState.RECEIVED,
                1,
                now,
                now,
                null,
                null));
        when(runs.start(any(AssistantRunMode.class), any(UUID.class), anyString()))
                .thenAnswer(ignored -> current.get());
        when(runs.advance(
                        eq(runId),
                        anyLong(),
                        any(AssistantRunState.class),
                        anyString()))
                .thenAnswer(invocation -> {
                    RunSnapshot previous = current.get();
                    RunSnapshot next = new RunSnapshot(
                            runId,
                            previous.mode(),
                            previous.subjectId(),
                            previous.ownerUsername(),
                            invocation.getArgument(2),
                            previous.revision() + 1,
                            previous.createdAt(),
                            Instant.now(),
                            null,
                            null);
                    current.set(next);
                    return next;
                });
        return runs;
    }

    private HybridRuleSearch search(RuleEvidenceHit source) {
        return (documentVersionId, query, options) -> List.of(hit(source));
    }

    private RuleEvidenceHit source(String excerpt) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "RULE", "Rule", excerpt, 8, 8, 0.9);
    }

    private static HybridEvidenceHit hit(RuleEvidenceHit source) {
        return new HybridEvidenceHit(source, source.score(), 1, null, false);
    }

    private ModelDraft draft(RuleEvidenceHit source) {
        return new ModelDraft(
                true,
                null,
                "One move is allowed.",
                "The cited rule permits that move.",
                List.of(source.chunkId()),
                List.of(),
                "HIGH",
                "DIRECT_RULE");
    }

    private enum EvidenceFailure {
        REJECTED(AnswerEvidenceRetriever.State.READY, "REJECTED", AnswerStatus.INSUFFICIENT_EVIDENCE),
        UNAVAILABLE(AnswerEvidenceRetriever.State.UNAVAILABLE, "UNAVAILABLE", AnswerStatus.INVALID_MODEL_OUTPUT),
        CONFLICTING(AnswerEvidenceRetriever.State.CONFLICTING, "CONFLICTING", AnswerStatus.INSUFFICIENT_EVIDENCE);

        private final AnswerEvidenceRetriever.State retrievalState;
        private final String traceStatus;
        private final AnswerStatus answerStatus;

        EvidenceFailure(
                AnswerEvidenceRetriever.State retrievalState,
                String traceStatus,
                AnswerStatus answerStatus) {
            this.retrievalState = retrievalState;
            this.traceStatus = traceStatus;
            this.answerStatus = answerStatus;
        }

        private AnswerEvidenceRetriever.Result result() {
            return new AnswerEvidenceRetriever.Result(List.of(), retrievalState);
        }
    }

    private static final class RecordingCapture implements CaptureHandle {

        private final UUID traceId = UUID.randomUUID();
        private final List<String> timeline;
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private final List<ToolObservation> toolObservations = new ArrayList<>();
        private final List<ModelTurn> modelTurns = new ArrayList<>();

        private RecordingCapture(List<String> timeline) {
            this.timeline = timeline;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<UUID> traceId() {
            return Optional.of(traceId);
        }

        @Override
        public void userTurn(UserTurn event) {}

        @Override
        public void modelCallStarted(ModelCallStarted event) {}

        @Override
        public void modelTurn(ModelTurn event) {
            modelTurns.add(event);
        }

        @Override
        public void toolCall(ToolCall event) {
            toolCalls.add(event);
            timeline.add("toolCall");
        }

        @Override
        public void toolObservation(ToolObservation event) {
            toolObservations.add(event);
            timeline.add("toolObservation");
        }

        @Override
        public void publication(Publication event) {}

        @Override
        public void bindingOrFailure(BindingOrFailure event) {}

        @Override
        public boolean bind(ResourceRef resource) {
            return true;
        }
    }

    private static final class InMemoryAnswerCache implements RuleAnswerCache {

        private final Map<AnswerCacheKey, StructuredRuleAnswer> values = new HashMap<>();

        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            values.put(key, answer);
        }
    }

    private static final class RecordingRateLimiter implements RuleAnswerRateLimiter {

        @Override
        public void checkUser(String username) {}

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            return () -> {};
        }
    }

    private static final class MutableRuleDataVersion implements RuleDataVersion {

        private long value = 1;

        @Override
        public long current(UUID documentVersionId) {
            return value;
        }

        @Override
        public long increment(UUID documentVersionId) {
            return ++value;
        }
    }
}
