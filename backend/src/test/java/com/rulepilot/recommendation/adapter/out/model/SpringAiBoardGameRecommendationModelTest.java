package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.genai.types.Schema;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.util.JacksonUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.annotation.JsonDeserialize;

class SpringAiBoardGameRecommendationModelTest {

    @Test
    void streamsTheSameAutonomousTurnWhenTheModelChoosesDirectConversation() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("qwen3.7-plus");
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("qwen3.7-plus")
                .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("你好，今天想找什么样的游戏？").build(),
                ChatGenerationMetadata.builder().finishReason("stop").build()))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        var turn = adapter.streamNext(
                new Request(
                        List.of(Message.system("Choose text or one action."), Message.user("你好")),
                        List.of(new ToolSpec("search", "Search only when needed", "{\"type\":\"object\"}")),
                        384,
                        ToolChoice.AUTO),
                null,
                parts::add);

        assertThat(parts).containsExactly("你好，今天想找什么样的游戏？");
        assertThat(turn.text()).isEqualTo("你好，今天想找什么样的游戏？");
        assertThat(turn.toolCalls()).isEmpty();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("auto");
        assertThat(options.getTemperature()).isZero();
    }

    @Test
    void aggregatesAnAutonomousActionWithoutPublishingItsArgumentsAsPlayerText() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("openai");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("gpt-5-mini");
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("gpt-5-mini")
                .build());
        AssistantMessage actionStart = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "search", "{\"query\":")))
                .build();
        AssistantMessage actionEnd = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "", "function", "", "\"Mosaic Field\"}")))
                .build();
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(actionStart))),
                new ChatResponse(List.of(new Generation(
                        actionEnd,
                        ChatGenerationMetadata.builder().finishReason("tool_calls").build())))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        var turn = adapter.streamNext(
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
    }

    @Test
    void retriesTheSameAutonomousTurnWithoutStreamingWhenACompatibleProviderBreaksActionChunks() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("compatible");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("compatible-model");
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("compatible-model")
                .build());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new java.util.NoSuchElementException("fragmented action")));
        AssistantMessage action = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "discover", "{\"query\":\"creator alias\"}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                action,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();

        var turn = adapter.streamNext(
                new Request(
                        List.of(Message.system("Choose text or one action."), Message.user("Who is this alias?")),
                        List.of(new ToolSpec("discover", "Discover a public identity", "{\"type\":\"object\"}")),
                        384,
                        ToolChoice.AUTO),
                null,
                parts::add);

        assertThat(parts).isEmpty();
        assertThat(turn.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("discover");
            assertThat(call.argumentsJson()).contains("creator alias");
        });
        verify(chatModel).stream(any(Prompt.class));
        verify(chatModel).call(any(Prompt.class));
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
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
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
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
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
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
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
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
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

    @Test
    void preservesBothCatalogIntentUpdateBranchesWhenGoogleGenAiDeserializesTheBrowseSchema() throws Exception {
        var googleMapper = JacksonUtils.getDefaultJsonMapper().rebuild()
                .addMixIn(Schema.class, GoogleSchemaMixin.class)
                .build();
        List<ToolSpec> actions = recommendationActions(List.of("U1"));
        for (ToolSpec action : actions) {
            googleMapper.readValue(action.inputSchema(), Schema.class);
        }
        ToolSpec browse = actions.stream()
                .filter(action -> action.name().equals("browse_bgg_catalog"))
                .findFirst()
                .orElseThrow();
        Schema browseSchema = googleMapper.readValue(browse.inputSchema(), Schema.class);
        Schema catalogIntentUpdate = browseSchema.properties()
                .orElseThrow()
                .get("catalogIntentUpdate");

        assertThat(catalogIntentUpdate.anyOf()).hasValueSatisfying(branches -> {
            assertThat(branches).hasSize(2);

            Schema replace = branches.getFirst();
            assertThat(replace.properties().orElseThrow()).containsKeys("operation", "criteria");
            assertThat(replace.properties().orElseThrow().get("operation").enum_())
                    .hasValue(List.of("REPLACE"));
            assertThat(replace.required()).hasValue(List.of("operation", "criteria"));
            Schema criteria = replace.properties().orElseThrow().get("criteria");
            assertThat(criteria.minItems()).contains(1L);
            Schema criterion = criteria.items().orElseThrow();
            assertThat(criterion.properties().orElseThrow())
                    .containsKeys("dimension", "value", "evidence");
            assertThat(criterion.properties().orElseThrow().get("dimension").enum_())
                    .hasValue(List.of("CATEGORY", "MECHANIC", "FAMILY", "DESIGNER", "PUBLISHER"));
            assertThat(criterion.properties().orElseThrow().get("value").minLength()).contains(1L);
            assertThat(criterion.properties().orElseThrow().get("value").maxLength()).contains(120L);
            assertThat(criterion.properties().orElseThrow().get("evidence").enum_())
                    .hasValue(List.of("U1"));
            assertThat(criterion.required())
                    .hasValue(List.of("dimension", "value", "evidence"));

            Schema clear = branches.get(1);
            assertThat(clear.properties().orElseThrow()).containsKeys("operation", "evidence");
            assertThat(clear.properties().orElseThrow().get("operation").enum_())
                    .hasValue(List.of("CLEAR"));
            assertThat(clear.properties().orElseThrow().get("evidence").enum_())
                    .hasValue(List.of("U1"));
            assertThat(clear.required()).hasValue(List.of("operation", "evidence"));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<ToolSpec> recommendationActions(List<String> preferenceEvidenceIds) throws Exception {
        Class<?> loop = Class.forName("com.rulepilot.recommendation.application.RecommendationReActLoop");
        Method actions = loop.getDeclaredMethod("actions", int.class, List.class);
        actions.setAccessible(true);
        return (List<ToolSpec>) actions.invoke(null, 3, preferenceEvidenceIds);
    }

    @JsonDeserialize(builder = Schema.Builder.class)
    abstract static class GoogleSchemaMixin {}
}
