package com.rulepilot.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent;
import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.CaptureHandle;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ModelDescriptor;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationAgentTraceTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void capturesExactApplicationVisibleModelAndToolBoundariesWithoutCapturingThePrompt() throws Exception {
        RecordingCapture capture = new RecordingCapture();
        UUID turnOperation = UUID.randomUUID();
        RecommendationAgentTrace trace = RecommendationAgentTrace.begin(
                capture, json, turnOperation);
        ToolSpec tool = new ToolSpec(
                "browse_bgg_catalog",
                "Search the local catalog",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}");
        Request modelRequest = new Request(
                List.of(
                        Message.system("PRIVATE SYSTEM PROMPT MUST NOT ENTER THE TRACE"),
                        Message.user("ASSEMBLED AGENT INPUT MUST NOT ENTER THE TRACE")),
                List.of(tool),
                512,
                ToolChoice.REQUIRED);
        var rawCall = new BoardGameRecommendationModel.ToolCall(
                "call-7", "browse_bgg_catalog", "{\"z\":2,\"a\":1}");
        var rawTurn = new BoardGameRecommendationModel.Turn(
                "provider-visible pre-action text",
                List.of(rawCall),
                BoardGameRecommendationModel.CompletionStatus.COMPLETE);

        trace.modelCallStarted(1, modelRequest, List.of(tool), new ModelDescriptor("qwen", "qwen-plus"));
        trace.modelTurn(1, rawTurn, new ModelDescriptor("qwen", "qwen-plus"));
        trace.beginTool();
        trace.toolCall(rawCall, tool, true);
        trace.toolObservation(rawCall, "{\"verifiedBggIds\":[10,20,30]}", false, false);

        assertThat(capture.boundResources).isEmpty();
        assertThat(capture.events).hasSize(4);
        assertThat(capture.events).noneMatch(UserTurn.class::isInstance);
        assertThat(capture.events).filteredOn(ModelTurn.class::isInstance).singleElement()
                .isInstanceOfSatisfying(ModelTurn.class, event -> {
                    assertThat(event.assistantText()).isEqualTo("provider-visible pre-action text");
                    assertThat(event.toolCalls()).singleElement().satisfies(call ->
                            assertThat(call.argumentsJson()).isEqualTo("{\"z\":2,\"a\":1}"));
                });
        assertThat(capture.events).filteredOn(ToolCall.class::isInstance).singleElement()
                .isInstanceOfSatisfying(ToolCall.class, event -> {
                    assertThat(event.rawArgumentsJson()).isEqualTo("{\"z\":2,\"a\":1}");
                    assertThat(event.canonicalArgumentsJson()).isEqualTo("{\"a\":1,\"z\":2}");
                    assertThat(event.schemaHash()).hasSize(64);
                });
        assertThat(capture.events).filteredOn(ToolObservation.class::isInstance).singleElement()
                .isInstanceOfSatisfying(ToolObservation.class, event ->
                        assertThat(event.modelVisibleObservationJson())
                                .isEqualTo("{\"verifiedBggIds\":[10,20,30]}"));
        assertThat(capture.events).noneMatch(Publication.class::isInstance);
        String serialized = json.writeValueAsString(capture.events);
        assertThat(serialized)
                .doesNotContain(
                        "PRIVATE SYSTEM PROMPT MUST NOT ENTER THE TRACE",
                        "ASSEMBLED AGENT INPUT MUST NOT ENTER THE TRACE");
    }

    @Test
    void remainsFailOpenWhenThePrivateCaptureRejectsEveryEvent() {
        CaptureHandle failing = new RecordingCapture() {
            @Override
            public void userTurn(UserTurn event) {
                throw new IllegalStateException("trace unavailable");
            }
        };

        assertThatCode(() -> RecommendationAgentTrace.begin(
                        failing,
                        json,
                        UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    private static class RecordingCapture implements CaptureHandle {
        private final UUID traceId = UUID.randomUUID();
        private final List<AgentTraceEvent> events = new ArrayList<>();
        private final List<ResourceRef> boundResources = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<UUID> traceId() {
            return Optional.of(traceId);
        }

        @Override
        public void userTurn(UserTurn event) {
            events.add(event);
        }

        @Override
        public void modelCallStarted(ModelCallStarted event) {
            events.add(event);
        }

        @Override
        public void modelTurn(ModelTurn event) {
            events.add(event);
        }

        @Override
        public void toolCall(ToolCall event) {
            events.add(event);
        }

        @Override
        public void toolObservation(ToolObservation event) {
            events.add(event);
        }

        @Override
        public void publication(Publication event) {
            events.add(event);
        }

        @Override
        public void bindingOrFailure(BindingOrFailure event) {
            events.add(event);
        }

        @Override
        public boolean bind(ResourceRef resource) {
            boundResources.add(resource);
            return true;
        }
    }
}
