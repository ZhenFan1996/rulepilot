package com.rulepilot.assistant;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A read-only, scope-bound capability that may be exposed to a native model tool protocol. */
public interface NativeAgentTool {

    enum Role {
        ANSWER,
        TEACHING,
        VISUAL
    }

    enum ObservationStatus {
        SUCCESS,
        PARTIAL,
        ERROR
    }

    String name();

    String description();

    String inputSchema();

    String schemaVersion();

    Set<Role> allowedRoles();

    default boolean readOnly() {
        return true;
    }

    ToolObservation execute(String argumentsJson, ToolScope scope);

    record ToolScope(
            String ownerUsername,
            UUID documentVersionId,
            UUID runId,
            Instant deadlineAt,
            int maxObservationTokens) {

        /**
         * Compatibility constructor for direct tool tests and non-Agent callers. Production Agent execution replaces
         * this fallback with the current run's remaining context envelope before every tool invocation.
         */
        public ToolScope(String ownerUsername, UUID documentVersionId, UUID runId, Instant deadlineAt) {
            this(ownerUsername, documentVersionId, runId, deadlineAt, 4_096);
        }

        public ToolScope {
            if (ownerUsername == null || ownerUsername.isBlank()
                    || documentVersionId == null || runId == null || deadlineAt == null || maxObservationTokens < 0) {
                throw new IllegalArgumentException("native tool scope is invalid");
            }
            ownerUsername = ownerUsername.strip();
        }

        public ToolScope withMaxObservationTokens(int tokens) {
            return new ToolScope(ownerUsername, documentVersionId, runId, deadlineAt, tokens);
        }
    }

    record ToolObservation(
            ObservationStatus status,
            String code,
            Map<String, Object> data,
            int evidenceCount,
            java.util.List<ToolMedia> media) {
        public ToolObservation(
                ObservationStatus status,
                String code,
                Map<String, Object> data,
                int evidenceCount) {
            this(status, code, data, evidenceCount, java.util.List.of());
        }

        public ToolObservation {
            if (status == null || code == null || code.isBlank()
                    || data == null || evidenceCount < 0 || media == null) {
                throw new IllegalArgumentException("native tool observation is invalid");
            }
            code = code.strip();
            data = Map.copyOf(data);
            media = java.util.List.copyOf(media);
        }

        public static ToolObservation success(String code, Map<String, Object> data, int evidenceCount) {
            return new ToolObservation(ObservationStatus.SUCCESS, code, data, evidenceCount);
        }

        public static ToolObservation partial(String code, Map<String, Object> data, int evidenceCount) {
            return new ToolObservation(ObservationStatus.PARTIAL, code, data, evidenceCount);
        }

        public static ToolObservation error(String code) {
            return new ToolObservation(ObservationStatus.ERROR, code, Map.of(), 0);
        }
    }

    /** Bounded image observation attached to the model's next turn; never serialized into audit JSON. */
    record ToolMedia(String mediaType, byte[] content, String label, int width, int height) {
        public ToolMedia {
            if (mediaType == null || !Set.of("image/png", "image/jpeg", "image/webp").contains(mediaType)
                    || content == null || content.length == 0 || content.length > 12_000_000
                    || label == null || label.isBlank()
                    || width < 1 || height < 1) {
                throw new IllegalArgumentException("native tool media is invalid");
            }
            content = content.clone();
            label = label.strip();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
