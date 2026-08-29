package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.NativeAgentTool;
import com.rulepilot.assistant.NativeAgentTool.ToolMedia;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import com.rulepilot.assistant.NativeToolModel.MessageRole;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeAgentConversationTest {

    @Test
    void preservesEveryCompletedCallResultExchangeForTheNextDecision() {
        NativeAgentConversation conversation = new NativeAgentConversation("system", "player");
        ToolSpec spec = spec("hash-v1");
        appendExchange(conversation, spec, "call-1", "x".repeat(2_300));
        appendExchange(conversation, spec, "call-2", "y".repeat(2_300));

        var messages = conversation.messages();

        assertThat(messages).extracting(message -> message.role())
                .containsExactly(MessageRole.SYSTEM, MessageRole.USER,
                        MessageRole.ASSISTANT, MessageRole.TOOL,
                        MessageRole.ASSISTANT, MessageRole.TOOL);
        assertThat(messages.get(3).content()).isEqualTo("x".repeat(2_300));
        assertThat(messages.get(5).content()).isEqualTo("y".repeat(2_300));
    }

    @Test
    void sendsCompleteMediaOnTheNextDecisionThenRetainsOnlyItsTextualProvenance() {
        NativeAgentConversation conversation = new NativeAgentConversation("system", "player");
        ToolSpec spec = spec("hash-v1");
        ModelToolCall call = new ModelToolCall("call-1", spec.name(), "{}");
        List<ToolMedia> media = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new ToolMedia(
                        "image/png", new byte[] {(byte) index},
                        "Complete visual observation label " + "detail ".repeat(30) + index,
                        100, 100))
                .toList();
        ToolObservation observation = new ToolObservation(
                NativeAgentTool.ObservationStatus.SUCCESS,
                "COMPLETE_APPLICATION_OBSERVATION_METADATA_".repeat(3),
                Map.of("pageCount", media.size()),
                media.size(),
                media);
        conversation.appendAssistant("", List.of(call), List.of(spec));
        conversation.assertAdvertisedSchema(call, spec);
        conversation.appendTool(call, "{\"status\":\"SUCCESS\"}");
        conversation.appendVisual("Every application-selected visual follows.", observation.media());

        var nextTurn = conversation.messages();
        var laterTurn = conversation.messages();

        assertThat(observation.media()).hasSize(3);
        assertThat(observation.code()).startsWith("COMPLETE_APPLICATION_OBSERVATION_METADATA_");
        assertThat(nextTurn.getLast().media()).containsExactlyElementsOf(media);
        assertThat(nextTurn.getLast().media()).allSatisfy(item ->
                assertThat(item.label()).startsWith("Complete visual observation label"));
        assertThat(laterTurn.getLast().content()).isEqualTo("Every application-selected visual follows.");
        assertThat(laterTurn.getLast().media()).isEmpty();
    }

    @Test
    void rejectsIncompleteCorrelationAndStaleAdvertisedSchemaBeforeExecution() {
        NativeAgentConversation conversation = new NativeAgentConversation("system", "player");
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
        NativeAgentConversation conversation = new NativeAgentConversation("system", "player");
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
