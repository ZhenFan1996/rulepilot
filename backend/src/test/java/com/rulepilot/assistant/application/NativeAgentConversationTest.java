package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.NativeToolModel.MessageRole;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeAgentConversationTest {

    @Test
    void truncatesOnlyWholeCompletedCallResultExchanges() {
        NativeAgentConversation conversation = new NativeAgentConversation(
                "system", "player", 4_096);
        ToolSpec spec = spec("hash-v1");
        appendExchange(conversation, spec, "call-1", "x".repeat(2_300));
        appendExchange(conversation, spec, "call-2", "y".repeat(2_300));

        var messages = conversation.messages();

        assertThat(messages).extracting(message -> message.role())
                .containsExactly(MessageRole.SYSTEM, MessageRole.USER, MessageRole.USER,
                        MessageRole.ASSISTANT, MessageRole.TOOL);
        assertThat(messages.get(2).content()).contains("truncated");
        assertThat(messages.get(3).toolCalls()).singleElement().extracting(ModelToolCall::id).isEqualTo("call-2");
        assertThat(messages.get(4).toolCallId()).isEqualTo("call-2");
    }

    @Test
    void rejectsIncompleteCorrelationAndStaleAdvertisedSchemaBeforeExecution() {
        NativeAgentConversation conversation = new NativeAgentConversation("system", "player", 4_096);
        ModelToolCall call = new ModelToolCall("call-1", "search_rule_evidence", "{}");
        conversation.appendAssistant("", List.of(call), List.of(spec("hash-v1")));

        assertThatThrownBy(conversation::messages)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("continuity");
        assertThatThrownBy(() -> conversation.assertAdvertisedSchema(call, spec("hash-v2")))
                .isInstanceOf(NativeAgentConversation.StaleSchemaException.class);
    }

    @Test
    void rejectsAResultThatDoesNotMatchTheNextAdvertisedCall() {
        NativeAgentConversation conversation = new NativeAgentConversation("system", "player", 4_096);
        ModelToolCall call = new ModelToolCall("call-1", "search_rule_evidence", "{}");
        conversation.appendAssistant("", List.of(call), List.of(spec("hash-v1")));

        assertThatThrownBy(() -> conversation.assertAdvertisedSchema(
                        new ModelToolCall("other", call.name(), "{}"), spec("hash-v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order");
    }

    private void appendExchange(
            NativeAgentConversation conversation, ToolSpec spec, String callId, String observation) {
        ModelToolCall call = new ModelToolCall(callId, spec.name(), "{}");
        conversation.appendAssistant("", List.of(call), List.of(spec));
        conversation.assertAdvertisedSchema(call, spec);
        conversation.appendTool(call, observation);
    }

    private ToolSpec spec(String hash) {
        return new ToolSpec(
                "search_rule_evidence", "Read evidence", "{\"type\":\"object\"}", "1", hash);
    }
}
