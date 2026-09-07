package com.rulepilot.recommendation.adapter.out.model;

import com.rulepilot.modelconfig.IncrementalToolCallChatModel;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolChoice;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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

    @Override
    public Turn nextStreaming(
            Request request,
            String ownerUsername,
            Consumer<ToolCall> accumulatedActionListener) {
        RuntimeModelConfiguration.ResolvedModel selected = resolvedModelFor(ownerUsername);
        String effectiveModelName = selected.modelName();
        Prompt prompt = new Prompt(
                request.messages().stream().map(this::message).toList(),
                requestOptions(selected, request)
                        .temperature(temperature)
                        .build());
        long startedAt = System.nanoTime();
        AtomicLong firstOutputAt = new AtomicLong();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        AtomicReference<BoardGameRecommendationModel.CompletionStatus> completion =
                new AtomicReference<>(BoardGameRecommendationModel.CompletionStatus.UNKNOWN);
        StringBuilder text = new StringBuilder();
        StringBuilder privateReasoning = new StringBuilder();
        Map<Integer, StreamingToolCall> toolCalls = new LinkedHashMap<>();

        if (selected.model() instanceof IncrementalToolCallChatModel rawStream
                && rawStream.supportsIncrementalToolCallChunks()) {
            rawStream.streamToolCallChunks(prompt).doOnNext(chunk -> {
                privateReasoning.append(chunk.privateReasoning());
                if (chunk.promptTokens() > 0) promptTokens.set(chunk.promptTokens());
                if (chunk.completionTokens() > 0) completionTokens.set(chunk.completionTokens());
                if (!chunk.text().isEmpty()) {
                    firstOutputAt.compareAndSet(0, System.nanoTime());
                    text.append(chunk.text());
                }
                for (IncrementalToolCallChatModel.ToolCallDelta call : chunk.toolCalls()) {
                    firstOutputAt.compareAndSet(0, System.nanoTime());
                    StreamingToolCall toolCall = toolCalls.computeIfAbsent(
                            call.index(), ignored -> new StreamingToolCall());
                    if (!call.id().isBlank()) toolCall.id = call.id();
                    if (!call.name().isBlank()) toolCall.name = call.name();
                    if (!call.arguments().isEmpty()) {
                        toolCall.arguments.append(call.arguments());
                    }
                    if (call.index() == 0) toolCall.publishSnapshot(accumulatedActionListener);
                }
                if (!chunk.finishReason().isBlank()) {
                    BoardGameRecommendationModel.CompletionStatus observed =
                            completionStatus(chunk.finishReason());
                    if (observed != BoardGameRecommendationModel.CompletionStatus.UNKNOWN) {
                        completion.set(observed);
                    }
                }
            }).blockLast();
        } else {
            selected.model().stream(prompt).doOnNext(response -> {
                lastResponse.set(response);
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    return;
                }
                AssistantMessage output = response.getResult().getOutput();
                privateReasoning.append(output.getMetadata().getOrDefault("reasoningContent", ""));
                String textChunk = output.getText();
                if (textChunk != null && !textChunk.isEmpty()) {
                    firstOutputAt.compareAndSet(0, System.nanoTime());
                    text.append(textChunk);
                }
                for (int index = 0; index < output.getToolCalls().size(); index++) {
                    AssistantMessage.ToolCall chunk = output.getToolCalls().get(index);
                    firstOutputAt.compareAndSet(0, System.nanoTime());
                    StreamingToolCall toolCall = toolCalls.computeIfAbsent(
                            index, ignored -> new StreamingToolCall());
                    if (chunk.id() != null && !chunk.id().isBlank()) toolCall.id = chunk.id();
                    if (chunk.name() != null && !chunk.name().isBlank()) toolCall.name = chunk.name();
                    if (chunk.arguments() != null && !chunk.arguments().isEmpty()) {
                        toolCall.arguments.append(chunk.arguments());
                    }
                    if (index == 0) toolCall.publishSnapshot(accumulatedActionListener);
                }
                String finishReason = response.getResult().getMetadata() == null
                        ? null
                        : response.getResult().getMetadata().getFinishReason();
                BoardGameRecommendationModel.CompletionStatus observed = completionStatus(finishReason);
                if (observed != BoardGameRecommendationModel.CompletionStatus.UNKNOWN) completion.set(observed);
            }).blockLast();
        }

        ChatResponse response = lastResponse.get();
        List<ToolCall> completed = completedToolCalls(request, toolCalls);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        long firstOutputMs = firstOutputAt.get() == 0 ? -1 : (firstOutputAt.get() - startedAt) / 1_000_000;
        if (response == null) {
            logUsage(
                    request,
                    elapsedMs,
                    temperature,
                    "react_raw_stream",
                    selected,
                    effectiveModelName,
                    firstOutputMs,
                    text.length(),
                    toolCalls.values().stream().mapToInt(call -> call.arguments.length()).sum(),
                    promptTokens.get(),
                    completionTokens.get());
        } else {
            logUsage(
                    request,
                    response,
                    elapsedMs,
                    temperature,
                    "react_stream",
                    selected,
                    effectiveModelName,
                    firstOutputMs);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            promptTokens.set(tokenCount(usage == null ? null : usage.getPromptTokens()));
            completionTokens.set(tokenCount(usage == null ? null : usage.getCompletionTokens()));
        }
        return new Turn(
                text.toString(),
                completed,
                completion.get(),
                tokenCount(promptTokens.get()),
                tokenCount(completionTokens.get()),
                privateReasoning.toString());
    }

    private List<ToolCall> completedToolCalls(
            Request request,
            Map<Integer, StreamingToolCall> streamed) {
        if (streamed.isEmpty()) return List.of();
        List<ToolCall> completed = new ArrayList<>(streamed.size());
        streamed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> completed.add(entry.getValue().finish(
                        request.toolChoice() == ToolChoice.REQUIRED && request.tools().size() == 1
                                ? request.tools().getFirst().name()
                                : null,
                        entry.getKey())));
        return List.copyOf(completed);
    }

    private Turn invoke(
            Request request, double requestTemperature, String operation, String ownerUsername) {
        RuntimeModelConfiguration.ResolvedModel selected = resolvedModelFor(ownerUsername);
        ChatModel model = selected.model();
        String effectiveModelName = selected.modelName();
        long startedAt = System.nanoTime();
        Prompt prompt = new Prompt(
                request.messages().stream().map(this::message).toList(),
                requestOptions(selected, request)
                        .temperature(requestTemperature)
                        .build());
        ChatResponse response = model.call(prompt);
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

    private ToolCallingChatOptions.Builder<?> requestOptions(
            RuntimeModelConfiguration.ResolvedModel selected,
            Request request) {
        ChatModel model = selected.model();
        List<ToolCallback> callbacks = request.tools().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        ToolCallingChatOptions.Builder<?> options;
        if (model.getOptions() instanceof OpenAiChatOptions defaults) {
            OpenAiChatOptions.Builder builder = defaults.mutate();
            if ("deepseek".equals(selected.provider())) {
                builder.extraBody(Map.of("thinking", Map.of("type",
                        selected.deepSeekNonThinkingGeneration() ? "disabled" : "enabled")));
            } else if ("qwen".equals(selected.provider())) {
                builder.extraBody(Map.of("enable_thinking", false));
            }
            builder.toolChoice(openAiToolChoice(request, selected));
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

    private Object openAiToolChoice(Request request, RuntimeModelConfiguration.ResolvedModel selected) {
        String provider = selected.provider();
        // Only thinking mode requires this provider wire fallback.
        if ("deepseek".equals(provider) && !selected.deepSeekNonThinkingGeneration()) return "auto";
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
                tokenCount(usage == null ? null : usage.getCompletionTokens()),
                String.valueOf(output.getMetadata().getOrDefault("reasoningContent", "")));
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
        AssistantMessage output = response.getResult().getOutput();
        int assistantTextCharacters = output.getText() == null ? 0 : output.getText().length();
        int toolArgumentCharacters = output.getToolCalls().stream()
                .mapToInt(call -> call.arguments() == null ? 0 : call.arguments().length())
                .sum();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        logUsage(
                request,
                elapsedMs,
                requestTemperature,
                operation,
                selected,
                effectiveModelName,
                firstTextMs,
                assistantTextCharacters,
                toolArgumentCharacters,
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens().longValue(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens().longValue());
    }

    private void logUsage(
            Request request,
            long elapsedMs,
            double requestTemperature,
            String operation,
            RuntimeModelConfiguration.ResolvedModel selected,
            String effectiveModelName,
            long firstTextMs,
            int assistantTextCharacters,
            int toolArgumentCharacters,
            long promptTokens,
            long completionTokens) {
        int inputCharacters = request.messages().stream()
                        .mapToInt(message -> message.content().length())
                        .sum()
                + request.tools().stream()
                        .mapToInt(tool -> tool.name().length()
                                + tool.description().length()
                                + tool.inputSchema().length())
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
                promptTokens,
                completionTokens);
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
                    .properties(message.privateReasoning().isEmpty()
                            ? Map.of() : Map.of("reasoningContent", message.privateReasoning()))
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

    private static final class StreamingToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private void publishSnapshot(Consumer<ToolCall> listener) {
            String snapshot = arguments.toString();
            if (id == null || id.isBlank() || name == null || name.isBlank() || snapshot.isBlank()) return;
            listener.accept(new ToolCall(id, name, snapshot));
        }

        private ToolCall finish(String requiredName, int index) {
            String completedName = name == null || name.isBlank() ? requiredName : name;
            if (completedName == null || completedName.isBlank()) {
                throw new BoardGameRecommendationModel.ProtocolFailure(
                        "STREAMED_ACTION_NAME_MISSING", null);
            }
            if (arguments.isEmpty()) {
                throw new BoardGameRecommendationModel.ProtocolFailure(
                        "STREAMED_ACTION_ARGUMENTS_MISSING", null);
            }
            return new ToolCall(
                    id == null || id.isBlank() ? "streamed-action-" + index : id,
                    completedName,
                    arguments.toString());
        }


    }
}
