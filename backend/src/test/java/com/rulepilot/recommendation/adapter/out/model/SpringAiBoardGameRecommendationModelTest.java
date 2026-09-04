package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import java.util.List;
import java.util.Map;
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
    void letsGeminiChooseAnActionOrFinishNaturally() {
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
            assertThat(choice.mode()).isEqualTo(GoogleGenAiChatOptions.ToolChoice.Mode.AUTO);
            assertThat(choice.allowedFunctionNames()).containsExactly("reply_to_user", "search_bgg");
        });
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("reply_to_user", "search_bgg");
    }

    @Test
    void requiresOneGeminiActionAfterCandidatesHaveBeenVerified() {
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
                new AssistantMessage.ToolCall("publish-1", "function", "recommend_games", "{}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(
                List.of(new ToolSpec("recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).satisfies(choice -> {
            assertThat(choice.mode()).isEqualTo(GoogleGenAiChatOptions.ToolChoice.Mode.ANY);
            assertThat(choice.allowedFunctionNames()).containsExactly("recommend_games");
        });
    }

    @Test
    void keepsDeepSeekAutoWireModeForOneActionWithoutEnablingThinking() {
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
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isNull();
        assertThat(options.getMaxTokens()).isEqualTo(4_096);
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void leavesOutputSizingToTheSelectedModelWhenTheAgentDidNotRequestACap() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("browse-1", "function", "browse", "{\"requestedCount\":1}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        Request uncapped = new Request(
                List.of(Message.system("Choose one typed action."), Message.user("Help me choose.")),
                List.of(new ToolSpec("browse", "Browse the catalog", "{\"type\":\"object\"}")),
                ToolChoice.AUTO);

        adapter.next(uncapped);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(uncapped.maxOutputTokens()).isNull();
        assertThat(options.getMaxTokens()).isNull();
    }

    @Test
    void enablesQwenParallelToolCallsWhenSeveralTypedActionsAreAvailable() {
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
        assertThat(options.getParallelToolCalls()).isTrue();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void keepsQwenOnItsSupportedAutoWireModeButSerializesTheRequiredApplicationBoundary() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("publish-1", "function", "recommend_games", "{}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(
                List.of(
                        new ToolSpec("research_game_fit", "Read experience", "{\"type\":\"object\"}"),
                        new ToolSpec("recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void selectsTheExactQwenFunctionWhenOneRequiredActionRemains() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("publish-1", "function", "recommend_games", "{}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(
                List.of(new ToolSpec(
                        "recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo(Map.of(
                "type", "function",
                "function", Map.of("name", "recommend_games")));
        assertThat(options.getParallelToolCalls()).isFalse();
    }

    @Test
    void usesTheConfiguredPublicationModelForTheStartupQwenTerminalAction() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "qwen", "qwen3.7-plus", false, true));
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("publish-1", "function", "recommend_games", "{}")));
        var adapter = new SpringAiBoardGameRecommendationModel(
                configuration, 0.0, "qwen3.7-flash");

        adapter.next(request(
                List.of(new ToolSpec(
                        "recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen3.7-flash");
        assertThat(options.getToolChoice()).isEqualTo(Map.of(
                "type", "function",
                "function", Map.of("name", "recommend_games")));
    }

    @Test
    void keepsAPersonalQwenSelectionOnItsOwnersModel() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "personal-qwen");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("publish-1", "function", "recommend_games", "{}")));
        var adapter = new SpringAiBoardGameRecommendationModel(
                configuration, 0.0, "qwen3.6-flash");

        adapter.next(request(
                List.of(new ToolSpec(
                        "recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("personal-qwen");
    }

    @Test
    void hedgesASlowStartupQwenCallAndUsesTheFirstCompletedResponse() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "qwen", "qwen3.7-plus", false, true));
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var releasePrimary = new java.util.concurrent.CountDownLatch(1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                releasePrimary.await();
            }
            return response(
                    "tool_calls",
                    new AssistantMessage.ToolCall(
                            "publish-1", "function", "recommend_games", "{}"));
        });
        var adapter = new SpringAiBoardGameRecommendationModel(
                configuration, 0.0, "", java.time.Duration.ofMillis(5));

        var turn = adapter.next(request(
                List.of(new ToolSpec(
                        "recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        assertThat(turn.toolCalls()).extracting(call -> call.name()).containsExactly("recommend_games");
        assertThat(calls).hasValue(2);
        releasePrimary.countDown();
        adapter.stopHedgedCalls();
    }

    @Test
    void usesNativeRequiredModeForOpenAiAfterCandidatesHaveBeenVerified() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "openai", "gpt-test");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("publish-1", "function", "recommend_games", "{}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(
                List.of(new ToolSpec("recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isNull();
    }

    @Test
    void keepsDeepSeekAutoWireModeWhenSeveralTypedActionsAreAvailable() {
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
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isNull();
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void keepsAutoWireModeForOtherOpenAiCompatibleProvidersWithSeveralActions() {
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
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isNull();
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void keepsAutoWireModeForAGenericCompatibleProviderWithOneAction() {
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
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isNull();
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
        assertThat(options.getToolChoice()).isEqualTo("auto");
    }

    @Test
    void rejectsARecommendationModelRequestWithoutATypedAction() {
        assertThatThrownBy(() -> new Request(
                        List.of(Message.system("Choose an action."), Message.user("Help me choose.")),
                        List.of(),
                        ToolChoice.AUTO))
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
        return request(tools, ToolChoice.AUTO);
    }

    private Request request(List<ToolSpec> tools, ToolChoice toolChoice) {
        return new Request(
                List.of(Message.system("Choose one typed action."), Message.user("Help me choose.")),
                tools,
                4_096,
                toolChoice);
    }

    private ChatResponse response(
            String finishReason,
            AssistantMessage.ToolCall call) {
        return response(finishReason, "", call);
    }

    private ChatResponse response(
            String finishReason,
            String text,
            AssistantMessage.ToolCall call) {
        AssistantMessage output = AssistantMessage.builder()
                .content(text)
                .toolCalls(List.of(call))
                .build();
        return new ChatResponse(List.of(new Generation(
                output,
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

}
