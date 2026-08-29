package com.rulepilot.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.assistant.NativeAgentTool.ToolObservation;
import java.util.List;
import java.util.Map;

/** Owns the canonical native-tool observation envelope and the estimator applied to that exact JSON. */
final class NativeEvidenceObservationBudget {

    /** Registered native tools always use a lowercase SHA-256 hash; the value changes no serialized byte count. */
    private static final String PROVISIONAL_SCHEMA_HASH = "0".repeat(64);

    /** Opaque continuation handles are UUID strings, so a provisional handle has the exact serialized size. */
    static final String PROVISIONAL_CURSOR = "00000000-0000-0000-0000-000000000000";

    private NativeEvidenceObservationBudget() {}

    /**
     * Bounds a speculative read before its values are known. Every retained evidence value consumes at least one
     * estimated token, while the exact post-read prefix is selected with {@link #fits}. This is a resource-derived
     * window, not an answer-count or retry ceiling.
     */
    static int candidateWindow(ObjectMapper objectMapper, int maxObservationTokens) {
        if (maxObservationTokens <= 0) return 0;
        int fixedTokens = serializedTokens(
                objectMapper, queryWindowObservation(List.of()), PROVISIONAL_SCHEMA_HASH);
        int oneEvidenceTokens = serializedTokens(
                objectMapper, queryWindowObservation(List.of(queryWindowEvidence())), PROVISIONAL_SCHEMA_HASH);
        int incrementalEvidenceTokens = Math.max(1, oneEvidenceTokens - fixedTokens);
        if (maxObservationTokens <= fixedTokens) return 0;
        return Math.max(1, (maxObservationTokens - fixedTokens) / incrementalEvidenceTokens);
    }

    private static ToolObservation queryWindowObservation(List<Map<String, Object>> evidence) {
        return ToolObservation.partial("EVIDENCE_PAGE_FOUND", Map.of(
                "evidence", evidence,
                "hasMore", true,
                "nextCursor", PROVISIONAL_CURSOR,
                "requestedLimit", Integer.MAX_VALUE,
                "returnedThrough", Integer.MAX_VALUE), evidence.size());
    }

    private static Map<String, Object> queryWindowEvidence() {
        return Map.of(
                "evidenceId", "0".repeat(36),
                "sectionType", "S".repeat(40),
                // Heading has no storage limit. This query-only projection leaves room for a substantial heading;
                // exact post-read packing below remains authoritative for every real value and escape sequence.
                "heading", "H".repeat(512),
                "excerpt", "E".repeat(1_800),
                "pageFrom", Integer.MAX_VALUE,
                "pageTo", Integer.MAX_VALUE);
    }

    static boolean fits(ObjectMapper objectMapper, ToolObservation observation, int maxObservationTokens) {
        return serializedTokens(objectMapper, observation, PROVISIONAL_SCHEMA_HASH) <= maxObservationTokens;
    }

    static boolean fits(
            ObjectMapper objectMapper,
            NativeAgentToolRegistry.ToolExecution execution,
            int maxObservationTokens) {
        return serializedTokens(
                        objectMapper,
                        execution.observation(),
                        execution.specification().schemaHash())
                <= maxObservationTokens;
    }

    static int serializedTokens(
            ObjectMapper objectMapper,
            ToolObservation observation,
            String schemaHash) {
        return estimateTokens(serialize(objectMapper, observation, schemaHash));
    }

    static String serialize(
            ObjectMapper objectMapper,
            NativeAgentToolRegistry.ToolExecution execution) {
        return serialize(
                objectMapper,
                execution.observation(),
                execution.specification().schemaHash());
    }

    private static String serialize(
            ObjectMapper objectMapper,
            ToolObservation observation,
            String schemaHash) {
        if (objectMapper == null || observation == null || schemaHash == null || schemaHash.isBlank()) {
            throw new IllegalArgumentException("native observation envelope is invalid");
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", observation.status().name(),
                    "code", observation.code(),
                    "data", observation.data(),
                    "evidenceCount", observation.evidenceCount(),
                    "schemaHash", schemaHash));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("native tool observation serialization failed", exception);
        }
    }

    static int estimateTokens(String value) {
        if (value == null || value.isEmpty()) return 0;

        long tokens = 0;
        int compactAscii = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7f && !isJsonDelimiter(codePoint)) {
                compactAscii++;
                continue;
            }
            tokens += compactAsciiTokens(compactAscii);
            compactAscii = 0;
            // JSON delimiters and escapes fragment tokenization, so charge each one directly. Non-ASCII code
            // points are charged by their UTF-8 width: this intentionally avoids treating one CJK character or
            // one supplementary emoji as a quarter/half token merely because Java stores UTF-16 code units.
            tokens += codePoint <= 0x7f ? 1 : utf8Width(codePoint);
            if (tokens >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        tokens += compactAsciiTokens(compactAscii);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, tokens));
    }

    private static int compactAsciiTokens(int characters) {
        return characters == 0 ? 0 : (characters + 3) / 4;
    }

    private static boolean isJsonDelimiter(int codePoint) {
        return codePoint == '"'
                || codePoint == '\\'
                || codePoint == '{'
                || codePoint == '}'
                || codePoint == '['
                || codePoint == ']'
                || codePoint == ':'
                || codePoint == ',';
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }
}
