package com.rulepilot.recommendation.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceInterpretationRequest;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceEvidence;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceEvidenceStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceProposal;
import com.rulepilot.recommendation.BoardGameRecommendationModel.PreferenceReviewRequest;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
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
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiBoardGameRecommendationModelTest {

    @Test
    void interpretsConfirmedAndContextualPreferencesAtZeroTemperatureBeforeReact() {
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)).thenReturn("deepseek");
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION))
                .thenReturn("deepseek-v4-flash");
        when(chatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://provider.example/v1")
                .model("deepseek-v4-flash")
                .build());
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "interpret-1",
                        "function",
                        "interpret_preference_evidence",
                        "{\"preferences\":["
                                + "{\"field\":\"playerCount\",\"value\":{\"minimum\":3,\"maximum\":3},\"evidenceId\":\"U1\","
                                + "\"status\":\"CONTEXTUAL\",\"reason\":\"COMPLETE_GROUP_INFERENCE\"},"
                                + "{\"field\":\"durationMinutes\",\"value\":{\"minimum\":null,\"maximum\":60},\"evidenceId\":\"U2\","
                                + "\"status\":\"DIRECT\",\"reason\":\"DIRECT\"},"
                                + "{\"field\":\"complexity\",\"value\":null,\"evidenceId\":\"U3\","
                                + "\"status\":\"DIRECT\",\"reason\":\"DIRECT\"}]}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                output,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration, 0.8);

        var interpretation = adapter.interpretPreferences(new PreferenceInterpretationRequest(
                List.of(
                        new PreferenceEvidence("U1", "周末想带爸妈玩一局"),
                        new PreferenceEvidence("U2", "最多 60 分钟"),
                        new PreferenceEvidence("U3", "复杂度不限")),
                List.of()));

        assertThat(interpretation.preferences()).satisfiesExactly(
                preference -> {
                    assertThat(preference.field()).isEqualTo("playerCount");
                    assertThat(preference.value()).isEqualTo("3..3");
                    assertThat(preference.decision().status()).isEqualTo(PreferenceEvidenceStatus.CONTEXTUAL);
                },
                preference -> {
                    assertThat(preference.field()).isEqualTo("durationMinutes");
                    assertThat(preference.value()).isEqualTo("*..60");
                    assertThat(preference.decision().status()).isEqualTo(PreferenceEvidenceStatus.DIRECT);
                },
                preference -> {
                    assertThat(preference.field()).isEqualTo("complexity");
                    assertThat(preference.value()).isEqualTo("*..*");
                    assertThat(preference.decision().status()).isEqualTo(PreferenceEvidenceStatus.DIRECT);
                });
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getTemperature()).isZero();
        assertThat(options.getMaxTokens()).isEqualTo(500);
        assertThat(prompt.getValue().getInstructions().getFirst().getText())
                .contains("reversible working assumption", "later correction supersedes");
    }

    @Test
    void reviewsTypedPreferenceEvidenceAtZeroTemperatureWithOneStructuredDecisionPerProposal() {
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
                        "review-1",
                        "function",
                        "review_preference_evidence",
                        "{\"decisions\":["
                                + "{\"index\":0,\"status\":\"CONTEXTUAL\",\"reason\":\"COMPLETE_GROUP_INFERENCE\"},"
                                + "{\"index\":1,\"status\":\"DIRECT\",\"reason\":\"DIRECT\"}]}")))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                output,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        var adapter = new SpringAiBoardGameRecommendationModel(configuration, 0.7);

        var review = adapter.reviewPreferences(new PreferenceReviewRequest(
                List.of(
                        new PreferenceEvidence("U1", "周末想带爸妈玩一局"),
                        new PreferenceEvidence("U2", "一共 4 个人")),
                List.of(
                        new PreferenceProposal(0, "playerCount", "3..3", "U1"),
                        new PreferenceProposal(1, "playerCount", "4..4", "U2"))));

        assertThat(review.decisions()).extracting(value -> value.status())
                .containsExactly(PreferenceEvidenceStatus.CONTEXTUAL, PreferenceEvidenceStatus.DIRECT);
        assertThat(review.rawTurn().toolCalls()).singleElement()
                .extracting(call -> call.name())
                .isEqualTo("review_preference_evidence");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getTemperature()).isZero();
        assertThat(options.getMaxTokens()).isEqualTo(400);
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback ->
                assertThat(callback.getToolDefinition().name()).isEqualTo("review_preference_evidence"));
        assertThat(prompt.getValue().getInstructions().getFirst().getText())
                .contains("semantic evidence reviewer", "fully described participant group", "reversible working assumption");
    }

    @Test
    void disablesDeepSeekThinkingWhenTheActionProtocolIsRequired() {
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
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getExtraBody())
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of("thinking", java.util.Map.of("type", "disabled")));
    }

    @Test
    void preservesNativeActionCallsAndRequiresTheQwenActionProtocol() {
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
        assertThat(options.getToolChoice()).isEqualTo("required");
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
}
