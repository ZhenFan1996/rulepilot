package com.rulepilot.assistant.application;

import com.rulepilot.assistant.NativeToolAgent.TerminalValidation;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic publication boundary for typed hard numeric facts. */
final class RuleAnswerNumericBoundary {

    private RuleAnswerNumericBoundary() {}

    static TerminalValidation validate(
            List<NumericClaim> claims,
            List<String> citationIds,
            Map<String, ObservedEvidence> observed,
            Set<String> allowedIds) {
        for (int index = 0; index < claims.size(); index++) {
            NumericClaim claim = claims.get(index);
            if (!citationIds.contains(claim.evidenceId())) {
                return rejected(
                        "NUMERIC_EVIDENCE_NOT_CITED", "/numericClaims/" + index + "/evidenceId",
                        "A hard numeric fact must point to one of the answer's published citation identities.",
                        allowedIds);
            }
            ObservedEvidence source = observed.get(claim.evidenceId());
            if (source == null || !containsExactNumericLiteral(source.excerpt(), claim.value())) {
                return rejected(
                        "NUMERIC_VALUE_UNSUPPORTED", "/numericClaims/" + index + "/value",
                        "The exact numeric literal is absent from its cited canonical page observation.", allowedIds);
            }
        }
        return TerminalValidation.accepted();
    }

    private static boolean containsExactNumericLiteral(String excerpt, String literal) {
        if (excerpt == null || literal == null || literal.isBlank()) return false;
        int from = 0;
        while (from <= excerpt.length() - literal.length()) {
            int start = excerpt.indexOf(literal, from);
            if (start < 0) return false;
            int end = start + literal.length();
            if (hasNumericBoundary(excerpt, start, end, literal)) return true;
            from = start + 1;
        }
        return false;
    }

    private static boolean hasNumericBoundary(String excerpt, int start, int end, String literal) {
        if (start > 0 && Character.isDigit(excerpt.charAt(start - 1))) return false;
        if (end < excerpt.length() && Character.isDigit(excerpt.charAt(end))) return false;
        if (Character.isDigit(literal.charAt(0)) && start > 0 && isSign(excerpt.charAt(start - 1))) return false;
        if (start > 1 && isDecimalSeparator(excerpt.charAt(start - 1))
                && Character.isDigit(excerpt.charAt(start - 2))) {
            return false;
        }
        if (end + 1 < excerpt.length() && isDecimalSeparator(excerpt.charAt(end))
                && Character.isDigit(excerpt.charAt(end + 1))) {
            return false;
        }
        return true;
    }

    private static boolean isDecimalSeparator(char value) {
        return value == '.' || value == ',';
    }

    private static boolean isSign(char value) {
        return value == '+' || value == '-' || value == '\u2212';
    }

    private static TerminalValidation rejected(
            String code, String path, String reason, Set<String> allowedEvidenceIds) {
        return TerminalValidation.rejected(code, path, reason, allowedEvidenceIds);
    }

    record ObservedEvidence(String excerpt, int pageFrom, int pageTo) {}

    record NumericClaim(String value, String evidenceId) {}
}
