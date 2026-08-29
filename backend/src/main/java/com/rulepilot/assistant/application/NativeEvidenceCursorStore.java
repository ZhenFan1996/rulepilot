package com.rulepilot.assistant.application;

import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Run-local opaque cursor state. Random handles are meaningless without this state and are bound to the immutable
 * document, normalized request fingerprint, tool identity, and run deadline.
 */
@Component
@Profile("!test")
final class NativeEvidenceCursorStore {

    private final Map<UUID, Entry> entries = new HashMap<>();

    synchronized Position open(
            ToolScope scope, String toolName, String fingerprint, String cursor, Position initial) {
        purgeExpired();
        if (cursor == null) {
            return initial;
        }
        return requireEntry(scope, toolName, fingerprint, cursor).entry().position();
    }

    synchronized String continueFrom(
            ToolScope scope, String toolName, String fingerprint, String cursor, Position position) {
        purgeExpired();
        Entry replacement = new Entry(
                scope.runId(), toolName, scope.documentVersionId(), fingerprint, scope.deadlineAt(), position);
        StoredCursor consumed = cursor == null ? null : requireEntry(scope, toolName, fingerprint, cursor);
        if (consumed != null) {
            entries.remove(consumed.handle(), consumed.entry());
        }
        UUID handle = newHandle(consumed == null ? null : consumed.handle());
        entries.put(handle, replacement);
        return handle.toString();
    }

    synchronized void close(ToolScope scope, String toolName, String fingerprint, String cursor) {
        purgeExpired();
        if (cursor != null) {
            StoredCursor stored = requireEntry(scope, toolName, fingerprint, cursor);
            entries.remove(stored.handle(), stored.entry());
            return;
        }
        entries.entrySet().removeIf(stored ->
                stored.getValue().matchesQuery(scope, toolName, fingerprint));
    }

    private StoredCursor requireEntry(ToolScope scope, String toolName, String fingerprint, String cursor) {
        final UUID handle;
        try {
            handle = UUID.fromString(cursor);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("cursor is not a valid opaque continuation handle", invalid);
        }
        Entry entry = entries.get(handle);
        if (entry == null || !entry.matches(scope, toolName, fingerprint) || !Instant.now().isBefore(entry.deadlineAt())) {
            throw new IllegalArgumentException("cursor does not belong to this run, document version, or request");
        }
        return new StoredCursor(handle, entry);
    }

    private UUID newHandle(UUID consumedHandle) {
        UUID handle;
        do {
            handle = UUID.randomUUID();
        } while (handle.equals(consumedHandle) || entries.containsKey(handle));
        return handle;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().deadlineAt()));
    }

    record Position(int primaryOffset, int secondaryOffset, int identityCount, List<UUID> resolvedIds) {
        Position {
            if (primaryOffset < 0 || secondaryOffset < 0 || identityCount < 0 || resolvedIds == null) {
                throw new IllegalArgumentException("native evidence cursor position is invalid");
            }
            resolvedIds = List.copyOf(resolvedIds);
        }

        static Position initial() {
            return new Position(0, 0, 0, List.of());
        }
    }

    private record Entry(
            UUID runId,
            String toolName,
            UUID documentVersionId,
            String requestFingerprint,
            Instant deadlineAt,
            Position position) {

        private Entry {
            if (runId == null
                    || toolName == null
                    || toolName.isBlank()
                    || documentVersionId == null
                    || requestFingerprint == null
                    || requestFingerprint.isBlank()
                    || deadlineAt == null
                    || position == null) {
                throw new IllegalArgumentException("native evidence cursor entry is invalid");
            }
        }

        private boolean matches(ToolScope scope, String requestedToolName, String requestedFingerprint) {
            return matchesQuery(scope, requestedToolName, requestedFingerprint);
        }

        private boolean matchesQuery(ToolScope scope, String requestedToolName, String requestedFingerprint) {
            return runId.equals(scope.runId())
                    && toolName.equals(requestedToolName)
                    && documentVersionId.equals(scope.documentVersionId())
                    && requestFingerprint.equals(requestedFingerprint);
        }
    }

    private record StoredCursor(UUID handle, Entry entry) {}
}
