package com.rulepilot.recommendation.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.ai.chat.prompt.Prompt;
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
        this(models, 0.2);
    }

    @Autowired
    public SpringAiBoardGameRecommendationModel(
            RuntimeModelConfiguration models,
            @Value("${rulepilot.bgg.recommendation-agent.temperature:0.2}") double temperature) {
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
    public Turn streamNext(
            Request request,
            String ownerUsername,
            Consumer<String> accumulatedTextListener) {
        // Qwen emits valid continuation chunks that Spring AI 2.0 cannot aggregate when actions are advertised.
        // Keep the same autonomous action turn, but execute it once through the buffered client.
        if ("qwen".equals(providerFor(ownerUsername))) {
            Turn turn = invoke(request, temperature, "react_buffered", ownerUsername);
            if (turn.toolCalls().isEmpty() && !turn.text().isBlank()) {
                accumulatedTextListener.accept(turn.text());
            }
            return turn;
        }
        ChatModel model = modelFor(ownerUsername);
        Prompt prompt = new Prompt(
                request.messages().stream().map(this::message).toList(),
                actionOptions(model, request, ownerUsername).build());
        long startedAt = System.nanoTime();
        AtomicLong firstChunkAt = new AtomicLong();
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        AtomicReference<BoardGameRecommendationModel.CompletionStatus> completion =
                new AtomicReference<>(BoardGameRecommendationModel.CompletionStatus.UNKNOWN);
        AtomicBoolean actionSeen = new AtomicBoolean();
        AtomicBoolean textEmitted = new AtomicBoolean();
        StringBuilder accumulatedText = new StringBuilder();
        List<StreamingToolCall> streamedActions = new ArrayList<>();

        try {
            model.stream(prompt).doOnNext(response -> {
                lastResponse.set(response);
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) return;
                AssistantMessage output = response.getResult().getOutput();
                if (!output.getToolCalls().isEmpty()) {
                    actionSeen.set(true);
                    mergeStreamedActions(streamedActions, output.getToolCalls());
                }
                String chunk = output.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    firstChunkAt.compareAndSet(0, System.nanoTime());
                    mergeStreamedText(accumulatedText, chunk);
                    if (!actionSeen.get()) {
                        textEmitted.set(true);
                        accumulatedTextListener.accept(accumulatedText.toString());
                    }
                }
                String finishReason = response.getResult().getMetadata() == null
                        ? null
                        : response.getResult().getMetadata().getFinishReason();
                BoardGameRecommendationModel.CompletionStatus observed = completionStatus(finishReason);
                if (observed != BoardGameRecommendationModel.CompletionStatus.UNKNOWN) completion.set(observed);
            }).blockLast();
        } catch (RuntimeException streamingFailure) {
            if (textEmitted.get()) {
                LOGGER.warn(
                        "Recommendation action stream failed after emitting text (characters={}, actionSeen={})",
                        accumulatedText.length(),
                        actionSeen.get());
                throw streamingFailure;
            }
            LOGGER.warn(
                    "Recommendation action stream was incompatible with the provider; retrying the same turn without streaming ({})",
                    streamingFailure.getClass().getSimpleName());
            Turn recovered = invoke(request, temperature, "react_stream_recovery", ownerUsername);
            if (recovered.toolCalls().isEmpty() && !recovered.text().isBlank()) {
                accumulatedTextListener.accept(recovered.text());
            }
            return recovered;
        }
        ChatResponse response = lastResponse.get();
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("recommendation model returned no streamed turn");
        }
        if (!streamedActions.isEmpty() && textEmitted.get()) {
            throw new IllegalStateException("recommendation model mixed player text with an action call");
        }
        logUsage(
                request,
                response,
                (System.nanoTime() - startedAt) / 1_000_000,
                temperature,
                "react_stream",
                ownerUsername,
                firstChunkAt.get() == 0 ? -1 : (firstChunkAt.get() - startedAt) / 1_000_000);
        List<ToolCall> actions = java.util.stream.IntStream.range(0, streamedActions.size())
                .mapToObj(index -> streamedActions.get(index).finish(index))
                .toList();
        if (actions.isEmpty() && accumulatedText.isEmpty()) {
            throw new IllegalStateException("recommendation model returned no streamed turn");
        }
        return new Turn(accumulatedText.toString(), actions, completion.get());
    }

    private void mergeStreamedText(StringBuilder accumulated, String chunk) {
        accumulated.append(chunk);
    }

    private void mergeStreamedActions(
            List<StreamingToolCall> accumulated,
            List<AssistantMessage.ToolCall> chunks) {
        for (int index = 0; index < chunks.size(); index++) {
            int position = index;
            AssistantMessage.ToolCall chunk = chunks.get(index);
            StreamingToolCall target = accumulated.stream()
                    .filter(candidate -> candidate.matches(chunk))
                    .findFirst()
                    .orElseGet(() -> position < accumulated.size()
                            ? accumulated.get(position)
                            : addStreamingToolCall(accumulated));
            target.merge(chunk);
        }
    }

    private StreamingToolCall addStreamingToolCall(List<StreamingToolCall> accumulated) {
        StreamingToolCall added = new StreamingToolCall();
        accumulated.add(added);
        return added;
    }

    private static final class StreamingToolCall {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        private boolean matches(AssistantMessage.ToolCall chunk) {
            return !blank(chunk.id()) && chunk.id().equals(id)
                    || !blank(chunk.name()) && chunk.name().equals(name);
        }

        private void merge(AssistantMessage.ToolCall chunk) {
            if (!blank(chunk.id())) id = chunk.id();
            if (!blank(chunk.name())) name = chunk.name();
            mergeFragment(arguments, chunk.arguments());
        }

        private ToolCall finish(int index) {
            if (name.isBlank()) {
                throw new IllegalStateException("recommendation model streamed an action without a name");
            }
            String correlationId = id.isBlank() ? "stream-call-" + (index + 1) : id;
            String argumentJson = arguments.isEmpty() ? "{}" : arguments.toString();
            return new ToolCall(correlationId, name, argumentJson);
        }

        private static void mergeFragment(StringBuilder accumulated, String fragment) {
            if (fragment == null || fragment.isEmpty()) return;
            String current = accumulated.toString();
            if (fragment.startsWith(current)) {
                accumulated.setLength(0);
                accumulated.append(fragment);
            } else if (!current.endsWith(fragment)) {
                accumulated.append(fragment);
            }
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    private Turn invoke(
            Request request, double requestTemperature, String operation, String ownerUsername) {
        ChatModel model = modelFor(ownerUsername);
        long startedAt = System.nanoTime();
        ChatResponse response = model.call(new Prompt(
                request.messages().stream().map(this::message).toList(),
                actionOptions(model, request, ownerUsername)
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
                ownerUsername,
                -1);
        return turn(response);
    }

    private ToolCallingChatOptions.Builder<?> actionOptions(
            ChatModel model,
            Request request,
            String ownerUsername) {
        List<ToolCallback> callbacks = request.tools().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        ToolCallingChatOptions.Builder<?> options;
        if (model.getDefaultOptions() instanceof OpenAiChatOptions defaults) {
            OpenAiChatOptions.Builder builder = defaults.mutate();
            // The first conversational turn may answer directly. Once the application has read external evidence,
            // it requires a supplied action so prose cannot bypass the typed publication boundary.
            builder.toolChoice(request.toolChoice() == BoardGameRecommendationModel.ToolChoice.REQUIRED
                    ? "required"
                    : "auto");
            if (usesDeepSeekNonThinkingGeneration(ownerUsername)) {
                // Keep the low-latency non-thinking request mode used by Recommendation. Auto tool choice remains
                // available, so a direct conversational response does not become a forced action.
                builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else if ("qwen".equals(providerFor(ownerUsername))) {
                // Qwen's non-thinking mode keeps the direct-text versus action decision observable and bounded.
                builder.extraBody(Map.of("enable_thinking", false));
            }
            builder.parallelToolCalls(false);
            options = builder;
        } else if (model.getDefaultOptions() instanceof ToolCallingChatOptions defaults) {
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
            String ownerUsername,
            long firstChunkMs) {
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
                "Recommendation model usage: operation={}, provider={}, model={}, temperature={}, firstChunkMs={}, elapsedMs={}, inputCharacters={}, maxOutputTokens={}, promptTokens={}, completionTokens={}",
                operation,
                providerFor(ownerUsername),
                modelNameFor(ownerUsername),
                requestTemperature,
                firstChunkMs,
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
