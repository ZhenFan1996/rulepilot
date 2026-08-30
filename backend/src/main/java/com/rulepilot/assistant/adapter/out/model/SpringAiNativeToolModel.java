package com.rulepilot.assistant.adapter.out.model;

import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.MessageRole;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
@Profile("!test")
public class SpringAiNativeToolModel implements NativeToolModel {

    private final RuntimeModelConfiguration models;

    public SpringAiNativeToolModel(RuntimeModelConfiguration models) {
        this.models = models;
    }

    @Override
    public ModelTurn next(ModelRequest request) {
        List<ToolCallback> callbacks = request.tools().stream().map(DefinitionOnlyToolCallback::new).map(ToolCallback.class::cast).toList();
        ChatModel chatModel = models.modelFor(modelRole(request.role()), request.scope().ownerUsername());
        RuntimeModelConfiguration.Role modelRole = modelRole(request.role());
        ToolCallingChatOptions.Builder<?> optionsBuilder;
        if (chatModel.getDefaultOptions() instanceof OpenAiChatOptions openAiDefaults) {
            OpenAiChatOptions.Builder openAiOptions = openAiDefaults.mutate();
            boolean qwen = "qwen".equals(models.providerFor(modelRole, request.scope().ownerUsername()));
            // Qwen may propose independent read-only calls together. The application executes and validates the
            // complete compatible batch, returns every observation, then asks for exactly one next decision.
            openAiOptions.parallelToolCalls(qwen);
            if (callbacks.isEmpty()) {
                // A final synthesis turn follows completed tool acquisition. Some
                // OpenAI-compatible providers otherwise keep emitting a tool name
                // remembered from the conversation even though no definitions are
                // advertised on this turn.
                openAiOptions.toolChoice("none");
            }
            if (models.usesDeepSeekNonThinkingGeneration(modelRole, request.scope().ownerUsername())) {
                openAiOptions.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else if (qwen) {
                openAiOptions.extraBody(Map.of("enable_thinking", false));
            }
            optionsBuilder = openAiOptions;
        } else if (chatModel.getDefaultOptions() instanceof ToolCallingChatOptions defaults) {
            optionsBuilder = defaults.mutate();
        } else {
            optionsBuilder = ToolCallingChatOptions.builder();
        }
        ToolCallingChatOptions options = optionsBuilder
                .toolCallbacks(callbacks)
                .toolContext(Map.of(
                        "ownerUsername", request.scope().ownerUsername(),
                        "documentVersionId", request.scope().documentVersionId(),
                        "runId", request.scope().runId(),
                        "toolExecution", "application-controlled"))
                .temperature(0.0)
                .build();
        Prompt prompt = new Prompt(request.conversation().stream().map(this::message).toList(), options);
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("native tool model returned no result");
        }
        AssistantMessage output = response.getResult().getOutput();
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        int promptTokens = usage == null ? 0 : usage(usage.getPromptTokens());
        int completionTokens = usage == null ? 0 : usage(usage.getCompletionTokens());
        return new ModelTurn(
                output.getText(),
                output.getToolCalls().stream()
                        .map(call -> new ModelToolCall(call.id(), call.name(), call.arguments()))
                        .toList(),
                promptTokens,
                completionTokens);
    }

    @Override
    public String providerId(Role role, String ownerUsername) {
        return models.providerFor(modelRole(role), ownerUsername);
    }

    @Override
    public boolean supports(Role role, String ownerUsername) {
        RuntimeModelConfiguration.Role configuredRole = modelRole(role);
        return role != Role.VISUAL
                || (!models.usesFake(configuredRole, ownerUsername)
                        && models.supportsVision(configuredRole, ownerUsername));
    }

    private Message message(ConversationMessage message) {
        if (message.role() == MessageRole.SYSTEM) return new SystemMessage(message.content());
        if (message.role() == MessageRole.USER) {
            if (message.media().isEmpty()) return new UserMessage(message.content());
            return UserMessage.builder()
                    .text(message.content())
                    .media(message.media().stream()
                            .map(media -> new org.springframework.ai.content.Media(
                                    MimeTypeUtils.parseMimeType(media.mediaType()),
                                    new ByteArrayResource(media.content())))
                            .toList())
                    .build();
        }
        if (message.role() == MessageRole.ASSISTANT) {
            return AssistantMessage.builder()
                    .content(message.content())
                    .toolCalls(message.toolCalls().stream()
                            .map(call -> new AssistantMessage.ToolCall(
                                    call.id(), "function", call.name(), call.argumentsJson()))
                            .toList())
                    .build();
        }
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        message.toolCallId(), message.toolName(), message.content())))
                .build();
    }

    private RuntimeModelConfiguration.Role modelRole(Role role) {
        return switch (role) {
            case ANSWER -> RuntimeModelConfiguration.Role.ANSWER;
            case TEACHING -> RuntimeModelConfiguration.Role.TEACHING;
            case VISUAL -> RuntimeModelConfiguration.Role.VISUAL;
        };
    }

    private int usage(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static final class DefinitionOnlyToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        private DefinitionOnlyToolCallback(ToolSpec spec) {
            this.definition = ToolDefinition.builder()
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
            throw new IllegalStateException("native tools execute only through the audited application loop");
        }
    }
}
