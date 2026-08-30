package com.rulepilot.assistant.adapter.out.model;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.rulepilot.assistant.NativeAgentTool.Role;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.NativeToolModel.ConversationMessage;
import com.rulepilot.assistant.NativeToolModel.MessageRole;
import com.rulepilot.assistant.NativeToolModel.ModelRequestFailure;
import com.rulepilot.assistant.NativeToolModel.ModelRequestFailureKind;
import com.rulepilot.assistant.NativeToolModel.ModelToolCall;
import com.rulepilot.assistant.NativeToolModel.ToolSpec;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
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
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
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
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (OpenAIIoException failure) {
            throw transientRequestFailure(failure);
        } catch (OpenAIRetryableException failure) {
            throw transientRequestFailure(failure);
        } catch (OpenAIServiceException failure) {
            throw classifiedOpenAiFailure(failure);
        } catch (GenAiIOException failure) {
            throw transientRequestFailure(failure);
        } catch (ApiException failure) {
            throw classifiedGoogleFailure(failure);
        } catch (NonTransientAiException failure) {
            throw failure;
        } catch (TransientAiException failure) {
            throw transientRequestFailure(failure);
        } catch (RuntimeException failure) {
            throw classifiedWrappedGoogleFailure(failure);
        }
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

    private RuntimeException classifiedOpenAiFailure(OpenAIServiceException failure) {
        if (Thread.currentThread().isInterrupted()) return failure;
        int status = failure.statusCode();
        if (isTimeoutStatus(status)) return requestFailure(ModelRequestFailureKind.TIMEOUT, failure);
        if (status == 409 || status == 429 || isServerError(status)) {
            return requestFailure(ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE, failure);
        }
        return failure;
    }

    private RuntimeException classifiedGoogleFailure(ApiException failure) {
        if (Thread.currentThread().isInterrupted()) return failure;
        ModelRequestFailureKind kind = googleFailureKind(failure.code());
        return kind == null ? failure : requestFailure(kind, failure);
    }

    private RuntimeException classifiedWrappedGoogleFailure(RuntimeException failure) {
        if (Thread.currentThread().isInterrupted()) return failure;
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof GenAiIOException) return transientRequestFailure(failure);
            if (cause instanceof ApiException apiFailure) {
                ModelRequestFailureKind kind = googleFailureKind(apiFailure.code());
                return kind == null ? failure : requestFailure(kind, failure);
            }
            if (cause.getCause() == cause) return failure;
        }
        return failure;
    }

    private ModelRequestFailureKind googleFailureKind(int status) {
        if (isTimeoutStatus(status)) return ModelRequestFailureKind.TIMEOUT;
        if (status == 429 || isServerError(status)) return ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE;
        return null;
    }

    private RuntimeException transientRequestFailure(RuntimeException failure) {
        if (Thread.currentThread().isInterrupted()) return failure;
        ModelRequestFailureKind kind = causedByTimeout(failure)
                ? ModelRequestFailureKind.TIMEOUT
                : ModelRequestFailureKind.TEMPORARILY_UNAVAILABLE;
        return requestFailure(kind, failure);
    }

    private ModelRequestFailure requestFailure(ModelRequestFailureKind kind, RuntimeException failure) {
        return new ModelRequestFailure(kind, failure);
    }

    private boolean causedByTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
            if (cause.getCause() == cause) return false;
        }
        return false;
    }

    private boolean isTimeoutStatus(int status) {
        return status == 408 || status == 504;
    }

    private boolean isServerError(int status) {
        return status >= 500 && status <= 599;
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
