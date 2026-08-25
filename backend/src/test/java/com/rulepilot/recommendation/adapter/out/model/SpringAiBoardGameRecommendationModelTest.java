package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredOutput;
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
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

class SpringAiBoardGameRecommendationModelTest {

    @Test
    void appendsProviderJsonDeltasWithoutGuessingFromTheirContent() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen-test");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen-test")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("{\"a\":").build()))),
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("{\"a\":1}").build()))),
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("}").build(),
                        ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> deltas = new java.util.ArrayList<>();

        var turn = adapter.streamStructured(
                new Request(
                        List.of(Message.system("Return JSON."), Message.user("nested object")),
                        List.of(),
                        128,
                        ToolChoice.NONE,
                        new StructuredOutput("nested", "{\"type\":\"object\"}", false)),
                null,
                deltas::add);

        assertThat(deltas).containsExactly("{\"a\":", "{\"a\":1}", "}");
        assertThat(turn.json()).isEqualTo("{\"a\":{\"a\":1}}");
    }

    @Test
    void usesOneGeminiSystemInstructionAndNativeJsonMimeForStructuredPublication() throws Exception {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("gemini");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("gemini-test-model");
        when(chatModel.getOptions()).thenReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-test-model")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("{\"decision\":{}}").build(),
                ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        String schema = "{\"type\":\"object\",\"additionalProperties\":false}";

        adapter.streamStructured(
                new Request(
                        List.of(Message.system("Return the final envelope."), Message.user("verified state")),
                        List.of(),
                        1_200,
                        ToolChoice.NONE,
                        new StructuredOutput("recommendation_final", schema, true)),
                null,
                ignored -> {});

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        assertThat(prompt.getValue().getSystemMessages()).singleElement().satisfies(system ->
                assertThat(system.getText())
                        .contains("Return the final envelope.", schema)
                        .containsOnlyOnce("The exact JSON schema for this response"));
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getResponseSchema()).isNull();
        assertGeminiCanCreateRequest(prompt.getValue());
    }

    @Test
    void usesOneGeminiSystemInstructionForTheStructuredFirstDecision() throws Exception {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("gemini");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("gemini-test-model");
        when(chatModel.getOptions()).thenReturn(GoogleGenAiChatOptions.builder()
                .model("gemini-test-model")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"你好\"]}")
                        .build(),
                ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.streamDecision(
                new Request(
                        List.of(Message.system("Choose text or one action."), Message.user("你好")),
                        List.of(new ToolSpec("search", "Search only when useful", "{\"type\":\"object\"}")),
                        384,
                        ToolChoice.AUTO),
                null,
                ignored -> {});

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        assertThat(prompt.getValue().getSystemMessages()).singleElement().satisfies(system ->
                assertThat(system.getText())
                        .contains("Choose text or one action.", "replyDeltas", "search")
                        .containsOnlyOnce("The exact JSON schema for this response"));
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertGeminiCanCreateRequest(prompt.getValue());
    }

    @Test
    void streamsAQwenStructuredFinalWithoutAdvertisingActionsOrBufferingTheResponse() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("{\"decision\":")
                        .build()))),
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("{\"requestedCount\":1},\"replyBlocks\":[]}").build(),
                        ChatGenerationMetadata.builder().finishReason("stop").build()))),
                new ChatResponse(List.of())));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> deltas = new java.util.ArrayList<>();
        String schema = "{\"type\":\"object\"}";

        var turn = adapter.streamStructured(
                new Request(
                        List.of(Message.system("Return the final envelope."), Message.user("verified state")),
                        List.of(),
                        1_200,
                        ToolChoice.NONE,
                        new StructuredOutput("recommendation_final", schema, true)),
                null,
                deltas::add);

        assertThat(deltas).containsExactly(
                "{\"decision\":",
                "{\"requestedCount\":1},\"replyBlocks\":[]}");
        assertThat(turn.json()).isEqualTo("{\"decision\":{\"requestedCount\":1},\"replyBlocks\":[]}");
        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.COMPLETE);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        verify(chatModel, never()).call(any(Prompt.class));
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isNull();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getParallelToolCalls()).isNull();
        assertThat(options.getResponseFormat()).satisfies(format -> {
            assertThat(format.getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
            assertThat(format.getJsonSchema()).isNull();
        });
        assertThat(prompt.getValue().getInstructions())
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .anySatisfy(text -> assertThat(text).contains("The exact JSON schema", schema));
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void keepsNativeStrictJsonSchemaForOpenAiStructuredPublication() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("openai");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("gpt-5-mini");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.openai.com/v1")
                .model("gpt-5-mini")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("{\"decision\":{}}").build(),
                ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        String schema = "{\"type\":\"object\",\"additionalProperties\":false}";

        adapter.streamStructured(
                new Request(
                        List.of(Message.system("Return the final envelope."), Message.user("verified state")),
                        List.of(),
                        1_200,
                        ToolChoice.NONE,
                        new StructuredOutput("recommendation_final", schema, true)),
                null,
                ignored -> {});

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getResponseFormat()).satisfies(format -> {
            assertThat(format.getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
            assertThat(format.getJsonSchema()).isEqualTo(schema);
        });
        assertThat(prompt.getValue().getInstructions())
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .noneSatisfy(text -> assertThat(text).contains("The exact JSON schema for this response"));
    }

    @Test
    void usesJsonObjectForDeepSeekAndNeverRetriesAFailedStructuredStreamAsBufferedOutput() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("deepseek");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek-v4-flash");
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(true);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-v4-flash")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("stream failed")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        var request = new Request(
                List.of(Message.system("Return JSON."), Message.user("verified state")),
                List.of(),
                1_200,
                ToolChoice.NONE,
                new StructuredOutput("recommendation_final", "{\"type\":\"object\"}", true));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.streamStructured(request, null, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream failed");

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        verify(chatModel, never()).call(any(Prompt.class));
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getExtraBody())
                .containsEntry("thinking", java.util.Map.of("type", "disabled"));
    }

    @Test
    void rejectsMixedActionAndStructuredFinalRequestShapes() {
        ToolSpec action = new ToolSpec("search", "Search", "{\"type\":\"object\"}");
        StructuredOutput structured =
                new StructuredOutput("recommendation_final", "{\"type\":\"object\"}", true);
        List<Message> messages = List.of(Message.system("System"), Message.user("User"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Request(messages, List.of(), 512, ToolChoice.REQUIRED, structured))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Request(messages, List.of(action), 512, ToolChoice.NONE, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Request(messages, List.of(action), 512, ToolChoice.AUTO, structured))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamsTheSameAutonomousTurnWhenTheModelChoosesDirectConversation() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder()
                                .content("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"你好，\",")
                                .build()))),
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("\"今天想找什么样的游戏？\"]}").build(),
                        ChatGenerationMetadata.builder().finishReason("stop").build()))),
                new ChatResponse(List.of())));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        var turn = adapter.streamDecision(
                new Request(
                        List.of(Message.system("Choose text or one action."), Message.user("你好")),
                        List.of(new ToolSpec("search", "Search only when needed", "{\"type\":\"object\"}")),
                        384,
                        ToolChoice.AUTO),
                null,
                parts::add);

        assertThat(parts).containsExactly("你好，", "你好，今天想找什么样的游戏？");
        assertThat(turn.text()).isEqualTo("你好，今天想找什么样的游戏？");
        assertThat(turn.toolCalls()).isEmpty();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        verify(chatModel, never()).call(any(Prompt.class));
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isNull();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getParallelToolCalls()).isNull();
        assertThat(options.getResponseFormat()).satisfies(format -> {
            assertThat(format.getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        });
        assertThat(prompt.getValue().getInstructions())
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .anySatisfy(text -> assertThat(text).contains("replyDeltas", "search"));
        assertThat(options.getTemperature()).isZero();
    }

    @Test
    void neverPublishesAClosedReplyEnvelopeWhenTheProviderReportsAnOutputLimit() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[\"这段看似完整，但供应商明确标记输出被截断。\"]}")
                        .build(),
                ChatGenerationMetadata.builder().finishReason("max_tokens").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        var turn = adapter.streamDecision(
                new Request(
                        List.of(Message.system("Choose text or one action."), Message.user("请继续。")),
                        List.of(new ToolSpec("search", "Search only when needed", "{\"type\":\"object\"}")),
                        384,
                        ToolChoice.AUTO),
                null,
                parts::add);

        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.OUTPUT_LIMIT);
        assertThat(turn.text()).contains("供应商明确标记输出被截断");
        assertThat(parts)
                .as("an output-limited reply remains private so ReAct can reject the turn atomically")
                .isEmpty();
    }

    @Test
    void aggregatesAnAutonomousActionWithoutPublishingItsArgumentsAsPlayerText() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("openai");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("gpt-5-mini");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("gpt-5-mini")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("{\"mode\":\"ACTION\",\"action\":{\"name\":\"search\",\"arguments\":{\"query\":")
                        .build()))),
                new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("\"Mosaic Field\"}},\"replyDeltas\":[]}").build(),
                        ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        var turn = adapter.streamDecision(
                new Request(
                        List.of(Message.system("Choose text or one action."), Message.user("找 Mosaic Field")),
                        List.of(new ToolSpec("search", "Search a player-provided title", "{\"type\":\"object\"}")),
                        384,
                        ToolChoice.AUTO),
                null,
                parts::add);

        assertThat(parts).isEmpty();
        assertThat(turn.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("search");
            assertThat(call.argumentsJson()).contains("Mosaic Field");
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getToolChoice()).isNull();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void neverHidesASecondProviderAttemptWhenAnAutonomousStreamFails() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("compatible");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("compatible-model");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("compatible-model")
                .build());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new java.util.NoSuchElementException("fragmented action")));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.streamDecision(
                        new Request(
                                List.of(Message.system("Choose text or one action."), Message.user("Who is this alias?")),
                                List.of(new ToolSpec("discover", "Discover a public identity", "{\"type\":\"object\"}")),
                                384,
                                ToolChoice.AUTO),
                        null,
                        parts::add))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("fragmented action");
        assertThat(parts).isEmpty();
        verify(chatModel).stream(any(Prompt.class));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void reportsAStableSafeCodeWhenTheDecisionEnvelopeIsInvalid() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("{\"mode\":\"REPLY\",\"action\":null,\"replyDeltas\":[]}")
                        .build(),
                ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.streamDecision(
                        new Request(
                                List.of(Message.system("Choose text or one action."), Message.user("你好")),
                                List.of(new ToolSpec("search", "Search", "{\"type\":\"object\"}")),
                                384,
                                ToolChoice.AUTO),
                        null,
                        ignored -> {}))
                .isInstanceOfSatisfying(
                        com.rulepilot.recommendation.BoardGameRecommendationModel.ProtocolFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("DECISION_EMPTY_REPLY"));
    }

    @Test
    void letsDeepSeekChooseDirectTextOrAnActionWithoutEnablingThinking() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("deepseek");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek-v4-flash");
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn(true);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-v4-flash")
                .build());
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "clarify_preferences", "{\"question\":\"几个人玩？\"}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(output))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(new Request(
                List.of(Message.system("Use actions."), Message.user("想玩花砖物语类似机制的游戏")),
                List.of(new ToolSpec(
                        "clarify_preferences",
                        "Ask one useful question",
                        "{\"type\":\"object\"}")),
                1_200));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void requiresOneOfTheAdvertisedActionsAfterTheApplicationHasReadExternalEvidence() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "compare-1", "function", "compare_candidates",
                        "{\"message\":\"A grounded comparison\"}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(output))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        adapter.next(new Request(
                List.of(Message.system("Use the supplied action."), Message.user("Compare them.")),
                List.of(new ToolSpec(
                        "compare_candidates",
                        "Publish an attributed comparison",
                        "{\"type\":\"object\"}")),
                1_200,
                ToolChoice.REQUIRED));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isFalse();
    }

    @Test
    void preservesNativeActionCallsWhileQwenMayAlsoAnswerDirectly() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(false);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "reply_to_user", "{\"message\":\"你好\",\"referencedBggIds\":[]}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                output,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration, 0.45);

        var turn = adapter.next(new Request(
                List.of(Message.system("Be natural."), Message.user("你好")),
                List.of(new ToolSpec(
                        "reply_to_user",
                        "Reply naturally",
                        "{\"type\":\"object\"}")),
                1_200));

        assertThat(adapter.configured()).isTrue();
        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.COMPLETE);
        assertThat(turn.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("reply_to_user");
            assertThat(call.argumentsJson()).contains("你好");
        });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getTemperature()).isEqualTo(0.45);
        assertThat(options.getMaxTokens()).isEqualTo(1_200);
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("enable_thinking", false));
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback ->
                assertThat(callback.getToolDefinition().name()).isEqualTo("reply_to_user"));
    }

    @Test
    void exposesProviderOutputLimitsToTheAgentInsteadOfTreatingAValidLookingPayloadAsComplete() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "limited-1",
                        "function",
                        "reply_to_user",
                        "{\"message\":\"A syntactically valid but incomplete reply\"}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                output,
                ChatGenerationMetadata.builder().finishReason("max_tokens").build()))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);

        var turn = adapter.next(new Request(
                List.of(Message.system("Use actions."), Message.user("Compare them.")),
                List.of(new ToolSpec("reply_to_user", "Reply naturally", "{\"type\":\"object\"}")),
                600));

        assertThat(turn.completionStatus()).isEqualTo(CompletionStatus.OUTPUT_LIMIT);
        assertThat(turn.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.name()).isEqualTo("reply_to_user"));
    }

    private void assertGeminiCanCreateRequest(Prompt prompt) throws Exception {
        ChatModelFactory factory = new ChatModelFactory(
                io.micrometer.observation.ObservationRegistry.NOOP, java.time.Duration.ofSeconds(5));
        GoogleGenAiChatModel model = (GoogleGenAiChatModel) factory.create(
                "gemini", "test-api-key", null, "gemini-test-model");
        try {
            Object geminiRequest = ReflectionTestUtils.invokeMethod(model, "createGeminiRequest", prompt);
            assertThat(geminiRequest).isNotNull();
        } finally {
            model.destroy();
        }
    }
}
