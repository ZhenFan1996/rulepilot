package com.rulepilot.testing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Small opt-in-canary tracing runtime that exports the exact paid call path to the local Tempo collector.
 *
 * <p>Normal tests never construct this class. Paid canaries already require an explicit environment gate, and this
 * runtime keeps their trace identifier in the ignored evaluation artifact so a measured model/tool/state path can be
 * queried without putting prompts, model output, credentials, or user data into span attributes.</p>
 */
public final class PaidCanaryTrace implements AutoCloseable {

    private static final String DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces";

    private final OpenTelemetrySdk openTelemetry;
    private final SdkTracerProvider provider;
    private final OtelTracer tracer;
    private final Observation root;
    private final Observation.Scope rootScope;
    private final ObservationRegistry observations;
    private final String traceId;
    private boolean closed;

    private PaidCanaryTrace(
            OpenTelemetrySdk openTelemetry,
            SdkTracerProvider provider,
            OtelTracer tracer,
            Observation root,
            Observation.Scope rootScope,
            ObservationRegistry observations,
            String traceId) {
        this.openTelemetry = openTelemetry;
        this.provider = provider;
        this.tracer = tracer;
        this.root = root;
        this.rootScope = rootScope;
        this.observations = observations;
        this.traceId = traceId;
    }

    public static PaidCanaryTrace start(String workflow) {
        if (workflow == null || workflow.isBlank()) {
            throw new IllegalArgumentException("paid canary workflow is required");
        }
        String endpoint = environment("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", DEFAULT_ENDPOINT);
        var exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setConnectTimeout(Duration.ofSeconds(3))
                .setTimeout(Duration.ofSeconds(10))
                .build();
        var resource = Resource.getDefault().merge(Resource.builder()
                .put("service.name", "rulepilot-paid-canary")
                .put("service.namespace", "rulepilot")
                .build());
        var provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .setResource(resource)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        var currentTraceContext = new OtelCurrentTraceContext();
        var baggage = new OtelBaggageManager(currentTraceContext, List.of(), List.of());
        var tracer = new OtelTracer(
                openTelemetry.getTracer("rulepilot-paid-canary"),
                currentTraceContext,
                ignored -> {},
                baggage);
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
        Observation root = Observation.createNotStarted("rulepilot.paid.canary", observations)
                .contextualName(workflow)
                .lowCardinalityKeyValue("workflow", workflow)
                .start();
        Observation.Scope rootScope = root.openScope();
        String traceId = tracer.currentSpan().context().traceId();
        return new PaidCanaryTrace(
                openTelemetry,
                provider,
                tracer,
                root,
                rootScope,
                observations,
                traceId);
    }

    public ObservationRegistry observations() {
        return observations;
    }

    public String traceId() {
        return traceId;
    }

    public <T> T observe(String stage, Supplier<T> work) {
        if (stage == null || stage.isBlank() || work == null) {
            throw new IllegalArgumentException("paid canary trace stage and work are required");
        }
        Observation observation = Observation.createNotStarted("rulepilot.paid.canary.stage", observations)
                .contextualName(stage)
                .lowCardinalityKeyValue("stage", stage);
        return observation.observe(() -> {
            try {
                T result = work.get();
                observation.lowCardinalityKeyValue("outcome", "completed");
                return result;
            } catch (RuntimeException | Error failure) {
                observation.lowCardinalityKeyValue("outcome", "failed");
                throw failure;
            }
        });
    }

    public void recordFailure(Throwable failure) {
        if (failure != null) root.error(failure);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        rootScope.close();
        root.stop();
        provider.forceFlush().join(10, TimeUnit.SECONDS);
        openTelemetry.close();
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
