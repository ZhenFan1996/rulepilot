package com.rulepilot.modelconfig.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.modelconfig.IncrementalToolCallChatModel;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** One response-only recorder for all model roles; never serializes prompts or provider metadata. */
@Component
public final class ModelCallDebugRecorder {
    // Tempo's default attribute byte limit and the OTel SDK's 128 attributes/span.
    // Reserve 16 attribute slots for model identity, usage and diagnostic status.
    // These bound diagnostic copies only: exceeding them never interrupts a model or publication.
    static final int ATTRIBUTE_BYTES = 2048;
    static final int PART_LIMIT = 128 - 16;
    static final int OUTPUT_BYTES = ATTRIBUTE_BYTES * PART_LIMIT;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|apikey|authorization|cookie|credential|privatekey|"
                    + "reasoning|chainofthought|systemprompt|email|phone|address).*" );
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(bearer\\s+|(?:api[_ -]?key|password|secret|access[_ -]?token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+");
    private static final Pattern KEY = Pattern.compile("\\b(?:sk-|AIza)[A-Za-z0-9_-]+\\b");
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?\\\"<>]+)\\?[^\\s\\\"<>]*");
    private final Tracer tracer;
    private final boolean enabled;

    public ModelCallDebugRecorder(OpenTelemetry telemetry,
            @Value("${rulepilot.models.debug-recording.enabled:false}") boolean enabled) {
        this.tracer = telemetry.getTracer("com.rulepilot.model-debug");
        this.enabled = enabled;
    }

    ChatModel wrap(ChatModel delegate, String provider, String model, String credential) {
        return enabled ? new RecordedModel(delegate, provider, model, credential) : delegate;
    }

    private final class RecordedModel implements ChatModel, IncrementalToolCallChatModel {
        private final ChatModel delegate;
        private final String provider;
        private final String model;
        private final String credential;

        private RecordedModel(ChatModel delegate, String provider, String model, String credential) {
            this.delegate = delegate;
            this.provider = provider;
            this.model = model;
            this.credential = credential;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (!Span.current().getSpanContext().isValid()) return delegate.call(prompt);
            Capture capture = start(Context.current());
            try (Scope ignored = capture.span.makeCurrent()) {
                ChatResponse response = delegate.call(prompt);
                capture.accept(response);
                capture.finish("completed");
                return response;
            } catch (RuntimeException failure) {
                capture.finish("failed");
                throw failure;
            } finally {
                capture.finish("failed");
            }
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            Context parent = Context.current();
            if (!Span.fromContext(parent).getSpanContext().isValid()) return delegate.stream(prompt);
            return Flux.defer(() -> {
                Capture capture = start(parent);
                try (Scope ignored = capture.span.makeCurrent()) {
                    return delegate.stream(prompt)
                            .doOnNext(capture::accept)
                            .doOnComplete(() -> capture.finish("completed"))
                            .doOnError(ignoredError -> capture.finish("failed"))
                            .doFinally(signal -> capture.finish("cancelled"));
                } catch (RuntimeException failure) {
                    capture.finish("failed");
                    return Flux.error(failure);
                }
            });
        }

        @Override
        public boolean supportsIncrementalToolCallChunks() {
            return delegate instanceof IncrementalToolCallChatModel incremental
                    && incremental.supportsIncrementalToolCallChunks();
        }

        @Override
        public Flux<Chunk> streamToolCallChunks(Prompt prompt) {
            if (!(delegate instanceof IncrementalToolCallChatModel incremental)) {
                return Flux.error(new IllegalStateException("Model does not support incremental tool calls"));
            }
            Context parent = Context.current();
            if (!Span.fromContext(parent).getSpanContext().isValid()) return incremental.streamToolCallChunks(prompt);
            return Flux.defer(() -> {
                Capture capture = start(parent);
                try (Scope ignored = capture.span.makeCurrent()) {
                    return incremental.streamToolCallChunks(prompt)
                            .doOnNext(capture::accept)
                            .doOnComplete(() -> capture.finish("completed"))
                            .doOnError(ignoredError -> capture.finish("failed"))
                            .doFinally(signal -> capture.finish("cancelled"));
                } catch (RuntimeException failure) {
                    capture.finish("failed");
                    return Flux.error(failure);
                }
            });
        }

        private Capture start(Context parent) {
            Span span = tracer.spanBuilder("model-call").setParent(parent).startSpan();
            span.setAttribute("gen_ai.provider.name", provider);
            span.setAttribute("gen_ai.request.model", model);
            return new Capture(span, credential);
        }

        @Override public ChatOptions getOptions() { return delegate.getOptions(); }
        @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
    }

    private static final class Tool {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    private static final class Capture {
        private final Span span;
        private final String credential;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, Tool> tools = new LinkedHashMap<>();
        private long bytes;
        private boolean capacityExceeded;
        private boolean recordingFailed;

        Capture(Span span, String credential) {
            this.span = span;
            this.credential = credential;
        }

        synchronized void accept(ChatResponse response) {
            try {
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) return;
                var output = response.getResult().getOutput();
                if (response.getResult().getMetadata() != null && response.getResult().getMetadata().getFinishReason() != null) {
                    span.setAttribute("gen_ai.response.finish_reason", response.getResult().getMetadata().getFinishReason());
                }
                appendText(output.getText());
                for (int index = 0; index < output.getToolCalls().size(); index++) {
                    var call = output.getToolCalls().get(index);
                    tool(index, call.id(), call.name(), call.arguments());
                }
                var usage = response.getMetadata().getUsage();
                if (usage != null) {
                    if (usage.getPromptTokens() != null) span.setAttribute("gen_ai.usage.input_tokens", usage.getPromptTokens().longValue());
                    if (usage.getCompletionTokens() != null) span.setAttribute("gen_ai.usage.output_tokens", usage.getCompletionTokens().longValue());
                }
            } catch (RuntimeException ignored) { recordingFailed = true; }
        }

        synchronized void accept(IncrementalToolCallChatModel.Chunk chunk) {
            try {
                appendText(chunk.text());
                if (!chunk.finishReason().isBlank()) span.setAttribute("gen_ai.response.finish_reason", chunk.finishReason());
                for (var call : chunk.toolCalls()) tool(call.index(), call.id(), call.name(), call.arguments());
                if (chunk.promptTokens() > 0) span.setAttribute("gen_ai.usage.input_tokens", chunk.promptTokens());
                if (chunk.completionTokens() > 0) span.setAttribute("gen_ai.usage.output_tokens", chunk.completionTokens());
                // Deliberately do not read chunk.privateReasoning().
            } catch (RuntimeException ignored) { recordingFailed = true; }
        }

        private boolean admit(String value) {
            if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > OUTPUT_BYTES) capacityExceeded = true;
            return !capacityExceeded;
        }

        private void appendText(String value) {
            if (admit(value) && value != null) text.append(value);
        }

        private void tool(int index, String id, String name, String arguments) {
            boolean fits = admit(id);
            fits &= admit(name);
            fits &= admit(arguments);
            if (!fits) return;
            Tool tool = tools.computeIfAbsent(index, ignored -> new Tool());
            if (id != null && !id.isBlank()) tool.id = id;
            if (name != null && !name.isBlank()) tool.name = name;
            if (arguments != null) tool.arguments.append(arguments);
        }

        synchronized void finish(String outcome) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                span.setAttribute("model.debug.outcome", outcome);
                if ("failed".equals(outcome)) span.setStatus(StatusCode.ERROR);
                span.setAttribute("model.debug.output_bytes", bytes);
                if (recordingFailed || capacityExceeded) {
                    span.setAttribute("model.debug.output_status", recordingFailed ? "recording_failed" : "omitted_capacity");
                    return;
                }
                ObjectNode output = JSON.createObjectNode();
                output.put("text", safeOutput(text.toString(), credential));
                var calls = output.putArray("tool_calls");
                tools.forEach((index, tool) -> {
                    ObjectNode call = calls.addObject();
                    call.put("index", index);
                    call.put("id", safeText(tool.id, credential));
                    call.put("name", safeText(tool.name, credential));
                    try {
                        JsonNode arguments = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(tool.arguments.toString());
                        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("Not an object");
                        call.set("arguments", sanitize(arguments, credential));
                        call.put("arguments_status", "redacted");
                    } catch (Exception ignored) {
                        call.put("arguments_status", "omitted_invalid_or_incomplete_json");
                    }
                });
                String encoded = JSON.writeValueAsString(output);
                if (encoded.getBytes(StandardCharsets.UTF_8).length > OUTPUT_BYTES) {
                    span.setAttribute("model.debug.output_status", "omitted_capacity");
                    return;
                }
                // Split on Unicode code-point boundaries to respect Tempo's UTF-8 byte limit.
                java.util.List<String> parts = new java.util.ArrayList<>();
                StringBuilder part = new StringBuilder();
                int partBytes = 0;
                for (int offset = 0; offset < encoded.length();) {
                    int point = encoded.codePointAt(offset);
                    String value = new String(Character.toChars(point));
                    int length = value.getBytes(StandardCharsets.UTF_8).length;
                    if (partBytes + length > ATTRIBUTE_BYTES) {
                        parts.add(part.toString());
                        part.setLength(0);
                        partBytes = 0;
                    }
                    part.append(value);
                    partBytes += length;
                    offset += Character.charCount(point);
                }
                if (!part.isEmpty()) parts.add(part.toString());
                if (parts.size() > PART_LIMIT) {
                    span.setAttribute("model.debug.output_status", "omitted_capacity");
                    return;
                }
                for (int index = 0; index < parts.size(); index++) {
                    span.setAttribute("model.response." + String.format(java.util.Locale.ROOT, "%03d", index), parts.get(index));
                }
                int sequence = parts.size();
                span.setAttribute("model.debug.output_parts", sequence);
                span.setAttribute("model.debug.output_status", "completed".equals(outcome) ? "redacted" : "partial_redacted");
            } catch (Exception ignored) {
                span.setAttribute("model.debug.output_status", "recording_failed");
            } finally {
                text.setLength(0);
                tools.clear();
                span.end();
            }
        }

    }

    private static String safeOutput(String value, String credential) {
        try {
            JsonNode node = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (node != null && (node.isObject() || node.isArray())) return JSON.writeValueAsString(sanitize(node, credential));
        } catch (Exception ignored) {
            if (value.stripLeading().startsWith("{") || value.stripLeading().startsWith("[")) {
                return "[OMITTED: incomplete structured response]";
            }
        }
        return safeText(value, credential);
    }

    private static JsonNode sanitize(JsonNode node, String credential) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (SENSITIVE_KEY.matcher(key.replaceAll("[^A-Za-z]", "")).matches()) {
                    result.put(safeText(key, credential), "[REDACTED]");
                } else result.set(safeText(key, credential), sanitize(entry.getValue(), credential));
            });
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(value -> result.add(sanitize(value, credential)));
            return result;
        }
        return node.isTextual() ? JSON.getNodeFactory().textNode(safeOutput(node.textValue(), credential)) : node;
    }

    private static String safeText(String value, String credential) {
        // A provider that puts hidden reasoning in the text channel must not leak it through diagnostics.
        if (value.toLowerCase(java.util.Locale.ROOT).contains("<think")
                || value.toLowerCase(java.util.Locale.ROOT).contains("<analysis")) return "[OMITTED: reasoning markup]";
        String text = credential == null || credential.isBlank() ? value : value.replace(credential, "[REDACTED]");
        text = EMAIL.matcher(text).replaceAll("[REDACTED_EMAIL]");
        text = SECRET.matcher(text).replaceAll("$1[REDACTED]");
        text = KEY.matcher(text).replaceAll("[REDACTED]");
        return URL_QUERY.matcher(text).replaceAll("$1?[REDACTED]");
    }
}
