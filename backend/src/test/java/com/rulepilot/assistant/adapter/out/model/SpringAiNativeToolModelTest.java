package com.rulepilot.assistant.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeAgentTool.ToolMedia;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.ModelRequest;
import com.rulepilot.assistant.NativeToolModel.ModelRequestFailure;
import com.rulepilot.assistant.NativeToolModel.ModelRequestFailureKind;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.io.IOException;
import java.net.SocketTimeoutException;
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
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type;
import org.springframework.ai.retry.NonTransientAiException;

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
    void requestsProviderJsonModeForACustomTerminalContractWhileKeepingToolsAvailable() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("test-model")
                .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("{\"kind\":\"CHAT\",\"shortVerdict\":\"Hello\"}").build()))));
        SpringAiNativeToolModel model = new SpringAiNativeToolModel(configuration);

        model.next(new ModelRequest(
                Role.ANSWER,
                new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30)),
                List.of(ConversationMessage.system("Return JSON."), ConversationMessage.user("Hello")),
                List.of(new ToolSpec(
                        "search_rule_evidence", "Search evidence", "{\"type\":\"object\"}", "1", "hash")),
                true));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getResponseFormat().getType()).isEqualTo(Type.JSON_OBJECT);
        assertThat(options.getToolCallbacks()).hasSize(1);
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

    @Test
    void classifiesOneOpenAiCompatibleTransportTimeoutWithoutReplayingTheRequest() {
        OpenAIIoException providerFailure = new OpenAIIoException(
                "request failed", new SocketTimeoutException("socket stalled"));
        FailingFixture fixture = failingFixture(providerFailure);

        assertThatThrownBy(() -> fixture.model().next(modelRequest()))
                .isInstanceOfSatisfying(ModelRequestFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(ModelRequestFailureKind.TIMEOUT);
                    assertThat(failure.getCause()).isSameAs(providerFailure);
                });
        verify(fixture.chatModel(), times(1)).call(any(Prompt.class));
    }

    @Test
    void classifiesOpenAiCompatibleHttpTimeoutsWithoutReplayingTheRequest() {
        assertClassified(
                UnexpectedStatusCodeException.builder()
                        .statusCode(408)
                        .headers(Headers.builder().build())
                        .build(),
                ModelRequestFailureKind.TIMEOUT);
        assertClassified(
                InternalServerException.builder()
                        .statusCode(504)
                        .headers(Headers.builder().build())
                        .build(),
                ModelRequestFailureKind.TIMEOUT);
    }

    @Test
    void classifiesOneOpenAiCompatibleServerFailureWithoutReplayingTheRequest() {
        InternalServerException providerFailure = InternalServerException.builder()
                .statusCode(503)
                .headers(Headers.builder().build())
                .build();
        FailingFixture fixture = failingFixture(providerFailure);

        assertThatThrownBy(() -> fixture.model().next(modelRequest()))
                .isInstanceOfSatisfying(ModelRequestFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE);
                    assertThat(failure.getCause()).isSameAs(providerFailure);
                });
        verify(fixture.chatModel(), times(1)).call(any(Prompt.class));
    }

    @Test
    void leavesOnePermanentOpenAiCompatibleClientFailureUnclassified() {
        assertUnclassified(BadRequestException.builder()
                .headers(Headers.builder().build())
                .build());
    }

    @Test
    void classifiesSpringAiWrappedGoogleHttpFailuresWithoutReplayingTheRequest() {
        assertClassified(
                springGoogleWrapper(new ApiException(408, "REQUEST_TIMEOUT", "request timed out")),
                ModelRequestFailureKind.TIMEOUT);
        assertClassified(
                springGoogleWrapper(new ApiException(504, "GATEWAY_TIMEOUT", "gateway timed out")),
                ModelRequestFailureKind.TIMEOUT);
        assertClassified(
                springGoogleWrapper(new ApiException(429, "RESOURCE_EXHAUSTED", "quota unavailable")),
                ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE);
        assertClassified(
                springGoogleWrapper(new ApiException(503, "UNAVAILABLE", "provider unavailable")),
                ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void classifiesSpringAiWrappedGoogleTransportFailuresWithoutReplayingTheRequest() {
        assertClassified(
                springGoogleWrapper(new GenAiIOException(
                        "request failed", new SocketTimeoutException("socket stalled"))),
                ModelRequestFailureKind.TIMEOUT);
        assertClassified(
                springGoogleWrapper(new GenAiIOException(
                        "request failed", new IOException("connection reset"))),
                ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void leavesPermanentAndGenericSpringAiGoogleWrappersUnclassified() {
        assertUnclassified(springGoogleWrapper(
                new ApiException(400, "INVALID_ARGUMENT", "request was invalid")));
        assertUnclassified(new RuntimeException(
                "Failed to generate content", new IllegalStateException("generic provider failure")));
    }

    @Test
    void leavesGenericAndExplicitlyNonTransientFailuresUnclassifiedEvenWhenTheirMessagesSayTimeout() {
        assertUnclassified(new RuntimeException("provider timeout"));
        assertUnclassified(new NonTransientAiException("provider timeout"));
        assertUnclassified(new NonTransientAiException(
                "provider rejected the request",
                new ApiException(503, "UNAVAILABLE", "typed cause must not override the Spring boundary")));
    }

    private void assertClassified(
            RuntimeException providerFailure, ModelRequestFailureKind expectedKind) {
        FailingFixture fixture = failingFixture(providerFailure);

        assertThatThrownBy(() -> fixture.model().next(modelRequest()))
                .isInstanceOfSatisfying(ModelRequestFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(expectedKind);
                    assertThat(failure.getCause()).isSameAs(providerFailure);
                });
        verify(fixture.chatModel(), times(1)).call(any(Prompt.class));
    }

    private void assertUnclassified(RuntimeException providerFailure) {
        FailingFixture fixture = failingFixture(providerFailure);

        assertThatThrownBy(() -> fixture.model().next(modelRequest())).isSameAs(providerFailure);
        verify(fixture.chatModel(), times(1)).call(any(Prompt.class));
    }

    private RuntimeException springGoogleWrapper(RuntimeException providerFailure) {
        return new RuntimeException("Failed to generate content", providerFailure);
    }

    private FailingFixture failingFixture(RuntimeException providerFailure) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER, "player")).thenReturn(chatModel);
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("test-model")
                .build());
        when(chatModel.call(any(Prompt.class))).thenThrow(providerFailure);
        return new FailingFixture(new SpringAiNativeToolModel(configuration), chatModel);
    }

    private ModelRequest modelRequest() {
        return new ModelRequest(
                Role.ANSWER,
                new ToolScope("player", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30)),
                List.of(ConversationMessage.system("Use evidence."), ConversationMessage.user("How do I start?")),
                List.of());
    }

    private record FailingFixture(SpringAiNativeToolModel model, ChatModel chatModel) {}
}
