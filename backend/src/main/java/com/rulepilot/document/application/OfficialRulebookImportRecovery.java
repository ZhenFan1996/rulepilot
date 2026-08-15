package com.rulepilot.document.application;

import com.rulepilot.document.domain.OfficialRulebookImportJob;
import java.util.Set;

/** Converts durable import state into bounded player recovery capabilities. */
public record OfficialRulebookImportRecovery(
        State state,
        FailureKind failureKind,
        boolean busy,
        boolean canChooseAnotherSource,
        boolean canUseLocalUpload,
        boolean canRetryOriginalSource,
        boolean canOpenSourceInBrowser) {

    private static final Set<String> RETRYABLE_FAILURES = Set.of(
            "SOURCE_UNAVAILABLE",
            "IMPORT_QUEUE_FULL",
            "APPLICATION_RESTARTED");

    public static OfficialRulebookImportRecovery forJob(OfficialRulebookImportJob job) {
        if (job == null) throw new IllegalArgumentException("official import job is required");
        if (job.stage() == OfficialRulebookImportJob.Stage.FAILED) {
            FailureKind kind = failureKind(job.errorCode());
            return new OfficialRulebookImportRecovery(
                    State.FAILED,
                    kind,
                    false,
                    true,
                    true,
                    RETRYABLE_FAILURES.contains(job.errorCode()),
                    kind == FailureKind.BROWSER_HANDOFF);
        }
        boolean settled = job.stage() == OfficialRulebookImportJob.Stage.COMPLETED
                && switch (job.teachingHandoff().state()) {
                    case NOT_REQUESTED, LAUNCHED, FAILED -> true;
                    case WAITING_FOR_DOCUMENT, LAUNCHING -> false;
                };
        return new OfficialRulebookImportRecovery(
                settled ? State.SUCCEEDED : State.RUNNING,
                FailureKind.NONE,
                !settled,
                false,
                false,
                false,
                false);
    }

    private static FailureKind failureKind(String errorCode) {
        if ("SOURCE_UNAVAILABLE".equals(errorCode)) return FailureKind.TEMPORARY_SOURCE;
        if ("SOURCE_BROWSER_REQUIRED".equals(errorCode)) return FailureKind.BROWSER_HANDOFF;
        if ("INVALID_PDF_SOURCE".equals(errorCode)) return FailureKind.INVALID_SOURCE;
        if ("IMPORT_QUEUE_FULL".equals(errorCode)) return FailureKind.CAPACITY;
        if ("APPLICATION_RESTARTED".equals(errorCode)) return FailureKind.INTERRUPTED;
        return FailureKind.OTHER;
    }

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum FailureKind {
        NONE,
        TEMPORARY_SOURCE,
        BROWSER_HANDOFF,
        INVALID_SOURCE,
        CAPACITY,
        INTERRUPTED,
        OTHER
    }
}
