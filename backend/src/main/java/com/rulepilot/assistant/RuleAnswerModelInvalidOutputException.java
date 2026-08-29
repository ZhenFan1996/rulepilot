package com.rulepilot.assistant;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The answer provider replied, but its complete structured response did not satisfy the answer contract. */
public final class RuleAnswerModelInvalidOutputException extends IllegalStateException {

    private final RejectedOutput rejectedOutput;

    public RuleAnswerModelInvalidOutputException(String message) {
        this(message, null, null);
    }

    public RuleAnswerModelInvalidOutputException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public RuleAnswerModelInvalidOutputException(
            String message, Throwable cause, RejectedOutput rejectedOutput) {
        super(message, cause);
        this.rejectedOutput = rejectedOutput;
    }

    public Optional<RejectedOutput> rejectedOutput() {
        return Optional.ofNullable(rejectedOutput);
    }

    /** Complete application feedback for one rejected structured provider response. */
    public record RejectedOutput(
            String candidateJson,
            String validationError,
            String schema,
            Set<UUID> allowedEvidenceIds) {

        public RejectedOutput {
            if (candidateJson == null || validationError == null || validationError.isBlank()
                    || schema == null || schema.isBlank() || allowedEvidenceIds == null) {
                throw new IllegalArgumentException("rejected answer model output is invalid");
            }
            allowedEvidenceIds = Set.copyOf(allowedEvidenceIds);
        }
    }
}
