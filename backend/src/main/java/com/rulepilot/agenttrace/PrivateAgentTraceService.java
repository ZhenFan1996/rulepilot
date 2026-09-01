package com.rulepilot.agenttrace;

import com.rulepilot.agenttrace.AgentTraceEvent.BindingOrFailure;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelCallStarted;
import com.rulepilot.agenttrace.AgentTraceEvent.ModelTurn;
import com.rulepilot.agenttrace.AgentTraceEvent.Publication;
import com.rulepilot.agenttrace.AgentTraceEvent.ResourceRef;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolCall;
import com.rulepilot.agenttrace.AgentTraceEvent.ToolObservation;
import com.rulepilot.agenttrace.AgentTraceEvent.UserTurn;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceIntegrity;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceReadResult;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceSession;
import com.rulepilot.agenttrace.PrivateAgentTraceStore.TraceState;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PrivateAgentTraceService {

    public static final String SESSION_ATTRIBUTE = PrivateAgentTraceService.class.getName() + ".traceId";
    static final String DELETE_PENDING_ATTRIBUTE = PrivateAgentTraceService.class.getName() + ".deletePendingTraceId";
    private static final int MAXIMUM_CONCURRENT_EXPORTS = 2;

    private final PrivateAgentTraceStore store;
    private final PrivateAgentTraceProperties properties;
    private final Clock clock;
    private final Semaphore exportPermits = new Semaphore(MAXIMUM_CONCURRENT_EXPORTS);
    private final Set<String> exportingOwners = ConcurrentHashMap.newKeySet();

    public PrivateAgentTraceService(
            PrivateAgentTraceStore store, PrivateAgentTraceProperties properties, Clock clock) {
        if (store == null || properties == null || clock == null) {
            throw new IllegalArgumentException("private agent trace service dependencies are required");
        }
        properties.validate();
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public TraceStatus start(Principal principal, HttpSession session) {
        String owner = owner(principal);
        requireSession(session);
        synchronized (session) {
            if (!properties.allows(owner)) throw new TraceAccessException(AccessCode.TRACE_NOT_FOUND);
            Optional<UUID> current = traceId(session);
            if (current.isPresent()) {
                Optional<TraceSession> existing = find(current.orElseThrow());
                if (existing.isPresent()) throw new TraceAccessException(AccessCode.ACTIVE_TRACE_EXISTS);
                if (deletePending(session, current.orElseThrow())) {
                    throw new TraceAccessException(AccessCode.TRACE_UNAVAILABLE);
                }
                session.removeAttribute(SESSION_ATTRIBUTE);
            }
            if (!store.available()) throw new TraceAccessException(AccessCode.TRACE_UNAVAILABLE);
            Instant now = clock.instant();
            UUID traceId = UUID.randomUUID();
            TraceSession created;
            try {
                created = store.create(
                        traceId,
                        owner,
                        sessionDigest(session.getId()),
                        now,
                        now.plus(properties.getCaptureDuration()),
                        now.plus(properties.getRetention()));
            } catch (AgentTraceStoreException exception) {
                throw access(exception);
            }
            try {
                session.setAttribute(SESSION_ATTRIBUTE, traceId.toString());
            } catch (RuntimeException exception) {
                bestEffortDelete(traceId, owner);
                throw new TraceAccessException(AccessCode.TRACE_UNAVAILABLE);
            }
            return status(created);
        }
    }

    public Optional<TraceStatus> status(Principal principal, HttpSession session) {
        String owner = owner(principal);
        requireSession(session);
        Optional<UUID> traceId = traceId(session);
        if (traceId.isEmpty()) return Optional.empty();
        Optional<TraceSession> current = find(traceId.orElseThrow());
        if (current.isEmpty()) {
            if (deletePending(session, traceId.orElseThrow())) {
                throw new TraceAccessException(AccessCode.TRACE_UNAVAILABLE);
            }
            session.removeAttribute(SESSION_ATTRIBUTE);
            return Optional.empty();
        }
        TraceSession owned = requireOwned(current.orElseThrow(), owner, session.getId());
        return Optional.of(status(sealExpired(owned)));
    }

    public TraceStatus seal(Principal principal, HttpSession session) {
        TraceSession owned = requireCurrent(principal, session);
        try {
            return status(store.seal(owned.traceId(), clock.instant()));
        } catch (AgentTraceStoreException exception) {
            throw access(exception);
        }
    }

    public void delete(Principal principal, HttpSession session) {
        String owner = owner(principal);
        requireSession(session);
        synchronized (session) {
            UUID traceId = traceId(session).orElseThrow(() -> new TraceAccessException(AccessCode.TRACE_NOT_FOUND));
            Optional<TraceSession> current = find(traceId);
            boolean retryingVerifiedDelete = current.isEmpty() && deletePending(session, traceId);
            if (!retryingVerifiedDelete) {
                TraceSession owned = requireOwned(
                        current.orElseThrow(() -> new TraceAccessException(AccessCode.TRACE_NOT_FOUND)),
                        owner,
                        session.getId());
                try {
                    session.setAttribute(DELETE_PENDING_ATTRIBUTE, owned.traceId().toString());
                } catch (RuntimeException exception) {
                    throw new TraceAccessException(AccessCode.TRACE_UNAVAILABLE);
                }
            }
            try {
                store.delete(traceId, owner);
                session.removeAttribute(SESSION_ATTRIBUTE);
                session.removeAttribute(DELETE_PENDING_ATTRIBUTE);
            } catch (AgentTraceStoreException exception) {
                throw access(exception);
            } catch (RuntimeException exception) {
                throw new TraceAccessException(AccessCode.TRACE_UNAVAILABLE);
            }
        }
    }

    public CaptureHandle current(Principal principal, HttpSession session) {
        try {
            String owner = owner(principal);
            requireSession(session);
            Optional<UUID> traceId = traceId(session);
            if (traceId.isEmpty()) return CaptureHandle.noop();
            Optional<TraceSession> current = find(traceId.orElseThrow());
            if (current.isEmpty()) {
                if (!deletePending(session, traceId.orElseThrow())) {
                    session.removeAttribute(SESSION_ATTRIBUTE);
                }
                return CaptureHandle.noop();
            }
            TraceSession owned = requireOwned(current.orElseThrow(), owner, session.getId());
            TraceSession active = sealExpired(owned);
            return active.state() == TraceState.ACTIVE
                    ? new ActiveCaptureHandle(active.traceId(), store, clock)
                    : CaptureHandle.noop();
        } catch (TraceAccessException exception) {
            return CaptureHandle.noop();
        }
    }

    public CaptureHandle recover(ResourceRef resource, String ownerUsername) {
        if (resource == null) return CaptureHandle.noop();
        try {
            String owner = owner(ownerUsername);
            Optional<UUID> traceId = store.resolve(resource);
            if (traceId.isEmpty()) return CaptureHandle.noop();
            Optional<TraceSession> trace = store.find(traceId.orElseThrow());
            if (trace.isEmpty()) return CaptureHandle.noop();
            TraceSession active = sealExpired(trace.orElseThrow());
            return active.state() == TraceState.ACTIVE && store.matchesOwner(active, owner)
                    ? new ActiveCaptureHandle(active.traceId(), store, clock)
                    : CaptureHandle.noop();
        } catch (AgentTraceStoreException | TraceAccessException exception) {
            return CaptureHandle.noop();
        }
    }

    ExportSnapshot export(Principal principal, HttpSession session) {
        try (ExportLease lease = beginExport(principal, session)) {
            return lease.snapshot();
        }
    }

    ExportLease beginExport(Principal principal, HttpSession session) {
        String owner = owner(principal);
        TraceSession owned = sealExpired(requireCurrent(principal, session));
        if (!exportingOwners.add(owner)) throw new TraceAccessException(AccessCode.TRACE_EXPORT_BUSY);
        if (!exportPermits.tryAcquire()) {
            exportingOwners.remove(owner);
            throw new TraceAccessException(AccessCode.TRACE_EXPORT_BUSY);
        }
        try {
            TraceReadResult read = store.read(owned.traceId());
            TraceSession snapshot = requireOwned(read.session(), owner, session.getId());
            return new ExportLease(
                    new ExportSnapshot(snapshot, read), () -> releaseExport(owner));
        } catch (AgentTraceStoreException exception) {
            releaseExport(owner);
            throw access(exception);
        } catch (RuntimeException exception) {
            releaseExport(owner);
            throw exception;
        }
    }

    private void releaseExport(String owner) {
        if (exportingOwners.remove(owner)) exportPermits.release();
    }

    private TraceSession requireCurrent(Principal principal, HttpSession session) {
        String owner = owner(principal);
        requireSession(session);
        UUID traceId = traceId(session).orElseThrow(() -> new TraceAccessException(AccessCode.TRACE_NOT_FOUND));
        TraceSession trace = find(traceId).orElseThrow(() -> new TraceAccessException(AccessCode.TRACE_NOT_FOUND));
        return requireOwned(trace, owner, session.getId());
    }

    private TraceSession requireOwned(TraceSession trace, String owner, String sessionId) {
        boolean sameOwner = store.matchesOwner(trace, owner);
        boolean sameSession = MessageDigest.isEqual(
                trace.sessionDigest().getBytes(StandardCharsets.UTF_8),
                sessionDigest(sessionId).getBytes(StandardCharsets.UTF_8));
        if (!sameOwner || !sameSession) throw new TraceAccessException(AccessCode.TRACE_NOT_FOUND);
        return trace;
    }

    private TraceSession sealExpired(TraceSession trace) {
        if (trace.state() == TraceState.SEALED || clock.instant().isBefore(trace.captureUntil())) return trace;
        try {
            return store.seal(trace.traceId(), clock.instant());
        } catch (AgentTraceStoreException exception) {
            throw access(exception);
        }
    }

    private Optional<TraceSession> find(UUID traceId) {
        try {
            return store.find(traceId);
        } catch (AgentTraceStoreException exception) {
            throw access(exception);
        }
    }

    private TraceStatus status(TraceSession trace) {
        return new TraceStatus(
                trace.traceId(),
                CaptureState.valueOf(trace.state().name()),
                CaptureIntegrity.valueOf(trace.integrity().name()),
                trace.incompleteReason(),
                trace.createdAt(),
                trace.captureUntil(),
                trace.expiresAt(),
                trace.sealedAt(),
                trace.eventCount(),
                trace.storedBytes());
    }

    private void bestEffortDelete(UUID traceId, String ownerUsername) {
        try {
            store.delete(traceId, ownerUsername);
        } catch (RuntimeException ignored) {
            // Fixed Redis expiry remains the recovery boundary when eager cleanup is unavailable.
        }
    }

    private boolean deletePending(HttpSession session, UUID traceId) {
        Object value = session.getAttribute(DELETE_PENDING_ATTRIBUTE);
        return value != null && traceId.toString().equals(value.toString());
    }

    private Optional<UUID> traceId(HttpSession session) {
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value.toString()));
        } catch (IllegalArgumentException exception) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return Optional.empty();
        }
    }

    private String sessionDigest(String sessionId) {
        String checked = sessionId == null ? "" : sessionId.strip();
        if (checked.isBlank() || checked.length() > 512) {
            throw new TraceAccessException(AccessCode.TRACE_NOT_FOUND);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("rulepilot-private-agent-trace-session-v1\u0000" + checked)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String owner(Principal principal) {
        if (principal == null) throw new TraceAccessException(AccessCode.TRACE_NOT_FOUND);
        return owner(principal.getName());
    }

    private String owner(String value) {
        String checked = value == null ? "" : value.strip();
        if (checked.isBlank() || checked.length() > 120) {
            throw new TraceAccessException(AccessCode.TRACE_NOT_FOUND);
        }
        return checked;
    }

    private void requireSession(HttpSession session) {
        if (session == null) throw new TraceAccessException(AccessCode.TRACE_NOT_FOUND);
    }

    private TraceAccessException access(AgentTraceStoreException exception) {
        return new TraceAccessException(switch (exception.reason()) {
            case NOT_FOUND -> AccessCode.TRACE_NOT_FOUND;
            case OWNER_QUOTA_REACHED -> AccessCode.OWNER_TRACE_EXISTS;
            case SEALED, EXPIRED, CAP_REACHED -> AccessCode.TRACE_CONFLICT;
            case UNAVAILABLE, CORRUPT -> AccessCode.TRACE_UNAVAILABLE;
        });
    }

    public enum AccessCode {
        ACTIVE_TRACE_EXISTS,
        OWNER_TRACE_EXISTS,
        TRACE_CONFLICT,
        TRACE_EXPORT_BUSY,
        TRACE_NOT_FOUND,
        TRACE_UNAVAILABLE
    }

    public enum CaptureState {
        ACTIVE,
        SEALED
    }

    public enum CaptureIntegrity {
        COMPLETE,
        INCOMPLETE,
        TRUNCATED
    }

    public static final class TraceAccessException extends RuntimeException {
        private final AccessCode code;

        TraceAccessException(AccessCode code) {
            super(code == null ? "TRACE_UNAVAILABLE" : code.name());
            this.code = code == null ? AccessCode.TRACE_UNAVAILABLE : code;
        }

        public AccessCode code() {
            return code;
        }
    }

    public record TraceStatus(
            UUID traceId,
            CaptureState state,
            CaptureIntegrity integrity,
            String incompleteReason,
            Instant createdAt,
            Instant captureUntil,
            Instant expiresAt,
            Instant sealedAt,
            long eventCount,
            long storedBytes) {}

    record ExportSnapshot(TraceSession session, TraceReadResult readResult) {
        ExportSnapshot {
            if (session == null || readResult == null) {
                throw new IllegalArgumentException("private agent trace export snapshot is invalid");
            }
        }
    }

    static final class ExportLease implements AutoCloseable {
        private final ExportSnapshot snapshot;
        private final Runnable release;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private ExportLease(ExportSnapshot snapshot, Runnable release) {
            if (snapshot == null || release == null) {
                throw new IllegalArgumentException("private agent trace export lease is invalid");
            }
            this.snapshot = snapshot;
            this.release = release;
        }

        ExportSnapshot snapshot() {
            if (!open.get()) throw new IllegalStateException("private agent trace export lease is closed");
            return snapshot;
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) release.run();
        }
    }

    private static final class ActiveCaptureHandle implements CaptureHandle {
        private final UUID traceId;
        private final PrivateAgentTraceStore store;
        private final Clock clock;
        private final AtomicBoolean accepting = new AtomicBoolean(true);

        private ActiveCaptureHandle(UUID traceId, PrivateAgentTraceStore store, Clock clock) {
            this.traceId = traceId;
            this.store = store;
            this.clock = clock;
        }

        @Override
        public boolean enabled() {
            return accepting.get();
        }

        @Override
        public Optional<UUID> traceId() {
            return Optional.of(traceId);
        }

        @Override
        public void userTurn(UserTurn event) {
            append(event);
        }

        @Override
        public void modelCallStarted(ModelCallStarted event) {
            append(event);
        }

        @Override
        public void modelTurn(ModelTurn event) {
            append(event);
        }

        @Override
        public void toolCall(ToolCall event) {
            append(event);
        }

        @Override
        public void toolObservation(ToolObservation event) {
            append(event);
        }

        @Override
        public void publication(Publication event) {
            append(event);
        }

        @Override
        public void bindingOrFailure(BindingOrFailure event) {
            append(event);
        }

        @Override
        public boolean bind(ResourceRef resource) {
            if (!accepting.get() || resource == null) return false;
            try {
                boolean bound = store.bind(traceId, resource, clock.instant());
                if (!bound) failOpen("BINDING_REJECTED");
                return bound;
            } catch (RuntimeException exception) {
                failOpen("BINDING_WRITE_FAILED");
                return false;
            }
        }

        private void append(AgentTraceEvent event) {
            if (!accepting.get() || event == null) return;
            try {
                store.append(traceId, event, clock.instant());
            } catch (AgentTraceStoreException exception) {
                accepting.set(false);
                switch (exception.reason()) {
                    case SEALED -> bestEffortIncomplete("LATE_EVENT_AFTER_SEAL");
                    case EXPIRED -> bestEffortIncomplete("LATE_EVENT_AFTER_CAPTURE_DEADLINE");
                    case UNAVAILABLE, CORRUPT -> bestEffortIncomplete("EVENT_APPEND_FAILED");
                    default -> {
                        // Other typed store outcomes already carry their durable integrity state.
                    }
                }
            } catch (RuntimeException exception) {
                failOpen("EVENT_APPEND_FAILED");
            }
        }

        private void failOpen(String reasonCode) {
            accepting.set(false);
            bestEffortIncomplete(reasonCode);
        }

        private void bestEffortIncomplete(String reasonCode) {
            try {
                store.markIncomplete(traceId, reasonCode);
            } catch (RuntimeException ignored) {
                // Capture failures never replace the validated product result.
            }
        }
    }
}
