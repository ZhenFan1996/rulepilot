package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.application.DocumentOutboxStore.TraceHeaders;
import org.junit.jupiter.api.Test;

class DocumentOutboxStoreTest {

    private static final String TRACE_PARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void retainsOnlyAValidVersionZeroW3cTraceContext() {
        var headers = new TraceHeaders(TRACE_PARENT, "vendor=value");

        assertThat(headers.present()).isTrue();
        assertThat(headers.traceParent()).isEqualTo(TRACE_PARENT);
        assertThat(headers.traceState()).isEqualTo("vendor=value");
    }

    @Test
    void dropsMalformedOrAllZeroIdentifiersInsteadOfPersistingAnUnrestorableContext() {
        assertThat(new TraceHeaders("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01", "vendor=value"))
                .isEqualTo(TraceHeaders.none());
        assertThat(new TraceHeaders("00-4bf92f3577b34da6a3ce929d0e0e4736-" + "0".repeat(16) + "-01", null))
                .isEqualTo(TraceHeaders.none());
        assertThat(new TraceHeaders(TRACE_PARENT.toUpperCase(), "vendor=value"))
                .isEqualTo(TraceHeaders.none());
    }

    @Test
    void dropsTraceStateWithoutAValidParentAndRejectsNonPrintableOrOversizedValues() {
        assertThat(new TraceHeaders(null, "vendor=value")).isEqualTo(TraceHeaders.none());
        assertThat(new TraceHeaders(TRACE_PARENT, "vendor=bad\nvalue").traceState()).isNull();
        assertThat(new TraceHeaders(TRACE_PARENT, "v".repeat(513)).traceState()).isNull();
    }
}
