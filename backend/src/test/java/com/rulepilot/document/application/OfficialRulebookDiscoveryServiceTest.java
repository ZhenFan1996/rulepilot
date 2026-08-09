package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.AcquisitionMode;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.Candidate;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.SourceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialRulebookDiscoveryServiceTest {

    private static final UUID EDITION_ID = UUID.fromString("26ba0d70-51e3-445b-9245-d50754562a13");

    @Test
    void validatesClassifiesAndRanksCandidatesWithoutTrustingModelUrls() {
        var finder = new FakeFinder(List.of(
                new OfficialRulebookCandidateFinder.Candidate(
                        "Rules", "https://cdn.example.net/rules.pdf", "Unknown Publisher", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Official Rules", "https://stonemaiergames.com/files/rules.pdf", "Stonemaier Games", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Trusted source page", "https://trusted.example/wingspan", "", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Private", "http://127.0.0.1/rules.pdf", "Stonemaier Games", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Publisher page", "https://stonemaiergames.com/rules", "Stonemaier Games", "en", "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Untrusted page", "https://random.example/rules", "", "en", "First")));
        var service = new OfficialRulebookDiscoveryService(catalog(), sourceIdentity(), finder, "trusted.example");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.configured()).isTrue();
        assertThat(result.candidates()).hasSize(5);
        assertThat(result.candidates().getFirst().officialDomainVerified()).isTrue();
        assertThat(result.candidates().getFirst().sourceDomain()).isEqualTo("stonemaiergames.com");
        assertThat(result.candidates()).extracting(Candidate::sourceType).containsExactly(
                SourceType.PUBLISHER,
                SourceType.PUBLISHER,
                SourceType.TRUSTED_REPOSITORY,
                SourceType.COMMUNITY_PLATFORM,
                SourceType.PUBLIC_WEB);
        assertThat(result.candidates()).extracting(Candidate::acquisitionMode).containsExactly(
                AcquisitionMode.DIRECT_PDF,
                AcquisitionMode.SOURCE_PAGE,
                AcquisitionMode.SOURCE_PAGE,
                AcquisitionMode.SOURCE_PAGE,
                AcquisitionMode.DIRECT_PDF);
        assertThat(result.candidates()).extracting(Candidate::url)
                .contains("https://boardgamegeek.com/files/thing/266192");
    }

    @Test
    void degradesWithoutCallingAnUnconfiguredFinder() {
        FakeFinder finder = new FakeFinder(List.of());
        finder.configured = false;
        var service = new OfficialRulebookDiscoveryService(catalog(), sourceIdentity(), finder, "");

        var result = service.discover(EDITION_ID, "zh-CN");

        assertThat(result.configured()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(finder.calls).isZero();
    }

    private CatalogGamePresentationLookup catalog() {
        return editionId -> Optional.of(new CatalogGamePresentationLookup.Presentation(
                EDITION_ID,
                "Wingspan",
                "BGG 基础版",
                "und",
                2019,
                266192,
                "https://example.test/cover.jpg",
                1,
                5,
                70,
                10,
                "https://boardgamegeek.com/boardgame/266192"));
    }

    private CatalogGameSourceIdentityLookup sourceIdentity() {
        return bggId -> Optional.of(new CatalogGameSourceIdentityLookup.Identity(
                "Wingspan", List.of("Wingspan", "展翅翱翔"), List.of("Stonemaier Games")));
    }

    private static final class FakeFinder implements OfficialRulebookCandidateFinder {
        private final List<Candidate> candidates;
        private boolean configured = true;
        private int calls;

        private FakeFinder(List<Candidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public List<Candidate> find(Request request) {
            calls++;
            assertThat(request.gameName()).isEqualTo("Wingspan");
            assertThat(request.publishers()).containsExactly("Stonemaier Games");
            assertThat(request.trustedDomains()).containsExactly("trusted.example");
            return candidates;
        }
    }
}
