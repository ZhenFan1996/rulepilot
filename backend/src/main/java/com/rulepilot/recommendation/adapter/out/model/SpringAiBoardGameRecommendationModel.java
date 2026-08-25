package com.rulepilot.recommendation.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationModel;
import com.rulepilot.recommendation.BoardGameRecommendationModel.CompletionStatus;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Message;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Request;
import com.rulepilot.recommendation.BoardGameRecommendationModel.StructuredTurn;
import com.rulepilot.recommendation.BoardGameRecommendationModel.ToolSpec;
import com.rulepilot.recommendation.BoardGameRecommendationModel.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
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
    private static final ObjectMapper DECISION_JSON = new ObjectMapper();
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
    public Turn streamDecision(
            Request request,
            String ownerUsername,
            Consumer<String> accumulatedTextListener) {
        ChatModel model = modelFor(ownerUsername);
        if (request.toolChoice() != BoardGameRecommendationModel.ToolChoice.AUTO
                || request.structuredOutput() != null
                || request.tools().isEmpty()) {
            throw new IllegalArgumentException("first recommendation decision requires auto action options");
        }
        RecommendationDecisionStream decision =
                new RecommendationDecisionStream(DECISION_JSON, request.tools(), accumulatedTextListener);
        List<Message> decisionMessages = new ArrayList<>(request.messages());
        int protocolPosition = 0;
        while (protocolPosition < decisionMessages.size()
                && decisionMessages.get(protocolPosition).role() == BoardGameRecommendationModel.Role.SYSTEM) {
            protocolPosition++;
        }
        decisionMessages.add(
                protocolPosition,
                Message.system(decision.instruction(false)));
        Request decisionRequest = new Request(
                decisionMessages,
                List.of(),
                request.maxOutputTokens(),
                BoardGameRecommendationModel.ToolChoice.NONE,
                decision.output());
        try {
            StructuredTurn structured = streamJson(
                    model,
                    decisionRequest,
                    ownerUsername,
                    decision::accept,
                    "structured_decision_stream");
            decision.finish();
            Turn turn = decision.turn(structured.completionStatus());
            if (structured.completionStatus() != CompletionStatus.OUTPUT_LIMIT) {
                decision.publishReply();
            }
            return turn;
        } catch (RecommendationDecisionStream.Failure failure) {
            throw new BoardGameRecommendationModel.ProtocolFailure(
                    "DECISION_" + failure.code().name(), failure);
        }
    }

    @Override
    public StructuredTurn streamStructured(
            Request request,
            String ownerUsername,
            Consumer<String> jsonDeltaListener) {
        return streamJson(
                modelFor(ownerUsername),
                request,
                ownerUsername,
                jsonDeltaListener,
                "structured_publication_stream");
    }

    private StructuredTurn streamJson(
            ChatModel model,
            Request request,
            String ownerUsername,
            Consumer<String> jsonDeltaListener,
            String operation) {
        List<Message> providerMessages = new ArrayList<>(request.messages());
        if (!usesNativeJsonSchema(model, request, ownerUsername)) {
            int schemaPosition = 0;
            while (schemaPosition < providerMessages.size()
                    && providerMessages.get(schemaPosition).role() == BoardGameRecommendationModel.Role.SYSTEM) {
                schemaPosition++;
            }
            providerMessages.add(
                    schemaPosition,
                    Message.system("The exact JSON schema for this response is:\n"
                            + request.structuredOutput().jsonSchema()));
        }
        providerMessages = mergeLeadingSystemMessages(providerMessages);
        Prompt prompt = new Prompt(
                providerMessages.stream().map(this::message).toList(),
                requestOptions(model, request, ownerUsername).build());
        long startedAt = System.nanoTime();
        AtomicLong firstChunkAt = new AtomicLong();
        AtomicReference<ChatResponse> lastObservedResponse = new AtomicReference<>();
        AtomicReference<ChatResponse> lastUsableResponse = new AtomicReference<>();
        AtomicReference<BoardGameRecommendationModel.CompletionStatus> completion =
                new AtomicReference<>(BoardGameRecommendationModel.CompletionStatus.UNKNOWN);
        AtomicBoolean actionSeen = new AtomicBoolean();
        StringBuilder accumulatedJson = new StringBuilder();

        model.stream(prompt).doOnNext(response -> {
            if (response != null) lastObservedResponse.set(response);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) return;
            lastUsableResponse.set(response);
            AssistantMessage output = response.getResult().getOutput();
            if (!output.getToolCalls().isEmpty()) actionSeen.set(true);
            String chunk = output.getText();
            if (chunk != null && !chunk.isEmpty()) {
                accumulatedJson.append(chunk);
                firstChunkAt.compareAndSet(0, System.nanoTime());
                jsonDeltaListener.accept(chunk);
            }
            String finishReason = response.getResult().getMetadata() == null
                    ? null
                    : response.getResult().getMetadata().getFinishReason();
            BoardGameRecommendationModel.CompletionStatus observed = completionStatus(finishReason);
            if (observed != BoardGameRecommendationModel.CompletionStatus.UNKNOWN) completion.set(observed);
        }).blockLast();

        ChatResponse response = lastUsableResponse.get();
        if (response == null) {
            throw new IllegalStateException("recommendation model returned no structured stream");
        }
        if (actionSeen.get()) {
            throw new IllegalStateException("structured recommendation turn returned an action call");
        }
        if (accumulatedJson.isEmpty()) {
            throw new IllegalStateException("recommendation model returned an empty structured stream");
        }
        logUsage(
                request,
                lastObservedResponse.get() == null ? response : lastObservedResponse.get(),
                (System.nanoTime() - startedAt) / 1_000_000,
                temperature,
                operation,
                ownerUsername,
                firstChunkAt.get() == 0 ? -1 : (firstChunkAt.get() - startedAt) / 1_000_000);
        return new StructuredTurn(accumulatedJson.toString(), completion.get());
    }

    private List<Message> mergeLeadingSystemMessages(List<Message> messages) {
        int systemCount = 0;
        while (systemCount < messages.size()
                && messages.get(systemCount).role() == BoardGameRecommendationModel.Role.SYSTEM) {
            systemCount++;
        }
        if (systemCount < 2) {
            return messages;
        }
        String merged = messages.subList(0, systemCount).stream()
                .map(Message::content)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        List<Message> normalized = new ArrayList<>(messages.size() - systemCount + 1);
        normalized.add(Message.system(merged));
        normalized.addAll(messages.subList(systemCount, messages.size()));
        return normalized;
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
                ownerUsername,
                -1);
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
            if (request.tools().isEmpty()) {
                builder.toolChoice(null);
                builder.parallelToolCalls(null);
                OpenAiChatModel.ResponseFormat format = usesNativeJsonSchema(model, request, ownerUsername)
                        ? OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                                .jsonSchema(request.structuredOutput().jsonSchema())
                                .build()
                        : OpenAiChatModel.ResponseFormat.builder()
                                .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                                .build();
                builder.responseFormat(format);
            } else {
                // Action turns after the structured first-decision barrier require one supplied action.
                builder.toolChoice(request.toolChoice() == BoardGameRecommendationModel.ToolChoice.REQUIRED
                        ? "required"
                        : "auto");
                builder.parallelToolCalls(false);
            }
            options = builder;
        } else if (model.getOptions() instanceof GoogleGenAiChatOptions defaults) {
            GoogleGenAiChatOptions.Builder builder = defaults.mutate();
            if (request.tools().isEmpty()) {
                builder.responseMimeType("application/json");
            }
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

    private boolean usesNativeJsonSchema(ChatModel model, Request request, String ownerUsername) {
        return request.structuredOutput() != null
                && request.structuredOutput().strictPreferred()
                && model.getOptions() instanceof OpenAiChatOptions
                && "openai".equals(providerFor(ownerUsername));
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
                        .sum()
                + (request.structuredOutput() == null
                        ? 0
                        : request.structuredOutput().name().length()
                                + request.structuredOutput().jsonSchema().length());
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
