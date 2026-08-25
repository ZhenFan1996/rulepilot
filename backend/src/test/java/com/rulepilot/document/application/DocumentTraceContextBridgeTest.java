package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rulepilot.document.application.DocumentOutboxStore.TraceHeaders;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTraceContextBridgeTest {

    private static final String TRACE_PARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String UNSAMPLED_TRACE_PARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";

    @Test
    void capturePersistsAnUnsampledW3cParentButNeverBaggageOrApplicationHeaders() {
        Tracer tracer = mock(Tracer.class);
        Span current = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(current);
        // Micrometer OTel reports a valid non-recording span as noop; propagation must still retain its identity.
        when(current.isNoop()).thenReturn(true);
        when(current.context()).thenReturn(context);
        var propagator = new RecordingPropagator();
        var bridge = new DocumentTraceContextBridge(tracer, propagator);

        TraceHeaders captured = bridge.capture();

        assertThat(captured).isEqualTo(new TraceHeaders(UNSAMPLED_TRACE_PARENT, "vendor=value"));
        assertThat(propagator.injectedFields).containsExactly("traceparent", "tracestate", "baggage", "x-player-id");
    }

    @Test
    void openRestoresTheStoredParentAndNeverOffersApplicationOrBaggageFieldsToExtraction() {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext currentTraceContext = mock(CurrentTraceContext.class);
        CurrentTraceContext.Scope clearedAmbient = mock(CurrentTraceContext.Scope.class);
        Tracer.SpanInScope inScope = mock(Tracer.SpanInScope.class);
        Span child = mock(Span.class);
        when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        when(currentTraceContext.maybeScope(null)).thenReturn(clearedAmbient);
        when(tracer.withSpan(child)).thenReturn(inScope);
        when(child.tag("outcome", "published")).thenReturn(child);
        var propagator = new RecordingPropagator(child);
        var bridge = new DocumentTraceContextBridge(tracer, propagator);

        try (var scope = bridge.open(new TraceHeaders(TRACE_PARENT, "vendor=value"), "document.outbox.publish")) {
            scope.outcome("published");
        }

        assertThat(propagator.extractedTraceParent).isEqualTo(TRACE_PARENT);
        assertThat(propagator.extractedTraceState).isEqualTo("vendor=value");
        assertThat(propagator.extractedBaggage).isNull();
        verify(propagator.extractedBuilder).name("document.outbox.publish");
        verify(propagator.extractedBuilder).start();
        verify(currentTraceContext).maybeScope(null);
        verify(clearedAmbient).close();
        verify(child).tag("outcome", "published");
        verify(inScope).close();
        verify(child).end();
    }

    @Test
    void missingOrInvalidStoredContextStartsARealRootWithoutConsultingAmbientExtraction() {
        Tracer tracer = mock(Tracer.class);
        Tracer.SpanInScope inScope = mock(Tracer.SpanInScope.class);
        Span child = mock(Span.class);
        Span.Builder root = mock(Span.Builder.class);
        CurrentTraceContext currentTraceContext = mock(CurrentTraceContext.class);
        CurrentTraceContext.Scope clearedAmbient = mock(CurrentTraceContext.Scope.class);
        when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        when(currentTraceContext.maybeScope(null)).thenReturn(clearedAmbient);
        when(tracer.spanBuilder()).thenReturn(root);
        when(root.setNoParent()).thenReturn(root);
        when(root.name("document.outbox.publish")).thenReturn(root);
        when(root.start()).thenReturn(child);
        when(tracer.withSpan(child)).thenReturn(inScope);
        Propagator propagator = mock(Propagator.class);
        var bridge = new DocumentTraceContextBridge(tracer, propagator);

        try (var ignored = bridge.open(new TraceHeaders("not-a-trace", "vendor=value"), "document.outbox.publish")) {
            // Legacy and malformed events are intentionally disconnected from any wake-up request.
        }

        verify(root).setNoParent();
        verify(root).name("document.outbox.publish");
        verify(root).start();
        verify(currentTraceContext).maybeScope(null);
        verify(clearedAmbient).close();
        verifyNoInteractions(propagator);
        verify(child).end();
    }

    private static final class RecordingPropagator implements Propagator {
        private final Span extractedSpan;
        private final Span.Builder extractedBuilder;
        private final List<String> injectedFields = new ArrayList<>();
        private String extractedTraceParent;
        private String extractedTraceState;
        private String extractedBaggage;

        private RecordingPropagator() {
            this(null);
        }

        private RecordingPropagator(Span extractedSpan) {
            this.extractedSpan = extractedSpan;
            this.extractedBuilder = mock(Span.Builder.class);
            when(extractedBuilder.name(org.mockito.ArgumentMatchers.anyString())).thenReturn(extractedBuilder);
            when(extractedBuilder.start()).thenReturn(extractedSpan == null ? mock(Span.class) : extractedSpan);
        }

        @Override
        public List<String> fields() {
            return List.of("traceparent", "tracestate", "baggage", "x-player-id");
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            inject(setter, carrier, "traceparent", UNSAMPLED_TRACE_PARENT);
            inject(setter, carrier, "tracestate", "vendor=value");
            inject(setter, carrier, "baggage", "private=value");
            inject(setter, carrier, "x-player-id", "player-123");
        }

        private <C> void inject(Setter<C> setter, C carrier, String name, String value) {
            injectedFields.add(name);
            setter.set(carrier, name, value);
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            extractedTraceParent = getter.get(carrier, "traceparent");
            extractedTraceState = getter.get(carrier, "tracestate");
            extractedBaggage = getter.get(carrier, "baggage");
            return extractedBuilder;
        }
    }
}
