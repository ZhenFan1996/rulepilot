package com.rulepilot.document.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public interface DocumentOutboxStore {

    List<PendingEvent> findReady(Instant now, int limit);

    void markPublished(UUID eventId, Instant publishedAt);

    record PendingEvent(
            UUID id,
            String eventType,
            String payload,
            Instant occurredAt,
            TraceHeaders traceHeaders) {
        public PendingEvent(UUID id, String eventType, String payload, Instant occurredAt) {
            this(id, eventType, payload, occurredAt, TraceHeaders.none());
        }

        public PendingEvent {
            traceHeaders = traceHeaders == null ? TraceHeaders.none() : traceHeaders;
        }
    }

    /** The only cross-process trace fields persisted with an outbox event. */
    record TraceHeaders(String traceParent, String traceState) {
        private static final Pattern TRACE_PARENT = Pattern.compile(
                "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");

        public TraceHeaders {
            traceParent = validTraceParent(traceParent) ? traceParent : null;
            traceState = traceParent != null && validTraceState(traceState) ? traceState : null;
        }

        public static TraceHeaders none() {
            return new TraceHeaders(null, null);
        }

        public boolean present() {
            return traceParent != null;
        }

        private static boolean validTraceParent(String value) {
            return value != null && TRACE_PARENT.matcher(value).matches();
        }

        private static boolean validTraceState(String value) {
            if (value == null || value.isBlank()) return false;
            if (value.length() > 512) return false;
            return value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e);
        }
    }
}
