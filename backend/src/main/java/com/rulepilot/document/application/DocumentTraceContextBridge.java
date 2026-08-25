package com.rulepilot.document.application;

import com.rulepilot.document.application.DocumentOutboxStore.TraceHeaders;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Captures and restores only W3C tracing headers; baggage and application data never enter the outbox. */
@Component
public class DocumentTraceContextBridge {

    private final Tracer tracer;
    private final Propagator propagator;

    public DocumentTraceContextBridge(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public static DocumentTraceContextBridge noop() {
        return new DocumentTraceContextBridge(Tracer.NOOP, Propagator.NOOP);
    }

    public TraceHeaders capture() {
        Span current = tracer.currentSpan();
        if (current == null) return TraceHeaders.none();
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(current.context(), headers, (carrier, name, value) -> {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (("traceparent".equals(normalized) || "tracestate".equals(normalized)) && value != null) {
                carrier.put(normalized, value);
            }
        });
        return new TraceHeaders(headers.get("traceparent"), headers.get("tracestate"));
    }

    public Scope open(TraceHeaders traceHeaders, String spanName) {
        TraceHeaders stored = traceHeaders == null ? TraceHeaders.none() : traceHeaders;
        Map<String, String> headers = new LinkedHashMap<>();
        if (stored.present()) {
            headers.put("traceparent", stored.traceParent());
            if (stored.traceState() != null) headers.put("tracestate", stored.traceState());
        }
        // Micrometer's OTel bridge both extracts from and merges baggage with the ambient Context. Keep it cleared
        // for the complete publication scope so only the persisted remote parent can cross this durable boundary.
        CurrentTraceContext.Scope clearedAmbient = tracer.currentTraceContext().maybeScope(null);
        try {
            Span.Builder spanBuilder;
            if (stored.present()) {
                spanBuilder = propagator.extract(
                        headers, (carrier, name) -> carrier.get(name.toLowerCase(Locale.ROOT)));
            } else {
                spanBuilder = tracer.spanBuilder().setNoParent();
            }
            Span span = spanBuilder.name(spanName).start();
            return new Scope(span, tracer.withSpan(span), clearedAmbient);
        } catch (RuntimeException | Error failure) {
            clearedAmbient.close();
            throw failure;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope inScope;
        private final CurrentTraceContext.Scope clearedAmbient;

        private Scope(
                Span span,
                Tracer.SpanInScope inScope,
                CurrentTraceContext.Scope clearedAmbient) {
            this.span = span;
            this.inScope = inScope;
            this.clearedAmbient = clearedAmbient;
        }

        public void outcome(String value) {
            span.tag("outcome", value);
        }

        public void error(Throwable failure) {
            span.error(failure);
        }

        @Override
        public void close() {
            try {
                try {
                    inScope.close();
                } finally {
                    span.end();
                }
            } finally {
                clearedAmbient.close();
            }
        }
    }
}
