package com.rulepilot.agenttrace;

import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PrivateAgentTraceStore {

    boolean available();

    TraceSession create(
            UUID traceId,
            String ownerUsername,
            String sessionDigest,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt);

    Optional<TraceSession> find(UUID traceId);

    boolean matchesOwner(TraceSession trace, String ownerUsername);

    TraceSession seal(UUID traceId, Instant sealedAt);

    void delete(UUID traceId, String ownerUsername);

    AppendResult append(UUID traceId, AgentTraceEvent event, Instant appendedAt);

    void markIncomplete(UUID traceId, String reasonCode);

    boolean bind(UUID traceId, ResourceRef resource, Instant boundAt);

    Optional<UUID> resolve(ResourceRef resource);

    TraceReadResult read(UUID traceId);

    enum TraceState {
        ACTIVE,
        SEALED
    }

    enum TraceIntegrity {
        COMPLETE,
        INCOMPLETE,
        TRUNCATED
    }

    record TraceSession(
            UUID traceId,
            String ownerIdentity,
            String sessionDigest,
            TraceState state,
            TraceIntegrity integrity,
            String incompleteReason,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt,
            Instant sealedAt,
            long eventCount,
            long storedBytes) {
        public TraceSession {
            if (traceId == null || ownerIdentity == null || ownerIdentity.isBlank()
                    || sessionDigest == null || sessionDigest.isBlank()
                    || state == null || integrity == null || createdAt == null
                    || captureUntil == null || expiresAt == null || eventCount < 0 || storedBytes < 0) {
                throw new IllegalArgumentException("private agent trace session is invalid");
            }
            incompleteReason = incompleteReason == null ? "" : incompleteReason;
        }
    }

    record AppendResult(long sequence, long storedBytes) {
        public AppendResult {
            if (sequence < 1 || storedBytes < 1) {
                throw new IllegalArgumentException("private agent trace append result is invalid");
            }
        }
    }

    record StoredEvent(long sequence, AgentTraceEvent event) {
        public StoredEvent {
            if (sequence < 1 || event == null) {
                throw new IllegalArgumentException("stored private agent trace event is invalid");
            }
        }
    }

    record TraceReadResult(
            TraceSession session,
            List<StoredEvent> events,
            List<String> problemCodes,
            long observedEventCount,
            long observedStoredBytes) {
        public TraceReadResult {
            if (session == null || observedEventCount < 0 || observedStoredBytes < 0) {
                throw new IllegalArgumentException("private agent trace read result is invalid");
            }
            events = events == null ? List.of() : List.copyOf(events);
            problemCodes = problemCodes == null ? List.of() : List.copyOf(problemCodes);
        }
    }
}
