package com.rulepilot.modelconfig.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.modelconfig.IncrementalToolCallChatModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ModelCallDebugRecorderTest {
    private final List<SpanData> spans = new ArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .setSpanLimits(SpanLimits.builder().setMaxNumberOfEvents(0)
                    .setMaxAttributeValueLength(ModelCallDebugRecorder.ATTRIBUTE_BYTES).build())
            .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
                public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
                public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
                public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
            })).build();
    private final OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    private final ModelCallDebugRecorder recorder = new ModelCallDebugRecorder(telemetry, true);
    private final Prompt prompt = new Prompt("PRIVATE PROMPT MUST NOT APPEAR");

    @AfterEach void close() { telemetry.close(); }

    @Test void recordsReturnedContentAndToolsUnderTheOriginalTraceWithoutPrivateMetadata() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = response("Try this game", List.of(new AssistantMessage.ToolCall("call-1", "function", "search_bgg_catalog",
                "{\"players\":4,\"nested\":{\"api_key\":\"secret-parameter\",\"email\":\"person@example.org\"},\"note\":\"live-provider-credential\"}")));
        when(delegate.call(prompt)).thenReturn(response);
        Span parent = telemetry.getTracer("test").spanBuilder("recommendation-react").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            assertThat(recorder.wrap(delegate, "qwen", "test-model", "live-provider-credential").call(prompt)).isSameAs(response);
        } finally { parent.end(); }
        SpanData call = call();
        assertThat(call.getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
        assertThat(call.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
        assertThat(content(call)).contains("Try this game", "search_bgg_catalog", "players", "[REDACTED]")
                .doesNotContain("secret-parameter", "person@example.org", "live-provider-credential", "PRIVATE PROMPT", "PRIVATE REASONING");
    }

    @Test void joinsIncrementalToolArgumentsAndKeepsSeparateSubscriptionsSeparate() {
        RawModel delegate = mock(RawModel.class);
        when(delegate.supportsIncrementalToolCallChunks()).thenReturn(true);
        when(delegate.streamToolCallChunks(prompt)).thenReturn(Flux.just(
                new IncrementalToolCallChatModel.Chunk("Hello ", List.of(new IncrementalToolCallChatModel.ToolCallDelta(0,"a","publish","{\"text\":")), "",0,0,"PRIVATE REASONING"),
                new IncrementalToolCallChatModel.Chunk("world", List.of(new IncrementalToolCallChatModel.ToolCallDelta(0,"","","\"answer\",\"password\":\"secret\"}")), "stop",12,8)));
        Span parent = telemetry.getTracer("test").spanBuilder("teaching-first-section").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            var model = (IncrementalToolCallChatModel) recorder.wrap(delegate,"openai","model","key");
            assertThat(model.supportsIncrementalToolCallChunks()).isTrue();
            Flux<IncrementalToolCallChatModel.Chunk> stream = model.streamToolCallChunks(prompt);
            stream.blockLast();
            stream.blockLast();
        } finally { parent.end(); }
        var calls = spans.stream().filter(s -> s.getName().equals("model-call")).toList();
        assertThat(calls).hasSize(2);
        for (SpanData call : calls) assertThat(content(call)).contains("Hello world", "publish", "answer", "[REDACTED]")
                .doesNotContain("PRIVATE REASONING", "secret", "Hello worldHello world");
    }

    @Test void cancellationPreservesPartialTextButNeverPublishesIncompleteToolArguments() {
        RawModel delegate = mock(RawModel.class);
        var chunk = new IncrementalToolCallChatModel.Chunk("partial", List.of(new IncrementalToolCallChatModel.ToolCallDelta(0,"a","publish","{\"password\":\"secret")), "",0,0);
        when(delegate.streamToolCallChunks(prompt)).thenReturn(Flux.concat(Flux.just(chunk), Flux.never()));
        Span parent = telemetry.getTracer("test").spanBuilder("answer-agent").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            ((IncrementalToolCallChatModel) recorder.wrap(delegate,"qwen","model","key"))
                    .streamToolCallChunks(prompt).take(1).blockLast();
        } finally { parent.end(); }
        assertThat(call().getAttributes().get(AttributeKey.stringKey("model.debug.outcome"))).isEqualTo("cancelled");
        assertThat(content(call())).contains("partial", "omitted_invalid_or_incomplete_json").doesNotContain("secret");
    }

    @Test void providerFailureIsUnchangedAndDoesNotExportItsPotentiallySensitiveMessage() {
        ChatModel delegate = mock(ChatModel.class);
        RuntimeException failure = new IllegalStateException("SECRET provider body");
        when(delegate.call(prompt)).thenThrow(failure);
        Span parent = telemetry.getTracer("test").spanBuilder("answer-agent").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            assertThatThrownBy(() -> recorder.wrap(delegate,"qwen","model","key").call(prompt)).isSameAs(failure);
        } finally { parent.end(); }
        assertThat(call().getAttributes().get(AttributeKey.stringKey("model.debug.outcome"))).isEqualTo("failed");
        assertThat(content(call())).doesNotContain("SECRET");
        assertThat(call().getEvents()).isEmpty();
    }

    @Test void largeResponseDoesNotInterruptPublicationAndReportsMissingDiagnosticContent() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = response("x".repeat(ModelCallDebugRecorder.OUTPUT_BYTES + 1), List.of());
        when(delegate.call(prompt)).thenReturn(response);
        Span parent = telemetry.getTracer("test").spanBuilder("teaching-first-section").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            assertThat(recorder.wrap(delegate,"qwen","model","key").call(prompt)).isSameAs(response);
        } finally { parent.end(); }
        assertThat(call().getAttributes().get(AttributeKey.stringKey("model.debug.output_status"))).isEqualTo("omitted_capacity");
        assertThat(content(call())).isEmpty();
    }

    @Test void unicodeResponseSurvivesCollectorAttributeLimitsAndStructuredContentIsRedacted() {
        ChatModel delegate = mock(ChatModel.class);
        String text = "规则🎲".repeat(1000);
        when(delegate.call(prompt)).thenReturn(response("{\"text\":\""+text+"\",\"password\":\"hidden\",\"reasoning_content\":\"hidden reasoning\"}", List.of()));
        Span parent = telemetry.getTracer("test").spanBuilder("teaching-first-section").startSpan();
        try (Scope ignored = parent.makeCurrent()) { recorder.wrap(delegate,"qwen","model","key").call(prompt); }
        finally { parent.end(); }
        assertThat(content(call())).contains(text).doesNotContain("hidden");
        call().getAttributes().asMap().forEach((key,value)-> {
            if (key.getKey().startsWith("model.response.")) assertThat(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(ModelCallDebugRecorder.ATTRIBUTE_BYTES);
        });
    }

    @Test void disabledRecorderDoesNotWrapModels() {
        ChatModel delegate = mock(ChatModel.class);
        assertThat(new ModelCallDebugRecorder(telemetry,false).wrap(delegate,"qwen","model","key")).isSameAs(delegate);
    }

    @Test void ordinaryStreamingKeepsTheRequestParentAcrossSubscriptionThreads() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(prompt)).thenReturn(Flux.just(response("Hello ", List.of()), response("world", List.of())));
        Span parent = telemetry.getTracer("test").spanBuilder("teaching-first-section").startSpan();
        Flux<ChatResponse> stream;
        try (Scope ignored = parent.makeCurrent()) {
            stream = recorder.wrap(delegate,"gemini","model","key").stream(prompt);
        }
        try { stream.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).blockLast(); }
        finally { parent.end(); }
        assertThat(call().getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
        assertThat(content(call())).contains("Hello world");
    }

    private SpanData call() { return spans.stream().filter(s -> s.getName().equals("model-call")).findFirst().orElseThrow(); }
    private String content(SpanData span) {
        return span.getAttributes().asMap().entrySet().stream().filter(e -> e.getKey().getKey().startsWith("model.response."))
                .sorted(java.util.Comparator.comparing(e -> e.getKey().getKey())).map(e -> e.getValue().toString()).collect(java.util.stream.Collectors.joining());
    }
    private ChatResponse response(String text, List<AssistantMessage.ToolCall> calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .properties(Map.of("reasoningContent","PRIVATE REASONING")).toolCalls(calls).build())));
    }
    private interface RawModel extends ChatModel, IncrementalToolCallChatModel {}
}
