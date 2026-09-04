package com.rulepilot.modelconfig.adapter.out;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.rulepilot.modelconfig.IncrementalToolCallChatModel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;

/**
 * Keeps Spring AI as the OpenAI request owner while exposing raw chunks for incremental validated
 * publication. Remove this adapter when Spring AI exposes unaggregated tool-call deltas publicly.
 */
final class IncrementalOpenAiChatModel implements ChatModel, IncrementalToolCallChatModel {

    private static final Method CREATE_REQUEST = createRequestMethod();

    private final OpenAiChatModel delegate;
    private final OpenAIClientAsync asyncClient;

    IncrementalOpenAiChatModel(OpenAiChatModel delegate, OpenAIClientAsync asyncClient) {
        this.delegate = delegate;
        this.asyncClient = asyncClient;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public Flux<Chunk> streamToolCallChunks(Prompt prompt) {
        ChatCompletionCreateParams request = createRequest(prompt);
        Flux<ChatCompletionChunk> chunks = Flux.create(sink -> {
            AsyncStreamResponse<ChatCompletionChunk> response =
                    asyncClient.chat().completions().createStreaming(request);
            sink.onDispose(response::close);
            response.subscribe(sink::next).onCompleteFuture().whenComplete((unused, failure) -> {
                if (failure == null) sink.complete();
                else sink.error(failure);
            });
        });
        return chunks.map(this::providerNeutralChunk);
    }

    private Chunk providerNeutralChunk(ChatCompletionChunk chunk) {
        StringBuilder text = new StringBuilder();
        java.util.List<ToolCallDelta> toolCalls = new java.util.ArrayList<>();
        String finishReason = "";
        for (ChatCompletionChunk.Choice choice : chunk.choices()) {
            text.append(choice.delta().content().orElse(""));
            for (ChatCompletionChunk.Choice.Delta.ToolCall call :
                    choice.delta().toolCalls().orElse(java.util.List.of())) {
                var function = call.function().orElse(null);
                toolCalls.add(new ToolCallDelta(
                        Math.toIntExact(call.index()),
                        call.id().orElse(""),
                        function == null ? "" : function.name().orElse(""),
                        function == null ? "" : function.arguments().orElse("")));
            }
            if (choice.finishReason().isPresent()) {
                finishReason = choice.finishReason().get().value().toString();
            }
        }
        var usage = chunk.usage().orElse(null);
        return new Chunk(
                text.toString(),
                toolCalls,
                finishReason,
                usage == null ? 0 : usage.promptTokens(),
                usage == null ? 0 : usage.completionTokens());
    }

    private ChatCompletionCreateParams createRequest(Prompt prompt) {
        try {
            return (ChatCompletionCreateParams) CREATE_REQUEST.invoke(delegate, prompt, true);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Spring AI OpenAI request bridge is inaccessible", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Spring AI OpenAI request creation failed", cause);
        }
    }

    private static Method createRequestMethod() {
        try {
            Method method = OpenAiChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
