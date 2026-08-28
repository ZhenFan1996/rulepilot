package com.rulepilot.recommendation.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Native action-call adapter; all actions execute inside the application-owned ReAct loop. */
@Component
@Profile("!test")
public class SpringAiBoardGameRecommendationModel implements BoardGameRecommendationModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiBoardGameRecommendationModel.class);
    private final RuntimeModelConfiguration models;
    private final double temperature;

    public SpringAiBoardGameRecommendationModel(RuntimeModelConfiguration models) {
        this(models, 0.0);
    }

    @Autowired
    public SpringAiBoardGameRecommendationModel(
            RuntimeModelConfiguration models,
            @Value("${rulepilot.bgg.recommendation-agent.temperature:0.0}") double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("recommendation model temperature must be between 0 and 2");
        }
        this.models = models;
        this.temperature = temperature;
    }

    @Override
    public boolean configured() {
        return configured(null);
    }

    @Override
    public boolean configured(String ownerUsername) {
        return !usesFake(ownerUsername);
    }

    @Override
    public Turn next(Request request) {
        return next(request, null);
    }

    @Override
    public Turn next(Request request, String ownerUsername) {
        return invoke(request, temperature, "react", ownerUsername);
    }

    private Turn invoke(
            Request request, double requestTemperature, String operation, String ownerUsername) {
        ChatModel model = modelFor(ownerUsername);
        long startedAt = System.nanoTime();
        ChatResponse response = model.call(new Prompt(
                request.messages().stream().map(this::message).toList(),
                requestOptions(model, request, ownerUsername)
                        .temperature(requestTemperature)
                        .build()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("recommendation model returned no result");
        }
        logUsage(
                request,
                response,
                (System.nanoTime() - startedAt) / 1_000_000,
                requestTemperature,
                operation,
                ownerUsername);
        return turn(response);
    }

    private ToolCallingChatOptions.Builder<?> requestOptions(
            ChatModel model,
            Request request,
            String ownerUsername) {
        List<ToolCallback> callbacks = request.tools().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        ToolCallingChatOptions.Builder<?> options;
        if (model.getOptions() instanceof OpenAiChatOptions defaults) {
            OpenAiChatOptions.Builder builder = defaults.mutate();
            if (usesDeepSeekNonThinkingGeneration(ownerUsername)) {
                builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else if ("qwen".equals(providerFor(ownerUsername))) {
                builder.extraBody(Map.of("enable_thinking", false));
            }
            // Qwen's OpenAI-compatible endpoint accepts the same typed tools reliably in auto mode. Its required
            // wire mode is unsupported by some current Qwen models and sends others down a severe latency path.
            // The provider hint does not weaken the application contract: Request remains logically REQUIRED and
            // the ReAct loop rejects zero, multiple incompatible, unknown, or schema-invalid action calls.
            builder.toolChoice("qwen".equals(providerFor(ownerUsername)) ? "auto" : "required");
            builder.parallelToolCalls(false);
            options = builder;
        } else if (model.getOptions() instanceof GoogleGenAiChatOptions defaults) {
            GoogleGenAiChatOptions.Builder builder = defaults.mutate();
            builder.toolChoice(new GoogleGenAiChatOptions.ToolChoice(
                    GoogleGenAiChatOptions.ToolChoice.Mode.ANY,
                    request.tools().stream().map(ToolSpec::name).toList()));
            options = builder;
        } else if (model.getOptions() instanceof ToolCallingChatOptions defaults) {
            options = defaults.mutate();
        } else {
            options = ToolCallingChatOptions.builder();
        }
        return options.toolCallbacks(callbacks)
                .temperature(temperature)
                .maxTokens(request.maxOutputTokens());
    }

    private Turn turn(ChatResponse response) {
        AssistantMessage output = response.getResult().getOutput();
        return new Turn(
                output.getText(),
                output.getToolCalls().stream()
                        .map(call -> new ToolCall(call.id(), call.name(), call.arguments()))
                        .toList(),
                completionStatus(response.getResult().getMetadata() == null
                        ? null
                        : response.getResult().getMetadata().getFinishReason()));
    }

    private BoardGameRecommendationModel.CompletionStatus completionStatus(String finishReason) {
        String value = finishReason == null ? "" : finishReason.strip().toLowerCase(java.util.Locale.ROOT);
        if (Set.of("length", "max_tokens", "max_output_tokens", "token_limit").contains(value)) {
            return BoardGameRecommendationModel.CompletionStatus.OUTPUT_LIMIT;
        }
        if (Set.of("stop", "tool_calls", "end_turn", "complete", "completed").contains(value)) {
            return BoardGameRecommendationModel.CompletionStatus.COMPLETE;
        }
        return BoardGameRecommendationModel.CompletionStatus.UNKNOWN;
    }

    private void logUsage(
            Request request,
            ChatResponse response,
            long elapsedMs,
            double requestTemperature,
            String operation,
            String ownerUsername) {
        int inputCharacters = request.messages().stream()
                        .mapToInt(message -> message.content().length())
                        .sum()
                + request.tools().stream()
                        .mapToInt(tool -> tool.name().length()
                                + tool.description().length()
                                + tool.inputSchema().length())
                        .sum();
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        LOGGER.info(
                "Recommendation model usage: operation={}, provider={}, model={}, temperature={}, elapsedMs={}, inputCharacters={}, maxOutputTokens={}, promptTokens={}, completionTokens={}",
                operation,
                providerFor(ownerUsername),
                modelNameFor(ownerUsername),
                requestTemperature,
                elapsedMs,
                inputCharacters,
                request.maxOutputTokens(),
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }

    private ChatModel modelFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
    }

    private String providerFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
    }

    private String modelNameFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.modelNameFor(RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
    }

    private boolean usesFake(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
    }

    private boolean usesDeepSeekNonThinkingGeneration(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
    }

    private org.springframework.ai.chat.messages.Message message(BoardGameRecommendationModel.Message message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> AssistantMessage.builder()
                    .content(message.content())
                    .toolCalls(message.toolCalls().stream()
                            .map(call -> new AssistantMessage.ToolCall(
                                    call.id(), "function", call.name(), call.argumentsJson()))
                            .toList())
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId(), message.toolName(), message.content())))
                    .build();
        };
    }

    private static final class DefinitionOnlyToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        private DefinitionOnlyToolCallback(ToolSpec spec) {
            definition = ToolDefinition.builder()
                    .name(spec.name())
                    .description(spec.description())
                    .inputSchema(spec.inputSchema())
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String input) {
            throw new IllegalStateException("recommendation actions execute only in the application-owned loop");
        }
    }
}
