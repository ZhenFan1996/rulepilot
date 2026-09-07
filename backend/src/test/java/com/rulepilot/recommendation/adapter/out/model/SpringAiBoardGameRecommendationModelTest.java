package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.IncrementalToolCallChatModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolCall;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
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
import reactor.core.publisher.Flux;

class SpringAiBoardGameRecommendationModelTest {

    @Test
    void roundTripsPrivateProviderContinuationWithoutPublishingOrSerializingIt() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var received = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(json.readTree(exchange.getRequestBody()));
            String events = """
                    data: {"id":"completion","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"reasoning_content":"private-fragment-"},"finish_reason":null}]}

                    data: {"id":"completion","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"reasoning_content":"complete","tool_calls":[{"index":0,"id":"publish-1","type":"function","function":{"name":"publish","arguments":"{}"}}]},"finish_reason":null}]}

                    data: {"id":"completion","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """;
            byte[] bytes = events.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new com.rulepilot.modelconfig.adapter.out.ChatModelFactory(
                    io.micrometer.observation.ObservationRegistry.NOOP, java.time.Duration.ofSeconds(5))
                    .create("deepseek", "test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "deepseek-v4-pro");
            var configuration = mock(RuntimeModelConfiguration.class);
            when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                    .thenReturn(new RuntimeModelConfiguration.ResolvedModel(model, "deepseek", "deepseek-v4-pro", false));
            var adapter = new SpringAiBoardGameRecommendationModel(configuration);
            var search = new ToolCall("search-1", "search", "{}");
            var prior = new BoardGameRecommendationModel.Turn("", List.of(search), CompletionStatus.COMPLETE, 1, 1, "private-prior-state");
            var messages = List.of(Message.user("Choose a game"), Message.assistant(prior), Message.tool(search, "{}"));
            List<ToolCall> visible = new ArrayList<>();
            var turn = adapter.nextStreaming(new Request(messages,
                    List.of(new ToolSpec("publish", "Publish the complete response", "{\"type\":\"object\"}")), ToolChoice.REQUIRED),
                    null, visible::add);

            assertThat(received.get().path("tool_choice").asText()).isEqualTo("auto");
            assertThat(received.get().path("thinking").path("type").asText()).isEqualTo("enabled");
            assertThat(received.get().path("messages").get(1).path("reasoning_content").asText())
                    .isEqualTo("private-prior-state");
            assertThat(turn.privateReasoning()).isEqualTo("private-fragment-complete");
            assertThat(turn.toolCalls()).containsExactly(new ToolCall("publish-1", "publish", "{}"));
            assertThat(json.writeValueAsString(turn)).doesNotContain("private-fragment", "privateReasoning");
            assertThat(json.writeValueAsString(Message.assistant(turn))).doesNotContain("private-fragment", "privateReasoning");
            assertThat(turn.toString() + Message.assistant(turn) + visible).doesNotContain("private-fragment", "private-prior");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesAnUnexpectedToolNameForApplicationOwnedAvailabilityFeedback() {
        var configuration = mock(RuntimeModelConfiguration.class);
        var model = mock(StreamingChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-v4-pro").build());
        when(model.supportsIncrementalToolCallChunks()).thenReturn(true);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(model, "deepseek", "deepseek-v4-pro", true));
        when(model.streamToolCallChunks(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("unexpected", "search", "{}", "tool_calls")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        var turn = adapter.nextStreaming(request(List.of(new ToolSpec("publish", "Publish", "{}")),
                ToolChoice.REQUIRED), null, ignored -> {});

        assertThat(turn.toolCalls()).containsExactly(new ToolCall("unexpected", "search", "{}"));
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void reassemblesRawArgumentDeltasWithoutInterpretingRepeatedPrefixes() {
        var configuration = mock(RuntimeModelConfiguration.class);
        var model = mock(StreamingChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        when(model.supportsIncrementalToolCallChunks()).thenReturn(true);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(model, "compatible", "test-model", false));
        when(model.streamToolCallChunks(any(Prompt.class))).thenReturn(Flux.just(
                chunk("nested", "lookup", "{\"query\":", ""),
                chunk("", "", "{\"query\":\"harbor\"}", ""),
                chunk("", "", "}", "tool_calls")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        var turn = adapter.nextStreaming(request(List.of(new ToolSpec("lookup", "Lookup", "{}")),
                ToolChoice.AUTO), null, ignored -> {});

        assertThat(turn.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.argumentsJson()).isEqualTo("{\"query\":{\"query\":\"harbor\"}}"));
    }

    @Test
    void publishesRawProviderArgumentDeltasWithoutWaitingForTheCompletedToolCall() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        StreamingChatModel chatModel = mock(StreamingChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("qwen3.7-plus")
                .build());
        when(chatModel.supportsIncrementalToolCallChunks()).thenReturn(true);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "qwen", "qwen3.7-plus", false, false, true));
        when(chatModel.streamToolCallChunks(any(Prompt.class))).thenReturn(Flux.just(
                chunk("", "", "{\"selections\":[{", ""),
                chunk("call-1", "recommend_games", "", ""),
                chunk("", "", "\"bggId\":1}]", ""),
                chunk("", "", ",\"playerReply\":\"好了\"}", "tool_calls")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration, 0.0);
        List<ToolCall> accumulated = new ArrayList<>();

        var turn = adapter.nextStreaming(
                request(
                        List.of(new ToolSpec(
                                "recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                        ToolChoice.REQUIRED),
                null,
                accumulated::add);

        assertThat(accumulated).allSatisfy(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("recommend_games");
        });
        assertThat(accumulated).extracting(ToolCall::argumentsJson).containsExactly(
                "{\"selections\":[{",
                "{\"selections\":[{\"bggId\":1}]",
                "{\"selections\":[{\"bggId\":1}],\"playerReply\":\"好了\"}");
        assertThat(turn.toolCalls()).containsExactly(new ToolCall(
                "call-1",
                "recommend_games",
                "{\"selections\":[{\"bggId\":1}],\"playerReply\":\"好了\"}"));
        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.COMPLETE);
        verify(chatModel).streamToolCallChunks(any(Prompt.class));
        verify(chatModel, never()).stream(any(Prompt.class));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void streamsTheFirstAutoActionAndStillReassemblesEveryReturnedAction() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        StreamingChatModel chatModel = mock(StreamingChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .model("qwen3.7-plus")
                .build());
        when(chatModel.supportsIncrementalToolCallChunks()).thenReturn(true);
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "qwen", "qwen3.7-plus", false, false, true));
        when(chatModel.streamToolCallChunks(any(Prompt.class))).thenReturn(Flux.just(
                chunk(0, "search", "search_bgg", "{\"decisionBrief\":{", ""),
                chunk(1, "research", "research_fit", "{\"bggIds\":[1]}", ""),
                chunk(0, "", "", "\"chosenAction\":\"search_bgg\"}}", "tool_calls")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration, 0.0);
        List<ToolCall> accumulated = new ArrayList<>();

        var turn = adapter.nextStreaming(
                request(List.of(
                        new ToolSpec("search_bgg", "Search", "{\"type\":\"object\"}"),
                        new ToolSpec("research_fit", "Research", "{\"type\":\"object\"}")),
                        ToolChoice.AUTO),
                null,
                accumulated::add);

        assertThat(accumulated).allSatisfy(call -> {
            assertThat(call.id()).isEqualTo("search");
            assertThat(call.name()).isEqualTo("search_bgg");
        });
        assertThat(accumulated).extracting(ToolCall::argumentsJson).containsExactly(
                "{\"decisionBrief\":{",
                "{\"decisionBrief\":{\"chosenAction\":\"search_bgg\"}}");
        assertThat(turn.toolCalls()).containsExactly(
                new ToolCall("search", "search_bgg", "{\"decisionBrief\":{\"chosenAction\":\"search_bgg\"}}"),
                new ToolCall("research", "research_fit", "{\"bggIds\":[1]}"));
        verify(chatModel).streamToolCallChunks(any(Prompt.class));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void streamsAndReassemblesRequiredQwenActionArgumentsWithBlankContinuationIdentity() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "qwen", "qwen3.7-plus");
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "qwen", "qwen3.7-plus", false, false, true));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                response(
                        null,
                        new AssistantMessage.ToolCall(
                                "call-1", "function", "recommend_games", "{\"selections\":[{")),
                response(
                        null,
                        new AssistantMessage.ToolCall(
                                "", "function", "", "\"bggId\":1}]")),
                response(
                        "tool_calls",
                        new AssistantMessage.ToolCall(
                                "", "function", "", ",\"playerReply\":\"好了\"}"))));
        var adapter = new SpringAiBoardGameRecommendationModel(
                configuration, 0.0);
        List<ToolCall> accumulated = new ArrayList<>();

        var turn = adapter.nextStreaming(
                request(
                        List.of(new ToolSpec(
                                "recommend_games", "Publish verified games", "{\"type\":\"object\"}")),
                        ToolChoice.REQUIRED),
                null,
                accumulated::add);

        assertThat(accumulated).allSatisfy(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("recommend_games");
        });
        assertThat(accumulated).extracting(ToolCall::argumentsJson).containsExactly(
                "{\"selections\":[{",
                "{\"selections\":[{\"bggId\":1}]",
                "{\"selections\":[{\"bggId\":1}],\"playerReply\":\"好了\"}");
        assertThat(turn.toolCalls()).containsExactly(new ToolCall(
                "call-1",
                "recommend_games",
                "{\"selections\":[{\"bggId\":1}],\"playerReply\":\"好了\"}"));
        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.COMPLETE);
        verify(chatModel).stream(any(Prompt.class));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private IncrementalToolCallChatModel.Chunk chunk(
            String id, String name, String arguments, String finishReason) {
        return chunk(0, id, name, arguments, finishReason);
    }

    private IncrementalToolCallChatModel.Chunk chunk(
            int index, String id, String name, String arguments, String finishReason) {
        return new IncrementalToolCallChatModel.Chunk(
                "",
                List.of(new IncrementalToolCallChatModel.ToolCallDelta(index, id, name, arguments)),
                finishReason,
                0,
                0);
    }

    private interface StreamingChatModel extends ChatModel, IncrementalToolCallChatModel {}

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ToolChoice.class)
    void honorsResolvedDeepSeekGenerationModeAndApplicationToolChoice(ToolChoice choice) {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = compatibleModel(configuration, "deepseek", "deepseek-v4-flash");
        when(configuration.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(new RuntimeModelConfiguration.ResolvedModel(
                        chatModel, "deepseek", "deepseek-v4-flash", true));
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "tool_calls",
                new AssistantMessage.ToolCall("ask-1", "function", "ask", "{\"question\":\"几个人玩？\"}")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(request(List.of(new ToolSpec("ask", "Ask one useful question", "{\"type\":\"object\"}")), choice));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo(choice == ToolChoice.REQUIRED ? "required" : "auto");
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
