package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialRulebookImportRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void separatesRunningWorkFromTerminalFailureActions() {
        var running = OfficialRulebookImportRecovery.forJob(OfficialRulebookImportJob.queued(
                UUID.randomUUID(), "alice", null, "Opaque Rules", DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf", NOW));

        assertThat(running.state()).isEqualTo(OfficialRulebookImportRecovery.State.RUNNING);
        assertThat(running.busy()).isTrue();
        assertThat(running.canChooseAnotherSource()).isFalse();
        assertThat(running.canUseLocalUpload()).isFalse();
        assertThat(running.canRetryOriginalSource()).isFalse();

        var failed = OfficialRulebookImportRecovery.forJob(failed("SOURCE_UNAVAILABLE"));

        assertThat(failed.state()).isEqualTo(OfficialRulebookImportRecovery.State.FAILED);
        assertThat(failed.failureKind()).isEqualTo(
                OfficialRulebookImportRecovery.FailureKind.TEMPORARY_SOURCE);
        assertThat(failed.busy()).isFalse();
        assertThat(failed.canChooseAnotherSource()).isTrue();
        assertThat(failed.canUseLocalUpload()).isTrue();
        assertThat(failed.canRetryOriginalSource()).isTrue();
        assertThat(failed.canOpenSourceInBrowser()).isFalse();
    }

    @Test
    void neverOffersAutomaticRetryForInvalidOrBrowserGatedSources() {
        var invalid = OfficialRulebookImportRecovery.forJob(failed("INVALID_PDF_SOURCE"));
        var browser = OfficialRulebookImportRecovery.forJob(failed("SOURCE_BROWSER_REQUIRED"));

        assertThat(invalid.failureKind()).isEqualTo(
                OfficialRulebookImportRecovery.FailureKind.INVALID_SOURCE);
        assertThat(invalid.canRetryOriginalSource()).isFalse();
        assertThat(invalid.canOpenSourceInBrowser()).isFalse();
        assertThat(browser.failureKind()).isEqualTo(
                OfficialRulebookImportRecovery.FailureKind.BROWSER_HANDOFF);
        assertThat(browser.canRetryOriginalSource()).isFalse();
        assertThat(browser.canOpenSourceInBrowser()).isTrue();
    }

    private OfficialRulebookImportJob failed(String errorCode) {
        return new OfficialRulebookImportJob(
                UUID.randomUUID(),
                "alice",
                null,
                "Opaque Rules",
                DocumentSourceType.BASE_RULEBOOK,
                "https://publisher.example/rules.pdf",
                OfficialRulebookImportJob.Stage.FAILED,
                0,
                null,
                null,
                false,
                errorCode,
                NOW,
                NOW,
                NOW);
    }
}
