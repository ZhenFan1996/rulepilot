package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolArgumentValidation;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.assistant.PrivateAgentTraceCapture;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.AnswerRetrievalContext;
import com.rulepilot.retrieval.AnswerRetrievalPlan;
import com.rulepilot.retrieval.AnswerRetrievalQuestion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Records the ordinary answer RAG boundary without turning deterministic retrieval into a model turn. */
final class AnswerEvidenceTrace {

    static final String TOOL_NAME = "retrieve_answer_evidence";
    static final String SCHEMA_VERSION = "1";
    static final String SCHEMA_HASH = "57d2e784fac0237f00072bd198f9298cfb0d1ca259a35bcc898050bdeb043414";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private AnswerEvidenceTrace() {}

    static Operation start(
            CaptureHandle candidate,
            UUID assistantRunId,
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            AnswerRetrievalPlan plan) {
        CaptureHandle capture = PrivateAgentTraceCapture.failOpen(candidate);
        UUID operationId = UUID.randomUUID();
        Operation operation = new Operation(capture, assistantRunId, operationId);
        operation.captureCall(new RetrievalRequest(question, context, plan));
        return operation;
    }

    enum Outcome {
        READY,
        REJECTED,
        FAILED
    }

    static final class Operation {

        private final CaptureHandle capture;
        private final UUID assistantRunId;
        private final UUID operationId;
        private final String callId;
        private final ResourceRef resource;
        private boolean closed;

        private Operation(CaptureHandle capture, UUID assistantRunId, UUID operationId) {
            this.capture = capture;
            this.assistantRunId = assistantRunId;
            this.operationId = operationId;
            this.callId = operationId.toString();
            this.resource = new ResourceRef(ResourceType.ASSISTANT_RUN, assistantRunId);
        }

        void ready(AnswerEvidenceRetriever.State retrievalState, List<EvidenceInput> evidence) {
            observe(
                    Outcome.READY,
                    retrievalState,
                    null,
                    null,
                    evidence,
                    "READY");
        }

        void rejected(AnswerEvidenceRetriever.State retrievalState, AnswerStatus failureStatus) {
            String statusCode = switch (retrievalState) {
                case CONFLICTING -> "CONFLICTING";
                case UNAVAILABLE -> "UNAVAILABLE";
                case READY -> "REJECTED";
            };
            observe(
                    Outcome.REJECTED,
                    retrievalState,
                    failureStatus,
                    null,
                    List.of(),
                    statusCode);
        }

        void failed(String failureCode) {
            observe(
                    Outcome.FAILED,
                    null,
                    null,
                    failureCode,
                    List.of(),
                    "FAILED");
        }

        private void captureCall(RetrievalRequest request) {
            if (!capture.enabled()) return;
            try {
                String argumentsJson = JSON.writeValueAsString(request);
                capture.toolCall(new AgentTraceEvent.ToolCall(
                        context(),
                        callId,
                        TOOL_NAME,
                        argumentsJson,
                        argumentsJson,
                        SCHEMA_VERSION,
                        SCHEMA_HASH,
                        ToolArgumentValidation.ACCEPTED));
            } catch (JsonProcessingException | RuntimeException ignored) {
                // Private diagnostics must never replace the answer journey.
            }
        }

        private void observe(
                Outcome outcome,
                AnswerEvidenceRetriever.State retrievalState,
                AnswerStatus failureStatus,
                String failureCode,
                List<EvidenceInput> evidence,
                String statusCode) {
            if (closed) return;
            closed = true;
            if (!capture.enabled()) return;
            try {
                List<EvidenceInput> exactEvidence = evidence == null ? List.of() : List.copyOf(evidence);
                String observationJson = JSON.writeValueAsString(new RetrievalObservation(
                        outcome,
                        retrievalState,
                        failureStatus,
                        failureCode,
                        exactEvidence));
                capture.toolObservation(new AgentTraceEvent.ToolObservation(
                        context(),
                        callId,
                        TOOL_NAME,
                        observationJson,
                        statusCode,
                        exactEvidence.size(),
                        false,
                        List.of()));
            } catch (JsonProcessingException | RuntimeException ignored) {
                // Private diagnostics must never replace the answer journey.
            }
        }

        private TraceEventContext context() {
            return TraceEventContext.create(
                    Instant.now(), JourneyStage.ANSWER, operationId, assistantRunId, resource);
        }
    }

    private record RetrievalRequest(
            AnswerRetrievalQuestion question,
            AnswerRetrievalContext context,
            AnswerRetrievalPlan plan) {}

    private record RetrievalObservation(
            Outcome outcome,
            AnswerEvidenceRetriever.State retrievalState,
            AnswerStatus failureStatus,
            String failureCode,
            List<EvidenceInput> evidence) {}
}
