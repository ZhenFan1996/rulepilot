package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.application.DocumentOutboxStore.TraceHeaders;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTraceContextBridgeOtelTest {

    private static final String REMOTE_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String REMOTE_SPAN_ID = "00f067aa0ba902b7";
    private static final String REMOTE_PARENT = "00-" + REMOTE_TRACE_ID + "-" + REMOTE_SPAN_ID + "-01";

    @Test
    void keepsAValidAlwaysOffOtelSpanAsAnUnsampledRemoteParent() {
        try (var runtime = runtime(Sampler.alwaysOff())) {
            Span source = runtime.tracer.nextSpan().name("source-request").start();
            try (var ignored = runtime.tracer.withSpan(source)) {
                assertThat(source.isNoop()).as("Micrometer maps a non-recording OTel span to noop").isTrue();

                TraceHeaders captured = runtime.bridge.capture();

                assertThat(captured.present()).isTrue();
                int traceFlags = Integer.parseInt(captured.traceParent().substring(53), 16);
                assertThat(traceFlags & 1).as("the W3C sampled bit").isZero();
                assertThat(captured.traceParent()).contains(source.context().traceId(), source.context().spanId());
            } finally {
                source.end();
            }
        }
    }

    @Test
    void isolatesLegacyRootsAndPersistedRemoteParentsFromAmbientTraceAndBaggage() {
        try (var runtime = runtime(Sampler.alwaysOn())) {
            Span ambient = runtime.tracer.nextSpan().name("wakeup-request").start();
            try (var ambientScope = runtime.tracer.withSpan(ambient);
                    var baggage = runtime.tracer.createBaggageInScope("private", "player-secret")) {
                String ambientTraceId = ambient.context().traceId();
                assertThat(runtime.tracer.getAllBaggage()).containsEntry("private", "player-secret");

                try (var ignored = runtime.bridge.open(TraceHeaders.none(), "document.outbox.publish")) {
                    assertThat(runtime.tracer.currentSpan().context().traceId()).isNotEqualTo(ambientTraceId);
                    assertThat(runtime.tracer.getAllBaggage()).doesNotContainKey("private");
                }

                assertThat(runtime.tracer.currentSpan().context().traceId()).isEqualTo(ambientTraceId);
                assertThat(runtime.tracer.getAllBaggage()).containsEntry("private", "player-secret");

                try (var ignored = runtime.bridge.open(
                        new TraceHeaders(REMOTE_PARENT, "vendor=value"), "document.outbox.publish")) {
                    assertThat(runtime.tracer.currentSpan().context().traceId()).isEqualTo(REMOTE_TRACE_ID);
                    assertThat(runtime.tracer.getAllBaggage()).doesNotContainKey("private");
                }

                assertThat(runtime.tracer.currentSpan().context().traceId()).isEqualTo(ambientTraceId);
                assertThat(runtime.tracer.getAllBaggage()).containsEntry("private", "player-secret");
            } finally {
                ambient.end();
            }
        }
    }

    private OtelRuntime runtime(Sampler sampler) {
        SdkTracerProvider provider = SdkTracerProvider.builder().setSampler(sampler).build();
        ContextPropagators propagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(propagators)
                .build();
        io.opentelemetry.api.trace.Tracer otelTracer = sdk.getTracer("rulepilot-outbox-test");
        var current = new OtelCurrentTraceContext();
        var baggage = new OtelBaggageManager(current, List.of("private"), List.of());
        var tracer = new OtelTracer(otelTracer, current, ignored -> {}, baggage);
        var propagator = new OtelPropagator(propagators, otelTracer);
        return new OtelRuntime(sdk, tracer, new DocumentTraceContextBridge(tracer, propagator));
    }

    private record OtelRuntime(
            OpenTelemetrySdk sdk,
            OtelTracer tracer,
            DocumentTraceContextBridge bridge)
            implements AutoCloseable {

        @Override
        public void close() {
            sdk.close();
        }
    }
}
