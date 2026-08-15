package com.rulepilot.document.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rulepilot.document.application.OfficialRulebookDiscoveryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialRulebookDiscoveryControllerTest {

    @Test
    void exposesReviewableProvenanceAndVerificationState() {
        UUID editionId = UUID.randomUUID();
        OfficialRulebookDiscoveryService discovery = mock(OfficialRulebookDiscoveryService.class);
        when(discovery.discover(editionId, "en")).thenReturn(new OfficialRulebookDiscoveryService.Result(
                true,
                List.of(new OfficialRulebookDiscoveryService.Candidate(
                        "Official Rules",
                        "https://publisher.example/rules.pdf",
                        "Publisher",
                        "en",
                        "First",
                        "publisher.example",
                        true,
                        true,
                        OfficialRulebookDiscoveryService.SourceType.PUBLISHER,
                        OfficialRulebookDiscoveryService.AcquisitionMode.DIRECT_PDF,
                        OfficialRulebookDiscoveryService.SourceCapability.DIRECT_DOCUMENT,
                        List.of(OfficialRulebookDiscoveryService.CapabilityEvidence.DOCUMENT_RESPONSE_CONFIRMED),
                        Instant.parse("2026-08-15T12:00:00Z")))));

        var response = new OfficialRulebookDiscoveryController(discovery).discover(editionId, "en");

        assertThat(response.configured()).isTrue();
        assertThat(response.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.sourceDomain()).isEqualTo("publisher.example");
            assertThat(candidate.officialDomainVerified()).isTrue();
            assertThat(candidate.languageVerified()).isTrue();
            assertThat(candidate.sourceType()).isEqualTo(OfficialRulebookDiscoveryService.SourceType.PUBLISHER);
            assertThat(candidate.acquisitionMode())
                    .isEqualTo(OfficialRulebookDiscoveryService.AcquisitionMode.DIRECT_PDF);
            assertThat(candidate.capability())
                    .isEqualTo(OfficialRulebookDiscoveryService.SourceCapability.DIRECT_DOCUMENT);
            assertThat(candidate.capabilityEvidence())
                    .containsExactly(OfficialRulebookDiscoveryService.CapabilityEvidence.DOCUMENT_RESPONSE_CONFIRMED);
            assertThat(candidate.capabilityCheckedAt()).isEqualTo(Instant.parse("2026-08-15T12:00:00Z"));
            assertThat(candidate.nextAction())
                    .isEqualTo(OfficialRulebookDiscoveryService.SourceAction.IMPORT_DOCUMENT);
        });
    }
}
