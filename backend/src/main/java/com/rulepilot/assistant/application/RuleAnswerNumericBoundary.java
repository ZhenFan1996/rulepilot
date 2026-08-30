package com.rulepilot.assistant.application;

import com.rulepilot.assistant.NativeToolAgent.TerminalValidation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic publication boundary for hard numeric facts in player prose. */
final class RuleAnswerNumericBoundary {

    private static final Pattern NUMERIC_LITERAL =
            Pattern.compile("[-+]?\\p{Nd}+(?:[.,]\\p{Nd}+)?(?:%|％)?");

    private RuleAnswerNumericBoundary() {}

    static TerminalValidation validate(
            String playerProse,
            List<NumericClaim> claims,
            List<String> citationIds,
            Map<String, ObservedEvidence> observed,
            Set<String> allowedIds) {
        Map<String, NumericClaim> claimsByValue = new LinkedHashMap<>();
        for (int index = 0; index < claims.size(); index++) {
            NumericClaim claim = claims.get(index);
            if (!citationIds.contains(claim.evidenceId())) {
                return rejected(
                        "NUMERIC_EVIDENCE_NOT_CITED", "/numericClaims/" + index + "/evidenceId",
                        "A hard numeric fact must point to one of the answer's published citation identities.",
                        allowedIds);
            }
            ObservedEvidence source = observed.get(claim.evidenceId());
            if (source == null || !source.excerpt().contains(claim.value())) {
                return rejected(
                        "NUMERIC_VALUE_UNSUPPORTED", "/numericClaims/" + index + "/value",
                        "The exact numeric literal is absent from its cited canonical page observation.", allowedIds);
            }
            claimsByValue.putIfAbsent(claim.value(), claim);
        }
        Matcher numbers = NUMERIC_LITERAL.matcher(playerProse);
        while (numbers.find()) {
            String literal = numbers.group();
            if (!claimsByValue.containsKey(literal)) {
                return rejected(
                        "NUMERIC_CLAIM_UNDECLARED", "/numericClaims",
                        "Every numeric literal in player-facing rule prose must be declared with its evidence identity; "
                                + "missing literal: " + literal,
                        allowedIds);
            }
        }
        return TerminalValidation.accepted();
    }

    private static TerminalValidation rejected(
            String code, String path, String reason, Set<String> allowedEvidenceIds) {
        return TerminalValidation.rejected(code, path, reason, allowedEvidenceIds);
    }

    record ObservedEvidence(String excerpt, int pageFrom, int pageTo) {}

    record NumericClaim(String value, String evidenceId) {}
}
