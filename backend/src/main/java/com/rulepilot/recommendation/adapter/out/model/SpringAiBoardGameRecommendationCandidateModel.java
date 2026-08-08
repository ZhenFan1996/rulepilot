package com.rulepilot.recommendation.adapter.out.model;

import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.recommendation.BoardGameRecommendationCandidateModel;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SpringAiBoardGameRecommendationCandidateModel
        implements BoardGameRecommendationCandidateModel {

    private final RuntimeModelConfiguration models;

    public SpringAiBoardGameRecommendationCandidateModel(RuntimeModelConfiguration models) {
        this.models = models;
    }

    @Override
    public boolean configured() {
        return !models.usesFake(RuntimeModelConfiguration.Role.RECOMMENDATION);
    }

    @Override
    public Turn next(Request request) {
        ChatModel model = models.modelFor(RuntimeModelConfiguration.Role.RECOMMENDATION);
        List<ToolCallback> callbacks = request.tools().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
        ToolCallingChatOptions.Builder<?> options;
        if (model.getDefaultOptions() instanceof OpenAiChatOptions defaults) {
            OpenAiChatOptions.Builder builder = defaults.mutate();
            builder.toolChoice("required").parallelToolCalls(false);
            if ("qwen".equals(models.providerFor(RuntimeModelConfiguration.Role.RECOMMENDATION))) {
                builder.extraBody(Map.of("enable_thinking", false));
            }
            options = builder;
        } else if (model.getDefaultOptions() instanceof ToolCallingChatOptions defaults) {
            options = defaults.mutate();
        } else {
            options = ToolCallingChatOptions.builder();
        }
        ChatResponse response = model.call(new Prompt(
                request.messages().stream().map(this::message).toList(),
                options.toolCallbacks(callbacks)
                        .temperature(0.0)
                        .maxTokens(request.maxOutputTokens())
                        .build()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("recommendation candidate model returned no result");
        }
        AssistantMessage output = response.getResult().getOutput();
        return new Turn(
                output.getText(),
                output.getToolCalls().stream()
                        .map(call -> new ToolCall(call.id(), call.name(), call.arguments()))
                        .toList());
    }

    private org.springframework.ai.chat.messages.Message message(
            BoardGameRecommendationCandidateModel.Message message) {
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
            throw new IllegalStateException("recommendation tools execute only in the application-owned loop");
        }
    }
}
