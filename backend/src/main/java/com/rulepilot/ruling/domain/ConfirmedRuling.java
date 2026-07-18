package com.rulepilot.ruling.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record ConfirmedRuling(
        UUID id,
        RulingApplicability applicability,
        String originalQuestion,
        String normalizedQuestion,
        String normalizedQuestionHash,
        String shortVerdict,
        String explanation,
        List<RulingCitation> citations,
        List<String> exceptions,
        RulingConfidence confidence,
        boolean official,
        RulingStatus status,
        String createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public ConfirmedRuling {
        if (id == null || applicability == null || citations == null || citations.isEmpty()
                || exceptions == null || confidence == null || status == null
                || version < 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("confirmed ruling is invalid");
        }
        originalQuestion = normalized(originalQuestion, "question", 2000);
        normalizedQuestion = normalizeQuestion(normalizedQuestion);
        if (!normalizeQuestion(originalQuestion).equals(normalizedQuestion)) {
            throw new IllegalArgumentException("normalized ruling question does not match the original question");
        }
        String expectedHash = hash(normalizedQuestion);
        if (normalizedQuestionHash == null) {
            normalizedQuestionHash = expectedHash;
        } else if (!normalizedQuestionHash.equals(expectedHash)) {
            throw new IllegalArgumentException("ruling question hash is invalid");
        }
        shortVerdict = normalized(shortVerdict, "short verdict", 2000);
        explanation = normalized(explanation, "explanation", 20000);
        createdBy = normalized(createdBy, "creator", 120);
        citations = List.copyOf(citations);
        exceptions = exceptions.stream()
                .map(value -> normalized(value, "exception", 2000))
                .toList();
        if (citations.stream().anyMatch(citation ->
                !applicability.documentVersionId().equals(citation.documentVersionId()))) {
            throw new IllegalArgumentException("ruling citation uses a different document version");
        }
    }

    public static ConfirmedRuling confirm(
            RulingApplicability applicability,
            String question,
            String shortVerdict,
            String explanation,
            List<RulingCitation> citations,
            List<String> exceptions,
            RulingConfidence confidence,
            String createdBy,
            Instant now) {
        String normalizedQuestion = normalizeQuestion(question);
        return new ConfirmedRuling(
                UUID.randomUUID(), applicability, question, normalizedQuestion, null,
                shortVerdict, explanation, citations, exceptions == null ? List.of() : exceptions,
                confidence, false, RulingStatus.CONFIRMED, createdBy, 0, now, now);
    }

    private static String normalizeQuestion(String value) {
        return normalized(value, "normalized question", 2000).toLowerCase(Locale.ROOT);
    }

    private static String normalized(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
