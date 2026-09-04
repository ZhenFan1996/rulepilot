package com.rulepilot.recommendation.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
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
    private final String publicationModel;
    private final Duration hedgeDelay;
    private final ExecutorService hedgedCalls;

    public SpringAiBoardGameRecommendationModel(RuntimeModelConfiguration models) {
        this(models, 0.0, "", Duration.ZERO);
    }

    public SpringAiBoardGameRecommendationModel(
            RuntimeModelConfiguration models,
            @Value("${rulepilot.bgg.recommendation-agent.temperature:0.0}") double temperature) {
        this(models, temperature, "", Duration.ZERO);
    }

    public SpringAiBoardGameRecommendationModel(
            RuntimeModelConfiguration models,
            double temperature,
            String publicationModel) {
        this(models, temperature, publicationModel, Duration.ZERO);
    }

    @Autowired
    public SpringAiBoardGameRecommendationModel(
            RuntimeModelConfiguration models,
            @Value("${rulepilot.bgg.recommendation-agent.temperature:0.0}") double temperature,
            @Value("${rulepilot.bgg.recommendation-agent.publication-model:}") String publicationModel,
            @Value("${rulepilot.bgg.recommendation-agent.hedge-delay:PT0S}") Duration hedgeDelay) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("recommendation model temperature must be between 0 and 2");
        }
        this.models = models;
        this.temperature = temperature;
        this.publicationModel = publicationModel == null ? "" : publicationModel.strip();
        if (hedgeDelay == null || hedgeDelay.isNegative()) {
            throw new IllegalArgumentException("recommendation hedge delay must not be negative");
        }
        this.hedgeDelay = hedgeDelay;
        this.hedgedCalls = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("recommendation-model-hedge-", 0).factory());
    }

    @PreDestroy
    void stopHedgedCalls() {
        hedgedCalls.shutdownNow();
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
        RuntimeModelConfiguration.ResolvedModel selected = resolvedModelFor(ownerUsername);
        ChatModel model = selected.model();
        String effectiveModelName = effectiveModelName(selected, request);
        long startedAt = System.nanoTime();
        Prompt prompt = new Prompt(
                request.messages().stream().map(this::message).toList(),
                requestOptions(selected, request, effectiveModelName)
                        .temperature(requestTemperature)
                        .build());
        ChatResponse response = invokeModel(model, prompt, selected, effectiveModelName);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("recommendation model returned no result");
        }
        logUsage(
                request,
                response,
                (System.nanoTime() - startedAt) / 1_000_000,
                requestTemperature,
                operation,
                selected,
                effectiveModelName,
                -1);
        return turn(response);
    }

    private ChatResponse invokeModel(
            ChatModel model,
            Prompt prompt,
            RuntimeModelConfiguration.ResolvedModel selected,
            String effectiveModelName) {
        if (hedgeDelay.isZero()
                || !selected.platformManaged()
                || !"qwen".equals(selected.provider())
                || !selected.modelName().equals(effectiveModelName)) {
            return model.call(prompt);
        }
        ExecutorCompletionService<ChatResponse> completion =
                new ExecutorCompletionService<>(hedgedCalls);
        Future<ChatResponse> primary = completion.submit(() -> model.call(prompt));
        Future<ChatResponse> hedge = null;
        try {
            Future<ChatResponse> early = completion.poll(hedgeDelay.toMillis(), TimeUnit.MILLISECONDS);
            if (early != null) return completedResponse(early);
            hedge = completion.submit(() -> model.call(prompt));
            ExecutionException firstFailure = null;
            for (int remaining = 2; remaining > 0; remaining--) {
                try {
                    return completedResponse(completion.take());
                } catch (ExecutionException failure) {
                    if (firstFailure != null) throw firstFailure;
                    firstFailure = failure;
                }
            }
            throw firstFailure == null
                    ? new IllegalStateException("hedged recommendation call returned no result")
                    : modelFailure(firstFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("recommendation model call was interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw modelFailure(failure);
        } finally {
            primary.cancel(true);
            if (hedge != null) hedge.cancel(true);
        }
    }

    private ChatResponse completedResponse(Future<ChatResponse> completed)
            throws InterruptedException, ExecutionException {
        return completed.get();
    }

    private RuntimeException modelFailure(ExecutionException failure) {
        Throwable cause = failure.getCause();
        return cause instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("recommendation model call failed", cause);
    }

    private ToolCallingChatOptions.Builder<?> requestOptions(
            RuntimeModelConfiguration.ResolvedModel selected,
            Request request,
            String effectiveModelName) {
        ChatModel model = selected.model();
        List<ToolCallback> callbacks = request.tools().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        ToolCallingChatOptions.Builder<?> options;
        if (model.getOptions() instanceof OpenAiChatOptions defaults) {
            OpenAiChatOptions.Builder builder = defaults.mutate();
            if (selected.deepSeekNonThinkingGeneration()) {
                builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else if ("qwen".equals(selected.provider())) {
                builder.extraBody(Map.of("enable_thinking", false));
            }
            if (!effectiveModelName.equals(selected.modelName())) builder.model(effectiveModelName);
            builder.toolChoice(openAiToolChoice(request, selected.provider()));
            if ("qwen".equals(selected.provider())) {
                builder.parallelToolCalls(request.toolChoice() == ToolChoice.AUTO);
            }
            options = builder;
        } else if (model.getOptions() instanceof GoogleGenAiChatOptions defaults) {
            GoogleGenAiChatOptions.Builder builder = defaults.mutate();
            builder.toolChoice(new GoogleGenAiChatOptions.ToolChoice(
                    request.toolChoice() == ToolChoice.REQUIRED
                            ? GoogleGenAiChatOptions.ToolChoice.Mode.ANY
                            : GoogleGenAiChatOptions.ToolChoice.Mode.AUTO,
                    request.tools().stream().map(ToolSpec::name).toList()));
            options = builder;
        } else if (model.getOptions() instanceof ToolCallingChatOptions defaults) {
            options = defaults.mutate();
        } else {
            options = ToolCallingChatOptions.builder();
        }
        options.toolCallbacks(callbacks).temperature(temperature);
        if (request.maxOutputTokens() != null) {
            options.maxTokens(request.maxOutputTokens());
        }
        return options;
    }

    private String effectiveModelName(
            RuntimeModelConfiguration.ResolvedModel selected,
            Request request) {
        boolean exactPublication = request.toolChoice() == ToolChoice.REQUIRED
                && request.tools().size() == 1;
        return exactPublication
                        && selected.platformManaged()
                        && "qwen".equals(selected.provider())
                        && !publicationModel.isBlank()
                ? publicationModel
                : selected.modelName();
    }

    private Object openAiToolChoice(Request request, String provider) {
        if (request.toolChoice() == ToolChoice.REQUIRED
                && "qwen".equals(provider)
                && request.tools().size() == 1) {
            return Map.of(
                    "type", "function",
                    "function", Map.of("name", request.tools().getFirst().name()));
        }
        return request.toolChoice() == ToolChoice.REQUIRED && !"qwen".equals(provider)
                ? "required"
                : "auto";
    }

    private Turn turn(ChatResponse response) {
        AssistantMessage output = response.getResult().getOutput();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new Turn(
                output.getText(),
                output.getToolCalls().stream()
                        .map(call -> new ToolCall(call.id(), call.name(), call.arguments()))
                        .toList(),
                completionStatus(response.getResult().getMetadata() == null
                        ? null
                        : response.getResult().getMetadata().getFinishReason()),
                tokenCount(usage == null ? null : usage.getPromptTokens()),
                tokenCount(usage == null ? null : usage.getCompletionTokens()));
    }

    private int tokenCount(Number value) {
        return value == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value.longValue()));
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
            RuntimeModelConfiguration.ResolvedModel selected,
            String effectiveModelName,
            long firstTextMs) {
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
        AssistantMessage output = response.getResult().getOutput();
        int assistantTextCharacters = output.getText() == null ? 0 : output.getText().length();
        int toolArgumentCharacters = output.getToolCalls().stream()
                .mapToInt(call -> call.arguments() == null ? 0 : call.arguments().length())
                .sum();
        LOGGER.info(
                "Recommendation model usage: operation={}, provider={}, model={}, temperature={}, elapsedMs={}, firstTextMs={}, inputCharacters={}, assistantTextCharacters={}, toolArgumentCharacters={}, promptTokens={}, completionTokens={}",
                operation,
                selected.provider(),
                effectiveModelName,
                requestTemperature,
                elapsedMs,
                firstTextMs,
                inputCharacters,
                assistantTextCharacters,
                toolArgumentCharacters,
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }

    private RuntimeModelConfiguration.ResolvedModel resolvedModelFor(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.resolvedModelFor(RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
    }

    private boolean usesFake(String ownerUsername) {
        return ownerUsername == null || ownerUsername.isBlank()
                ? models.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION)
                : models.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION, ownerUsername);
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
