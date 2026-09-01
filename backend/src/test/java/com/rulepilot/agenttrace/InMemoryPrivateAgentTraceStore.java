package com.rulepilot.agenttrace;

import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class InMemoryPrivateAgentTraceStore implements PrivateAgentTraceStore {

    private final Map<UUID, TraceSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, List<StoredEvent>> events = new LinkedHashMap<>();
    private final Map<ResourceRef, UUID> bindings = new LinkedHashMap<>();
    private final Map<String, UUID> ownerSlots = new LinkedHashMap<>();
    private boolean available = true;
    private boolean failAppend;
    private boolean failDelete;

    @Override
    public boolean available() {
        return available;
    }

    void setAvailable(boolean available) {
        this.available = available;
    }

    void setFailAppend(boolean failAppend) {
        this.failAppend = failAppend;
    }

    void setFailDelete(boolean failDelete) {
        this.failDelete = failDelete;
    }

    @Override
    public synchronized TraceSession create(
            UUID traceId,
            String ownerUsername,
            String sessionDigest,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt) {
        if (!available) throw new AgentTraceStoreException(AgentTraceStoreException.Reason.UNAVAILABLE);
        if (sessions.containsKey(traceId)) {
            throw new AgentTraceStoreException(AgentTraceStoreException.Reason.CORRUPT);
        }
        if (ownerSlots.containsKey(ownerUsername)) {
            throw new AgentTraceStoreException(AgentTraceStoreException.Reason.OWNER_QUOTA_REACHED);
        }
        TraceSession session = new TraceSession(
                traceId,
                UUID.randomUUID().toString(),
                sessionDigest,
                TraceState.ACTIVE,
                TraceIntegrity.COMPLETE,
                "",
                createdAt,
                captureUntil,
                expiresAt,
                null,
                0,
                0);
        sessions.put(traceId, session);
        events.put(traceId, new ArrayList<>());
        ownerSlots.put(ownerUsername, traceId);
        return session;
    }

    @Override
    public synchronized Optional<TraceSession> find(UUID traceId) {
        return Optional.ofNullable(sessions.get(traceId));
    }

    @Override
    public synchronized boolean matchesOwner(TraceSession trace, String ownerUsername) {
        if (trace == null || ownerUsername == null) return false;
        return trace.traceId().equals(ownerSlots.get(ownerUsername));
    }

    @Override
    public synchronized TraceSession seal(UUID traceId, Instant sealedAt) {
        TraceSession current = required(traceId);
        TraceSession sealed = copy(current, TraceState.SEALED, current.integrity(), current.incompleteReason(), sealedAt);
        sessions.put(traceId, sealed);
        return sealed;
    }

    @Override
    public synchronized void delete(UUID traceId, String ownerUsername) {
        if (failDelete) throw new AgentTraceStoreException(AgentTraceStoreException.Reason.UNAVAILABLE);
        TraceSession current = sessions.get(traceId);
        if (current != null && !matchesOwner(current, ownerUsername)) {
            throw new AgentTraceStoreException(AgentTraceStoreException.Reason.NOT_FOUND);
        }
        if (current == null && ownerSlots.containsValue(traceId)) {
            throw new AgentTraceStoreException(AgentTraceStoreException.Reason.CORRUPT);
        }
        sessions.remove(traceId);
        events.remove(traceId);
        bindings.entrySet().removeIf(entry -> entry.getValue().equals(traceId));
        ownerSlots.remove(ownerUsername, traceId);
    }

    @Override
    public synchronized AppendResult append(UUID traceId, AgentTraceEvent event, Instant appendedAt) {
        if (failAppend) throw new AgentTraceStoreException(AgentTraceStoreException.Reason.UNAVAILABLE);
        TraceSession current = required(traceId);
        if (current.state() != TraceState.ACTIVE) {
            markIncomplete(traceId, "LATE_EVENT_AFTER_SEAL");
            throw new AgentTraceStoreException(AgentTraceStoreException.Reason.SEALED);
        }
        if (!appendedAt.isBefore(current.captureUntil())) {
            TraceSession expired = copy(
                    current,
                    TraceState.SEALED,
                    TraceIntegrity.INCOMPLETE,
                    current.integrity() == TraceIntegrity.COMPLETE
                            ? "LATE_EVENT_AFTER_CAPTURE_DEADLINE"
                            : current.incompleteReason(),
                    appendedAt);
            sessions.put(traceId, expired);
            throw new AgentTraceStoreException(AgentTraceStoreException.Reason.EXPIRED);
        }
        List<StoredEvent> stored = events.get(traceId);
        long sequence = stored.size() + 1L;
        stored.add(new StoredEvent(sequence, event));
        long eventBytes = 100;
        sessions.put(traceId, new TraceSession(
                current.traceId(),
                current.ownerIdentity(),
                current.sessionDigest(),
                current.state(),
                current.integrity(),
                current.incompleteReason(),
                current.createdAt(),
                current.captureUntil(),
                current.expiresAt(),
                current.sealedAt(),
                sequence,
                current.storedBytes() + eventBytes));
        return new AppendResult(sequence, eventBytes);
    }

    @Override
    public synchronized void markIncomplete(UUID traceId, String reasonCode) {
        TraceSession current = required(traceId);
        if (current.integrity() != TraceIntegrity.COMPLETE) return;
        sessions.put(traceId, copy(current, current.state(), TraceIntegrity.INCOMPLETE, reasonCode, current.sealedAt()));
    }

    @Override
    public synchronized boolean bind(UUID traceId, ResourceRef resource, Instant boundAt) {
        TraceSession current = required(traceId);
        if (current.state() != TraceState.ACTIVE || !boundAt.isBefore(current.captureUntil())) return false;
        UUID existing = bindings.putIfAbsent(resource, traceId);
        return existing == null || existing.equals(traceId);
    }

    @Override
    public synchronized Optional<UUID> resolve(ResourceRef resource) {
        return Optional.ofNullable(bindings.get(resource));
    }

    @Override
    public synchronized TraceReadResult read(UUID traceId) {
        TraceSession session = required(traceId);
        return new TraceReadResult(
                session, events.get(traceId), List.of(), events.get(traceId).size(), session.storedBytes());
    }

    private TraceSession required(UUID traceId) {
        TraceSession session = sessions.get(traceId);
        if (session == null) throw new AgentTraceStoreException(AgentTraceStoreException.Reason.NOT_FOUND);
        return session;
    }

    private TraceSession copy(
            TraceSession current,
            TraceState state,
            TraceIntegrity integrity,
            String reason,
            Instant sealedAt) {
        return new TraceSession(
                current.traceId(),
                current.ownerIdentity(),
                current.sessionDigest(),
                state,
                integrity,
                reason,
                current.createdAt(),
                current.captureUntil(),
                current.expiresAt(),
                sealedAt,
                current.eventCount(),
                current.storedBytes());
    }
}
