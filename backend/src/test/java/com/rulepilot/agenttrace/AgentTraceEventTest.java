package com.rulepilot.agenttrace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.agenttrace.AgentTraceEvent.JourneyStage;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceType;
import com.rulepilot.agenttrace.AgentTraceEvent.TraceEventContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentTraceEventTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesAndRestoresOnlyTheTypedApplicationVisibleModelTurn() throws Exception {
        ModelTurn event = new ModelTurn(
                context(JourneyStage.ANSWER),
                "openai",
                "answer-model",
                1,
                "{\"shortVerdict\":\"Allowed\"}",
                List.of(new ModelToolCall("call-1", "search_rule_evidence", "{\"query\":\"setup\"}")),
                "TOOL_CALLS",
                120,
                35,
                false);

        String encoded = json.writeValueAsString(event);
        AgentTraceEvent decoded = json.readValue(encoded, AgentTraceEvent.class);

        assertThat(encoded).contains("\"kind\":\"MODEL_TURN\"");
        assertThat(encoded).contains("Allowed", "search_rule_evidence");
        assertThat(encoded.toLowerCase())
                .doesNotContain("systemprompt", "developerprompt", "reasoning", "chainofthought");
        assertThat(decoded).isEqualTo(event);
    }

    @Test
    void exposesANoopHandleThatNeverAllocatesOrBinds() {
        CaptureHandle noop = CaptureHandle.noop();

        noop.modelTurn(null);

        assertThat(noop.enabled()).isFalse();
        assertThat(noop.traceId()).isEmpty();
        assertThat(noop.bind(new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID())))
                .isFalse();
    }

    private TraceEventContext context(JourneyStage stage) {
        return TraceEventContext.create(
                Instant.parse("2026-08-24T10:00:00Z"),
                stage,
                UUID.randomUUID(),
                null,
                new ResourceRef(ResourceType.ASSISTANT_RUN, UUID.randomUUID()));
    }
}
