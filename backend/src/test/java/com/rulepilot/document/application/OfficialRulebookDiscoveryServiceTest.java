package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.catalog.CatalogGamePresentationLookup;
import com.rulepilot.catalog.CatalogGameSourceIdentityLookup;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.AcquisitionMode;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.Candidate;
import com.rulepilot.document.application.OfficialRulebookDiscoveryService.SourceType;
import java.net.URI;
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
                        "Observed BGG rulebook download",
                        "https://boardgamegeek.com/file/download_redirect/c66d839e5ef882cf86295abc25caef76456ef0ed43746421/wingspan-rules.pdf",
                        "Community uploader",
                        "en",
                        "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Guessed BGG download",
                        "https://boardgamegeek.com/file/download_redirect/88465/wingspan-rules.pdf",
                        "",
                        "en",
                        "First"),
                new OfficialRulebookCandidateFinder.Candidate(
                        "Untrusted page", "https://random.example/rules", "", "en", "First")));
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), emptyInspector(), "trusted.example");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.configured()).isTrue();
        assertThat(result.candidates()).hasSize(6);
        assertThat(result.candidates().getFirst().officialDomainVerified()).isTrue();
        assertThat(result.candidates().getFirst().sourceDomain()).isEqualTo("stonemaiergames.com");
        assertThat(result.candidates()).extracting(Candidate::sourceType).containsExactly(
                SourceType.PUBLISHER,
                SourceType.PUBLISHER,
                SourceType.TRUSTED_REPOSITORY,
                SourceType.COMMUNITY_PLATFORM,
                SourceType.COMMUNITY_PLATFORM,
                SourceType.PUBLIC_WEB);
        assertThat(result.candidates()).extracting(Candidate::acquisitionMode).containsExactly(
                AcquisitionMode.DIRECT_PDF,
                AcquisitionMode.SOURCE_PAGE,
                AcquisitionMode.SOURCE_PAGE,
                AcquisitionMode.DIRECT_PDF,
                AcquisitionMode.SOURCE_PAGE,
                AcquisitionMode.DIRECT_PDF);
        assertThat(result.candidates()).extracting(Candidate::url)
                .contains("https://boardgamegeek.com/files/thing/266192");
    }

    @Test
    void degradesWithoutCallingAnUnconfiguredFinder() {
        FakeFinder finder = new FakeFinder(List.of());
        finder.configured = false;
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), emptyInspector(), "");

        var result = service.discover(EDITION_ID, "zh-CN");

        assertThat(result.configured()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(finder.calls).isZero();
    }

    @Test
    void followsABoundedDownloadControlAndPromotesASuffixlessPdfOnlyAfterInspection() {
        var finder = new FakeFinder(List.of(new OfficialRulebookCandidateFinder.Candidate(
                "Publisher rules",
                "https://stonemaiergames.com/catalog-game/rules",
                "Stonemaier Games",
                "en",
                "First")));
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getPath().equals("/catalog-game/rules")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(new OfficialRulebookSourceInspector.Link(
                                URI.create("https://stonemaiergames.com/api/download?id=42"), "Download"))));
            }
            if (source.getPath().equals("/api/download")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.PDF, List.of()));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), inspector, "");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.url()).isEqualTo("https://stonemaiergames.com/api/download?id=42");
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.PUBLISHER);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.DIRECT_PDF);
                });
        assertThat(finder.refinementCalls).isZero();
    }

    @Test
    void followsAGstoneRulebookLinkAndPromotesItsExplicitPageImageViewerForImport() {
        var finder = new FakeFinder(List.of());
        finder.configured = false;
        GstoneRulebookCatalogLookup gstoneCatalog = request -> {
            assertThat(request.gameName()).isEqualTo("Wingspan");
            return List.of(new OfficialRulebookCandidateFinder.Candidate(
                    "目录游戏",
                    "https://www.gstonegames.com/game/info-1234.html",
                    "集石",
                    "zh-CN",
                    "基础版"));
        };
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getPath().equals("/game/info-1234.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(
                                new OfficialRulebookSourceInspector.Link(
                                        URI.create("https://www.gstonegames.com/game/doc-1111.html"),
                                        "Official Rulebook"),
                                new OfficialRulebookSourceInspector.Link(
                                        URI.create("https://www.gstonegames.com/game/doc-4321.html"),
                                        "官方规则书"),
                                new OfficialRulebookSourceInspector.Link(
                                        URI.create("https://www.gstonegames.com/game/doc-2222.html"),
                                        "目录游戏图标概览 / 术语表"))));
            }
            if (source.getPath().equals("/game/doc-1111.html")
                    || source.getPath().equals("/game/doc-4321.html")
                    || source.getPath().equals("/game/doc-2222.html")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source, OfficialRulebookSourceInspector.MediaType.IMAGE_GALLERY, List.of()));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, gstoneCatalog, inspector, "");

        var result = service.discover(EDITION_ID, "zh-CN");

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.title()).isEqualTo("官方规则书");
                    assertThat(candidate.url()).isEqualTo("https://www.gstonegames.com/game/doc-4321.html");
                    assertThat(candidate.language()).isEqualTo("zh-CN");
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.COMMUNITY_PLATFORM);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.IMAGE_GALLERY);
                });
        assertThat(result.candidates())
                .filteredOn(candidate -> candidate.url().endsWith("/game/doc-1111.html"))
                .singleElement()
                .extracting(Candidate::language)
                .isEqualTo("en");
        assertThat(result.candidates())
                .noneMatch(candidate -> candidate.url().endsWith("/game/doc-2222.html"));
        assertThat(finder.refinementCalls).isZero();
        assertThat(finder.calls).isZero();
    }

    @Test
    void followsAOneJourRulebookPageAndKeepsCommunityProvenanceForItsObservedPdf() {
        var finder = new FakeFinder(List.of(new OfficialRulebookCandidateFinder.Candidate(
                "Wingspan (2019)",
                "https://en.1jour-1jeu.com/table-game/2019-wingspan",
                "1jour-1jeu",
                "en",
                "First")));
        OfficialRulebookSourceInspector inspector = source -> {
            if (source.getHost().equals("en.1jour-1jeu.com")) {
                return Optional.of(new OfficialRulebookSourceInspector.Inspection(
                        source,
                        OfficialRulebookSourceInspector.MediaType.HTML,
                        List.of(new OfficialRulebookSourceInspector.Link(
                                URI.create("https://cdn.1j1ju.com/medias/12/34/wingspan-rulebook.pdf"),
                                "English"))));
            }
            return Optional.empty();
        };
        var service = new OfficialRulebookDiscoveryService(
                catalog(), sourceIdentity(), finder, request -> List.of(), inspector, "");

        var result = service.discover(EDITION_ID, "en");

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.url())
                            .isEqualTo("https://cdn.1j1ju.com/medias/12/34/wingspan-rulebook.pdf");
                    assertThat(candidate.sourceDomain()).isEqualTo("cdn.1j1ju.com");
                    assertThat(candidate.sourceType()).isEqualTo(SourceType.COMMUNITY_PLATFORM);
                    assertThat(candidate.acquisitionMode()).isEqualTo(AcquisitionMode.DIRECT_PDF);
                });
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

    private OfficialRulebookSourceInspector emptyInspector() {
        return source -> Optional.empty();
    }

    private static final class FakeFinder implements OfficialRulebookCandidateFinder {
        private final List<OfficialRulebookCandidateFinder.Candidate> candidates;
        private boolean configured = true;
        private int calls;
        private int refinementCalls;

        private FakeFinder(List<OfficialRulebookCandidateFinder.Candidate> candidates) {
            this.candidates = candidates;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public List<OfficialRulebookCandidateFinder.Candidate> find(Request request) {
            calls++;
            assertThat(request.gameName()).isEqualTo("Wingspan");
            assertThat(request.publishers()).containsExactly("Stonemaier Games");
            return candidates;
        }

        @Override
        public List<OfficialRulebookCandidateFinder.Candidate> findAfterSourcePages(
                Request request, List<OfficialRulebookCandidateFinder.Candidate> observedSourcePages) {
            refinementCalls++;
            return List.of();
        }
    }
}
