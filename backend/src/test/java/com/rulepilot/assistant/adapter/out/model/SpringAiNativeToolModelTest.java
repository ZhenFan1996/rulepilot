package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolMedia;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.ModelRequest;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiNativeToolModelTest {

    @Test
    void registersDefinitionsAndReturnsProviderNativeCallsWithoutExecutingCallbacks() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER, "player")).thenReturn(chatModel);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.ANSWER, "player"))
                .thenReturn(true);
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("test-model")
                .build());
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "search_rule_evidence", "{\"query\":\"setup\"}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(output))));
        SpringAiNativeToolModel model = new SpringAiNativeToolModel(configuration);
        UUID documentVersionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        var turn = model.next(new ModelRequest(
                Role.ANSWER,
                new ToolScope("player", documentVersionId, runId, Instant.now().plusSeconds(30)),
                List.of(ConversationMessage.system("Use evidence."), ConversationMessage.user("How do I start?")),
                List.of(new ToolSpec(
                        "search_rule_evidence",
                        "Search evidence",
                        "{\"type\":\"object\"}",
                        "1",
                        "hash"))));

        assertThat(turn.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("search_rule_evidence");
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getValue().getOptions();
        assertThat(((OpenAiChatOptions) options).getExtraBody())
                .containsEntry("thinking", java.util.Map.of("type", "disabled"));
        assertThat(((OpenAiChatOptions) options).getParallelToolCalls()).isFalse();
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback ->
                assertThat(callback.getToolDefinition().name()).isEqualTo("search_rule_evidence"));
        assertThat(options.getToolContext()).containsEntry("ownerUsername", "player")
                .containsEntry("documentVersionId", documentVersionId)
                .containsEntry("runId", runId);
    }

    @Test
    void attachesVisualToolObservationsOnlyForAVisionCapableVisualModel() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn(chatModel);
        when(configuration.supportsVision(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn(true);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn(false);
        when(chatModel.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("{\"regions\":[]}").build()))));
        SpringAiNativeToolModel model = new SpringAiNativeToolModel(configuration);

        assertThat(model.supports(Role.VISUAL, "player")).isTrue();
        model.next(new ModelRequest(
                Role.VISUAL,
                new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30)),
                List.of(
                        ConversationMessage.system("Inspect literal appearance."),
                        ConversationMessage.user("Locate one cited component."),
                        ConversationMessage.visualObservation(
                                "Page observation",
                                List.of(new ToolMedia(
                                        "image/png", new byte[] {1, 2, 3}, "page", 10, 20)))),
                List.of()));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).filteredOn(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anySatisfy(message -> assertThat(message.getMedia()).hasSize(1));

        when(configuration.supportsVision(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn(false);
        assertThat(model.supports(Role.VISUAL, "player")).isFalse();
    }

    @Test
    void explicitlyDisablesToolChoiceForAFinalOpenAiCompatibleSynthesisTurn() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn("qwen");
        when(configuration.supportsVision(RuntimeModelConfiguration.Role.VISUAL, "player")).thenReturn(true);
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("test-model")
                .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("{\"regions\":[]}").build()))));
        SpringAiNativeToolModel model = new SpringAiNativeToolModel(configuration);

        model.next(new ModelRequest(
                Role.VISUAL,
                new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30)),
                List.of(
                        ConversationMessage.system("Compose from prior observations."),
                        ConversationMessage.user("Return the final JSON now.")),
                List.of()));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("none");
        assertThat(options.getParallelToolCalls()).isTrue();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }
}
