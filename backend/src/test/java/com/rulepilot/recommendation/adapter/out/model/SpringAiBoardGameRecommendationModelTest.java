package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiBoardGameRecommendationModelTest {

    @Test
    void requiresOneOfTheAdvertisedNativeActionsFromGemini() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "gemini", "gemini-test", false));
        when(chatModel.getOptions()).thenReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-test")
                .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("reply-1", "function", "reply_to_user", "{\"playerReply\":\"你好\"}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(
                new ToolSpec("reply_to_user", "Reply naturally", "{\"type\":\"object\"}"),
                new ToolSpec("search_bgg", "Search the catalog", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).satisfies(choice -> {
            assertThat(choice.mode()).isEqualTo(GoogleGenAiChatOptions.ToolChoice.Mode.ANY);
            assertThat(choice.allowedFunctionNames()).containsExactly("reply_to_user", "search_bgg");
        });
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("reply_to_user", "search_bgg");
    }

    @Test
    void keepsDeepSeekRequiredWireModeForOneActionWithoutEnablingThinking() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "deepseek", "deepseek-v4-flash");
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "deepseek", "deepseek-v4-flash", true));
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("ask-1", "function", "ask", "{\"question\":\"几个人玩？\"}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(new ToolSpec("ask", "Ask one useful question", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void selectsTheOnlyQwenActionByExactName() throws Exception {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("browse-1", "function", "browse", "{\"requestedCount\":1}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(new ToolSpec("browse", "Browse the catalog", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertNamedFunctionChoice(options, "browse");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void keepsQwenAutoWireModeWhenSeveralTypedActionsAreAvailable() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("browse-1", "function", "browse", "{\"requestedCount\":1}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(
                new ToolSpec("browse", "Browse the catalog", "{\"type\":\"object\"}"),
                new ToolSpec("reply_to_user", "Reply naturally", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void keepsDeepSeekRequiredWireModeWhenSeveralTypedActionsAreAvailable() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "deepseek", "deepseek-v4-flash");
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "deepseek", "deepseek-v4-flash", true));
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("ask-1", "function", "ask", "{\"question\":\"几个人玩？\"}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(
                new ToolSpec("ask", "Ask one useful question", "{\"type\":\"object\"}"),
                new ToolSpec("search_bgg", "Search the catalog", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void keepsRequiredWireModeForOtherOpenAiCompatibleProvidersWithSeveralActions() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "openai", "gpt-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("search-1", "function", "search_bgg", "{\"query\":\"co-op\"}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(
                new ToolSpec("ask", "Ask one useful question", "{\"type\":\"object\"}"),
                new ToolSpec("search_bgg", "Search the catalog", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void keepsRequiredWireModeForAGenericCompatibleProviderWithOneAction() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "compatible", "vendor-model");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("search-1", "function", "search_bgg", "{\"query\":\"co-op\"}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(
                new ToolSpec("search_bgg", "Search the catalog", "{\"type\":\"object\"}"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void exposesProviderOutputLimitsToTheAgent() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "length",
                new AssistantMessage.ToolCall("browse-1", "function", "browse", "{\"requestedCount\":1}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        var turn = adapter.next(request(List.of(
                new ToolSpec("browse", "Browse the catalog", "{\"type\":\"object\"}"))));

        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.OUTPUT_LIMIT);
    }

    @Test
    void keepsOneResolvedProviderSnapshotForTheEntireOwnedCall() throws Exception {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        RuntimeModelConfiguration.ResolvedModel selection = new RuntimeModelConfiguration.ResolvedModel(
                chatModel, "qwen", "qwen-snapshot", false);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice"))
                .thenReturn(selection);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen-snapshot")
                .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("browse-1", "function", "browse", "{\"requestedCount\":1}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(
                request(List.of(new ToolSpec("browse", "Browse the catalog", "{\"type\":\"object\"}"))),
                "alice");

        verify(configuration).resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, "alice");
        verifyNoMoreInteractions(configuration);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
        assertNamedFunctionChoice(options, "browse");
    }

    @Test
    void rejectsARecommendationModelRequestWithoutATypedAction() {
        assertThatThrownBy(() -> new Request(
                        List.of(Message.system("Choose an action."), Message.user("Help me choose.")),
                        List.of(),
                        512,
                        ToolChoice.REQUIRED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ChatModel compatibleModel(
            RuntimeModelConfiguration configuration,
            String provider,
            String modelName) {
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, provider, modelName, false));
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model(modelName)
                .build());
        return chatModel;
    }

    private Request request(List<ToolSpec> tools) {
        return new Request(
                List.of(Message.system("Choose one typed action."), Message.user("Help me choose.")),
                tools,
                512,
                ToolChoice.REQUIRED);
    }

    private void assertNamedFunctionChoice(OpenAiChatOptions options, String expectedName) throws Exception {
        assertThat(options.getToolChoice()).isInstanceOf(ChatCompletionToolChoiceOption.class);
        ChatCompletionToolChoiceOption choice = (ChatCompletionToolChoiceOption) options.getToolChoice();
        assertThat(choice.isNamedToolChoice()).isTrue();
        assertThat(choice.asNamedToolChoice().function().name()).isEqualTo(expectedName);

        var wire = ObjectMappers.jsonMapper()
                .readTree(ObjectMappers.jsonMapper().writeValueAsString(choice));
        assertThat(wire.size()).isEqualTo(2);
        assertThat(wire.path("type").asText()).isEqualTo("function");
        assertThat(wire.path("function").size()).isOne();
        assertThat(wire.path("function").path("name").asText()).isEqualTo(expectedName);
    }

    private ChatResponse response(
            String finishReason,
            AssistantMessage.ToolCall call) {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(call))
                .build();
        return new ChatResponse(List.of(new Generation(
                output,
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }
}
